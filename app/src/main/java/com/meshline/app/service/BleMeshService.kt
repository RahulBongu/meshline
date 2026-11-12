package com.meshline.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meshline.app.R
import com.meshline.app.profile.ProfileStore
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import com.meshline.app.router.MeshRouter
import com.meshline.app.router.RouterConfig
import com.meshline.app.security.AndroidKeystoreIdentityStore
import com.meshline.app.security.AnnounceVerifier
import com.meshline.app.security.DbPassphrase
import com.meshline.app.security.Identity
import com.meshline.app.security.KeyCache
import com.meshline.app.security.SecureStorageStatus
import com.meshline.app.storage.ContactsRepository
import com.meshline.app.storage.MessageRetention
import com.meshline.app.storage.RetentionStore
import com.meshline.app.transport.BleTransport
import com.meshline.app.transport.PeerInfo
import com.meshline.app.transport.ReceivedMessageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Counter snapshot for the debug overlay (PROTOCOL §8.1 counter split).
 * Top-level so both the service and the UI can name it without companion
 * nesting rules.
 */
data class MeshStatsSnapshot(
    val delivered: Int = 0,
    val relayedForwards: Int = 0,
    val droppedUnknownKey: Int = 0,
    val droppedBadSignature: Int = 0,
    val droppedBudget: Int = 0,
    val droppedInvalid: Int = 0,
    val droppedBufferFull: Int = 0,
    val heldTotal: Int = 0,
    val releasedPending: Int = 0,
)

/**
 * D1 service: owns BLE + MeshRouter + the Keystore identity.
 *
 * - Identity is Keystore-backed (Ed25519 + X25519). ANNOUNCE and PUBLIC_MESSAGE
 *   are signed; the router verifies every signature before dedupe/relay and
 *   keeps the two-tier [KeyCache] (PROTOCOL §8.1).
 * - Announces every ~30 s (intervalUnits=3) with the profile's fruit avatar.
 * - QR-verified contacts are pre-loaded into the protected key tier.
 * - Runs as a connected-device foreground service.
 * - BLE does not work on emulators. Test on two physical phones (see README).
 */
class BleMeshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: BleTransport? = null
    private var router: MeshRouter? = null
    private val jobs = mutableListOf<Job>()
    private var announceJob: Job? = null

    private val identityStore by lazy { AndroidKeystoreIdentityStore(applicationContext) }
    private val profileStore by lazy { ProfileStore(applicationContext) }
    private val contacts by lazy { ContactsRepository(applicationContext) }
    private val retentionStore by lazy { RetentionStore(applicationContext) }
    private val keyCache = KeyCache()

    /** Peers we are currently linked to (transport view) — drives the settle window. */
    @Volatile private var connectedPeers: Set<ByteArray> = emptySet()

    /** The D2 secure channel (Noise sessions, private messages, receipts). */
    @Volatile private var secureChannel: SecureChannel? = null

    /** The connected peers as hex ids (the D2 layer's view). */
    private fun connectedPeersHex(): Set<String> =
        connectedPeers.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()

    /** Keystore-backed identity; a reset creates a new one (see SecureStorageStatus). */
    val identity: Identity by lazy {
        identityStore.load() ?: AndroidKeystoreIdentityStore.generateAndStore(applicationContext)
    }

    val senderId: ByteArray get() = identity.senderId
    val nickname: String get() = profileStore.load().nickname

    override fun onCreate() {
        super.onCreate()
        if (!startForegroundCompat()) {
            // Android 12+/14 can reject the FGS start (background start or a
            // missing connected-device permission). Fail soft instead of crashing.
            stopSelf()
            return
        }

        val id = identity
        val t = BleTransport(applicationContext, id.senderId, scope)
        val r = MeshRouter(
            ownId = id.senderId,
            transport = t,
            config = RouterConfig(),
            neighboursProvider = { emptySet() },
            // The transport's connection view drives the settle window only
            // (PROTOCOL §8.1): the sender waits ~2 s after linking a new peer
            // before its first data packet to it.
            connectedPeersProvider = { connectedPeers },
            signatureVerifier = { pkt, from -> AnnounceVerifier.verify(pkt, keyCache, from) },
        )
        transport = t
        router = r

        // The D2 secure channel: Noise handshakes, private messages, receipts.
        val channel = SecureChannel(
            identity = id,
            router = r,
            keyCache = keyCache,
            contacts = contacts,
            outbox = contacts.secure.outbox,
            dedupe = contacts.secure.seen,
            messageStore = contacts.secure.messages,            sessionDirectory = contacts.secure.sessions,
            connectedPeers = { connectedPeersHex() },
            retentionDays = { retentionStore.load().days },
        )
        secureChannel = channel

        jobs += scope.launch { channel.messages.collect { setPrivateMessages(it) } }
        jobs += scope.launch { channel.peers.collect { setPrivatePeers(it) } }
        jobs += scope.launch { channel.keyChange.collect { setKeyChange(it) } }

        jobs += scope.launch {
            t.incoming.collect { (from, bytes) -> runCatching { r.onReceive(bytes, from) } }
        }
        // Track the connected peers (transport view) for the settle window.
        jobs += scope.launch {
            t.neighbours.collect { set -> connectedPeers = set.map { it.copyOf() }.toSet() }
        }
        // D2: every packet the router delivers to us goes through the secure
        // channel (handshakes, private messages, receipts).
        jobs += scope.launch {
            r.deliveredWithPeer.collect { (from, pkt) ->
                runCatching { channel.onPacket(pkt, from) }
            }
        }
        // D2: the periodic work — handshake retransmits, outbox retries,
        // re-keys, expiry.
        jobs += scope.launch {
            while (true) {
                runCatching { channel.tick() }
                delay(2_000)
            }
        }
        jobs += scope.launch { t.debugPeers.collect { setPeers(it) } }
        jobs += scope.launch { t.lastGattError.collect { if (it != null) setGattError(it) } }
        jobs += scope.launch { t.advertiseSupported.collect { setAdvertiseSupported(it) } }
        jobs += scope.launch {
            r.delivered.collect { pkt ->
                val hops = (PacketCodec.MAX_TTL - pkt.ttl).coerceAtLeast(0)
                pushMessage(
                    ReceivedMessageInfo(
                        senderIdHex = pkt.senderId.joinToString("") { "%02x".format(it) },
                        typeName = pkt.type.name,
                        receivedTtl = pkt.ttl,
                        hops = hops,
                    )
                )
            }
        }
        // Counter snapshot for the debug overlay (D1 counter split).
        jobs += scope.launch {
            while (true) {
                val s = r.stats
                // Held packets time out on a timer, not only when traffic
                // arrives (PROTOCOL §8.1).
                r.sweepExpiredPending()
                setStats(
                    MeshStatsSnapshot(
                        delivered = s.delivered,
                        relayedForwards = s.relayedForwards,
                        droppedUnknownKey = s.droppedUnknownKey,
                        droppedBadSignature = s.droppedBadSignature,
                        droppedBudget = s.droppedBudget,
                        droppedInvalid = s.droppedInvalid,
                        droppedBufferFull = s.droppedBufferFull,
                        heldTotal = s.heldTotal,
                        releasedPending = s.releasedPending,
                    )
                )
                delay(2_000)
            }
        }
        // QR-verified contacts are trusted immediately (protected tier).
        jobs += scope.launch {
            contacts.all().forEach { c ->
                runCatching { keyCache.protect(c.senderIdHex.hexToBytes(), c.ed25519PublicKey) }
            }
        }

        t.start()
        announceJob = scope.launch {
            sendAnnounce()
            while (true) {
                delay(30_000L + Random.nextLong(-5_000L, 5_000L))
                sendAnnounce()
            }
        }
        Log.d(TAG, "BleMeshService up")
    }

    private suspend fun sendAnnounce() {
        val r = router ?: return
        val id = identity
        val profile = profileStore.load()
        val payload = Payloads.encodeAnnounce(
            Payloads.Announce(
                nickname = profile.nickname,
                ed25519PublicKey = id.ed25519PublicKey,
                x25519PublicKey = id.x25519PublicKey,
                capabilities = Payloads.Capabilities.RELAY_ENABLED,
                avatarIndex = profile.avatarIndex,
                intervalUnits = INTERVAL_UNITS,
            )
        )
        runCatching { r.onLocalSend(signed(PacketType.ANNOUNCE, payload)) }
    }

    /** Public Nearby chat (signed, not encrypted). */
    suspend fun sendPublic(text: String) {
        val r = router ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        r.onLocalSend(signed(PacketType.PUBLIC_MESSAGE, Payloads.encodePublicText(clean)))
    }

    private fun signed(type: PacketType, payload: ByteArray): MeshPacket {
        val id = identity
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, type, PacketCodec.MAX_TTL, 0,
            System.currentTimeMillis(), payload, id.senderId, PacketCodec.BROADCAST_ID,
        )
        return unsigned.copy(
            flags = 0x02, // HAS_SIGNATURE (broadcast, §3)
            signature = id.signEd25519(PacketCodec.signingInput(unsigned)),
        )
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Mesh relay", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Meshline")
                .setContentText("Nearby mesh is running")
                .setSmallIcon(R.drawable.ic_mesh)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_PUBLIC -> {
                intent.getStringExtra(EXTRA_TEXT)?.let { text ->
                    scope.launch { sendPublic(text) }
                }
            }
            ACTION_SEND_PRIVATE -> {
                val peer = intent.getStringExtra(EXTRA_PEER_ID)
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (peer != null && text != null) {
                    scope.launch { secureChannel?.send(peer, text) }
                }
            }
            ACTION_MARK_READ -> {
                val peer = intent.getStringExtra(EXTRA_PEER_ID)
                if (peer != null) scope.launch { secureChannel?.markRead(peer) }
            }
            ACTION_OPEN_CHAT -> {
                val peer = intent.getStringExtra(EXTRA_PEER_ID)
                if (peer != null) {
                    scope.launch {
                        secureChannel?.ensureSession(peer)
                        secureChannel?.markRead(peer)
                    }
                }
            }
            ACTION_CLEAR_CHAT -> {
                val peer = intent.getStringExtra(EXTRA_PEER_ID)
                if (peer != null) scope.launch { secureChannel?.clearChat(peer) }
            }
            ACTION_RESEND -> {
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
                if (messageId != null) scope.launch { secureChannel?.resend(messageId) }
            }
            ACTION_SET_RETENTION -> {
                val days = intent.getIntExtra(EXTRA_RETENTION_DAYS, 0)
                retentionStore.save(MessageRetention.of(days))
            }
            ACTION_ACCEPT_KEY -> {
                val peer = intent.getStringExtra(EXTRA_PEER_ID)
                if (peer != null) scope.launch { secureChannel?.acceptKeyChange(peer) }
            }
            ACTION_REJECT_KEY -> {
                val peer = intent.getStringExtra(EXTRA_PEER_ID)
                if (peer != null) scope.launch { secureChannel?.rejectKeyChange(peer) }
            }
            ACTION_PANIC_WIPE -> scope.launch { panicWipe() }
        }
        return START_STICKY
    }

    /**
     * Panic wipe: the identity, the contacts, every session and every message
     * go. The app comes back with a **new** identity on the next launch.
     */
    private suspend fun panicWipe() {
        PanicWiper(
            context = applicationContext,
            identityStore = identityStore,
            contacts = contacts,
            profileStore = profileStore,
            retentionStore = retentionStore,
        ).wipe(secureChannel)
        setPrivateMessages(emptyList())
        setPrivatePeers(emptyList())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        announceJob?.cancel()
        jobs.forEach { it.cancel() }
        transport?.stopInternal()
        contacts.close()
        scope.cancel()
        super.onDestroy()
    }

    /** Debug overlay flows (Phase C #1) + send action for the composer. */
    companion object Bridge {
        const val ACTION_SEND_PUBLIC = "com.meshline.app.action.SEND_PUBLIC"
        const val ACTION_SEND_PRIVATE = "com.meshline.app.action.SEND_PRIVATE"
        const val ACTION_MARK_READ = "com.meshline.app.action.MARK_READ"
        const val ACTION_OPEN_CHAT = "com.meshline.app.action.OPEN_CHAT"
        const val ACTION_CLEAR_CHAT = "com.meshline.app.action.CLEAR_CHAT"
        const val ACTION_RESEND = "com.meshline.app.action.RESEND"
        const val ACTION_SET_RETENTION = "com.meshline.app.action.SET_RETENTION"
        const val ACTION_ACCEPT_KEY = "com.meshline.app.action.ACCEPT_KEY"
        const val ACTION_REJECT_KEY = "com.meshline.app.action.REJECT_KEY"
        const val ACTION_PANIC_WIPE = "com.meshline.app.action.PANIC_WIPE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PEER_ID = "peerIdHex"
        const val EXTRA_MESSAGE_ID = "messageIdHex"
        const val EXTRA_RETENTION_DAYS = "retentionDays"

        private const val CHANNEL_ID = "meshline_mesh"
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_UNITS: Byte = 3 // 30 s announce cadence
        private const val TAG = "BleMeshService"

        private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
        val peers: Flow<List<PeerInfo>> = _peers.asStateFlow()
        private val _messages = MutableStateFlow<List<ReceivedMessageInfo>>(emptyList())
        val messages: Flow<List<ReceivedMessageInfo>> = _messages.asStateFlow()
        private val _lastGattError = MutableStateFlow<Int?>(null)
        val lastGattError: Flow<Int?> = _lastGattError.asStateFlow()
        private val _advertiseSupported = MutableStateFlow(true)
        val advertiseSupported: Flow<Boolean> = _advertiseSupported.asStateFlow()
        private val _stats = MutableStateFlow(MeshStatsSnapshot())
        val stats: Flow<MeshStatsSnapshot> = _stats.asStateFlow()

        // ---- D2: the private-chat state the UI reads ----
        private val _privateMessages = MutableStateFlow<List<PrivateChatMessage>>(emptyList())
        val privateMessages: StateFlow<List<PrivateChatMessage>> = _privateMessages.asStateFlow()
        private val _privatePeers = MutableStateFlow<List<PrivatePeer>>(emptyList())
        val privatePeers: StateFlow<List<PrivatePeer>> = _privatePeers.asStateFlow()
        private val _keyChange = MutableStateFlow<KeyChangeEvent?>(null)
        val keyChange: StateFlow<KeyChangeEvent?> = _keyChange.asStateFlow()

        internal fun setPeers(v: List<PeerInfo>) { _peers.value = v }
        internal fun pushMessage(m: ReceivedMessageInfo) { _messages.value = (_messages.value + m).takeLast(50) }
        internal fun setGattError(c: Int) { _lastGattError.value = c }
        internal fun setAdvertiseSupported(b: Boolean) { _advertiseSupported.value = b }
        internal fun setStats(s: MeshStatsSnapshot) { _stats.value = s }
        internal fun setPrivateMessages(v: List<PrivateChatMessage>) { _privateMessages.value = v }
        internal fun setPrivatePeers(v: List<PrivatePeer>) { _privatePeers.value = v }
        internal fun setKeyChange(v: KeyChangeEvent?) { _keyChange.value = v }

        private fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
