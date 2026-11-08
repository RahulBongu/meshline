package com.meshline.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meshline.app.service.BleMeshService

/**
 * Phase C activity: permission prompt + Start button + public composer +
 * debug overlay (peers/MTU/RSSI/role, received TTL/hops, last GATT error,
 * advertise-support banner). Private chat and UI polish land in D/E.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissionsIfNeeded()
        setContent { MeshlineTheme { Home(onStart = { startMesh() }) } }
    }

    private fun startMesh() {
        val intent = Intent(this, BleMeshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestBlePermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 0)
        }
    }
}

@Composable
private fun Home(onStart: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    val peers by BleMeshService.peers.collectAsState(initial = emptyList())
    val messages by BleMeshService.messages.collectAsState(initial = emptyList())
    val lastGattError by BleMeshService.lastGattError.collectAsState(initial = null)
    val advertiseSupported by BleMeshService.advertiseSupported.collectAsState(initial = true)

    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("// MESHLINE — NEARBY (PUBLIC)", color = MeshlineColors.Accent, style = MaterialTheme.typography.headlineSmall)
            Text(
                "public chat is signed in Phase D, NOT encrypted yet. Anyone in range can read it.",
                color = MeshlineColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!advertiseSupported) {
                Text(
                    "Advertising unsupported on this device — you can still chat as client only (scan + connect). Check Logcat isMultipleAdvertisementSupported.",
                    color = Color(0xFFFFD54F),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start mesh (BLE)") }
            Spacer(Modifier.height(12.dp))
            Text(
                "no peers nearby yet — open on two physical phones, allow Bluetooth, start both. BLE does not work on emulators.",
                color = MeshlineColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = msg,
                onValueChange = { if (it.length <= 500) msg = it },
                label = { Text("public (500 chars max)") },
                modifier = Modifier.fillMaxWidth(),
            )
            // ---- debug overlay (Phase C #1) ----
            Spacer(Modifier.height(16.dp))
            Text("DEBUG — peers (${peers.size})", color = MeshlineColors.Accent, style = MaterialTheme.typography.titleSmall)
            if (peers.isEmpty()) Text("none", color = MeshlineColors.TextSecondary)
            peers.forEach { p ->
                Text(
                    "peer ${p.peerIdHex.take(8)}… rssi=${p.rssiDbm ?: "?"}dBm mtu=${p.mtu ?: "?"} role=${p.role}",
                    color = MeshlineColors.TextPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("DEBUG — received (${messages.size})", color = MeshlineColors.Accent, style = MaterialTheme.typography.titleSmall)
            if (messages.isEmpty()) Text("none yet", color = MeshlineColors.TextSecondary)
            messages.takeLast(20).reversed().forEach { m ->
                Text(
                    "${m.typeName} from ${m.senderIdHex.take(8)}… ttl=${m.receivedTtl} hops=${m.hops}",
                    color = MeshlineColors.TextPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("last GATT error: ${lastGattError?.let { "code $it" } ?: "none"}", color = MeshlineColors.TextSecondary)
            Text("See README: physical checklist incl. 500-char, screen-off, permission-deny, logcat.", color = MeshlineColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Terminal palette: dark-first, one neon accent (PRD §13). */
object MeshlineColors {
    val Background = Color(0xFF0A0F0A)
    val Surface = Color(0xFF111711)
    val Accent = Color(0xFF39FF88)
    val TextPrimary = Color(0xFFE8F5E9)
    val TextSecondary = Color(0xFF9DBE9D)
}

@Composable
fun MeshlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = MeshlineColors.Accent,
            background = MeshlineColors.Background,
            surface = MeshlineColors.Surface,
        ),
        content = content,
    )
}
