package com.meshline.app.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Keystore tests (need a device/emulator — cannot run on the JVM).
 * Run: ./gradlew :app:connectedDebugAndroidTest
 *
 * The JVM cannot exercise `AndroidKeyStore`, so this is the only automated
 * coverage for the AES-GCM wrapping path. Process restart is approximated by
 * constructing a new store instance: the keys live in the Keystore, not in the
 * object, so a fresh instance reads the same slot. A real reboot is on the
 * README phone checklist.
 */
@RunWith(AndroidJUnit4::class)
class IdentityStoreInstrumentedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun wrapUnwrapRoundTripKeepsPrivateKeysUsable() = runBlocking {
        AndroidKeystoreIdentityStore(ctx).wipe()
        val (identity, edPriv, xPriv) = IdentityFactory.generate()
        AndroidKeystoreIdentityStore(ctx).save(identity, edPriv, xPriv)

        val loaded = AndroidKeystoreIdentityStore(ctx).load()
        assertNotNull(loaded)
        assertArrayEquals(identity.ed25519PublicKey, loaded!!.ed25519PublicKey)
        assertArrayEquals(identity.x25519PublicKey, loaded.x25519PublicKey)

        // Decrypted private keys must still sign/agree — not just round-trip bytes.
        val msg = "keystore round trip".encodeToByteArray()
        assertTrue(AnnounceVerifier.verifyEd(loaded.ed25519PublicKey, msg, loaded.signEd25519(msg)))
        val (peer, _, _) = IdentityFactory.generate()
        assertArrayEquals(
            loaded.agreeX25519(peer.x25519PublicKey),
            peer.agreeX25519(loaded.x25519PublicKey),
        )
    }

    @Test
    fun sameIdentityAfterNewStoreInstance() = runBlocking {
        AndroidKeystoreIdentityStore(ctx).wipe()
        val (identity, edPriv, xPriv) = IdentityFactory.generate()
        AndroidKeystoreIdentityStore(ctx).save(identity, edPriv, xPriv)

        val first = AndroidKeystoreIdentityStore(ctx).load()
        val second = AndroidKeystoreIdentityStore(ctx).load()
        assertNotNull(first)
        assertNotNull(second)
        assertArrayEquals(first!!.ed25519PublicKey, second!!.ed25519PublicKey)
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun panicWipeLeavesNoIdentityAndCreatesANewOne() = runBlocking {
        AndroidKeystoreIdentityStore(ctx).wipe()
        val (id1, edPriv, xPriv) = IdentityFactory.generate()
        AndroidKeystoreIdentityStore(ctx).save(id1, edPriv, xPriv)

        AndroidKeystoreIdentityStore(ctx).wipe()
        assertNull(AndroidKeystoreIdentityStore(ctx).load())

        val id2 = AndroidKeystoreIdentityStore.generateAndStore(ctx)
        val reloaded = AndroidKeystoreIdentityStore(ctx).load()
        assertNotNull(reloaded)
        assertArrayEquals(id2.ed25519PublicKey, reloaded!!.ed25519PublicKey)
        assertNotEquals(id1.fingerprint, id2.fingerprint)
    }

    @Test
    fun tamperedCiphertextFailsClosed() = runBlocking {
        AndroidKeystoreIdentityStore(ctx).wipe()
        val (identity, edPriv, xPriv) = IdentityFactory.generate()
        AndroidKeystoreIdentityStore(ctx).save(identity, edPriv, xPriv)

        val prefs = ctx.getSharedPreferences("meshline_identity", Context.MODE_PRIVATE)
        val blob = Base64.decode(prefs.getString("ed_wrapped", null)!!, Base64.NO_WRAP)
        blob[0] = (blob[0].toInt() xor 0xFF).toByte()
        prefs.edit()
            .putString("ed_wrapped", Base64.encodeToString(blob, Base64.NO_WRAP))
            .commit()

        // Must return null (regenerate), never throw an AEADBadTagException.
        assertNull(AndroidKeystoreIdentityStore(ctx).load())
    }
}

/**
 * The DB passphrase must be 32 random bytes, stable across calls, and never
 * stored in plaintext (only Keystore-wrapped ciphertext + IV).
 */
@RunWith(AndroidJUnit4::class)
class DbPassphraseInstrumentedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun passphraseIsRandomStableAndWrapped() {
        DbPassphrase.wipe(ctx)
        val first = DbPassphrase.getOrCreate(ctx)
        val second = DbPassphrase.getOrCreate(ctx)

        assertEquals(32, first.size)
        assertArrayEquals(first, second) // stable: same wrapped key unwraps to the same bytes

        val prefs = ctx.getSharedPreferences("meshline_db_key", Context.MODE_PRIVATE)
        val wrapped = prefs.getString("wrapped", null)
        assertNotNull(wrapped)
        val plainInPrefs = Base64.encodeToString(first, Base64.NO_WRAP)
        assertNotEquals(plainInPrefs, wrapped) // never plaintext at rest

        DbPassphrase.wipe(ctx)
        val afterWipe = DbPassphrase.getOrCreate(ctx)
        assertEquals(32, afterWipe.size)
        assertNotEquals(
            Base64.encodeToString(first, Base64.NO_WRAP),
            Base64.encodeToString(afterWipe, Base64.NO_WRAP),
        )
    }
}
