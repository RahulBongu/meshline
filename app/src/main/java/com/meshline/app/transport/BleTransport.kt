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
import com.meshline.app.protocol.PacketCodec
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
    private val clients = mutableMapOf<String, BluetoothGatt>() // mac -> gatt (we are central)
    private val serverLinks = mutableSetOf<String>() // macs that connected to our GATT server (we are peripheral)
    private val mtuByMac = mutableMapOf<String, Int>()
    private val rssiByMac = mutableMapOf<String, Int>()
    private val senderToDevice = mutableMapOf<String, BluetoothDevice>()
    private val deviceToSender = mutableMapOf<String, ByteArray>()
    private val rawReassembler = BleReassemblyManager()
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
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    serverLinks.add(device.address)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    serverLinks.remove(device.address)
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
        // Lower-ID-as-central tie-break is enforced *after* Announce reveals both
        // IDs; pre-Announce we connect once and rely on the server-side handling.
        // (Phase C keeps the simple side; Phase E tightens the pre-connect decision.)
        connectWithRetry(device, attempt = 0)
    }

    private fun connectWithRetry(device: BluetoothDevice, attempt: Int) {
        val mac = device.address ?: return
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) _lastGattError.value = status
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    retryAttempts.remove(mac)
                    g.requestMtu(BleUuids.REQUESTED_MTU)
                    g.discoverServices()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
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
        clients.remove(device.address)
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

    // ---------------------------------------------------------------- sends

    override suspend fun sendTo(peer: ByteArray, packet: ByteArray) {
        val hex = VirtualNetwork.hex(peer)
        val device = senderToDevice[hex]
        if (device == null) {
            // Unknown senderId yet (pre-Announce): cannot address. Broadcast is the only path.
            Log.w(TAG, "sendTo unknown peer; dropping")
            return
        }
        sendToDevice(device, packet)
    }

    override suspend fun broadcast(packet: ByteArray) {
        // Per-connection MTU: fragment separately for each connection's MTU.
        // (Server links from peers that connected to us are written via the
        // characteristic notify path in production; Phase C writes via clients.)
        for (gatt in clients.values) sendToDevice(gatt.device, packet)
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
        val gatt = clients[device.address] ?: return
        val char = gatt.getService(BleUuids.SERVICE_UUID)?.getCharacteristic(BleUuids.CHARACTERISTIC_UUID) ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value = bytes
        gatt.writeCharacteristic(char)
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
        val senderId = peekSenderId(bytes) ?: hashMac(device.address)
        registerPeer(senderId, device)
        _incoming.tryEmit(senderId to bytes)
    }

    private fun peekSenderId(bytes: ByteArray): ByteArray? {
        if (bytes.size < 24) return null
        return bytes.copyOfRange(16, 24)
    }

    private fun hashMac(mac: String?): ByteArray {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest((mac ?: "").encodeToByteArray())
        return h.copyOfRange(0, 8)
    }

    /** Called by the app layer once it knows a device's identity (Announce). */
    fun registerPeer(senderId: ByteArray, device: BluetoothDevice) {
        val hex = VirtualNetwork.hex(senderId)
        senderToDevice[hex] = device
        deviceToSender[device.address] = senderId
        refreshNeighbours()
        refreshDebugPeers()
        maybeCloseDuplicate(device.address, senderId)
    }

    /**
     * If we hold both a central (client) and a peripheral (server) link to the
     * same device, keep exactly one — the lower senderId's central link.
     * The higher node drops its central link and logs `duplicate link closed`.
     */
    private fun maybeCloseDuplicate(mac: String?, senderId: ByteArray) {
        if (mac == null) return
        val hasCentral = clients.containsKey(mac)
        val hasPeripheral = serverLinks.contains(mac)
        if (!hasCentral || !hasPeripheral) return
        val decision = LinkArbiter.resolveDuplicate(localId, senderId, duplicateLink = true)
        if (decision == LinkArbiter.DuplicateDecision.DROP_LOCAL_CENTRAL) {
            Log.i(TAG, "duplicate link closed (higher ID dropped its central link)")
            clients.remove(mac)?.let { safeClose(it) }
        } else {
            Log.i(TAG, "duplicate link closed (lower ID kept central; peer will drop its duplicate)")
        }
        refreshDebugPeers()
    }

    private fun refreshNeighbours() {
        _neighbours.value = deviceToSender.values.mapTo(mutableSetOf()) { it.copyOf() }
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
        val device = senderToDevice.remove(VirtualNetwork.hex(peer)) ?: return
        deviceToSender.remove(device.address)
        clients.remove(device.address)?.let { safeClose(it) }
        rawReassembler.drop(device.address)
        serverLinks.remove(device.address)
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
