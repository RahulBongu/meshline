package com.meshline.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
fun ContactsScreen(contacts: List<ContactEntity>) {
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.padding(16.dp)) {
            items(contacts) { c ->
                Text(
                    "${AvatarTable.name(c.avatarIndex)} ${c.nickname} - ${c.fingerprint.substring(0, 4).uppercase()}" +
                        (if (c.verified) " ✓ verified" else "") + (if (c.blocked) " blocked" else "")
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
