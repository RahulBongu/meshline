package com.meshline.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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

    /** True once the full start below ran (transport, router, channel). */
    @Volatile private var initialized = false
    private val jobs = mutableListOf<Job>()
    private var announceJob: Job? = null

    private val identityStore by lazy { AndroidKeystoreIdentityStore(applicationContext) }
    private val profileStore by lazy { ProfileStore(applicationContext) }

    /**
     * The contacts database, created on first use and closed in onDestroy.
     * Explicit (not `by lazy`) so a service that never got as far as opening
     * it does not open it just to close it.
     */
    private var contactsStore: ContactsRepository? = null
    private val contacts: ContactsRepository
        get() = contactsStore ?: ContactsRepository(applicationContext).also { contactsStore = it }
    private val retentionStore by lazy { RetentionStore(applicationContext) }
    private val keyCache = KeyCache()

    /** Peers we are currently linked to (transport view) — drives the settle window. */
    @Volatile private var connectedPeers: Set<ByteArray> = emptySet()

    /** When the last announce went out (the re-announce cadence uses it). */
    @Volatile private var lastAnnounceAtMs: Long = 0L

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
        setStartError(null)
        tryInitialize()
    }

    /**
     * The full service start, idempotent: onCreate runs it once and every
     * later startForegroundService() retries it through onStartCommand — that
     * is what makes "grant the permission, then tap Start mesh again" work
     * after a refused start left the service un-initialized.
     *
     * The ordering is the point. startForegroundService() obliges the service
     * to go foreground before anything else: a service that is destroyed
     * without a successful startForeground() crashes the whole app
     * (ForegroundServiceDidNotStartInTimeException) — **even when the service
     * stops itself on purpose**. So the foreground start comes FIRST; only
     * once it succeeded may the missing-permission path stopSelf() safely.
     */
    private fun tryInitialize() {
        if (initialized) return
        if (!startForegroundCompat()) {
            // The foreground start itself was refused: a revoked Bluetooth
            // permission on Android 14+ makes the connected-device type
            // inadmissible, or Android blocked a background start. The reason
            // is already surfaced in the UI. stopSelf() here would crash the
            // app (the service never went foreground) — stay a plain started
            // service instead; the next "Start mesh" tap retries.
            return
        }
        val missing = missingBlePermissions()
        if (missing.isNotEmpty()) {
            // Without them the transport cannot scan, connect or advertise:
            // say so in the UI instead of failing silently in the log.
            setStartError(
                "Bluetooth permission missing: ${missing.joinToString()}. " +
                    "Grant it and tap \"Start mesh\" again."
            )
            stopSelf() // crash-safe: the service did go foreground
            return
        }

        val id = identity
        val t = BleTransport(
            context = applicationContext,
            localId = id.senderId,
            scope = scope,
            // The transport binds a *device* to an identity only when the
            // announce proves it (self-certifying) — a forged announce must
            // not be able to claim somebody else's id and pull our traffic to
            // the impostor.
            announceIdentityVerifier = { pkt -> AnnounceVerifier.verifyAnnounceIdentity(pkt) },
        )
        val r = MeshRouter(
            ownId = id.senderId,
            transport = t,
            config = RouterConfig(),
            // The peers we can address: the ones that sent us a verified
            // ANNOUNCE (the transport registers them under their senderId).
            // With this the router can use the direct-send shortcut (rule 5)
            // and the adaptive relay probability (rule 6) instead of always
            // flooding to everybody.
            neighboursProvider = { connectedPeers },
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
                // Release the links that never proved a peer identity: they
                // would hold one of the six GATT slots forever.
                runCatching { t.sweepUnannouncedLinks() }
                // While a peer is still unproven, announce again (every 5 s):
                // it learns our id only from an announce, and a lost announce
                // would otherwise leave the link unusable for a whole cycle.
                if (t.unprovenLinkCount > 0 &&
                    System.currentTimeMillis() - lastAnnounceAtMs > RE_ANNOUNCE_INTERVAL_MS
                ) {
                    runCatching { sendAnnounce() }
                }
                delay(2_000)
            }
        }
        jobs += scope.launch { t.debugPeers.collect { setPeers(it) } }
        jobs += scope.launch { t.lastGattError.collect { if (it != null) setGattError(it) } }
        jobs += scope.launch { t.advertiseSupported.collect { setAdvertiseSupported(it) } }
        // A new link came up: announce ourselves right away. The peer learns
        // our id only from an announce, and the periodic announce (30 s) would
        // leave the link unusable for most of that cycle.
        jobs += scope.launch {
            t.newLink.collect { runCatching { sendAnnounce() } }
        }
        jobs += scope.launch {
            r.delivered.collect { pkt ->
                // The identity is confirmed by the peer's **signed ANNOUNCE**
                // only: the router verified the signature before delivering
                // it, so from now on this id can be addressed and the peer
                // counts as a neighbour.
                if (pkt.type == PacketType.ANNOUNCE) {
                    val isNew = runCatching { t.confirmPeerIdentity(pkt.senderId) }.getOrDefault(false)
                    // Announce ourselves to a **new direct** peer, so it learns
                    // us too. A relayed announce (the origin is further away)
                    // must not trigger an announce per hop.
                    if (isNew && t.isDirectPeer(pkt.senderId)) {
                        runCatching { sendAnnounce() }
                    }
                }
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
        initialized = true
        setStartError(null) // a retry succeeded — the old failure banner is stale
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
        lastAnnounceAtMs = System.currentTimeMillis()
        // The announce goes to **every** link, not only to the known
        // neighbours: a peer that has not heard from us yet cannot address us
        // and would never show up in the neighbours set at all.
        runCatching { r.onLocalBroadcast(signed(PacketType.ANNOUNCE, payload)) }
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
            setStartError(describeFgsFailure(e))
            false
        }
    }

    /**
     * Turns a rejected foreground-service start into something the user can
     * act on. The old behaviour was a log line only, so the mesh simply never
     * came up and nobody knew why.
     */
    private fun describeFgsFailure(e: Exception): String = when {
        e is SecurityException && Build.VERSION.SDK_INT >= 34 ->
            "Android 14 refused the connected-device foreground service. The " +
                "FOREGROUND_SERVICE_CONNECTED_DEVICE permission or a Bluetooth permission is missing."
        e is SecurityException ->
            "The system refused the foreground service — a Bluetooth permission is missing."
        Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException ->
            "Android blocked the background start of the mesh. Open Meshline and tap \"Start mesh\" again."
        else -> "The mesh service could not start (${e.javaClass.simpleName})."
    }

    /** The Bluetooth permissions the transport needs, missing ones named. */
    private fun missingBlePermissions(): List<String> {
        val out = mutableListOf<String>()
        fun granted(permission: String) =
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted("android.permission.BLUETOOTH_CONNECT")) out += "Nearby devices (connect)"
            if (!granted("android.permission.BLUETOOTH_SCAN")) out += "Nearby devices (scan)"
            if (!granted("android.permission.BLUETOOTH_ADVERTISE")) out += "Nearby devices (advertise)"
        } else if (!granted("android.permission.ACCESS_FINE_LOCATION")) {
            out += "Location (needed for the BLE scan before Android 12)"
        }
        return out
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A start that was refused before (foreground or permission) left the
        // service un-initialized: retry now — the user may have just granted
        // the permission and tapped "Start mesh" again.
        tryInitialize()
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
        contactsStore?.close()
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

        /** Re-announce cadence while a peer has not yet proved its identity. */
        private const val RE_ANNOUNCE_INTERVAL_MS: Long = 5_000L
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

        /**
         * Why the mesh service could not start (a missing Bluetooth permission,
         * a blocked foreground-service start). The UI shows it — the mesh
         * simply not coming up is not a usable error message.
         */
        private val _startError = MutableStateFlow<String?>(null)
        val startError: StateFlow<String?> = _startError.asStateFlow()

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
        internal fun setStartError(reason: String?) { _startError.value = reason }
        internal fun setPrivateMessages(v: List<PrivateChatMessage>) { _privateMessages.value = v }
        internal fun setPrivatePeers(v: List<PrivatePeer>) { _privatePeers.value = v }
        internal fun setKeyChange(v: KeyChangeEvent?) { _keyChange.value = v }

        private fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
