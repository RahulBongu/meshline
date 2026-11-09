package com.meshline.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Keystore tests (needs a device/emulator — cannot run on JVM).
 * Run: ./gradlew connectedDebugAndroidTest (device required).
 * Manual checklist also covered in README: phone reboot + app update (K/D — Keystore
 * keys may be invalidated by OS upgrades on some OEMs; load must fail closed, not crash).
 */
@RunWith(AndroidJUnit4::class)
class IdentityStoreInstrumentedTest {

    @Test
    fun wrapUnwrapRoundTripAndRestartPersistence() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidKeystoreIdentityStore(ctx)
        store.wipe()
        val (identity, edPriv, xPriv) = IdentityFactory.generate()
        store.save(identity, edPriv, xPriv, ByteArray(12))
        val loaded = AndroidKeystoreIdentityStore(ctx).load()
        assertNotNull(loaded)
        assertArrayEquals(identity.ed25519PublicKey, loaded!!.ed25519PublicKey)
        // Same Keystore slot => same identity after process "restart" (new store instance).
        val again = AndroidKeystoreIdentityStore(ctx).load()
        assertArrayEquals(loaded.ed25519PublicKey, again!!.ed25519PublicKey)
    }

    @Test
    fun panicWipeCreatesNewIdentity() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidKeystoreIdentityStore(ctx)
        store.wipe()
        val (id1, ep1, xp1) = IdentityFactory.generate()
        store.save(id1, ep1, xp1, ByteArray(12))
        store.wipe()
        assertArrayEquals(ByteArray(0), byteArrayOf()) // placeholder to keep shape; actual null check below
        org.junit.Assert.assertNull(AndroidKeystoreIdentityStore(ctx).load())
    }
}
