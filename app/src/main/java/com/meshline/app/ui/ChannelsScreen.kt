package com.meshline.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.meshline.app.channels.ChannelItem
import com.meshline.app.channels.ChannelMessageUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChannelsScreen(
    channels: List<ChannelItem>,
    activeChannel: String,
    messages: List<ChannelMessageUi>,
    onSelectChannel: (String) -> Unit,
    onJoinChannel: (String) -> Unit,
    onSendMessage: (channel: String, text: String) -> Unit,
    onExecuteCommand: (commandText: String, activeChannel: String) -> Unit,
) {
    var showJoinDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val currentChannel = channels.firstOrNull { it.name.equals(activeChannel, ignoreCase = true) }
        ?: ChannelItem(name = activeChannel, description = "Topic Channel")

    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Horizontal Channel Pills Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                channels.forEach { ch ->
                    val isSelected = ch.name.equals(activeChannel, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectChannel(ch.name) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    ch.name,
                                    color = if (isSelected) Color.Black else MeshlineColors.TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (ch.unreadCount > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (isSelected) Color.Black else MeshlineColors.Accent,
                                        shape = CircleShape,
                                        modifier = Modifier.size(16.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "${ch.unreadCount}",
                                                color = if (isSelected) MeshlineColors.Accent else Color.Black,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MeshlineColors.Accent,
                            containerColor = Color(0xFF161E17),
                        ),
                    )
                }

                // Join Button
                TextButton(onClick = { showJoinDialog = true }) {
                    Text("+ Join", color = MeshlineColors.Accent, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Channel Info Header
            Surface(
                color = Color(0xFF111711),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            currentChannel.name,
                            color = MeshlineColors.Accent,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            currentChannel.description,
                            color = MeshlineColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (currentChannel.isGeographic) {
                        Text(
                            "📍 Geo",
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Message Stream
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No messages in ${currentChannel.name} yet.\nStart the conversation below or invite nearby peers.",
                        color = MeshlineColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
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
                                color = if (isSelf) Color(0xFF1C3822) else Color(0xFF141A14),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    if (!isSelf) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(fruitEmoji(m.senderAvatarIndex), fontSize = 14.sp)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                m.senderNickname,
                                                color = MeshlineColors.Accent,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Spacer(Modifier.height(2.dp))
                                    }
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
                listOf("/help", "/who", "/channels", "/join #mesh", "/clear", "/slap").forEach { cmd ->
                    Surface(
                        color = Color(0xFF19231A),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable {
                            if (cmd.startsWith("/join ")) {
                                onJoinChannel(cmd.removePrefix("/join "))
                            } else if (cmd == "/help" || cmd == "/who" || cmd == "/channels" || cmd == "/clear") {
                                onExecuteCommand(cmd, activeChannel)
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

            // Message Composer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 1000) draft = it },
                    placeholder = { Text("Message $activeChannel (or /command)…") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            if (text.startsWith("/")) {
                                onExecuteCommand(text, activeChannel)
                            } else {
                                onSendMessage(activeChannel, text)
                            }
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
                ) {
                    Text("Send", color = Color.Black)
                }
            }
        }

        // Join Channel Dialog
        if (showJoinDialog) {
            var newChannelInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showJoinDialog = false },
                title = { Text("Join Topic Channel", color = MeshlineColors.TextPrimary) },
                text = {
                    Column {
                        Text(
                            "Enter the topic channel name (e.g. #radio, #crypto, #hiking):",
                            color = MeshlineColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newChannelInput,
                            onValueChange = { newChannelInput = it.lowercase().replace(" ", "-") },
                            placeholder = { Text("#channel") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val norm = if (newChannelInput.startsWith("#")) newChannelInput else "#$newChannelInput"
                            if (norm.length > 1) {
                                onJoinChannel(norm)
                                showJoinDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
                    ) {
                        Text("Join", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}
