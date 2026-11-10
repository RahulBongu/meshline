package com.meshline.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed identity store (D1).
 *
 * Raw Ed25519/X25519 keys cannot live in the Android Keystore on minSdk 26,
 * so each private key is encrypted separately with a non-exportable Keystore
 * AES-256-GCM key (alias below). Each ciphertext carries its **own** IV; the
 * ciphertext + IV are stored in SharedPreferences. Public keys are stored
 * plaintext because they are public.
 *
 * Panic wipe deletes both prefs and the Keystore key, so old QR codes and
 * senderIds become invalid (the private keys are gone).
 *
 * [load] fails closed: if the Keystore key was invalidated (OS upgrade on some
 * OEMs) or the ciphertext is corrupt, it returns null instead of throwing, and
 * the app generates a fresh identity.
 *
 * JVM unit tests use [InMemoryIdentityStore] (same shape, no Keystore).
 */
interface IdentityStore {
    fun load(): Identity?
    fun save(identity: Identity, edPrivate: ByteArray, xPrivate: ByteArray)
    suspend fun wipe()
}

object IdentityFactory {
    /** Generate a fresh Ed25519 + X25519 identity (BouncyCastle). */
    fun generate(): Triple<Identity, ByteArray, ByteArray> {
        val edGen = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(java.security.SecureRandom())
        val xGen = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom())
        val edPk = edGen.generatePublicKey().encoded
        val xPk = xGen.generatePublicKey().encoded
        val edPriv = edGen.encoded
        val xPriv = xGen.encoded
        return Triple(Identity(edPk, xPk, edPriv, xPriv), edPriv, xPriv)
    }
}

class AndroidKeystoreIdentityStore(private val context: Context) : IdentityStore {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Identity? {
        // No stored identity at all is a normal first launch, not a reset.
        if (!prefs.contains("ed_wrapped")) return null
        val identity = runCatching {
            val edPub = prefs.getString("ed_pub", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: error("identity incomplete: ed_pub")
            val xPub = prefs.getString("x_pub", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: error("identity incomplete: x_pub")
            val edWrapped = prefs.getString("ed_wrapped", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: error("identity incomplete: ed_wrapped")
            val xWrapped = prefs.getString("x_wrapped", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: error("identity incomplete: x_wrapped")
            val edIv = prefs.getString("ed_iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: error("identity incomplete: ed_iv")
            val xIv = prefs.getString("x_iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: error("identity incomplete: x_iv")
            val key = keystoreKey(createIfMissing = false) ?: error("Keystore wrap key missing")
            val edPriv = decrypt(edWrapped, edIv, key)
            val xPriv = decrypt(xWrapped, xIv, key)
            Identity(edPub, xPub, edPriv, xPriv)
        }.getOrNull()
        if (identity == null) {
            // Fail closed and tell the UI: a new identity will be created.
            SecureStorageStatus.record(context, SecureStorageStatus.Reason.KEYSTORE_INVALIDATED)
        }
        return identity
    }

    override fun save(identity: Identity, edPrivate: ByteArray, xPrivate: ByteArray) {
        val key = keystoreKey(createIfMissing = true)!!
        val (edWrapped, edIv) = encrypt(edPrivate, key)
        val (xWrapped, xIv) = encrypt(xPrivate, key)
        prefs.edit()
            .putString("ed_pub", Base64.encodeToString(identity.ed25519PublicKey, Base64.NO_WRAP))
            .putString("x_pub", Base64.encodeToString(identity.x25519PublicKey, Base64.NO_WRAP))
            .putString("ed_wrapped", Base64.encodeToString(edWrapped, Base64.NO_WRAP))
            .putString("x_wrapped", Base64.encodeToString(xWrapped, Base64.NO_WRAP))
            .putString("ed_iv", Base64.encodeToString(edIv, Base64.NO_WRAP))
            .putString("x_iv", Base64.encodeToString(xIv, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun wipe() {
        prefs.edit().clear().apply()
        deleteWrapKey()
    }

    /** Deletes the wrapping key; old ciphertext can never be decrypted again. */
    fun deleteWrapKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        runCatching { ks.deleteEntry(ALIAS) }
    }

    private fun keystoreKey(createIfMissing: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!createIfMissing) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns ciphertext + the random IV the cipher chose for this operation. */
    private fun encrypt(plaintext: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plaintext) to cipher.iv
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val PREFS = "meshline_identity"
        private const val ALIAS = "meshline-key-wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        fun generateAndStore(context: Context): Identity {
            val store = AndroidKeystoreIdentityStore(context)
            // A reset may leave an unusable entry behind (OEM invalidation);
            // replace it so the new identity is writable and decryptable.
            store.deleteWrapKey()
            val (identity, edPriv, xPriv) = IdentityFactory.generate()
            store.save(identity, edPriv, xPriv)
            return identity
        }
    }
}

/** JVM-test double (same public shape, keeps keys in memory only). */
class InMemoryIdentityStore : IdentityStore {
    var identity: Identity? = null
    private var edWrapped: ByteArray? = null
    private var xWrapped: ByteArray? = null

    override fun load(): Identity? = identity

    override fun save(identity: Identity, edPrivate: ByteArray, xPrivate: ByteArray) {
        this.identity = identity
        edWrapped = edPrivate.copyOf()
        xWrapped = xPrivate.copyOf()
    }

    override suspend fun wipe() {
        identity = null
        edWrapped = null
        xWrapped = null
    }
}
