@file:Suppress("DEPRECATION")
package com.meshline.app.transport

import android.annotation.SuppressLint

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.meshline.app.protocol.Fragmentation
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Reassembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.min

/**
 * Real BLE transport (Phase C): dual-role GATT server + client over the frozen
 * [BleUuids] service/characteristic.
 *
 * - Advertise the service UUID; always scan with a **service-UUID filter**.
 *   Peers are identified by the ANNOUNCE *packet* (peeked senderId, bytes
 *   16..24) after connecting — never by the advertisement (PROTOCOL.md §9).
 * - Request MTU 512; anything larger fragments via the codec (§5) and each
 *   fragment travels as one GATT write.
 * - Cap concurrent connections ([BleUuids.MAX_CONNECTIONS]); the lower senderId
 *   acts as central (see `shouldActAsCentral`).
 * - GATT error 133 (or any connect failure) retries with backoff + fresh
 *   connection. Scan restarts are throttled to ≤5 starts per 30 s (Android's
 *   own scan-restart throttle).
 * - Must run inside a foreground service (connected-device) with a persistent
 *   notification (Phase E). BLE does not work on emulators — physical devices
 *   only.
 *
 * The peer-id mapping problem: BLE discovers MAC addresses, but the router
 * addresses packets by *senderId* (truncated SHA-256 of the Ed25519 key).
 * This transport therefore:
 *   1. emits incoming packets tagged with the peeked senderId (so dedupe /
 *      source-exclusion work without a second round-trip), and
 *   2. maps senderId ⇄ BluetoothDevice whenever an incoming packet from a
 *      device reveals its senderId, or the app layer calls [registerPeer].
 * A broadcast that needs no mapping (`broadcast()`) writes to every open
 * connection directly.
 */
@SuppressLint("MissingPermission") // Permissions checked once at start() via canConnect(); lint cannot follow that.
class BleTransport(
    private val context: Context,
    override val localId: ByteArray,
    private val scope: CoroutineScope,
    private val maxConnections: Int = BleUuids.MAX_CONNECTIONS,
    /**
     * Verifies an ANNOUNCE's identity claim (self-certifying: the announced
     * key's hash must equal the claimed `senderId` and the signature must
     * verify with that key — no cache, no prior trust).
     *
     * The transport needs it to bind a **device** to an identity: the packet
     * header only *claims* an id, so a forged announce could otherwise claim
     * somebody else's id and pull our traffic to the impostor. The app layer
     * injects `AnnounceVerifier.verifyAnnounceIdentity`.
     */
    private val announceIdentityVerifier: (MeshPacket) -> ByteArray? = { null },
) : Transport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _incoming = MutableSharedFlow<Pair<ByteArray, ByteArray>>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<Pair<ByteArray, ByteArray>> = _incoming.asSharedFlow()

    private val _neighbours = MutableStateFlow<Set<ByteArray>>(emptySet())
    override val neighbours: Flow<Set<ByteArray>> = _neighbours.asStateFlow()

    /** Debug overlay (Phase C #1 + #4): peers with MTU/RSSI/role, last GATT error, advertise capability. */
    private val _debugPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val debugPeers: Flow<List<PeerInfo>> = _debugPeers.asStateFlow()
    private val _lastGattError = MutableStateFlow<Int?>(null)
    val lastGattError: Flow<Int?> = _lastGattError.asStateFlow()
    private val _advertiseSupported = MutableStateFlow(true)
    val advertiseSupported: Flow<Boolean> = _advertiseSupported.asStateFlow()

    // --- state ---
    private var server: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    // Concurrent: the BLE callbacks mutate these from binder threads while the
    // send path iterates them from the service's coroutines.
    private val clients = ConcurrentHashMap<String, BluetoothGatt>() // mac -> gatt (we are central)
    private val serverLinks: MutableSet<String> = ConcurrentHashMap.newKeySet() // macs that connected to our GATT server (we are peripheral)
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>() // mac -> device (we are peripheral)
    private val mtuByMac = ConcurrentHashMap<String, Int>()
    private val rssiByMac = ConcurrentHashMap<String, Int>()
    private val senderToDevice = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceToSender = ConcurrentHashMap<String, ByteArray>()

    /**
     * The peers whose identity we **verified** (the app layer confirmed it
     * after the router checked the peer's signed ANNOUNCE).
     *
     * A packet header carries a *claimed* senderId, so the id cannot be trusted
     * before the signature has been checked. Only confirmed peers are
     * addressable and only confirmed peers count as neighbours — otherwise a
     * peer could claim somebody else's id and pull our traffic to itself.
     */
    private val verifiedSenders = ConcurrentHashMap.newKeySet<String>()

    /**
     * Per-link bookkeeping, used by the slot-exhaustion guard and by the
     * duplicate-link arbitration.
     *
     * A link only becomes *usable* when the peer proves its identity with a
     * verified ANNOUNCE. Until then the link costs us one of the six GATT
     * slots (BleUuids.MAX_CONNECTIONS) and gives us nothing.
     *
     * A peer can hold **two** physical links to us (we connected to it *and*
     * it connected to our server), so the record tracks both kinds.
     */
    private class LinkRecord(val openedAtMs: Long) {
        @Volatile var lastTrafficAtMs: Long = openedAtMs
        @Volatile var verified: Boolean = false
        @Volatile var central: Boolean = false
        @Volatile var peripheral: Boolean = false
    }

    private val links = ConcurrentHashMap<String, LinkRecord>()

    /**
     * The peers whose **central** link we gave up in the duplicate-link
     * arbitration (we are the higher id).
     *
     * The lower id keeps the canonical link, so reconnecting as central would
     * only flip the two nodes back and forth — every flip costs a connect and
     * a slot. The suppression is lifted when the peer is gone entirely.
     */
    private val suppressedCentral = ConcurrentHashMap.newKeySet<String>()

    private val _newLink = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /**
     * Emits whenever a **new** peer link comes up (central or peripheral).
     *
     * The app layer uses it to announce itself: a peer learns our id only from
     * our ANNOUNCE, and the periodic announce (30 s) would leave the link
     * unusable for most of that cycle.
     */
    val newLink: Flow<Unit> = _newLink.asSharedFlow()
    private val rawReassembler = BleReassemblyManager()

    /** Protocol-level fragments (a fragmented ANNOUNCE) — see [maybeBindAnnounce]. */
    private val fragReassembler = Reassembler()
    private val writeQueue = kotlinx.coroutines.channels.Channel<Pair<BluetoothDevice, ByteArray>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var writeJob: Job? = null
    private val scanStartTimes = CopyOnWriteArraySet<Long>()
    private var scanning = false
    private var advertising = false

    private fun canConnect(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission("android.permission.BLUETOOTH_SCAN") == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") == PackageManager.PERMISSION_GRANTED
        } else true

    fun start() {
        if (!canConnect()) {
            Log.w(TAG, "Missing BLE runtime permissions; not starting scanner/advertiser")
            return
        }
        startServer()
        startAdvertising()
        startScanning()
        startWriteWorker()
    }

    fun stopInternal() {
        stopScanning()
        stopAdvertising()
        clients.values.forEach { safeClose(it) }
        clients.clear()
        serverLinks.clear()
        serverDevices.clear()
        links.clear()
        server?.close()
        server = null
        writeJob?.cancel()
    }

    // ------------------------------------------------------------ server side

    private fun startServer() {
        val ad = adapter ?: return
        if (server != null) return
        server = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val mac = device.address
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    if (mac != null) {
                        serverLinks.add(mac)
                        serverDevices[mac] = device
                        openLink(mac, central = false)
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    if (mac != null) {
                        serverLinks.remove(mac)
                        serverDevices.remove(mac)
                        closeLink(mac, central = false)
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) _lastGattError.value = status
                }
                refreshDebugPeers()
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                mtuByMac[device.address] = min(mtu, BleUuids.REQUESTED_MTU)
                refreshDebugPeers()
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (characteristic.uuid == BleUuids.CHARACTERISTIC_UUID) {
                    emit(device, value)
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
        })
        val service = android.bluetooth.BluetoothGattService(
            BleUuids.SERVICE_UUID,
            android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val char = BluetoothGattCharacteristic(
            BleUuids.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(char)
        server?.addService(service)
        serverCharacteristic = char
    }

    // --------------------------------------------------------- advertise/scan

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            _advertiseSupported.value = false
            Log.w(TAG, "BluetoothLeAdvertiser is null — advertising unsupported on this device; continuing scan+server only (client-only chat)")
            refreshDebugPeers()
            return
        }
        val multiSupported = adapter?.isMultipleAdvertisementSupported() ?: false
        Log.i(TAG, "isMultipleAdvertisementSupported=$multiSupported")
        if (!multiSupported) {
            _advertiseSupported.value = false
            Log.w(TAG, "multiple advertisement unsupported — advertising may fail; continuing")
        }
        if (advertising) return
        if (Build.VERSION.SDK_INT >= 31 && !canConnect()) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(android.os.ParcelUuid(BleUuids.SERVICE_UUID)).build()
        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings) { advertising = true }
            override fun onStartFailure(code: Int) { Log.w(TAG, "advertise failed: $code") }
        })
    }

    private fun stopAdvertising() {
        if (Build.VERSION.SDK_INT < 31 && !canConnect()) return
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        advertising = false
    }

    private fun startScanning() {
        if (scanning) return
        // Android throttles ~5 scan starts per 30 s; our session is long-lived, but
        // restarts (e.g. after failure) must respect the same bound.
        val now = System.currentTimeMillis()
        scanStartTimes.removeIf { now - it > 30_000 }
        if (scanStartTimes.size >= 5) {
            Log.w(TAG, "scan restart throttled (${scanStartTimes.size} starts/30s)")
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleUuids.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val mac = device.address
                if (mac != null) {
                    rssiByMac[mac] = result.rssi
                    refreshDebugPeers()
                }
                maybeConnect(device)
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { maybeConnect(it.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                scanning = false
            }
        })
        scanning = true
        scanStartTimes.add(now)
    }

    private fun stopScanning() {
        adapter?.bluetoothLeScanner?.stopScan(object : ScanCallback() {})
        scanning = false
    }

    // ------------------------------------------------------------ client side

    private val retryAttempts = mutableMapOf<String, Int>()

    private fun maybeConnect(device: BluetoothDevice) {
        val mac = device.address ?: return
        if (clients.containsKey(mac)) return
        if (clients.size >= maxConnections) return
        // We gave up this peer's central link in the duplicate arbitration:
        // reconnecting would only flip the two nodes back and forth.
        if (suppressedCentral.contains(mac)) return
        // Lower-ID-as-central tie-break is enforced *after* Announce reveals both
        // IDs; pre-Announce we connect once and rely on the server-side handling.
        // (Phase C keeps the simple side; Phase E tightens the pre-connect decision.)
        connectWithRetry(device, attempt = 0)
    }

    private fun connectWithRetry(device: BluetoothDevice, attempt: Int) {
        val mac = device.address ?: return
        if (suppressedCentral.contains(mac)) return
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) _lastGattError.value = status
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    retryAttempts.remove(mac)
                    openLink(mac, central = true)
                    g.requestMtu(BleUuids.REQUESTED_MTU)
                    g.discoverServices()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    // The slot must be released here: a stale entry in
                    // `clients` would block the reconnect AND count against
                    // the six-connection budget (slot exhaustion).
                    // Only when *this* GATT is the current one — a stale
                    // object's disconnect must not evict a live link.
                    if (clients[mac] === g) {
                        clients.remove(mac)
                        closeLink(mac, central = true)
                    }
                    safeClose(g)
                    if (status == 133) handle133(g, device, attempt) else scheduleRetry(device, attempt)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                mtuByMac[mac] = min(mtu, BleUuids.REQUESTED_MTU)
                clients[mac] = g
                if (status != BluetoothGatt.GATT_SUCCESS) _lastGattError.value = status
                refreshNeighbours()
                refreshDebugPeers()
            }

            override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                rssiByMac[mac] = rssi
                refreshDebugPeers()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val char = g.getService(BleUuids.SERVICE_UUID)?.getCharacteristic(BleUuids.CHARACTERISTIC_UUID) ?: return
                g.setCharacteristicNotification(char, true)
                char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
                clients[mac] = g
                refreshNeighbours()
                refreshDebugPeers()
                runCatching { g.readRemoteRssi() }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == BleUuids.CHARACTERISTIC_UUID) emit(device, characteristic.value)
            }

            @Deprecated("Use the callback with byte[] variant on API 33+")
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                // Write-without-response is fire-and-forget; only log failures.
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write failed $status")
            }
        }, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M)
        clients[mac] = gatt
    }

    private fun handle133(g: BluetoothGatt, device: BluetoothDevice, attempt: Int) {
        safeClose(g)
        val mac = device.address
        if (mac != null && clients[mac] === g) {
            clients.remove(mac)
            closeLink(mac, central = true)
        }
        val wait = min(8_000L, 1_000L shl attempt) // 1s, 2s, 4s, 8s
        Log.w(TAG, "GATT 133 for ${device.address}; retrying in ${wait}ms (attempt $attempt)")
        scope.launch {
            delay(wait)
            if (attempt < 5) connectWithRetry(device, attempt + 1)
        }
    }

    private fun scheduleRetry(device: BluetoothDevice, attempt: Int) {
        val wait = min(8_000L, 1_000L shl attempt)
        scope.launch {
            delay(wait)
            if (attempt < 5) connectWithRetry(device, attempt + 1)
        }
    }

    private fun safeClose(g: BluetoothGatt) {
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    // ------------------------------------------------------------ link state

    /** A peer link came up (central: we connected; peripheral: it connected). */
    private fun openLink(mac: String, central: Boolean) {
        val existing = links[mac]
        if (existing == null) {
            val rec = LinkRecord(System.currentTimeMillis())
            if (central) rec.central = true else rec.peripheral = true
            links[mac] = rec
            // The peer only learns our id from an announce: tell the app layer
            // so it can announce itself on this link right away.
            runCatching { _newLink.tryEmit(Unit) }
            return
        }
        if (central) existing.central = true else existing.peripheral = true
        existing.lastTrafficAtMs = System.currentTimeMillis()
    }

    /** One of the two links to [mac] is gone. */
    private fun closeLink(mac: String, central: Boolean) {
        val rec = links[mac] ?: return
        if (central) rec.central = false else rec.peripheral = false
        if (rec.central || rec.peripheral) return
        links.remove(mac)
        rawReassembler.drop(mac)
        // The peer is gone entirely: a future connection may be tried again.
        suppressedCentral.remove(mac)
    }

    /**
     * How many links are up but have **not** yet proved a peer identity.
     *
     * The app layer uses it to announce again (a lost announce would leave
     * the link unusable for a whole announce cycle).
     */
    val unprovenLinkCount: Int
        get() {
            val now = System.currentTimeMillis()
            return links.count { (_, rec) ->
                !rec.verified && now - rec.openedAtMs < LinkEvictionPolicy.UNPROVEN_INTEREST_MS
            }
        }

    /**
     * Closes the links that never produced a usable peer identity.
     *
     * Six GATT slots are the whole connection budget
     * ([BleUuids.MAX_CONNECTIONS]). A device that connects and stays silent —
     * or talks without ever proving who it is — would hold a slot forever and
     * starve the mesh, so such a link is closed and the slot released.
     *
     * Both link kinds can be closed: a central link with a GATT close, a
     * peripheral link with `BluetoothGattServer.cancelConnection` (the peer
     * connected to *our* server).
     *
     * @return how many links were closed.
     */
    fun sweepUnannouncedLinks(nowMs: Long = System.currentTimeMillis()): Int {
        var evicted = 0
        for ((mac, rec) in links.entries.toList()) {
            if (rec.verified) continue
            if (!LinkEvictionPolicy.shouldEvict(rec.openedAtMs, rec.lastTrafficAtMs, nowMs)) continue
            if (closeLinkTo(mac)) evicted++
        }
        return evicted
    }

    /** Closes **every** link to [mac] (central and peripheral). True when there was one. */
    private fun closeLinkTo(mac: String): Boolean {
        var closed = false
        clients.remove(mac)?.let {
            Log.w(TAG, "closing link: no verified announce")
            safeClose(it)
            closed = true
        }
        serverDevices.remove(mac)?.let { device ->
            Log.w(TAG, "closing server link: no verified announce")
            serverLinks.remove(mac)
            runCatching { server?.cancelConnection(device) }
            closed = true
        }
        links.remove(mac)
        suppressedCentral.remove(mac)
        rawReassembler.drop(mac)
        return closed
    }

    // ---------------------------------------------------------------- sends

    override suspend fun sendTo(peer: ByteArray, packet: ByteArray) {
        val hex = VirtualNetwork.hex(peer)
        val device = senderToDevice[hex]
        if (device == null) {
            // Unknown senderId yet (pre-Announce): cannot address. Broadcast is the only path.
            Log.w(TAG, "sendTo unknown peer; dropping")
            return
        }
        if (!verifiedSenders.contains(hex)) {
            // The peer claimed this id but never proved it (no verified
            // ANNOUNCE): we do not hand our traffic to an unproven id.
            Log.w(TAG, "sendTo unverified peer; dropping")
            return
        }
        sendToDevice(device, packet)
    }

    override suspend fun broadcast(packet: ByteArray) {
        // Per-connection MTU: fragment separately for each connection's MTU.
        // Two link kinds exist and both must be served:
        //  - **central**: we connected to the peer, so we write to it;
        //  - **peripheral**: the peer connected to *our* GATT server, so the
        //    only way to reach it is a notification from the server.
        // A peer that holds both links (the duplicate-link case) is served
        // through the central one — the arbiter closes the other.
        for (gatt in clients.values) sendToDevice(gatt.device, packet)
        for ((mac, device) in serverDevices) {
            if (clients.containsKey(mac)) continue
            sendToDevice(device, packet)
        }
    }

    /**
     * Send [packet] to one connection using **that connection's negotiated MTU**.
     * Falls back to raw chunking (BleChunker) when the MTU is too small for a
     * MeshPacket FRAGMENT (payload < 95), e.g. MTU 23.
     */
    private suspend fun sendToDevice(device: BluetoothDevice, packet: ByteArray) {
        val mtu = mtuByMac[device.address] ?: BleUuids.MAX_PACKET_PER_WRITE
        val payloadCap = mtu - 3
        if (packet.size <= payloadCap) {
            writeQueue.send(device to packet)
            return
        }
        if (payloadCap < Fragmentation.FRAGMENT_PREFIX_BYTES + PacketCodec.HEADER_BYTES) {
            // Tiny MTU: Mesh FRAGMENT cannot fit. Raw-chunk the whole packet.
            for (chunk in BleChunker.split(packet, payloadCap)) writeQueue.send(device to chunk)
            return
        }
        val pkt = PacketCodec.decode(packet)
        val fragments = Fragmentation.split(pkt, packet, payloadCap)
        for (f in fragments) writeQueue.send(device to PacketCodec.encode(f))
    }

    private fun startWriteWorker() {
        if (writeJob?.isActive == true) return
        writeJob = scope.launch {
            for ((device, bytes) in writeQueue) {
                try { writeOnce(device, bytes) } catch (_: Exception) { /* log in production */ }
                delay(50) // GATT serialization headroom
            }
        }
    }

    private fun writeOnce(device: BluetoothDevice, bytes: ByteArray) {
        val mac = device.address
        // Central link: a GATT write (write-without-response).
        val gatt = clients[mac]
        if (gatt != null) {
            val char = gatt.getService(BleUuids.SERVICE_UUID)?.getCharacteristic(BleUuids.CHARACTERISTIC_UUID) ?: return
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = bytes
            gatt.writeCharacteristic(char)
            return
        }
        // Peripheral link: the peer connected to *our* GATT server, so it is
        // reached with a server notification. The peer enabled notifications
        // when it discovered our characteristic (the CCCD write in
        // onServicesDiscovered), so this is the only way it can hear us.
        notifyServerLink(device, bytes)
    }

    /** Notifies one peripheral peer (it connected to our GATT server). */
    private fun notifyServerLink(device: BluetoothDevice, bytes: ByteArray) {
        val srv = server ?: return
        val char = serverCharacteristic ?: return
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+: the value travels with the call.
            srv.notifyCharacteristicChanged(device, char, false, bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            // Android 12 and older: the value must sit on the characteristic.
            char.value = bytes
            srv.notifyCharacteristicChanged(device, char, false)
        }
        if (!ok) Log.w(TAG, "notify failed")
    }

    // -------------------------------------------------------------- receive

    private fun emit(device: BluetoothDevice, bytes: ByteArray) {
        // Raw-chunk path (tiny MTU): reassemble fully before the router sees it.
        // Reassembly is capped per connection (bytes + in-flight), so an
        // unauthenticated peer cannot exhaust memory.
        if (BleChunker.isChunk(bytes)) {
            val assembled = rawReassembler.offer(device.address ?: device.toString(), bytes) ?: return
            emit(device, assembled)
            return
        }
        val mac = device.address
        links[mac]?.lastTrafficAtMs = System.currentTimeMillis()
        val header = peekHeader(bytes)
        // A peer's identity comes from its **own** signed ANNOUNCE, and only
        // from a packet that originated at that peer (TTL == MAX_TTL). A
        // relayed packet carries the *origin's* id, so learning from it would
        // map this peer's device to somebody else's identity — and the peer
        // would vanish from the neighbours set.
        if (header != null) maybeBindAnnounce(device, bytes, header)
        val senderId = header?.senderId ?: hashMac(device.address)
        _incoming.tryEmit(senderId to bytes)
    }

    /**
     * Binds this link to the peer's identity — but only when the announce
     * **proves** it.
     *
     * A packet header only claims a `senderId`; the binding is what makes a
     * peer addressable, so a forged announce must not be able to claim
     * somebody else's id. The announce is therefore verified here (the
     * app layer injects the verifier), and only a verified announce binds
     * the device to the id.
     *
     * A fragmented announce (a small negotiated MTU) is reassembled here too:
     * the router reassembles it again for the packet flow, but the router
     * cannot tell the transport *which device* delivered it.
     */
    private fun maybeBindAnnounce(device: BluetoothDevice, bytes: ByteArray, header: HeaderPeek) {
        // A relayed packet carries the origin's id — it is not this peer.
        if (header.ttl != PacketCodec.MAX_TTL) return
        when (header.type) {
            PacketType.ANNOUNCE -> {
                val pkt = runCatching { PacketCodec.decode(bytes) }.getOrNull() ?: return
                val id = announceIdentityVerifier(pkt) ?: return
                registerPeer(id, device)
            }
            PacketType.FRAGMENT -> {
                val pkt = runCatching { PacketCodec.decode(bytes) }.getOrNull() ?: return
                val parts = runCatching { Fragmentation.parseFragmentPayload(pkt.payload) }.getOrNull() ?: return
                // Only the first chunk tells the original's type; the others
                // are pure payload, so a data fragment is skipped without
                // reassembling it.
                if (parts.index != 0 || parts.chunk.size < 2) return
                if ((parts.chunk[1].toInt() and 0xFF) != PacketType.ANNOUNCE.code.toInt()) return
                val assembled = runCatching { fragReassembler.offer(pkt) }.getOrNull() ?: return
                val full = runCatching { PacketCodec.decode(assembled) }.getOrNull() ?: return
                val id = announceIdentityVerifier(full) ?: return
                registerPeer(id, device)
            }
            else -> Unit
        }
    }

    /** The header fields the transport needs before the router sees the packet. */
    private class HeaderPeek(val type: PacketType, val ttl: Int, val senderId: ByteArray)

    private fun peekHeader(bytes: ByteArray): HeaderPeek? {
        if (bytes.size < PacketCodec.HEADER_BYTES) return null
        if ((bytes[0].toInt() and 0xFF) != PacketCodec.PROTOCOL_VERSION) return null
        val type = PacketType.fromCode(bytes[1]) ?: return null
        val ttl = bytes[2].toInt() and 0xFF
        val senderId = bytes.copyOfRange(16, 24)
        return HeaderPeek(type, ttl, senderId)
    }

    private fun hashMac(mac: String?): ByteArray {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest((mac ?: "").encodeToByteArray())
        return h.copyOfRange(0, 8)
    }

    /**
     * Records the identity of a peer we are linked to.
     *
     * Called only for a peer's **own** ANNOUNCE (see [emit]), so the mapping
     * MAC ⇄ senderId always belongs to the peer on that link. The id is not
     * trusted yet: the app layer confirms it with [confirmPeerIdentity] after
     * the router verified the announce's signature.
     */
    private fun registerPeer(senderId: ByteArray, device: BluetoothDevice) {
        val mac = device.address ?: return
        val hex = VirtualNetwork.hex(senderId)
        val previous = deviceToSender[mac]
        if (previous != null && !previous.contentEquals(senderId)) {
            // The peer changed its identity (a panic wipe): the old id is
            // dead — nobody may be addressed with it any more.
            val oldHex = VirtualNetwork.hex(previous)
            senderToDevice.remove(oldHex)
            verifiedSenders.remove(oldHex)
            // The new identity deserves a fresh arbitration.
            suppressedCentral.remove(mac)
        }
        senderToDevice[hex] = device
        deviceToSender[mac] = senderId
        refreshNeighbours()
        refreshDebugPeers()
        maybeCloseDuplicate(mac, senderId)
    }

    /**
     * If we hold both a central (client) and a peripheral (server) link to the
     * same device, keep exactly one — the lower senderId's central link.
     * The higher node drops its central link and logs `duplicate link closed`.
     *
     * Dropping the central link closes the *physical* link, so the lower id's
     * server half disappears with it: the pair is left with one link, and
     * traffic still flows both ways (the higher id reaches the lower one
     * through the server notification path).
     */
    private fun maybeCloseDuplicate(mac: String?, senderId: ByteArray) {
        if (mac == null) return
        val hasCentral = clients.containsKey(mac)
        val hasPeripheral = serverLinks.contains(mac)
        if (!hasCentral || !hasPeripheral) return
        val decision = LinkArbiter.resolveDuplicate(localId, senderId, duplicateLink = true)
        if (decision == LinkArbiter.DuplicateDecision.DROP_LOCAL_CENTRAL) {
            Log.i(TAG, "duplicate link closed (higher ID dropped its central link)")
            // Never reconnect as central to this peer: the lower id keeps the
            // canonical link, and every reconnect would flip the two nodes
            // back and forth (and burn a slot each time).
            suppressedCentral.add(mac)
            clients.remove(mac)?.let { safeClose(it) }
        } else {
            Log.i(TAG, "duplicate link closed (lower ID kept central; peer will drop its duplicate)")
        }
        refreshDebugPeers()
    }

    /**
     * Confirms that [senderId] is the genuine identity of a peer we are
     * linked to.
     *
     * The app layer calls this **only** after the router verified the peer's
     * signed ANNOUNCE (`AnnounceVerifier`), so the id is bound to a key the
     * peer proved it owns. Until then the peer is not addressable and is not
     * a neighbour.
     *
     * @return true when this is a **new** identity — the caller announces
     * itself then, so the new peer learns us too.
     */
    fun confirmPeerIdentity(senderId: ByteArray): Boolean {
        val hex = VirtualNetwork.hex(senderId)
        val isNew = verifiedSenders.add(hex)
        if (!isNew) return false
        // The link that carries this identity is now usable.
        for ((mac, id) in deviceToSender) {
            if (id.contentEquals(senderId)) links[mac]?.verified = true
        }
        refreshNeighbours()
        refreshDebugPeers()
        return true
    }

    /**
     * True when [senderId] is a peer we are **directly** linked to (we know
     * which device it is).
     *
     * A relayed announce carries the origin's id, and that origin may be
     * several hops away — it is not addressable and it is not a neighbour.
     */
    fun isDirectPeer(senderId: ByteArray): Boolean =
        senderToDevice.containsKey(VirtualNetwork.hex(senderId))

    private fun refreshNeighbours() {
        // Only the peers whose identity we verified are neighbours: the router
        // uses this set for the direct-send shortcut (rule 5) and the adaptive
        // relay probability (rule 6).
        _neighbours.value = deviceToSender.values
            .filter { verifiedSenders.contains(VirtualNetwork.hex(it)) }
            .mapTo(mutableSetOf()) { it.copyOf() }
    }

    /** Debug overlay snapshot: central + peripheral peers with MTU/RSSI/role. */
    private fun refreshDebugPeers() {
        val macs = (clients.keys + serverLinks + deviceToSender.keys + rssiByMac.keys + mtuByMac.keys).distinct()
        _debugPeers.value = macs.mapNotNull { mac ->
            val sender = deviceToSender[mac]
            PeerInfo(
                peerIdHex = sender?.joinToString("") { "%02x".format(it) }
                    ?: ("mac:" + mac),
                rssiDbm = rssiByMac[mac],
                mtu = mtuByMac[mac],
                role = if (clients.containsKey(mac)) "central" else "peripheral",
            )
        }
    }

    override suspend fun connect(peer: ByteArray) { senderToDevice[VirtualNetwork.hex(peer)]?.let { maybeConnect(it) } }
    override suspend fun disconnect(peer: ByteArray) {
        val hex = VirtualNetwork.hex(peer)
        val device = senderToDevice.remove(hex) ?: return
        verifiedSenders.remove(hex)
        deviceToSender.remove(device.address)
        closeLinkTo(device.address)
        refreshNeighbours()
        refreshDebugPeers()
    }
    override fun stop() = stopInternal()

    /** Lower senderId (unsigned lexicographic) acts as central for a pair. */
    fun shouldActAsCentral(a: ByteArray, b: ByteArray): Boolean {
        val n = min(a.size, b.size)
        for (i in 0 until n) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            if (av != bv) return av < bv
        }
        return a.size <= b.size
    }

    companion object {
        private const val TAG = "BleTransport"
    }
}
