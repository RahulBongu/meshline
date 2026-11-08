package com.meshline.app.transport

/**
 * Debug-overlay data (Phase C request 1). Pure JVM data holders so the
 * service/UI layers and JVM tests share the shapes without Android types.
 */
data class PeerInfo(
    /** 16-char hex of the 8-byte senderId (or hashed MAC before Announce). */
    val peerIdHex: String,
    /** Best-known RSSI in dBm (scan, remote-read, or last notify); null if never measured. */
    val rssiDbm: Int?,
    /** Negotiated MTU for this connection; null before onMtuChanged/onServer mTu. */
    val mtu: Int?,
    /** CENTRAL if we initiated via GATT client, PERIPHERAL if the peer connected to our server. */
    val role: String, // "central" | "peripheral"
)

data class ReceivedMessageInfo(
    val senderIdHex: String,
    val typeName: String,
    val receivedTtl: Int,
    /** Hops = origin TTL (7) − received TTL. Only meaningful for TTL-7-originated floods. */
    val hops: Int,
)
