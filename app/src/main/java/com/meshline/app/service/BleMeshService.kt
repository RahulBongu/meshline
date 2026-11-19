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
import com.meshline.app.channels.ChannelItem
import com.meshline.app.channels.ChannelManager
import com.meshline.app.channels.ChannelMessageUi
import com.meshline.app.commands.CommandParser
import com.meshline.app.groups.GroupItem
import com.meshline.app.groups.GroupManager
import com.meshline.app.groups.GroupMessageUi
import com.meshline.app.groups.PendingGroupInvite
import com.meshline.app.profile.ProfileStore
import com.meshline.app.protocol.GroupPayloads
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
import com.meshline.app.transport.InternetRelayTransport
import com.meshline.app.transport.PeerInfo
import com.meshline.app.transport.ReceivedMessageInfo
import com.meshline.app.transport.RelayStatus
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

/** Public Nearby chat message (signed Ed25519). */
data class PublicChatMessage(
    val messageIdHex: String,
    val senderIdHex: String,
    val senderNickname: String,
    val senderAvatarIndex: Int,
    val text: String,
    val timestampMs: Long,
    val isSelf: Boolean,
    val hops: Int = 0,
)

/**
 * Meshline background mesh service.
 *
 * Responsibilities:
 *  - Owns BLE transport + MeshRouter + Android Keystore identity.
 *  - Coordinates Noise XX E2EE 1-on-1 private messaging via [SecureChannel].
 *  - Coordinates encrypted group messaging and membership epoch keys via [GroupManager].
 *  - Coordinates topic channels and IRC-style slash commands via [ChannelManager] & [CommandParser].
 *  - Optionally coordinates global Nostr relay synchronization via [InternetRelayTransport].
 *  - Provides persistent foreground service behavior on Android 8.0+.
 */
class BleMeshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: BleTransport? = null
    private var router: MeshRouter? = null

    @Volatile private var initialized = false
    private val jobs = mutableListOf<Job>()
    private var announceJob: Job? = null

    private val identityStore by lazy { AndroidKeystoreIdentityStore(applicationContext) }
    private val profileStore by lazy { ProfileStore(applicationContext) }

    private var contactsStore: ContactsRepository? = null
    private val contacts: ContactsRepository
        get() = contactsStore ?: ContactsRepository(applicationContext).also { contactsStore = it }
    private val retentionStore by lazy { RetentionStore(applicationContext) }
    private val keyCache = KeyCache()

    @Volatile private var connectedPeers: Set<ByteArray> = emptySet()
    @Volatile private var lastAnnounceAtMs: Long = 0L

    @Volatile private var secureChannel: SecureChannel? = null
    @Volatile private var groupManager: GroupManager? = null
    @Volatile private var channelManager: ChannelManager? = null
    @Volatile private var relayTransport: InternetRelayTransport? = null
    private val peerProfiles = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()

    private fun connectedPeersHex(): Set<String> =
        connectedPeers.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()

    val identity: Identity by lazy {
        identityStore.load()
            ?: AndroidKeystoreIdentityStore.generateAndStore(applicationContext)
            ?: error("Unable to initialize secure identity")
    }

    val senderId: ByteArray get() = identity.senderId
    val nickname: String get() = profileStore.load().nickname

    override fun onCreate() {
        super.onCreate()
        setStartError(null)
        tryInitialize()
    }

    private fun tryInitialize() {
        if (initialized) return
        if (!startForegroundCompat()) {
            return
        }

        val missing = missingBlePermissions()
        if (missing.isNotEmpty()) {
            setStartError(
                "Bluetooth permission missing: ${missing.joinToString()}. " +
                    "Grant it and tap \"Start mesh\" again."
            )
            stopSelf()
            return
        }

        val id = runCatching { identity }.getOrNull()
        if (id == null) {
            // Foregrounded already (startForegroundCompat ran first), so this
            // stop is safe. Say why and stop cleanly instead of crashing.
            setStartError(
                "Secure identity could not be created or loaded. " +
                    "See the secure-storage notice in the app, or wipe app data and try again."
            )
            stopSelf()
            return
        }
        val t = BleTransport(
            context = applicationContext,
            localId = id.senderId,
            scope = scope,
            announceIdentityVerifier = { pkt -> AnnounceVerifier.verifyAnnounceIdentity(pkt) },
        )
        val r = MeshRouter(
            ownId = id.senderId,
            transport = t,
            config = RouterConfig(),
            neighboursProvider = { connectedPeers },
            connectedPeersProvider = { connectedPeers },
            signatureVerifier = { pkt, from -> AnnounceVerifier.verify(pkt, keyCache, from) },
        )
        transport = t
        router = r

        // 1. Group Manager
        val gm = GroupManager(
            localIdentity = id,
            localNickname = { profileStore.load().nickname },
            localAvatarIndex = { profileStore.load().avatarIndex.toByte() },
        )
        groupManager = gm

        // 2. Channel Manager
        val cm = ChannelManager(
            ownIdHex = id.senderId.joinToString("") { "%02x".format(it) }
        )
        channelManager = cm

        // 3. Internet Relay Transport (Nostr)
        val rt = InternetRelayTransport(
            context = applicationContext,
            localIdHex = id.senderId.joinToString("") { "%02x".format(it) },
            scope = scope,
        )
        relayTransport = rt

        // 4. Secure Channel (Noise XX E2EE)
        val channel = SecureChannel(
            identity = id,
            router = r,
            keyCache = keyCache,
            contacts = contacts,
            outbox = contacts.secure.outbox,
            dedupe = contacts.secure.seen,
            messageStore = contacts.secure.messages,
            sessionDirectory = contacts.secure.sessions,
            connectedPeers = { connectedPeersHex() },
            retentionDays = { retentionStore.load().days },
            onGroupInvite = { inviteBytes ->
                gm.offerInvite(inviteBytes)
            },
        )
        secureChannel = channel

        // Flow bindings: Private Chat
        jobs += scope.launch { channel.messages.collect { setPrivateMessages(it) } }
        jobs += scope.launch { channel.peers.collect { setPrivatePeers(it) } }
        jobs += scope.launch { channel.keyChange.collect { setKeyChange(it) } }

        // Flow bindings: Groups
        jobs += scope.launch { gm.groups.collect { setGroups(it.values.toList()) } }
        jobs += scope.launch { gm.messages.collect { setGroupMessages(it) } }
        jobs += scope.launch { gm.pendingInvites.collect { setPendingGroupInvites(it) } }

        // Flow bindings: Channels
        jobs += scope.launch { cm.channels.collect { setChannels(it) } }
        jobs += scope.launch { cm.activeChannel.collect { setActiveChannel(it) } }
        jobs += scope.launch { cm.messages.collect { setChannelMessages(it) } }

        // Flow bindings: Internet Relay
        jobs += scope.launch { rt.status.collect { setRelayStatus(it) } }
        jobs += scope.launch {
            rt.incomingMessages.collect { (channelName, text) ->
                cm.addMessage(
                    ChannelMessageUi(
                        messageIdHex = "nostr-${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}",
                        channel = channelName,
                        senderIdHex = "nostr-relay",
                        senderNickname = "Nostr Peer",
                        senderAvatarIndex = 0,
                        text = text,
                        timestampMs = System.currentTimeMillis(),
                        isSelf = false,
                    )
                )
            }
        }

        // Transport incoming -> Router
        jobs += scope.launch {
            t.incoming.collect { (from, bytes) -> runCatching { r.onReceive(bytes, from) } }
        }
        jobs += scope.launch {
            t.neighbours.collect { set -> connectedPeers = set.map { it.copyOf() }.toSet() }
        }

        // Router delivered packets dispatcher
        jobs += scope.launch {
            r.deliveredWithPeer.collect { (from, pkt) ->
                runCatching {
                    when (pkt.type) {
                        PacketType.GROUP_MESSAGE -> {
                            gm.onIncomingGroupPacket(pkt)
                        }
                        PacketType.CHANNEL_MESSAGE -> {
                            val channelMsg = GroupPayloads.decodeChannelMessage(pkt.payload)
                            val senderHex = pkt.senderId.joinToString("") { "%02x".format(it) }
                            val prof = peerProfiles[senderHex]
                            cm.addMessage(
                                ChannelMessageUi(
                                    messageIdHex = pkt.messageId.joinToString("") { "%02x".format(it) },
                                    channel = channelMsg.channel,
                                    senderIdHex = senderHex,
                                    senderNickname = prof?.first ?: senderHex.take(4).uppercase(),
                                    senderAvatarIndex = prof?.second ?: 0,
                                    text = channelMsg.text,
                                    timestampMs = pkt.timestampMs,
                                    isSelf = pkt.senderId.contentEquals(id.senderId),
                                )
                            )
                        }
                        PacketType.PUBLIC_MESSAGE -> {
                            val text = Payloads.decodePublicText(pkt.payload)
                            val senderHex = pkt.senderId.joinToString("") { "%02x".format(it) }
                            val prof = peerProfiles[senderHex]
                            pushPublicMessage(
                                PublicChatMessage(
                                    messageIdHex = pkt.messageId.joinToString("") { "%02x".format(it) },
                                    senderIdHex = senderHex,
                                    senderNickname = prof?.first ?: senderHex.take(4).uppercase(),
                                    senderAvatarIndex = prof?.second ?: 0,
                                    text = text,
                                    timestampMs = pkt.timestampMs,
                                    isSelf = pkt.senderId.contentEquals(id.senderId),
                                    hops = (PacketCodec.MAX_TTL - pkt.ttl).coerceAtLeast(0),
                                )
                            )
                        }
                        else -> {
                            channel.onPacket(pkt, from)
                        }
                    }
                }
            }
        }

        // Periodic maintenance
        jobs += scope.launch {
            while (true) {
                runCatching { channel.tick() }
                runCatching { t.sweepUnannouncedLinks() }
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

        jobs += scope.launch {
            t.newLink.collect { runCatching { sendAnnounce() } }
        }

        jobs += scope.launch {
            r.delivered.collect { pkt ->
                if (pkt.type == PacketType.ANNOUNCE) {
                    val announce = runCatching { Payloads.decodeAnnounce(pkt.payload) }.getOrNull()
                    if (announce != null) {
                        peerProfiles[pkt.senderId.joinToString("") { "%02x".format(it) }] =
                            announce.nickname to announce.avatarIndex.toInt()
                    }
                    val isNew = runCatching { t.confirmPeerIdentity(pkt.senderId) }.getOrDefault(false)
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

        // Stats snapshot
        jobs += scope.launch {
            while (true) {
                val s = r.stats
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

        // QR-verified contacts are trusted immediately
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
        setStartError(null)
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
        runCatching { r.onLocalBroadcast(signed(PacketType.ANNOUNCE, payload)) }
    }

    /** Public Nearby chat (signed Ed25519, broadcast). */
    suspend fun sendPublic(text: String) {
        val r = router ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val pkt = signed(PacketType.PUBLIC_MESSAGE, Payloads.encodePublicText(clean))
        r.onLocalSend(pkt)

        val prof = profileStore.load()
        pushPublicMessage(
            PublicChatMessage(
                messageIdHex = pkt.messageId.joinToString("") { "%02x".format(it) },
                senderIdHex = identity.senderId.joinToString("") { "%02x".format(it) },
                senderNickname = prof.nickname,
                senderAvatarIndex = prof.avatarIndex.toInt(),
                text = clean,
                timestampMs = pkt.timestampMs,
                isSelf = true,
                hops = 0,
            )
        )
    }

    /** Broadcasts a channel message across the BLE mesh and optionally Nostr relays. */
    suspend fun sendChannelMessage(channel: String, text: String) {
        val r = router ?: return
        val id = identity
        val clean = text.trim()
        if (clean.isEmpty()) return
        val payload = GroupPayloads.encodeChannelMessage(GroupPayloads.ChannelMessage(channel, clean))
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.CHANNEL_MESSAGE, PacketCodec.MAX_TTL, 0,
            System.currentTimeMillis(), payload, id.senderId, PacketCodec.BROADCAST_ID,
        )
        val signedPkt = unsigned.copy(
            flags = 0x02, // HAS_SIGNATURE
            signature = id.signEd25519(PacketCodec.signingInput(unsigned)),
        )
        r.onLocalBroadcast(signedPkt)

        val prof = profileStore.load()
        channelManager?.addMessage(
            ChannelMessageUi(
                messageIdHex = signedPkt.messageId.joinToString("") { "%02x".format(it) },
                channel = channel,
                senderIdHex = id.senderId.joinToString("") { "%02x".format(it) },
                senderNickname = prof.nickname,
                senderAvatarIndex = prof.avatarIndex.toInt(),
                text = clean,
                timestampMs = signedPkt.timestampMs,
                isSelf = true,
            )
        )

        relayTransport?.publishChannelMessage(channel, clean)
    }

    /** Sends an encrypted group message. */
    suspend fun sendGroupMessage(groupIdHex: String, text: String) {
        val r = router ?: return
        val gm = groupManager ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val pkt = gm.createOutgoingGroupPacket(groupIdHex, clean) ?: return
        r.onLocalBroadcast(pkt)
    }

    /** Creates a new group and sends Noise-encrypted invites to members. */
    suspend fun createGroup(name: String, avatarIndex: Byte, initialMembers: List<GroupPayloads.RosterMember>) {
        val gm = groupManager ?: return
        val sc = secureChannel ?: return
        val (_, invites) = gm.createGroup(name, avatarIndex, initialMembers)
        for ((peerIdHex, inviteBytes) in invites) {
            sc.sendRaw(peerIdHex, inviteBytes)
        }
    }

    /** Executes IRC-style slash command. */
    suspend fun executeCommand(commandText: String, activeChannel: String = "#general") {
        val result = CommandParser.parse(commandText)
        when (result) {
            is CommandParser.ParseResult.NotCommand -> {
                if (activeChannel.startsWith("#")) {
                    sendChannelMessage(activeChannel, result.text)
                } else {
                    sendPublic(result.text)
                }
            }
            is CommandParser.ParseResult.Executable -> {
                when (val cmd = result.command) {
                    is CommandParser.ChatCommand.Help -> {
                        val helpText = CommandParser.formatHelp(cmd.topic)
                        pushSystemNotification("Meshline Help", helpText)
                    }
                    is CommandParser.ChatCommand.Join -> {
                        channelManager?.setActiveChannel(cmd.channel)
                        pushSystemNotification("Channel Joined", "Switched to ${cmd.channel}")
                    }
                    is CommandParser.ChatCommand.Channels -> {
                        val active = channelManager?.currentActiveChannel() ?: "#general"
                        pushSystemNotification("Channels", "Active channel: $active")
                    }
                    is CommandParser.ChatCommand.Msg -> {
                        secureChannel?.send(cmd.recipient, cmd.messageText)
                    }
                    is CommandParser.ChatCommand.Who -> {
                        val count = connectedPeers.size
                        pushSystemNotification("Mesh Neighbours", "$count active direct peer link(s) in radio range.")
                    }
                    is CommandParser.ChatCommand.Nick -> {
                        val current = profileStore.load()
                        profileStore.save(current.copy(nickname = cmd.newNick.take(32)))
                        pushSystemNotification("Nickname Updated", "Your nickname is now: ${cmd.newNick.take(32)}")
                        sendAnnounce()
                    }
                    is CommandParser.ChatCommand.Block -> {
                        contacts.block(cmd.target)
                        pushSystemNotification("Peer Blocked", "Blocked: ${cmd.target}")
                    }
                    is CommandParser.ChatCommand.Unblock -> {
                        contacts.unblock(cmd.target)
                        pushSystemNotification("Peer Unblocked", "Unblocked: ${cmd.target}")
                    }
                    is CommandParser.ChatCommand.Clear -> {
                        if (activeChannel.startsWith("#")) {
                            channelManager?.clearChannelMessages(activeChannel)
                        }
                    }
                    is CommandParser.ChatCommand.Slap -> {
                        val nick = profileStore.load().nickname
                        val slapText = "* $nick slaps ${cmd.target} around a bit with a large trout *"
                        if (activeChannel.startsWith("#")) {
                            sendChannelMessage(activeChannel, slapText)
                        } else {
                            sendPublic(slapText)
                        }
                    }
                }
            }
            is CommandParser.ParseResult.Unknown -> {
                pushSystemNotification("Unknown Command", "Unknown command: /${result.commandName}. Type /help for assistance.")
            }
            is CommandParser.ParseResult.Error -> {
                pushSystemNotification("Command Error", "${result.message}\nUsage: ${result.usage}")
            }
        }
    }

    private fun signed(type: PacketType, payload: ByteArray): MeshPacket {
        val id = identity
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, type, PacketCodec.MAX_TTL, 0,
            System.currentTimeMillis(), payload, id.senderId, PacketCodec.BROADCAST_ID,
        )
        return unsigned.copy(
            flags = 0x02, // HAS_SIGNATURE
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
            ACTION_CREATE_GROUP -> {
                val name = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "Mesh Group"
                val icon = intent.getByteExtra(EXTRA_AVATAR_INDEX, 0)
                val pubkeys = intent.getStringArrayExtra(EXTRA_MEMBERS_PUBLIC_KEYS) ?: emptyArray()
                scope.launch {
                    val allContacts = contacts.all()
                    val members = pubkeys.mapNotNull { hexKey ->
                        runCatching {
                            val pk = hexKey.hexToBytes()
                            val c = allContacts.firstOrNull { it.ed25519PublicKey.contentEquals(pk) }
                            GroupPayloads.RosterMember(
                                ed25519PublicKey = pk,
                                nickname = c?.nickname ?: hexKey.take(8),
                                avatarIndex = c?.avatarIndex?.toByte() ?: 0,
                            )
                        }.getOrNull()
                    }
                    createGroup(name, icon, members)
                }
            }
            ACTION_SEND_GROUP_MESSAGE -> {
                val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (groupId != null && text != null) {
                    scope.launch { sendGroupMessage(groupId, text) }
                }
            }
            ACTION_ACCEPT_GROUP_INVITE -> {
                intent.getStringExtra(EXTRA_GROUP_ID)?.let { gid ->
                    groupManager?.acceptInvite(gid)
                }
            }
            ACTION_DECLINE_GROUP_INVITE -> {
                intent.getStringExtra(EXTRA_GROUP_ID)?.let { gid ->
                    groupManager?.declineInvite(gid)
                }
            }
            ACTION_LEAVE_GROUP -> {
                intent.getStringExtra(EXTRA_GROUP_ID)?.let { gid ->
                    groupManager?.leaveGroup(gid)
                }
            }
            ACTION_CLEAR_GROUP_UNREAD -> {
                intent.getStringExtra(EXTRA_GROUP_ID)?.let { gid ->
                    groupManager?.clearUnread(gid)
                }
            }
            ACTION_SEND_CHANNEL_MESSAGE -> {
                val ch = intent.getStringExtra(EXTRA_CHANNEL) ?: "#general"
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (text != null) {
                    scope.launch { sendChannelMessage(ch, text) }
                }
            }
            ACTION_SET_ACTIVE_CHANNEL -> {
                intent.getStringExtra(EXTRA_CHANNEL)?.let { ch ->
                    channelManager?.setActiveChannel(ch)
                }
            }
            ACTION_SET_INTERNET_RELAY -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                relayTransport?.setEnabled(enabled)
            }
            ACTION_EXECUTE_COMMAND -> {
                val cmdText = intent.getStringExtra(EXTRA_COMMAND_TEXT) ?: ""
                val ch = intent.getStringExtra(EXTRA_CHANNEL) ?: "#general"
                if (cmdText.isNotBlank()) {
                    scope.launch { executeCommand(cmdText, ch) }
                }
            }
        }
        return START_STICKY
    }

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
        setGroups(emptyList())
        setGroupMessages(emptyMap())
        setPendingGroupInvites(emptyList())
        setPublicMessages(emptyList())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        announceJob?.cancel()
        jobs.forEach { it.cancel() }
        transport?.stopInternal()
        contactsStore?.close()
        relayTransport?.stop()
        scope.cancel()
        super.onDestroy()
    }

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
        const val ACTION_CREATE_GROUP = "com.meshline.app.action.CREATE_GROUP"
        const val ACTION_SEND_GROUP_MESSAGE = "com.meshline.app.action.SEND_GROUP_MESSAGE"
        const val ACTION_ACCEPT_GROUP_INVITE = "com.meshline.app.action.ACCEPT_GROUP_INVITE"
        const val ACTION_DECLINE_GROUP_INVITE = "com.meshline.app.action.DECLINE_GROUP_INVITE"
        const val ACTION_LEAVE_GROUP = "com.meshline.app.action.LEAVE_GROUP"
        const val ACTION_CLEAR_GROUP_UNREAD = "com.meshline.app.action.CLEAR_GROUP_UNREAD"
        const val ACTION_SEND_CHANNEL_MESSAGE = "com.meshline.app.action.SEND_CHANNEL_MESSAGE"
        const val ACTION_SET_ACTIVE_CHANNEL = "com.meshline.app.action.SET_ACTIVE_CHANNEL"
        const val ACTION_SET_INTERNET_RELAY = "com.meshline.app.action.SET_INTERNET_RELAY"
        const val ACTION_EXECUTE_COMMAND = "com.meshline.app.action.EXECUTE_COMMAND"

        const val EXTRA_TEXT = "text"
        const val EXTRA_PEER_ID = "peerIdHex"
        const val EXTRA_MESSAGE_ID = "messageIdHex"
        const val EXTRA_RETENTION_DAYS = "retentionDays"
        const val EXTRA_GROUP_ID = "groupIdHex"
        const val EXTRA_GROUP_NAME = "groupName"
        const val EXTRA_AVATAR_INDEX = "avatarIndex"
        const val EXTRA_MEMBERS_PUBLIC_KEYS = "membersPubkeys"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_COMMAND_TEXT = "commandText"

        private const val CHANNEL_ID = "meshline_mesh"
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_UNITS: Byte = 3

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

        private val _startError = MutableStateFlow<String?>(null)
        val startError: StateFlow<String?> = _startError.asStateFlow()

        // Private Chat
        private val _privateMessages = MutableStateFlow<List<PrivateChatMessage>>(emptyList())
        val privateMessages: StateFlow<List<PrivateChatMessage>> = _privateMessages.asStateFlow()
        private val _privatePeers = MutableStateFlow<List<PrivatePeer>>(emptyList())
        val privatePeers: StateFlow<List<PrivatePeer>> = _privatePeers.asStateFlow()
        private val _keyChange = MutableStateFlow<KeyChangeEvent?>(null)
        val keyChange: StateFlow<KeyChangeEvent?> = _keyChange.asStateFlow()

        // Public Nearby Chat
        private val _publicChatMessages = MutableStateFlow<List<PublicChatMessage>>(emptyList())
        val publicChatMessages: StateFlow<List<PublicChatMessage>> = _publicChatMessages.asStateFlow()

        // Groups
        private val _groups = MutableStateFlow<List<GroupItem>>(emptyList())
        val groups: StateFlow<List<GroupItem>> = _groups.asStateFlow()
        private val _groupMessages = MutableStateFlow<Map<String, List<GroupMessageUi>>>(emptyMap())
        val groupMessages: StateFlow<Map<String, List<GroupMessageUi>>> = _groupMessages.asStateFlow()
        private val _pendingGroupInvites = MutableStateFlow<List<PendingGroupInvite>>(emptyList())
        val pendingGroupInvites: StateFlow<List<PendingGroupInvite>> = _pendingGroupInvites.asStateFlow()

        // Channels
        private val _channels = MutableStateFlow<List<ChannelItem>>(ChannelManager.DEFAULT_CHANNELS)
        val channels: StateFlow<List<ChannelItem>> = _channels.asStateFlow()
        private val _activeChannel = MutableStateFlow("#general")
        val activeChannel: StateFlow<String> = _activeChannel.asStateFlow()
        private val _channelMessages = MutableStateFlow<Map<String, List<ChannelMessageUi>>>(emptyMap())
        val channelMessages: StateFlow<Map<String, List<ChannelMessageUi>>> = _channelMessages.asStateFlow()

        // Internet Relay
        private val _relayStatus = MutableStateFlow(RelayStatus())
        val relayStatus: StateFlow<RelayStatus> = _relayStatus.asStateFlow()

        // System notification
        private val _systemNotification = MutableStateFlow<String?>(null)
        val systemNotification: StateFlow<String?> = _systemNotification.asStateFlow()

        internal fun setPeers(v: List<PeerInfo>) { _peers.value = v }
        internal fun pushMessage(m: ReceivedMessageInfo) { _messages.value = (_messages.value + m).takeLast(50) }
        internal fun setGattError(c: Int) { _lastGattError.value = c }
        internal fun setAdvertiseSupported(b: Boolean) { _advertiseSupported.value = b }
        internal fun setStats(s: MeshStatsSnapshot) { _stats.value = s }
        internal fun setStartError(reason: String?) { _startError.value = reason }
        internal fun setPrivateMessages(v: List<PrivateChatMessage>) { _privateMessages.value = v }
        internal fun setPrivatePeers(v: List<PrivatePeer>) { _privatePeers.value = v }
        internal fun setKeyChange(v: KeyChangeEvent?) { _keyChange.value = v }

        internal fun pushPublicMessage(m: PublicChatMessage) {
            _publicChatMessages.value = (_publicChatMessages.value + m).takeLast(200)
        }
        internal fun setPublicMessages(v: List<PublicChatMessage>) { _publicChatMessages.value = v }
        internal fun setGroups(g: List<GroupItem>) { _groups.value = g }
        internal fun setGroupMessages(m: Map<String, List<GroupMessageUi>>) { _groupMessages.value = m }
        internal fun setPendingGroupInvites(invites: List<PendingGroupInvite>) { _pendingGroupInvites.value = invites }
        internal fun setChannels(c: List<ChannelItem>) { _channels.value = c }
        internal fun setActiveChannel(a: String) { _activeChannel.value = a }
        internal fun setChannelMessages(m: Map<String, List<ChannelMessageUi>>) { _channelMessages.value = m }
        internal fun setRelayStatus(s: RelayStatus) { _relayStatus.value = s }
        internal fun pushSystemNotification(title: String, msg: String) {
            _systemNotification.value = "$title: $msg"
        }
        fun clearSystemNotification() { _systemNotification.value = null }

        private fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
