package com.meshline.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix
import com.meshline.app.MeshlineColors
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.profile.Profile
import com.meshline.app.profile.ProfileStore
import com.meshline.app.security.QrPayload
import com.meshline.app.security.SecureStorageStatus
import com.meshline.app.storage.ContactEntity
import com.meshline.app.storage.MessageRetention

fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val matrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp
}

@Composable
fun MyProfileScreen(profile: Profile, fingerprintShort: String, qrText: String, onBack: () -> Unit = {}) {
    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.width(8.dp))
                Text("My QR Profile", color = MeshlineColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${fruitEmoji(profile.avatarIndex.toInt())} ${profile.nickname} · $fingerprintShort",
                color = MeshlineColors.Accent,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Image(
                    bitmap = qrBitmap(qrText).asImageBitmap(),
                    contentDescription = "Your QR code",
                    modifier = Modifier.size(260.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Show this QR code in person to nearby friends to establish a verified, authenticated cryptographic link.",
                color = MeshlineColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
fun ScanConfirmScreen(card: QrPayload.ContactCard, fingerprintShort: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Add Verified Contact?", color = MeshlineColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "${fruitEmoji(card.avatarIndex.toInt())} ${card.nickname} · $fingerprintShort",
                color = MeshlineColors.Accent,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Only confirm contacts you have physically met in person. Adding a contact places their public keys into your protected trust tier.",
                color = MeshlineColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
            ) {
                Text("Add Verified Contact", color = androidx.compose.ui.graphics.Color.Black)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
fun ContactsScreen(contacts: List<ContactEntity>, onOpen: (ContactEntity) -> Unit = {}, onBack: () -> Unit = {}) {
    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.width(8.dp))
                Text("Contacts Directory (${contacts.size})", color = MeshlineColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        "No verified contacts yet. Scan another device's QR code to verify them.",
                        color = MeshlineColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(contacts) { c ->
                        Surface(
                            color = androidx.compose.ui.graphics.Color(0xFF141A14),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(c) }
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(fruitEmoji(c.avatarIndex), fontSize = 22.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(
                                            c.nickname,
                                            color = MeshlineColors.TextPrimary,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                        )
                                        if (c.verified) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("✓ verified", color = MeshlineColors.Accent, fontSize = 11.sp)
                                        }
                                        if (c.blocked) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("blocked", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 11.sp)
                                        }
                                    }
                                    Text(
                                        "Fingerprint: ${c.fingerprint.take(8).uppercase()}…",
                                        color = MeshlineColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsProfileScreen(
    store: ProfileStore,
    initial: Profile,
    retention: MessageRetention = MessageRetention.OFF,
    onRetention: (MessageRetention) -> Unit = {},
    onSaved: () -> Unit,
) {
    var nick by remember(initial.nickname) { mutableStateOf(initial.nickname) }
    var avatar by remember(initial.avatarIndex) { mutableStateOf(initial.avatarIndex.toInt()) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Settings — Profile", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(value = nick, onValueChange = { nick = it }, label = { Text("Nickname") })
            Spacer(Modifier.height(8.dp))
            Text("Fruit: ${AvatarTable.name(avatar)}")
            Button(onClick = { avatar = (avatar + 1) % 16 }, modifier = Modifier.fillMaxWidth()) { Text("Next fruit") }
            Spacer(Modifier.height(16.dp))
            Text("Message retention", style = MaterialTheme.typography.titleSmall)
            Text(
                "How long the chat history is kept. \"Off\" keeps everything; " +
                    "\"Clear chat\" always empties one conversation.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in MessageRetention.entries) {
                    Button(
                        onClick = { onRetention(option) },
                        modifier = Modifier.weight(1f),
                    ) { Text(retentionLabel(option)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Currently: ${retentionLabel(retention)}",
                color = MeshlineColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { store.save(Profile(nick, avatar.toByte())); onSaved() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

/** The retention label the user sees. */
fun retentionLabel(retention: MessageRetention): String = when (retention) {
    MessageRetention.OFF -> "Off"
    MessageRetention.ONE_DAY -> "1 day"
    MessageRetention.SEVEN_DAYS -> "7 days"
    MessageRetention.THIRTY_DAYS -> "30 days"
}

/**
 * Shown once when Keystore/DB unlock failed and the app regenerated its
 * identity + emptied contacts. Peers will see a new key (D2 key-change warning).
 */
@Composable
fun SecureStorageResetDialog(event: SecureStorageStatus.Event, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secure storage was reset") },
        text = {
            Text(
                if (event.reason == SecureStorageStatus.Reason.KEYSTORE_INVALIDATED) {
                    "Meshline could not unlock its identity (the Android Keystore key was " +
                        "invalidated, usually after an OS update). A new identity was created " +
                        "and contacts are empty. Peers will see a new key and a key-change warning."
                } else if (event.reason == SecureStorageStatus.Reason.PANIC_WIPE) {
                    "You wiped this device. A new identity was created and every contact, " +
                        "session and message is gone. Peers will see a new key and a " +
                        "key-change warning."
                } else {
                    "The encrypted contacts database could not be decrypted and was reset. " +
                        "Contacts are empty. Your identity is unchanged; if it was also reset, " +
                        "peers will see a new key and a key-change warning."
                }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
