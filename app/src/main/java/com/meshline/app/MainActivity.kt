package com.meshline.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meshline.app.profile.Profile
import com.meshline.app.profile.ProfileStore
import com.meshline.app.protocol.MessageIds
import com.meshline.app.security.AndroidKeystoreIdentityStore
import com.meshline.app.security.Identity
import com.meshline.app.security.QrPayload
import com.meshline.app.security.SecureStorageStatus
import com.meshline.app.service.BleMeshService
import com.meshline.app.service.MeshStatsSnapshot
import com.meshline.app.storage.ContactsRepository
import com.meshline.app.ui.ContactsScreen
import com.meshline.app.ui.MyProfileScreen
import com.meshline.app.ui.ScanConfirmScreen
import com.meshline.app.ui.ScanQRScreen
import com.meshline.app.ui.SecureStorageResetDialog
import com.meshline.app.ui.SettingsProfileScreen
import kotlinx.coroutines.launch

/**
 * D1 activity: permissions, public composer, debug overlay, and the identity
 * flows — My QR, Scan (CameraX + ZXing), Contacts, Settings. Private chat and
 * full UI polish land in D2/E.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissionsIfNeeded()
        setContent {
            MeshlineTheme {
                App(
                    onStart = { startMesh() },
                    onSendPublic = { text -> sendPublic(text) },
                )
            }
        }
    }

    private fun startMesh() {
        // minSdk 26: startForegroundService is always available; the service
        // posts its connected-device notification in onCreate.
        startForegroundService(Intent(this, BleMeshService::class.java))
    }

    private fun sendPublic(text: String) {
        if (text.isBlank()) return
        val intent = Intent(this, BleMeshService::class.java)
            .setAction(BleMeshService.ACTION_SEND_PUBLIC)
            .putExtra(BleMeshService.EXTRA_TEXT, text)
        startForegroundService(intent)
    }

    private fun requestBlePermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 0)
        }
    }
}

private enum class Screen { HOME, MY_QR, SCAN, CONTACTS, SETTINGS }

@Composable
private fun App(onStart: () -> Unit, onSendPublic: (String) -> Unit) {
    val app = LocalContext.current.applicationContext
    val identityStore = remember { AndroidKeystoreIdentityStore(app) }
    val profileStore = remember { ProfileStore(app) }
    val contactsRepo = remember { ContactsRepository(app) }
    val scope = rememberCoroutineScope()

    var identity by remember { mutableStateOf<Identity?>(null) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var resetEvent by remember { mutableStateOf<SecureStorageStatus.Event?>(null) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var pendingCard by remember { mutableStateOf<QrPayload.ContactCard?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val contacts by remember(contactsRepo) { contactsRepo.observeAll() }
        .collectAsState(initial = emptyList())

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanError = null
            screen = Screen.SCAN
        } else {
            scanError = "Camera permission denied — allow it in Settings to scan a QR."
        }
    }

    LaunchedEffect(Unit) {
        identity = identityStore.load() ?: AndroidKeystoreIdentityStore.generateAndStore(app)
        profile = profileStore.load()
        resetEvent = SecureStorageStatus.consume(app)
    }
    // A reset can also happen when the DB is first opened (service or contacts
    // flow); surface it once, without popping on every normal empty list.
    LaunchedEffect(contacts) {
        if (resetEvent == null) resetEvent = SecureStorageStatus.consume(app)
    }

    resetEvent?.let { event ->
        SecureStorageResetDialog(event) { resetEvent = null }
    }

    val id = identity
    val prof = profile
    when {
        pendingCard != null -> {
            val card = pendingCard!!
            ScanConfirmScreen(
                card = card,
                fingerprintShort = MessageIds
                    .fingerprint(card.ed25519PublicKey, card.x25519PublicKey)
                    .substring(0, 4).uppercase(),
                onConfirm = {
                    scope.launch {
                        contactsRepo.addVerified(card)
                        pendingCard = null
                        screen = Screen.CONTACTS
                    }
                },
                onCancel = {
                    pendingCard = null
                    screen = Screen.HOME
                },
            )
        }
        screen == Screen.SCAN -> ScanQRScreen(
            error = scanError,
            onScanned = { text ->
                val card = runCatching { QrPayload.decode(text) }.getOrNull()
                if (card == null) {
                    scanError = "Not a valid Meshline contact QR (or the code is damaged)."
                } else {
                    scanError = null
                    pendingCard = card
                }
            },
        )
        id == null || prof == null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { Text("Preparing secure storage…") }
        screen == Screen.MY_QR -> MyProfileScreen(
            profile = prof,
            fingerprintShort = id.fingerprintShort,
            qrText = QrPayload.encode(
                QrPayload.ContactCard(
                    ed25519PublicKey = id.ed25519PublicKey,
                    x25519PublicKey = id.x25519PublicKey,
                    avatarIndex = prof.avatarIndex,
                    nickname = prof.nickname,
                )
            ),
        )
        screen == Screen.CONTACTS -> ContactsScreen(contacts)
        screen == Screen.SETTINGS -> SettingsProfileScreen(
            store = profileStore,
            initial = prof,
            onSaved = {
                profile = profileStore.load()
                screen = Screen.HOME
            },
        )
        else -> Home(
            profileLabel = ProfileStore.displayName(prof, id.fingerprintShort),
            contactsCount = contacts.size,
            onStart = onStart,
            onMyQr = { screen = Screen.MY_QR },
            onScan = {
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    scanError = null
                    screen = Screen.SCAN
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
            onContacts = { screen = Screen.CONTACTS },
            onSettings = { screen = Screen.SETTINGS },
            onSend = onSendPublic,
        )
    }
}

@Composable
private fun Home(
    profileLabel: String,
    contactsCount: Int,
    onStart: () -> Unit,
    onMyQr: () -> Unit,
    onScan: () -> Unit,
    onContacts: () -> Unit,
    onSettings: () -> Unit,
    onSend: (String) -> Unit,
) {
    var msg by remember { mutableStateOf("") }
    val peers by BleMeshService.peers.collectAsState(initial = emptyList())
    val messages by BleMeshService.messages.collectAsState(initial = emptyList())
    val lastGattError by BleMeshService.lastGattError.collectAsState(initial = null)
    val advertiseSupported by BleMeshService.advertiseSupported.collectAsState(initial = true)
    val stats by BleMeshService.stats.collectAsState(initial = MeshStatsSnapshot())

    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("// MESHLINE — NEARBY (PUBLIC)", color = MeshlineColors.Accent, style = MaterialTheme.typography.headlineSmall)
            Text("you: $profileLabel", color = MeshlineColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "public chat is signed (Ed25519) but NOT encrypted yet. Anyone in range can read it.",
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
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMyQr, modifier = Modifier.weight(1f)) { Text("My QR") }
                Button(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContacts, modifier = Modifier.weight(1f)) { Text("Contacts ($contactsCount)") }
                Button(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
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
            Button(
                onClick = {
                    onSend(msg)
                    msg = ""
                },
                enabled = msg.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send public") }
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
            Text(
                "counters: delivered=${stats.delivered} relayed=${stats.relayedForwards} " +
                    "unknownKey=${stats.droppedUnknownKey} badSig=${stats.droppedBadSignature} " +
                    "budget=${stats.droppedBudget} invalid=${stats.droppedInvalid}",
                color = MeshlineColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
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
