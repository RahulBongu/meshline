package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.meshline.app.protocol.MessageIds
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Secure sessions on disk (NOISE_DESIGN §6.1, PROTOCOL.md §7).
 *
 * The table lives in the SQLCipher-encrypted database, so the rows are
 * ciphertext at rest: the database key is wrapped by the Android Keystore, and
 * without the device key the file is useless.
 *
 * **Ephemeral private keys are never stored** (§3.5) — only the derived
 * session state. The `transportKey` column is the place for the Noise
 * transport key material; it is currently always `null` because
 * `noise-java` 0.1.1 cannot export it (see [SessionCodec] and PROTOCOL.md
 * §6.5). A session restored from the store therefore re-handshakes silently
 * instead of resuming in place — the behaviour NOISE_DESIGN §6.1 prescribes
 * for session loss.
 */
@Entity(tableName = "noise_sessions")
data class NoiseSessionEntity(
    @PrimaryKey val peerIdHex: String,
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val fingerprint: String,
    /** "ABSENT" | "INITIATING" | "RESP_AWAIT" | "ESTABLISHED" | "CLOSED". */
    val state: String,
    val sendNonce: Long,
    val recvNonce: Long,
    /** Serialised replay window (see `ReplayWindow.serialize`). */
    val replayWindow: ByteArray,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val messagesSent: Int,
    val messagesReceived: Int,
    /** Noise transport key material, when the library can export it (today: never). */
    val transportKey: ByteArray?,
    /** True when the peer was verified by QR / in person (§7.2). */
    val verified: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoiseSessionEntity) return false
        return peerIdHex == other.peerIdHex &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            x25519PublicKey.contentEquals(other.x25519PublicKey) &&
            fingerprint == other.fingerprint &&
            state == other.state &&
            sendNonce == other.sendNonce &&
            recvNonce == other.recvNonce &&
            replayWindow.contentEquals(other.replayWindow) &&
            createdAtMs == other.createdAtMs &&
            lastUsedAtMs == other.lastUsedAtMs &&
            messagesSent == other.messagesSent &&
            messagesReceived == other.messagesReceived &&
            ((transportKey == null && other.transportKey == null) ||
                (transportKey != null && other.transportKey != null && transportKey.contentEquals(other.transportKey))) &&
            verified == other.verified
    }

    override fun hashCode(): Int {
        var h = peerIdHex.hashCode()
        h = 31 * h + ed25519PublicKey.contentHashCode()
        h = 31 * h + x25519PublicKey.contentHashCode()
        h = 31 * h + fingerprint.hashCode()
        h = 31 * h + state.hashCode()
        h = 31 * h + sendNonce.hashCode()
        h = 31 * h + recvNonce.hashCode()
        h = 31 * h + replayWindow.contentHashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + lastUsedAtMs.hashCode()
        h = 31 * h + messagesSent
        h = 31 * h + messagesReceived
        h = 31 * h + (transportKey?.contentHashCode() ?: 0)
        h = 31 * h + verified.hashCode()
        return h
    }
}

@Dao
interface NoiseSessionDao {
    @Query("SELECT * FROM noise_sessions ORDER BY lastUsedAtMs DESC")
    suspend fun all(): List<NoiseSessionEntity>

    @Query("SELECT * FROM noise_sessions WHERE peerIdHex = :hex")
    suspend fun find(hex: String): NoiseSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NoiseSessionEntity)

    @Query("DELETE FROM noise_sessions WHERE peerIdHex = :hex")
    suspend fun delete(hex: String)

    /** Panic wipe (§7): every session goes. */
    @Query("DELETE FROM noise_sessions")
    suspend fun deleteAll()
}

/**
 * The storeable part of a session.
 *
 * [transportKey] is the Noise transport key material. It is `null` today:
 * `noise-java` 0.1.1 exposes no way to export the transport keys
 * (`HandshakeState` has no key getter and `SymmetricState` is package
 * private), so a session cannot be resumed byte-for-byte after a restart. The
 * column exists so a future library with an export path only has to fill it
 * in; until then a restored session re-handshakes silently (§6.1).
 */
data class SessionSnapshot(
    val peerIdHex: String,
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val fingerprint: String,
    val state: String,
    val sendNonce: Long,
    val recvNonce: Long,
    val replayWindow: ByteArray,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val messagesSent: Int,
    val messagesReceived: Int,
    val transportKey: ByteArray?,
    val verified: Boolean,
)

/**
 * Versioned binary codec for a [SessionSnapshot] (pure JVM, no Android).
 *
 * The snapshot is *not* encrypted here: it is written into the SQLCipher
 * database, which is already encrypted at rest with a Keystore-wrapped key.
 * Encrypting twice would only add a second key to lose in a panic wipe.
 */
object SessionCodec {
    /** "MLNS" — Meshline Noise Session. */
    private val MAGIC = byteArrayOf(0x4D, 0x4C, 0x4E, 0x53)
    private const val VERSION = 1

    /** Sanity bounds for the blobs a snapshot may carry. */
    private const val MAX_KEY_BYTES = 1024
    private const val MAX_WINDOW_BYTES = 4096

    /** Raised when a stored snapshot cannot be decoded. */
    class MalformedSnapshot(message: String) : Exception(message)

    fun encode(snapshot: SessionSnapshot): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeByte(VERSION)
            d.writeUTF(snapshot.peerIdHex)
            d.writeInt(snapshot.ed25519PublicKey.size)
            d.write(snapshot.ed25519PublicKey)
            d.writeInt(snapshot.x25519PublicKey.size)
            d.write(snapshot.x25519PublicKey)
            d.writeUTF(snapshot.fingerprint)
            d.writeUTF(snapshot.state)
            d.writeLong(snapshot.sendNonce)
            d.writeLong(snapshot.recvNonce)
            d.writeInt(snapshot.replayWindow.size)
            d.write(snapshot.replayWindow)
            d.writeLong(snapshot.createdAtMs)
            d.writeLong(snapshot.lastUsedAtMs)
            d.writeInt(snapshot.messagesSent)
            d.writeInt(snapshot.messagesReceived)
            val key = snapshot.transportKey
            d.writeBoolean(key != null)
            if (key != null) {
                d.writeInt(key.size)
                d.write(key)
            }
            d.writeBoolean(snapshot.verified)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): SessionSnapshot {
        try {
            DataInputStream(bytes.inputStream()).use { d ->
                val magic = ByteArray(4)
                d.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw MalformedSnapshot("bad magic")
                val version = d.readUnsignedByte()
                if (version != VERSION) throw MalformedSnapshot("unsupported version $version")
                val peerIdHex = d.readUTF()
                val ed = readSized(d, MAX_KEY_BYTES)
                val x = readSized(d, MAX_KEY_BYTES)
                val fingerprint = d.readUTF()
                val state = d.readUTF()
                val sendNonce = d.readLong()
                val recvNonce = d.readLong()
                val window = readSized(d, MAX_WINDOW_BYTES)
                val createdAtMs = d.readLong()
                val lastUsedAtMs = d.readLong()
                val sent = d.readInt()
                val received = d.readInt()
                val transportKey = if (d.readBoolean()) readSized(d, MAX_KEY_BYTES) else null
                val verified = d.readBoolean()
                return SessionSnapshot(
                    peerIdHex = peerIdHex,
                    ed25519PublicKey = ed,
                    x25519PublicKey = x,
                    fingerprint = fingerprint,
                    state = state,
                    sendNonce = sendNonce,
                    recvNonce = recvNonce,
                    replayWindow = window,
                    createdAtMs = createdAtMs,
                    lastUsedAtMs = lastUsedAtMs,
                    messagesSent = sent,
                    messagesReceived = received,
                    transportKey = transportKey,
                    verified = verified,
                )
            }
        } catch (e: MalformedSnapshot) {
            throw e
        } catch (e: OutOfMemoryError) {
            // A corrupt length field must never be able to allocate.
            throw MalformedSnapshot("session snapshot declares an impossible size")
        } catch (e: Exception) {
            throw MalformedSnapshot("cannot decode session snapshot: ${e.message}")
        }
    }

    /**
     * Reads a length-prefixed blob, refusing impossible lengths. The snapshot
     * may come from disk, so a corrupt length must not be able to allocate
     * gigabytes.
     */
    private fun readSized(d: DataInputStream, max: Int): ByteArray {
        val len = d.readInt()
        if (len < 0 || len > max) throw MalformedSnapshot("bad blob length $len (max $max)")
        val out = ByteArray(len)
        d.readFully(out)
        return out
    }

    /** Builds a snapshot for a peer from its identity keys. */
    fun snapshotOf(
        peerIdHex: String,
        ed25519PublicKey: ByteArray,
        x25519PublicKey: ByteArray,
        state: String,
        sendNonce: Long,
        recvNonce: Long,
        replayWindow: ByteArray,
        createdAtMs: Long,
        lastUsedAtMs: Long,
        messagesSent: Int,
        messagesReceived: Int,
        transportKey: ByteArray? = null,
        verified: Boolean = false,
    ): SessionSnapshot = SessionSnapshot(
        peerIdHex = peerIdHex,
        ed25519PublicKey = ed25519PublicKey,
        x25519PublicKey = x25519PublicKey,
        fingerprint = MessageIds.fingerprint(ed25519PublicKey, x25519PublicKey),
        state = state,
        sendNonce = sendNonce,
        recvNonce = recvNonce,
        replayWindow = replayWindow,
        createdAtMs = createdAtMs,
        lastUsedAtMs = lastUsedAtMs,
        messagesSent = messagesSent,
        messagesReceived = messagesReceived,
        transportKey = transportKey,
        verified = verified,
    )
}
