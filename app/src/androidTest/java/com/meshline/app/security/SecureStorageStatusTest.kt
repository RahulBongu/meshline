package com.meshline.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the secure-storage reset notice (D1 review item 4).
 *
 * The reset itself is destructive and exercised manually (README release
 * checklist), but the notice must be persisted and shown exactly once even when
 * the reset happens in the service while the UI is closed.
 */
@RunWith(AndroidJUnit4::class)
class SecureStorageStatusTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBefore() = clear()

    @After
    fun clearAfter() = clear()

    private fun clear() {
        ctx.getSharedPreferences("meshline_secure_storage", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `reset notice is recorded and consumed exactly once`() {
        assertNull(SecureStorageStatus.consume(ctx))

        SecureStorageStatus.record(ctx, SecureStorageStatus.Reason.DB_UNREADABLE)
        val event = SecureStorageStatus.consume(ctx)
        assertEquals(SecureStorageStatus.Reason.DB_UNREADABLE, event?.reason)
        assertNull("second consume must not repeat the dialog", SecureStorageStatus.consume(ctx))
    }

    @Test
    fun `identity reset reason is preserved`() {
        SecureStorageStatus.record(ctx, SecureStorageStatus.Reason.KEYSTORE_INVALIDATED)
        assertEquals(
            SecureStorageStatus.Reason.KEYSTORE_INVALIDATED,
            SecureStorageStatus.consume(ctx)?.reason,
        )
    }
}
