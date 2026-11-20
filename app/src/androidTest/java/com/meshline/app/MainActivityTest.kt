package com.meshline.app

import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshline.app.security.AndroidKeystoreIdentityStore
import com.meshline.app.security.DbPassphrase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Instrumented smoke & regression tests for MainActivity.
 *
 * Verifies:
 * 1. Clean launch: launches MainActivity with all prefs, Keystore keys, and DB wiped.
 *    Proves MainActivity reaches RESUMED state on a clean install and creates valid identity/DB.
 * 2. Re-launch after Keystore aliases and DB are deleted: proves graceful recovery
 *    and generation of a new identity and database when hardware keys are removed.
 * 3. Regression test: stale SharedPreferences with missing Keystore wrap key
 *    fails closed safely, replaces corrupt data, and creates an observable, loadable fresh identity.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanAll() {
        stopBleService()
        runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            automation.executeShellCommand("wm dismiss-keyguard")
        }
        grantBlePermissions()
        wipeAllStorage()
    }

    private fun grantBlePermissions() {
        runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val pkg = context.packageName
            if (Build.VERSION.SDK_INT >= 31) {
                automation.grantRuntimePermission(pkg, android.Manifest.permission.BLUETOOTH_SCAN)
                automation.grantRuntimePermission(pkg, android.Manifest.permission.BLUETOOTH_ADVERTISE)
                automation.grantRuntimePermission(pkg, android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                automation.grantRuntimePermission(pkg, android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                automation.grantRuntimePermission(pkg, android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @After
    fun tearDown() {
        stopBleService()
        wipeAllStorage()
    }

    private fun stopBleService() {
        com.meshline.app.service.BleMeshService.stopAndAwait(context)
        runCatching {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun wipeAllStorage() {
        com.meshline.app.storage.DatabaseFactory.closeAll()
        // Clear all SharedPreferences
        listOf(
            "meshline_identity",
            "meshline_db_key",
            "meshline_profile",
            "meshline_settings",
            "meshline_secure_storage",
            "meshline_theme",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        // Delete Keystore aliases
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry("meshline-key-wrap")
            ks.deleteEntry("meshline-db-wrap")
        }
        // Delete database files and SQLite journal/WAL/SHM
        DbPassphrase.deleteDatabaseFiles(context)
    }

    @Test
    fun launchOnCleanInstallReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertNotNull("Activity must be non-null and alive", activity)
            }
        }
        val store = AndroidKeystoreIdentityStore(context)
        assertNotNull("Clean install must generate a loadable identity", store.load())
    }

    @Test
    fun launchAfterKeystoreAliasAndDbDeletedReachesResumed() {
        // First launch to populate identity and DB
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertNotNull("Activity must be non-null and alive", activity)
            }
        }
        val store = AndroidKeystoreIdentityStore(context)
        val initialIdentity = store.load()
        assertNotNull("Initial identity must be present after first launch", initialIdentity)

        // Delete only the Keystore aliases and database files (leaving prefs)
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry("meshline-key-wrap")
            ks.deleteEntry("meshline-db-wrap")
        }
        DbPassphrase.deleteDatabaseFiles(context)

        // Second launch: must not crash with missing key or unreadable DB, and must reach RESUMED
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertNotNull("Activity must be non-null and alive", activity)
            }
        }

        // Must create valid replacement identity
        val replacementIdentity = store.load()
        assertNotNull("Replacement identity must be generated and loadable", replacementIdentity)
    }

    @Test
    fun regressionStalePrefsWithMissingKeyRecoversObservableIdentity() {
        val fakeEdPub = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val fakeXPub = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        // 1. Simulate stale identity prefs with missing Keystore key
        val prefs = context.getSharedPreferences("meshline_identity", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("ed_pub", fakeEdPub)
            .putString("x_pub", fakeXPub)
            .putString("ed_wrapped", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
            .putString("x_wrapped", "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=")
            .putString("ed_iv", "DDDDDDDDDDDDDDDD")
            .putString("x_iv", "EEEEEEEEEEEEEEEE")
            .commit()

        // 2. Launch MainActivity and ensure it drives to RESUMED
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }

        // 3. Verify observable recovery:
        // - meshline_identity contains valid public/private-key entries
        assertTrue("ed_pub entry must exist", prefs.contains("ed_pub"))
        assertTrue("x_pub entry must exist", prefs.contains("x_pub"))
        assertTrue("ed_wrapped entry must exist", prefs.contains("ed_wrapped"))
        assertTrue("x_wrapped entry must exist", prefs.contains("x_wrapped"))
        assertTrue("ed_iv entry must exist", prefs.contains("ed_iv"))
        assertTrue("x_iv entry must exist", prefs.contains("x_iv"))

        // - The wrapped identity can be loaded through AndroidKeystoreIdentityStore.load()
        val store = AndroidKeystoreIdentityStore(context)
        val loaded = store.load()
        assertNotNull("Identity must be successfully loadable after recovery", loaded)

        // - load() returns an identity different from the deliberately corrupted fake public keys
        val currentEdPub = prefs.getString("ed_pub", null)
        val currentXPub = prefs.getString("x_pub", null)
        assertNotEquals("Recovered ed_pub must differ from fake corrupted key", fakeEdPub, currentEdPub)
        assertNotEquals("Recovered x_pub must differ from fake corrupted key", fakeXPub, currentXPub)
        val recovered = checkNotNull(loaded) { "identity must be loadable after recovery" }
        assertEquals(32, recovered.ed25519PublicKey.size)
        assertEquals(32, recovered.x25519PublicKey.size)
        assertEquals(32, recovered.x25519PrivateKey().size)
        assertTrue(recovered.signEd25519("meshline".toByteArray()).isNotEmpty())
    }
}
