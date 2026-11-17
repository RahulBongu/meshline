package com.meshline.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshline.app.MeshlineColors
import com.meshline.app.profile.Profile
import com.meshline.app.profile.ProfileStore
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.storage.ContactEntity
import com.meshline.app.storage.MessageRetention
import com.meshline.app.transport.RelayStatus

@Composable
fun ProfileAndSettingsScreen(
    profile: Profile,
    fingerprintShort: String,
    contactsCount: Int,
    retention: MessageRetention,
    relayStatus: RelayStatus,
    onSaveProfile: (nickname: String, avatarIndex: Byte) -> Unit,
    onSetRetention: (MessageRetention) -> Unit,
    onSetInternetRelay: (Boolean) -> Unit,
    onMyQr: () -> Unit,
    onScanQr: () -> Unit,
    onViewContacts: () -> Unit,
    onPanicWipeArmed: () -> Unit,
) {
    var showEditProfileDialog by remember { mutableStateOf(false) }

    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Identity Header Card
            Surface(
                color = Color(0xFF141A14),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1D261E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(fruitEmoji(profile.avatarIndex.toInt()), fontSize = 28.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            profile.nickname,
                            color = MeshlineColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Fingerprint: $fingerprintShort · ${AvatarTable.name(profile.avatarIndex.toInt())}",
                            color = MeshlineColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { showEditProfileDialog = true }) {
                        Text("Edit", color = MeshlineColors.Accent)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Contact Exchange Action Buttons
            Text(
                "VERIFIED CONTACT EXCHANGE",
                color = MeshlineColors.Accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onMyQr,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C271E)),
                ) {
                    Text("My QR Code", color = MeshlineColors.TextPrimary)
                }
                Button(
                    onClick = onScanQr,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C271E)),
                ) {
                    Text("Scan QR Code", color = MeshlineColors.TextPrimary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onViewContacts,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF162117)),
            ) {
                Text("Contacts Directory ($contactsCount verified)", color = MeshlineColors.TextPrimary)
            }

            Spacer(Modifier.height(20.dp))

            // Network & Transport Settings
            Text(
                "NETWORK & TRANSPORT",
                color = MeshlineColors.Accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color(0xFF141A14),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Bluetooth LE Mesh Relay",
                                color = MeshlineColors.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Relay multi-hop encrypted packets for nearby peers in background.",
                                color = MeshlineColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = true,
                            onCheckedChange = { /* Always on for mesh operation */ },
                            colors = SwitchDefaults.colors(checkedThumbColor = MeshlineColors.Accent),
                            enabled = false,
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFF222B22))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Decentralized Nostr Internet Relay",
                                color = MeshlineColors.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Optional fallback over public Nostr relays for global channels when out of BLE range. Core mesh stays 100% offline.",
                                color = MeshlineColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = relayStatus.isEnabled,
                            onCheckedChange = { onSetInternetRelay(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MeshlineColors.Accent,
                                checkedTrackColor = Color(0xFF1B4D2B),
                            ),
                        )
                    }

                    if (relayStatus.isEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Status: ${relayStatus.state.name} (${relayStatus.activeRelayCount} relays connected)",
                            color = if (relayStatus.activeRelayCount > 0) MeshlineColors.Accent else Color(0xFFFFD54F),
                            fontSize = 11.sp,
                        )
                        Text(
                            "Relays: wss://relay.damus.io, wss://nos.lol, wss://relay.primal.net",
                            color = MeshlineColors.TextSecondary,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Message Retention & Privacy Settings
            Text(
                "DATA RETENTION & SECURITY",
                color = MeshlineColors.Accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color(0xFF141A14),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Auto-Delete Message Retention:",
                        color = MeshlineColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    val options = listOf(
                        MessageRetention.OFF to "Keep Forever",
                        MessageRetention.ONE_DAY to "Delete after 24 Hours",
                        MessageRetention.SEVEN_DAYS to "Delete after 7 Days",
                        MessageRetention.THIRTY_DAYS to "Delete after 30 Days",
                    )
                    options.forEach { (opt, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSetRetention(opt) }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = retention == opt,
                                onClick = { onSetRetention(opt) },
                                colors = RadioButtonDefaults.colors(selectedColor = MeshlineColors.Accent),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = MeshlineColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF222B22))
                    Text(
                        "Storage: SQLCipher v4 encrypted Room v7 database with Keystore key wrapping.",
                        color = MeshlineColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Emergency Data Wipe (Panic Wipe)
            Text(
                "EMERGENCY",
                color = Color(0xFFFF8A80),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            PanicWipeButton(onArmed = onPanicWipeArmed)
            Spacer(Modifier.height(32.dp))
        }

        // Edit Profile Dialog
        if (showEditProfileDialog) {
            var newNick by remember { mutableStateOf(profile.nickname) }
            var newAvatar by remember { mutableIntStateOf(profile.avatarIndex.toInt()) }

            AlertDialog(
                onDismissRequest = { showEditProfileDialog = false },
                title = { Text("Edit Identity Profile", color = MeshlineColors.TextPrimary) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newNick,
                            onValueChange = { if (it.length <= 32) newNick = it },
                            label = { Text("Display Nickname") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Select Fruit Avatar:", color = MeshlineColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            (0..7).forEach { idx ->
                                Text(
                                    fruitEmoji(idx),
                                    fontSize = if (newAvatar == idx) 26.sp else 20.sp,
                                    modifier = Modifier
                                        .clickable { newAvatar = idx }
                                        .padding(2.dp),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            (8..15).forEach { idx ->
                                Text(
                                    fruitEmoji(idx),
                                    fontSize = if (newAvatar == idx) 26.sp else 20.sp,
                                    modifier = Modifier
                                        .clickable { newAvatar = idx }
                                        .padding(2.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val clean = newNick.trim()
                            if (clean.isNotEmpty()) {
                                onSaveProfile(clean, newAvatar.toByte())
                                showEditProfileDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
                    ) {
                        Text("Save", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEditProfileDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}
