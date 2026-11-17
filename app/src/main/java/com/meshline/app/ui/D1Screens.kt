package com.meshline.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.meshline.app.profile.Profile
import com.meshline.app.profile.ProfileStore
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.security.QrPayload
import com.meshline.app.security.SecureStorageStatus
import com.meshline.app.storage.ContactEntity
import com.meshline.app.storage.MessageRetention
import com.meshline.app.ui.theme.MeshlineDesign

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
fun MyProfileScreen(
    profile: Profile,
    fingerprintShort: String,
    qrText: String,
    onBack: () -> Unit = {},
) {
    val colors = MeshlineDesign.colors
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Text("←", color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "My Contact Card",
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${fruitEmoji(profile.avatarIndex.toInt())} ${profile.nickname}",
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Fingerprint: $fingerprintShort",
                        color = colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))

                    // High contrast QR Code
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp),
                    ) {
                        Image(
                            bitmap = qrBitmap(qrText).asImageBitmap(),
                            contentDescription = "Contact QR Code",
                            modifier = Modifier.size(220.dp),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Have a peer scan this QR code with their camera to establish an authenticated, verified cryptographic link.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun ScanConfirmScreen(
    card: QrPayload.ContactCard,
    fingerprintShort: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MeshlineDesign.colors
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(18.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✓", color = colors.primary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Verified Contact Found",
                        color = colors.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${fruitEmoji(card.avatarIndex.toInt())} ${card.nickname}",
                        color = colors.primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Fingerprint: $fingerprintShort",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "This contact was physically verified via out-of-band QR scan. Their Noise XX identity will be marked Verified in your secure contacts directory.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9999.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                                .clickable(onClick = onCancel)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Cancel", color = colors.textSecondary, fontSize = 13.sp)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9999.dp))
                                .background(colors.primary)
                                .clickable(onClick = onConfirm)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Add Contact", color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsScreen(
    contacts: List<ContactEntity>,
    onOpen: (ContactEntity) -> Unit,
    onBack: () -> Unit = {},
) {
    val colors = MeshlineDesign.colors
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Text("←", color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Verified Contacts (${contacts.size})",
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👥", fontSize = 42.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No contacts verified yet.",
                            color = colors.textSecondary,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scan a peer's QR code in person to add them.",
                            color = colors.textMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(contacts, key = { it.senderIdHex }) { c ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                                .clickable { onOpen(c) }
                                .padding(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(colors.surfaceElevated)
                                        .border(1.dp, colors.border, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(fruitEmoji(c.avatarIndex), fontSize = 22.sp)
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = c.nickname,
                                            color = colors.textPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (c.verified) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("✓", color = colors.secondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                        if (c.blocked) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("blocked", color = colors.danger, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Fingerprint: ${c.fingerprint.take(8).uppercase()}…",
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                                Text("Chat →", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
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

@Composable
fun SecureStorageResetDialog(event: SecureStorageStatus.Event, onDismiss: () -> Unit) {
    val colors = MeshlineDesign.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secure Storage Reset", color = colors.danger, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Keystore unlock failed; the device was re-initialized with a brand new identity keypair. Contacts and previous sessions have been cleared.",
                color = colors.textPrimary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK", color = colors.primary) }
        },
    )
}
