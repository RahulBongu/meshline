package com.meshline.app.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshline.app.MeshlineColors
import com.meshline.app.service.MeshStatsSnapshot
import com.meshline.app.service.PublicChatMessage
import com.meshline.app.transport.PeerInfo
import com.meshline.app.transport.RelayStatus
import com.meshline.app.transport.TransportMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NearbyScreen(
    profileLabel: String,
    peers: List<PeerInfo>,
    messages: List<PublicChatMessage>,
    stats: MeshStatsSnapshot,
    lastGattError: Int?,
    advertiseSupported: Boolean,
    startError: String?,
    relayStatus: RelayStatus,
    onStartMesh: () -> Unit,
    onGrantPermissions: () -> Unit,
    onSendPublic: (String) -> Unit,
    onExecuteCommand: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Mesh Status Bar
            Surface(
                color = Color(0xFF141A14),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "●",
                                color = if (startError == null) MeshlineColors.Accent else Color(0xFFFF8A80),
                                fontSize = 16.sp,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(
                                "BLE MESH",
                                color = MeshlineColors.TextPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Surface(
                            color = if (relayStatus.mode == TransportMode.BLE_ONLY) Color(0xFF1E2820) else Color(0xFF2E2415),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                if (relayStatus.mode == TransportMode.BLE_ONLY) "OFFLINE RADIO" else "BLE + NOSTR",
                                color = if (relayStatus.mode == TransportMode.BLE_ONLY) MeshlineColors.Accent else Color(0xFFFFD54F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${peers.size} direct peer(s) linked · signed broadcast · hops ≤ 7",
                        color = MeshlineColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (startError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            startError,
                            color = Color(0xFFFF8A80),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onGrantPermissions,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                        ) {
                            Text("Grant Missing Bluetooth Permissions")
                        }
                    } else if (peers.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = onStartMesh,
                                colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Start Mesh (BLE)", color = Color.Black)
                            }
                        }
                    }
                }
            }

            // Diagnostics Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "NEARBY PUBLIC FEED",
                    color = MeshlineColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Text(
                        if (showDiagnostics) "Hide Diagnostics" else "Diagnostics (${peers.size} peers)",
                        color = MeshlineColors.Accent,
                        fontSize = 11.sp,
                    )
                }
            }

            // Collapsible Diagnostics Box
            AnimatedVisibility(visible = showDiagnostics) {
                Surface(
                    color = Color(0xFF0F140F),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Router Stats: delivered=${stats.delivered} | relayed=${stats.relayedForwards} | " +
                                "unknownKey=${stats.droppedUnknownKey} | badSig=${stats.droppedBadSignature} | " +
                                "held=${stats.heldTotal} | droppedBudget=${stats.droppedBudget}",
                            color = MeshlineColors.TextSecondary,
                            fontSize = 10.sp,
                        )
                        if (lastGattError != null) {
                            Text("Last GATT error: $lastGattError", color = Color(0xFFFF8A80), fontSize = 10.sp)
                        }
                        if (!advertiseSupported) {
                            Text("BLE Advertising not supported on this device (client mode only)", color = Color(0xFFFFD54F), fontSize = 10.sp)
                        }
                        peers.forEach { p ->
                            Text(
                                "Peer ${p.peerIdHex.take(8)}: rssi=${p.rssiDbm ?: "?"}dBm mtu=${p.mtu ?: "?"} role=${p.role}",
                                color = MeshlineColors.TextPrimary,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }

            // Messages Stream
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No nearby public messages yet.",
                            color = MeshlineColors.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Broadcasts are signed with your Ed25519 key and multi-hop relayed through mesh devices in radio range.",
                            color = MeshlineColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    reverseLayout = false,
                ) {
                    items(messages, key = { it.messageIdHex }) { m ->
                        val isSelf = m.isSelf
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
                        ) {
                            Surface(
                                color = if (isSelf) Color(0xFF1E3A24) else Color(0xFF141914),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(fruitEmoji(m.senderAvatarIndex), fontSize = 14.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            if (isSelf) "You" else m.senderNickname,
                                            color = if (isSelf) MeshlineColors.Accent else MeshlineColors.TextPrimary,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = Color(0xFF1D261E),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                if (m.hops == 0) "direct" else "${m.hops} hop${if (m.hops > 1) "s" else ""}",
                                                color = MeshlineColors.TextSecondary,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        m.text,
                                        color = MeshlineColors.TextPrimary,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        timeFormat.format(Date(m.timestampMs)),
                                        color = MeshlineColors.TextSecondary,
                                        fontSize = 10.sp,
                                        modifier = Modifier.align(Alignment.End),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Slash Command Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("/help", "/who", "/nick", "/channels", "/slap").forEach { cmd ->
                    Surface(
                        color = Color(0xFF182219),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable {
                            if (cmd == "/help" || cmd == "/who" || cmd == "/channels") {
                                onExecuteCommand(cmd)
                            } else {
                                draft = "$cmd "
                            }
                        },
                    ) {
                        Text(
                            cmd,
                            color = MeshlineColors.Accent,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Public Broadcast Composer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 500) draft = it },
                    placeholder = { Text("Nearby public broadcast (500 chars max)…") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            if (text.startsWith("/")) {
                                onExecuteCommand(text)
                            } else {
                                onSendPublic(text)
                            }
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
                ) {
                    Text("Broadcast", color = Color.Black)
                }
            }
        }
    }
}
