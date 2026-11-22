package com.meshline.app.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshline.app.service.GeoPingUi
import com.meshline.app.service.MeshStatsSnapshot
import com.meshline.app.service.PublicChatMessage
import com.meshline.app.service.ReactionUi
import com.meshline.app.transport.PeerInfo
import com.meshline.app.transport.RelayStatus
import com.meshline.app.transport.TransportMode
import com.meshline.app.ui.theme.MeshlineDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Redesigned Nearby Mesh radar & public chat screen based on Google Stitch design system.
 */
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
    reactions: Map<String, List<ReactionUi>> = emptyMap(),
    geoPings: List<GeoPingUi> = emptyList(),
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    onSendGeoPing: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
) {
    val colors = MeshlineDesign.colors
    var draft by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val geoPingsVisible = geoPings.filter { System.currentTimeMillis() - it.timestampMs < GEO_PING_VISIBLE_MS }

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // Header: Title + BLE Status Pill + Diagnostics Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Nearby Mesh",
                        color = colors.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clip(RoundedCornerShape(9999.dp))
                            .background(colors.primarySoft)
                            .border(0.5.dp, colors.border, RoundedCornerShape(9999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (startError == null) colors.statusConnected else colors.danger)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (startError == null) "BLE Active • Dual GATT" else "BLE Error",
                                color = if (startError == null) colors.primary else colors.danger,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9999.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                        .clickable { showDiagnostics = !showDiagnostics }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (showDiagnostics) "Hide Metrics" else "Mesh Telemetry",
                        color = colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Hero Mesh Radar & Metrics Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📡", fontSize = 24.sp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "${peers.size} Nearby Peers",
                                    color = colors.textPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (relayStatus.mode == TransportMode.BLE_ONLY) "Direct Offline Radio Mesh" else "BLE Mesh + Nostr Relay Active",
                                    color = colors.primary,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9999.dp))
                                .background(colors.primarySoft)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Max 7 Hops",
                                color = colors.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // Collapsible Diagnostics
                    AnimatedVisibility(visible = showDiagnostics) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .background(colors.surfaceContainerLow, RoundedCornerShape(10.dp))
                                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                        ) {
                            Text("DIAGNOSTICS & TELEMETRY", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Relayed: ${stats.relayedForwards} · Delivered: ${stats.delivered}", color = colors.textSecondary, fontSize = 12.sp)
                            Text("Rate Limit Drops: ${stats.droppedBudget} · Bad Signatures: ${stats.droppedBadSignature}", color = colors.textSecondary, fontSize = 12.sp)
                            Text("Active Connections: ${peers.size} / 6 max slots", color = colors.textSecondary, fontSize = 12.sp)
                            if (lastGattError != null) {
                                Text("Last GATT Status: $lastGattError", color = colors.danger, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Feature 8: tapable roster of nearby peers (nickname, signal, hops, chat).
            if (peers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = "NEARBY PEERS",
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    peers.forEach { peer ->
                        val rssi = peer.rssiDbm
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            // Signal strength: colored dot + dBm reading.
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(when {
                                        rssi == null -> colors.border
                                        rssi > -60 -> colors.statusConnected
                                        else -> colors.danger
                                    }),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = peer.nickname ?: peer.peerIdHex.take(6).uppercase(),
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Row {
                                    rssi?.let { value ->
                                        Text(
                                            text = "$value dBm",
                                            color = colors.textMuted,
                                            fontSize = 9.sp,
                                        )
                                    }
                                    peer.lastHops?.takeIf { it > 0 }?.let { hops ->
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "$hops hop${if (hops != 1) "s" else ""}",
                                            color = colors.textMuted,
                                            fontSize = 9.sp,
                                        )
                                    }
                                }
                            }
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clickable { onOpenChat(peer.peerIdHex) }
                                    .clip(RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("💬", fontSize = 14.sp)
                            }
                        }
                        if (peer != peers.last()) Spacer(Modifier.height(2.dp))
                    }
                }
            }

            // Feature 3: Geo pings section (recent pings received/sent on this node).
            if (geoPingsVisible.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = "GEO PINGS",
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    geoPingsVisible.forEach { ping ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = ping.nickname,
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = ping.geohash,
                                color = colors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = geoPingAge(ping.timestampMs),
                                color = colors.textMuted,
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            if (ping.hops > 0) {
                                Text(
                                    text = "${ping.hops} Hop${if (ping.hops != 1) "s" else ""}",
                                    color = colors.primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (ping != geoPingsVisible.last()) {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            // Public Broadcast Feed Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "PUBLIC BROADCAST STREAM",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Signed • Unencrypted",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                )
            }

            // Message Stream
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📡", fontSize = 36.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "No public broadcast messages yet.",
                                    color = colors.textSecondary,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    "Type below to broadcast to all nearby peers within range.",
                                    color = colors.textMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                } else {
                    items(messages, key = { it.messageIdHex }) { m ->
                        val isSelf = m.isSelf
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelf) colors.surfaceElevated else colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(fruitEmoji(m.senderAvatarIndex), fontSize = 16.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = m.senderNickname,
                                            color = if (isSelf) colors.primary else colors.textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(colors.surfaceContainerLow)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "${m.hops} Hop${if (m.hops != 1) "s" else ""}",
                                                color = colors.primary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = timeFormat.format(Date(m.timestampMs)),
                                            color = colors.textMuted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Text(
                                    text = m.text,
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                )
                                // Feature 1: emoji reaction strip under the message.
                                ReactionBar(
                                    reactions = reactions[m.messageIdHex] ?: emptyList(),
                                    onTap = { emoji -> onToggleReaction(m.messageIdHex, emoji) },
                                )
                            }
                        }
                    }
                }
            }

            // Quick Slash Command Suggestions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("/help", "/who", "/clear", "/slap").forEach { cmd ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                            .clickable { onExecuteCommand(cmd) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(cmd, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Keyboard-aware Pill Composer Dock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(colors.surfaceContainerLow, RoundedCornerShape(9999.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 1000) draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        decorationBox = { innerTextField ->
                            if (draft.isEmpty()) {
                                Text("Broadcast to nearby peers or run /cmd…", color = colors.textMuted, fontSize = 14.sp)
                            }
                            innerTextField()
                        },
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Feature 3: geo ping button — broadcasts this node's location
                // as a coarse geohash so people nearby can find each other.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(9999.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                        .clickable { onSendGeoPing() }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "📍", color = colors.textSecondary, fontSize = 16.sp)
                }

                Spacer(Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (draft.isNotBlank()) colors.primary else colors.surfaceElevated)
                        .clickable(enabled = draft.isNotBlank()) {
                            val text = draft.trim()
                            if (text.startsWith("/")) {
                                onExecuteCommand(text)
                            } else {
                                onSendPublic(text)
                            }
                            draft = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "➤",
                        color = if (draft.isNotBlank()) colors.onPrimary else colors.textMuted,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

/** Emojis offered as quick-tap reaction targets (Feature 1). */
private val COMMON_EMOJIS = listOf("👍", "❤️", "😂", "🥳", "👏", "🙌", "🔥", "😮")

/** How long a geo ping stays visible in the Nearby section (Feature 3). */
private const val GEO_PING_VISIBLE_MS = 5L * 60 * 1000

private fun geoPingAge(tsMs: Long): String {
    val d = System.currentTimeMillis() - tsMs
    return when {
        d < 60_000 -> "${d / 1000}s ago"
        d < 3_600_000 -> "${d / 60_000}m ago"
        else -> "${d / 3_600_000}h ago"
    }
}

/**
 * Feature 1: reaction strip under a public broadcast message. Shows the
 * common emojis (plus any extra emojis other nodes use) with counts;
 * tapping toggles the local identity's reaction on the message.
 */
@Composable
internal fun ReactionBar(
    reactions: List<ReactionUi>,
    onTap: (String) -> Unit,
) {
    val colors = MeshlineDesign.colors
    val rows = if (reactions.isEmpty()) {
        COMMON_EMOJIS
    } else {
        (COMMON_EMOJIS + reactions.map { it.emoji }.filterNot { it in COMMON_EMOJIS }).distinct()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        rows.forEach { emoji ->
            val ui = reactions.firstOrNull { it.emoji == emoji }
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(if (ui?.own == true) colors.primarySoft else colors.surfaceContainerLow)
                    .border(
                        1.dp,
                        if (ui?.own == true) colors.primary else colors.border,
                        RoundedCornerShape(9999.dp),
                    )
                    .clickable { onTap(emoji) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (ui != null && ui.count > 0) "$emoji ${ui.count}" else emoji,
                    color = colors.textPrimary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
