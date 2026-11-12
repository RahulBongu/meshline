package com.meshline.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meshline.app.MeshlineColors
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.service.KeyChangeEvent
import com.meshline.app.service.PrivateChatMessage
import com.meshline.app.service.PrivatePeer
import com.meshline.app.storage.OutboxState

/**
 * The **Chats** screen with two tabs: Nearby (the signed public feed) and
 * Private (the Noise-encrypted chats).
 *
 * Nothing here logs: the chat text stays in the UI and in the encrypted
 * database.
 */
@Composable
fun ChatsScreen(
    nearby: @Composable () -> Unit,
    private: @Composable () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Nearby (public)") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Private (encrypted)") },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (tab == 0) nearby() else private()
        }
    }
}

/**
 * The private peer list. The **verified badge is only for QR-verified
 * contacts** — a peer we merely met in the mesh has no badge, because a
 * matching 4-hex prefix proves nothing.
 */
@Composable
fun PrivatePeerList(
    peers: List<PrivatePeer>,
    onOpen: (PrivatePeer) -> Unit,
) {
    if (peers.isEmpty()) {
        Text(
            "No private chats yet. Start the mesh, open a chat with a peer, " +
                "or scan a contact's QR code to add them.",
            color = MeshlineColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(peers, key = { it.peerIdHex }) { peer ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(peer) }
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${AvatarTable.name(peer.avatarIndex)} ${peer.nickname}",
                        color = MeshlineColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (peer.verified) {
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("✓ verified", color = MeshlineColors.Accent, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    buildString {
                        append(peer.peerIdHex.take(8))
                        append("… ")
                        append(if (peer.online) "online" else "offline")
                        if (peer.sessionReady) append(" · session ready")
                        if (peer.onHold) append(" · key changed — needs your approval")
                    },
                    color = MeshlineColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * One private chat. The state column is honest about what BLE promises:
 * *Queued* (no session yet), *Sent* (handed to the mesh), *Delivered* (the
 * peer acknowledged it) and *Read* (the peer opened it).
 */
@Composable
fun PrivateChatScreen(
    peer: PrivatePeer,
    messages: List<PrivateChatMessage>,
    onSend: (String) -> Unit,
    onResend: (String) -> Unit,
    onClearChat: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Chats") }
                Text(
                    "${AvatarTable.name(peer.avatarIndex)} ${peer.nickname}",
                    color = MeshlineColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (peer.verified) {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("✓ verified", color = MeshlineColors.Accent, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearChat) { Text("Clear chat") }
            }
            if (peer.onHold) {
                Text(
                    "This peer's key changed. Their private traffic is held until you " +
                        "accept the new key.",
                    color = Color(0xFFFFD54F),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(messages, key = { it.messageIdHex + it.outgoing }) { m ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            m.text,
                            color = if (m.outgoing) MeshlineColors.TextPrimary else MeshlineColors.Accent,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stateLabel(m),
                                color = MeshlineColors.TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (m.resendable) {
                                TextButton(onClick = { onResend(m.messageIdHex) }) { Text("Resend") }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 2000) draft = it },
                label = { Text("private message") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send (encrypted)") }
        }
    }
}

/** The honest label for a message state. */
private fun stateLabel(m: PrivateChatMessage): String = when (m.state) {
    "QUEUED" -> "Queued — waiting for a secure session"
    "SENT" -> "Sent — handed to the mesh"
    "DELIVERED" -> "Delivered"
    "READ" -> "Read"
    "NOT_DELIVERED" -> "Not delivered — nobody acknowledged it"
    else -> if (m.outgoing) m.state else "received"
}

/**
 * The key-change warning (NOISE_DESIGN §7.2): a peer presented a different
 * key than the one we know. The user must **explicitly accept** it; until
 * then the contact's private traffic stays Queued.
 */
@Composable
fun KeyChangeDialog(
    event: KeyChangeEvent,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* the decision must be explicit */ },
        title = { Text("Identity changed — possible impersonation") },
        text = {
            Column {
                Text("${event.nickname} now presents a different key.")
                Spacer(Modifier.height(8.dp))
                Text("previous fingerprint: ${event.oldFingerprint}", style = MaterialTheme.typography.bodySmall)
                Text("new fingerprint: ${event.newFingerprint}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Their private messages stay Queued until you accept. If you did not " +
                        "expect this, reject it and they are blocked.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept new key") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Reject & block") } },
    )
}

/**
 * The panic-wipe confirmation. The wipe is irreversible: the identity, the
 * contacts, every session and every message are destroyed.
 */
@Composable
fun PanicWipeDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Wipe this device?") },
        text = {
            Text(
                "This erases your identity, your contacts, every secure session and every " +
                    "message. The device comes back with a brand new identity that nobody " +
                    "recognises. It cannot be undone."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Wipe everything") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * The panic-wipe trigger: three taps in a row (the third opens the
 * confirmation). A single accidental tap does nothing.
 */
@Composable
fun PanicWipeButton(onArmed: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    Surface(
        color = MeshlineColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable {
                val now = System.currentTimeMillis()
                taps = if (now - lastTap < TRIPLE_TAP_WINDOW_MS) taps + 1 else 1
                lastTap = now
                if (taps >= 3) {
                    taps = 0
                    onArmed()
                }
            },
    ) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("Danger zone", color = Color(0xFFFF8A80), style = MaterialTheme.typography.titleSmall)
            Text(
                "Panic wipe — tap three times, then confirm.",
                color = MeshlineColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val TRIPLE_TAP_WINDOW_MS = 1_500L
