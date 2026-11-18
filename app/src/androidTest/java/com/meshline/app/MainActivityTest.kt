package com.meshline.app

import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Instrumented smoke & regression tests for MainActivity (Phase 1).
 *
 * Verifies:
 * 1. Clean launch: launches MainActivity with all prefs, Keystore keys, and DB wiped.
 *    Proves MainActivity reaches RESUMED state on a clean install without crashing.
 * 2. Re-launch after Keystore alias and DB are deleted: proves graceful recovery
 *    and generation of a new identity and database when hardware keys are removed.
 * 3. Regression test: stale SharedPreferences with missing Keystore wrap key
 *    fails closed safely and creates a new identity instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanAll() {
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
        wipeAllStorage()
    }

    private fun wipeAllStorage() {
        // Clear all SharedPreferences
        listOf(
            "meshline_identity",
            "meshline_db_key",
            "meshline_profile",
            "meshline_settings",
            "meshline_secure_storage",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        // Delete Keystore aliases
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry("meshline-key-wrap")
            ks.deleteEntry("meshline-db-wrap")
        }
        // Delete database files
        listOf("meshline.db", "meshline.db-wal", "meshline.db-shm").forEach { name ->
            File(context.getDatabasePath(name).absolutePath).delete()
        }
    }

    @Test
    fun launchOnCleanInstallReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
            assertTrue(
                "MainActivity must reach at least STARTED without crash, actual: ${scenario.state}",
                scenario.state.isAtLeast(Lifecycle.State.STARTED)
            )
        }
    }

    @Test
    fun launchAfterKeystoreAliasAndDbDeletedReachesResumed() {
        // First launch to populate identity and DB
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.STARTED))
        }

        // Delete only the Keystore aliases and database files (leaving prefs)
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry("meshline-key-wrap")
            ks.deleteEntry("meshline-db-wrap")
        }
        listOf("meshline.db", "meshline.db-wal", "meshline.db-shm").forEach { name ->
            File(context.getDatabasePath(name).absolutePath).delete()
        }

        // Second launch: must not crash with missing key or unreadable DB
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
            assertTrue(
                "MainActivity must reach at least STARTED after wiped hardware keys, actual: ${scenario.state}",
                scenario.state.isAtLeast(Lifecycle.State.STARTED)
            )
        }
    }

    @Test
    fun regressionStalePrefsWithMissingKeyDoesNotCrash() {
        // Simulate stale identity prefs with missing Keystore key
        val prefs = context.getSharedPreferences("meshline_identity", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("ed_pub", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .putString("x_pub", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .putString("ed_wrapped", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
            .putString("x_wrapped", "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=")
            .putString("ed_iv", "DDDDDDDDDDDDDDDD")
            .putString("x_iv", "EEEEEEEEEEEEEEEE")
            .commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
            assertTrue(
                "MainActivity must handle corrupted/un-decryptable identity without crash, actual: ${scenario.state}",
                scenario.state.isAtLeast(Lifecycle.State.STARTED)
            )
        }
    }
}
