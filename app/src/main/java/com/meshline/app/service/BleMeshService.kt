package com.meshline.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import com.meshline.app.protocol.MessageIds
import com.meshline.app.router.MeshRouter
import com.meshline.app.router.RouterConfig
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Phase C service: owns BLE + MeshRouter, sends Announce, relays public chat.
 *
 * - Identity is **ephemeral per process** in Phase C (random nickname
 *   `anonXXXX` + random 8-byte senderId + random placeholder key bytes in the
 *   Announce payload). Phase D replaces this with Keystore-backed
 *   Ed25519/X25519 pairs and real signatures; the router's verifier is
 *   permissive until then.
 * - Announce is sent on start and every ~30 s ± jitter (PROTOCOL.md §8.8).
 * - Runs as a foreground service (connected-device) with a persistent
 *   notification — Phase E polishes the notification/channel and power modes.
 * - BLE does not work on emulators. Test on two physical phones (see README).
 */
class BleMeshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: BleTransport? = null
    private var router: MeshRouter? = null
    private val jobs = mutableListOf<Job>()
    private var announceJob: Job? = null

    // Ephemeral Phase C identity (Phase D: Keystore-backed).
    val nickname: String by lazy { "anon" + (1000 + Random.nextInt(9000)) }
    val senderId: ByteArray by lazy {
        ByteArray(8).also { SecureRandom().nextBytes(it) }.apply {
            if (contentEquals(PacketCodec.BROADCAST_ID)) this[0] = 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Foreground notification is posted by startForeground in Phase E;
        // in Phase C the service must still be started as FGS to keep BLE alive.
        val t = BleTransport(applicationContext, senderId, scope)
        val r = MeshRouter(senderId, t, RouterConfig(), neighboursProvider = { emptySet() })
        transport = t
        router = r
        jobs += scope.launch {
            t.incoming.collect { (from, bytes) -> runCatching { r.onReceive(bytes, from) } }
        }
        jobs += scope.launch { t.debugPeers.collect { setPeers(it) } }
        jobs += scope.launch { t.lastGattError.collect { if (it != null) setGattError(it) } }
        jobs += scope.launch { t.advertiseSupported.collect { setAdvertiseSupported(it) } }
        jobs += scope.launch {
            r.delivered.collect { pkt ->
                val hops = (PacketCodec.MAX_TTL - pkt.ttl).coerceAtLeast(0)
                pushMessage(ReceivedMessageInfo(
                    senderIdHex = pkt.senderId.joinToString("") { "%02x".format(it) },
                    typeName = pkt.type.name,
                    receivedTtl = pkt.ttl,
                    hops = hops,
                ))
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
        val payload = Payloads.encodeAnnounce(
            Payloads.Announce(
                nickname = nickname,
                // Phase D: real keypair public bytes. Phase C placeholders let the
                // codec shape be exercised end-to-end (router is permissive).
                ed25519PublicKey = ByteArray(32) { 0x11 },
                x25519PublicKey = ByteArray(32) { 0x22 },
                capabilities = Payloads.Capabilities.RELAY_ENABLED,
            )
        )
        val packet = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.ANNOUNCE, PacketCodec.MAX_TTL, 0,
            System.currentTimeMillis(), payload, senderId, PacketCodec.BROADCAST_ID
        )
        runCatching { r.onLocalSend(packet) }
    }

    /** Debug overlay flows (Phase C #1): peers, received messages, last GATT error, advertising. */
    companion object Bridge {
        private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
        val peers: Flow<List<PeerInfo>> = _peers.asStateFlow()
        private val _messages = MutableStateFlow<List<ReceivedMessageInfo>>(emptyList())
        val messages: Flow<List<ReceivedMessageInfo>> = _messages.asStateFlow()
        private val _lastGattError = MutableStateFlow<Int?>(null)
        val lastGattError: Flow<Int?> = _lastGattError.asStateFlow()
        private val _advertiseSupported = MutableStateFlow(true)
        val advertiseSupported: Flow<Boolean> = _advertiseSupported.asStateFlow()

        internal fun setPeers(v: List<PeerInfo>) { _peers.value = v }
        internal fun pushMessage(m: ReceivedMessageInfo) { _messages.value = (_messages.value + m).takeLast(50) }
        internal fun setGattError(c: Int) { _lastGattError.value = c }
        internal fun setAdvertiseSupported(b: Boolean) { _advertiseSupported.value = b }

        private const val TAG = "BleMeshService"
    }

    /** Public Nearby chat (signed in Phase D; unsigned + labelled "not encrypted" in Phase C). */
    suspend fun sendPublic(text: String) {
        val r = router ?: return
        val payload = Payloads.encodePublicText(text.trim())
        val packet = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.PUBLIC_MESSAGE, PacketCodec.MAX_TTL, 0,
            System.currentTimeMillis(), payload, senderId, PacketCodec.BROADCAST_ID
        )
        r.onLocalSend(packet)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        announceJob?.cancel()
        jobs.forEach { it.cancel() }
        transport?.stopInternal()
        scope.cancel()
        super.onDestroy()
    }
}
