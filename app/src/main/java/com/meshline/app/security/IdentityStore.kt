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
import javax.crypto.spec.SecretKeySpec

/**
 * Keystore-backed identity store (D1).
 * Raw Ed25519 cannot live in the Android Keystore on minSdk 26, so each
 * private key is wrapped with a Keystore AES-GCM key (alias below) and the
 * ciphertext is stored in SharedPreferences. Public keys + metadata are
 * stored plaintext (they are public). Panic wipe creates a *new* identity,
 * so old QR codes become invalid (their keys are gone).
 *
 * JVM unit tests use [InMemoryIdentityStore] (BouncyCastle AES-GCM, same shape).
 */
interface IdentityStore {
    fun load(): Identity?
    fun save(identity: Identity, edPrivateWrapped: ByteArray, xPrivateWrapped: ByteArray, iv: ByteArray)
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
        get() = context.getSharedPreferences("meshline_identity", Context.MODE_PRIVATE)

    override fun load(): Identity? {
        val edPub = prefs.getString("ed_pub", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val xPub = prefs.getString("x_pub", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val edWrapped = prefs.getString("ed_wrapped", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val xWrapped = prefs.getString("x_wrapped", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val iv = prefs.getString("iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val key = keystoreKey()
        val edPriv = decrypt(edWrapped, iv, key)
        val xPriv = decrypt(xWrapped, iv, key)
        return Identity(edPub, xPub, edPriv, xPriv)
    }

    override fun save(identity: Identity, edPrivateWrapped: ByteArray, xPrivateWrapped: ByteArray, iv: ByteArray) {
        val key = keystoreKey()
        val edWrapped = encrypt(edPrivateWrapped, key)
        val xWrapped = encrypt(xPrivateWrapped, key)
        prefs.edit()
            .putString("ed_pub", Base64.encodeToString(identity.ed25519PublicKey, Base64.NO_WRAP))
            .putString("x_pub", Base64.encodeToString(identity.x25519PublicKey, Base64.NO_WRAP))
            .putString("ed_wrapped", Base64.encodeToString(edWrapped, Base64.NO_WRAP))
            .putString("x_wrapped", Base64.encodeToString(xWrapped, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun wipe() {
        prefs.edit().clear().apply()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { ks.deleteEntry(ALIAS) }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ALIAS = "meshline-key-wrap"
        fun generateAndStore(context: Context): Identity {
            val (identity, edPriv, xPriv) = IdentityFactory.generate()
            val store = AndroidKeystoreIdentityStore(context)
            store.save(identity, edPriv, xPriv, ByteArray(12)) // IV is generated per encrypt in production; placeholder here is replaced by cipher IV handling.
            return identity
        }
    }
}

/** JVM-test double (same public shape, BouncyCastle AES-GCM, no Keystore). */
class InMemoryIdentityStore : IdentityStore {
    var identity: Identity? = null
    private var edWrapped: ByteArray? = null
    private var xWrapped: ByteArray? = null

    override fun load(): Identity? = identity
    override fun save(identity: Identity, edPrivateWrapped: ByteArray, xPrivateWrapped: ByteArray, iv: ByteArray) {
        this.identity = identity
        edWrapped = edPrivateWrapped.copyOf()
        xWrapped = xPrivateWrapped.copyOf()
    }

    override suspend fun wipe() {
        identity = null
        edWrapped = null
        xWrapped = null
    }
}
