package com.meshline.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshline.app.profile.Profile
import com.meshline.app.storage.MessageRetention
import com.meshline.app.transport.RelayStatus
import com.meshline.app.transport.TransportMode
import com.meshline.app.ui.theme.AppThemeMode
import com.meshline.app.ui.theme.MeshlineDesign

/**
 * Modernized Profile & Settings Screen based on Google Stitch design system.
 */
@Composable
fun ProfileAndSettingsScreen(
    profile: Profile,
    fingerprintShort: String,
    contactsCount: Int,
    retention: MessageRetention,
    relayStatus: RelayStatus,
    themeMode: AppThemeMode,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSaveProfile: (nickname: String, avatarIndex: Byte) -> Unit,
    onSetRetention: (MessageRetention) -> Unit,
    onSetInternetRelay: (Boolean) -> Unit,
    onMyQr: () -> Unit,
    onScanQr: () -> Unit,
    onViewContacts: () -> Unit,
    onPanicWipeArmed: () -> Unit,
) {
    val colors = MeshlineDesign.colors
    val context = LocalContext.current
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var copyNotice by remember { mutableStateOf<String?>(null) }

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Screen Header
            Text(
                text = "Settings",
                color = colors.textPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 14.dp),
            )

            // Identity Header Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.border, CircleShape)
                                .clickable { showEditProfileDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(fruitEmoji(profile.avatarIndex.toInt()), fontSize = 28.sp)
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.nickname,
                                    color = colors.textPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "✏️",
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable { showEditProfileDialog = true },
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Fingerprint: $fingerprintShort",
                                color = colors.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    // QR Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.primary, RoundedCornerShape(10.dp))
                                .clickable(onClick = onMyQr)
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("My QR Code", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.primary, RoundedCornerShape(10.dp))
                                .clickable(onClick = onScanQr)
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Scan QR", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Section 1: Appearance (Theme Mode)
            SettingsSectionCard(title = "APPEARANCE") {
                Column {
                    Text("Theme Mode", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Select display appearance", color = colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppThemeMode.values().forEach { mode ->
                            val isSelected = themeMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9999.dp))
                                    .background(if (isSelected) colors.primary.copy(alpha = 0.15f) else colors.surfaceElevated)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) colors.primary else colors.border,
                                        shape = RoundedCornerShape(9999.dp),
                                    )
                                    .clickable { onSetThemeMode(mode) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                    color = if (isSelected) colors.primary else colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Section 2: Trust & Identity
            SettingsSectionCard(title = "TRUST & IDENTITY") {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onViewContacts)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Verified Contacts", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("$contactsCount contacts verified via QR scan", color = colors.textSecondary, fontSize = 12.sp)
                        }
                        Text("View →", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))

                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text("Cryptographic Enclave", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Ed25519 Identity + X25519 Noise Keys stored in Android Keystore", color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Section 3: Mesh Network & Relay
            SettingsSectionCard(title = "MESH NETWORK & RELAYS") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bluetooth Mesh Relay", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Relay encrypted multi-hop packets for nearby peers", color = colors.textSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = true,
                            onCheckedChange = { /* Always active for core mesh */ },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.onPrimary,
                                checkedTrackColor = colors.primary,
                            ),
                        )
                    }

                    HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val isNostrActive = relayStatus.mode == TransportMode.BLE_AND_RELAY
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Nostr Internet Relay Fallback", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (isNostrActive) "Connected to global decentralized relays"
                                else "Disabled: operating 100% offline via Bluetooth radio",
                                color = if (isNostrActive) colors.primary else colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = isNostrActive,
                            onCheckedChange = { onSetInternetRelay(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.onPrimary,
                                checkedTrackColor = colors.primary,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Section 4: Data Retention & Privacy
            SettingsSectionCard(title = "DATA RETENTION & VACUUMING") {
                Column {
                    Text("Auto-Delete Stored Messages", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Automatically shred expired messages from SQLCipher", color = colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            MessageRetention.ONE_DAY to "24 Hours",
                            MessageRetention.SEVEN_DAYS to "7 Days",
                            MessageRetention.THIRTY_DAYS to "30 Days",
                            MessageRetention.OFF to "Keep Forever",
                        ).forEach { (opt, label) ->
                            val isSelected = retention == opt
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9999.dp))
                                    .background(if (isSelected) colors.primary.copy(alpha = 0.15f) else colors.surfaceElevated)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) colors.primary else colors.border,
                                        shape = RoundedCornerShape(9999.dp),
                                    )
                                    .clickable { onSetRetention(opt) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) colors.primary else colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Section 5: Emergency Danger Zone
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.danger.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text("EMERGENCY DANGER ZONE", color = colors.danger, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Irrevocably shred database files, delete Keystore master keys, and kill the app.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    PanicWipeTripleTapTrigger(onArmed = onPanicWipeArmed)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEditProfileDialog) {
        EditProfileDialog(
            currentNickname = profile.nickname,
            currentAvatar = profile.avatarIndex.toInt(),
            onDismiss = { showEditProfileDialog = false },
            onSave = { newNick, newAvatar ->
                onSaveProfile(newNick, newAvatar.toByte())
                showEditProfileDialog = false
            },
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = MeshlineDesign.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = title,
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun PanicWipeTripleTapTrigger(onArmed: () -> Unit) {
    val colors = MeshlineDesign.colors
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.danger.copy(alpha = 0.15f))
            .border(1.dp, colors.danger, RoundedCornerShape(12.dp))
            .clickable {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapTime < 1500L) tapCount + 1 else 1
                lastTapTime = now
                if (tapCount >= 3) {
                    tapCount = 0
                    onArmed()
                }
            }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (tapCount == 0) "🚨 Armed Panic Wipe (Tap 3 Times)"
            else "🚨 Armed Panic Wipe (${3 - tapCount} Taps Remaining)",
            color = colors.danger,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EditProfileDialog(
    currentNickname: String,
    currentAvatar: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    val colors = MeshlineDesign.colors
    var nickname by remember { mutableStateOf(currentNickname) }
    var selectedAvatar by remember { mutableIntStateOf(currentAvatar) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Identity Profile", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Nickname", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = nickname,
                        onValueChange = { if (it.length <= 32) nickname = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("Choose Fruit Avatar", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    (0..7).forEach { idx ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (selectedAvatar == idx) colors.primary.copy(alpha = 0.2f) else colors.surface)
                                .border(
                                    width = if (selectedAvatar == idx) 1.5.dp else 0.dp,
                                    color = if (selectedAvatar == idx) colors.primary else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { selectedAvatar = idx },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(fruitEmoji(idx), fontSize = 18.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    (8..15).forEach { idx ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (selectedAvatar == idx) colors.primary.copy(alpha = 0.2f) else colors.surface)
                                .border(
                                    width = if (selectedAvatar == idx) 1.5.dp else 0.dp,
                                    color = if (selectedAvatar == idx) colors.primary else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { selectedAvatar = idx },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(fruitEmoji(idx), fontSize = 18.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(if (nickname.isNotBlank()) colors.primary else colors.surfaceElevated)
                    .clickable(enabled = nickname.isNotBlank()) {
                        onSave(nickname.trim(), selectedAvatar)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "Save",
                    color = if (nickname.isNotBlank()) colors.onPrimary else colors.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}
