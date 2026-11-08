package com.meshline.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service for background relay (PRD FR-10, Phase E).
 *
 * Phase A is a compile-time stub so the manifest/service wiring exists and the
 * app structure is visible. Phase E moves the MessagingService + BLE transport
 * into this service, calls `startForeground` with
 * `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`, and posts the persistent
 * notification (channel name: mesh relay). BLE scanning/advertising must not
 * run outside this service in the final app.
 */
class MeshlineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Phase E: startForeground(NOTIFICATION_ID, notification, CONNECTED_DEVICE).
        // Phase A does nothing visible — it only proves the service component exists.
        return START_STICKY
    }

    companion object {
        const val ACTION_START = "com.meshline.app.START"
        const val ACTION_STOP = "com.meshline.app.STOP"
    }
}
