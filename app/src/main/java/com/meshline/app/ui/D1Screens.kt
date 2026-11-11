package com.meshline.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.profile.Profile
import com.meshline.app.profile.ProfileStore
import com.meshline.app.security.QrPayload
import com.meshline.app.security.SecureStorageStatus
import com.meshline.app.storage.ContactEntity

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
fun MyProfileScreen(profile: Profile, fingerprintShort: String, qrText: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("My Profile", style = MaterialTheme.typography.headlineSmall)
            Text("${AvatarTable.name(profile.avatarIndex.toInt())} ${profile.nickname} - $fingerprintShort")
            Spacer(Modifier.height(16.dp))
            Image(bitmap = qrBitmap(qrText).asImageBitmap(), contentDescription = "Your QR code")
            Text("Show this to a friend in person to add you as a verified contact.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ScanConfirmScreen(card: QrPayload.ContactCard, fingerprintShort: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Add verified contact?", style = MaterialTheme.typography.headlineSmall)
            Text("${AvatarTable.name(card.avatarIndex.toInt())} ${card.nickname} - $fingerprintShort")
            Text("Only add people you met in person.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Add verified") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
fun ContactsScreen(contacts: List<ContactEntity>, onOpen: (ContactEntity) -> Unit = {}) {
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.padding(16.dp)) {
            items(contacts) { c ->
                Text(
                    "${AvatarTable.name(c.avatarIndex)} ${c.nickname} - ${c.fingerprint.substring(0, 4).uppercase()}" +
                        (if (c.verified) " ✓ verified" else "") + (if (c.blocked) " blocked" else ""),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(c) }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsProfileScreen(store: ProfileStore, initial: Profile, onSaved: () -> Unit) {
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
            Button(onClick = { store.save(Profile(nick, avatar.toByte())); onSaved() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
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
