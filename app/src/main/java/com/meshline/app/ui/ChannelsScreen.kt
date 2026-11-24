package com.meshline.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshline.app.channels.ChannelItem
import com.meshline.app.channels.ChannelMessageUi
import com.meshline.app.protocol.MessageIds
import com.meshline.app.service.ReactionUi
import com.meshline.app.ui.theme.MeshlineDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modernized Topic Channels screen matching Stitch specifications.
 */
@Composable
fun ChannelsScreen(
    channels: List<ChannelItem>,
    activeChannel: String,
    messages: List<ChannelMessageUi>,
    onSelectChannel: (String) -> Unit,
    onJoinChannel: (String) -> Unit,
    onSendMessage: (channel: String, text: String) -> Unit,
    onSendControl: (messageIdHex: String, delete: Boolean, newText: String?) -> Unit = { _, _, _ -> },
    onExecuteCommand: (commandText: String, activeChannel: String) -> Unit,
    reactions: Map<String, List<ReactionUi>> = emptyMap(),
    onToggleReaction: (targetIdHex: String, emoji: String) -> Unit = { _, _ -> },
) {
    val colors = MeshlineDesign.colors
    var showJoinDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Edit/Delete dialog state
    var editTarget by remember { mutableStateOf<ChannelMessageUi?>(null) }
    var editText by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<ChannelMessageUi?>(null) }

    if (actionTarget != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("Message options", color = colors.textPrimary) },
            text = {
                Column {
                    TextButton(onClick = {
                        actionTarget?.let { m ->
                            editText = m.text
                            editTarget = m
                        }
                        actionTarget = null
                    }) { Text("Edit", color = colors.primary) }
                    TextButton(onClick = {
                        actionTarget?.let { m ->
                            onSendControl(m.messageIdHex, true, null)
                        }
                        actionTarget = null
                    }) { Text("Delete", color = colors.danger) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            }
        )
    }

    if (editTarget != null) {
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit message", color = colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { v -> editText = v.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.border,
                        cursorColor = colors.primary,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editTarget?.let { m ->
                        if (editText.isNotBlank()) {
                            onSendControl(m.messageIdHex, false, editText.trim())
                        }
                    }
                    editTarget = null
                }) { Text("Save", color = colors.primary) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            }
        )
    }

    val currentChannel = channels.firstOrNull { it.name.equals(activeChannel, ignoreCase = true) }
        ?: ChannelItem(name = activeChannel, description = "Topic Channel")

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // Header: Title + Join Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Topic Channels",
                        color = colors.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Public mesh broadcast rooms",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9999.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                        .clickable { showJoinDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "+ Join Channel",
                        color = colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Horizontal Channel Pills Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                channels.forEach { ch ->
                    val isSelected = ch.name.equals(activeChannel, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(if (isSelected) colors.primarySoft else colors.surfaceContainerLow)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) colors.primary else colors.border,
                                shape = RoundedCornerShape(9999.dp),
                            )
                            .clickable { onSelectChannel(ch.name) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = ch.name,
                                color = if (isSelected) colors.primary else colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                            if (ch.unreadCount > 0) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${ch.unreadCount}",
                                        color = colors.onPrimary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Active Channel Banner Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = currentChannel.name,
                            color = colors.primary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = currentChannel.description,
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        text = "${messages.size} msgs",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                    )
                }
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
                                Text("#", fontSize = 36.sp, color = colors.primary)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "No messages in ${currentChannel.name} yet.",
                                    color = colors.textSecondary,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    "Be the first to post a topic broadcast.",
                                    color = colors.textMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                } else {
                    items(messages, key = { it.messageIdHex }) { m ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .pointerInput(m.messageIdHex) {
                                    detectTapGestures(
                                        onLongPress = {
                                            if (m.isSelf && !m.deleted) actionTarget = m
                                        }
                                    )
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surface)
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
                                        Text(fruitEmoji(m.senderAvatarIndex), fontSize = 15.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = m.senderNickname,
                                            color = colors.secondary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(
                                        text = timeFormat.format(Date(m.timestampMs)),
                                        color = colors.textMuted,
                                        fontSize = 10.sp,
                                    )
                                }

                                Spacer(Modifier.height(6.dp))

                                if (m.deleted) {
                                    Text(
                                        text = "🚫 This message was deleted",
                                        color = colors.textMuted,
                                        fontSize = 13.sp,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    )
                                } else {
                                    Text(
                                        text = buildAnnotatedGroupText(m.text, colors.primary),
                                        color = colors.textPrimary,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                    )
                                    if (m.editedAtMs != null) {
                                        Text(
                                            text = "(edited)",
                                            color = colors.textMuted,
                                            fontSize = 10.sp,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        )
                                    }
                                    // Quoted reply line
                                    m.parentIdHex?.let { parent ->
                                        val quoted = messages.firstOrNull { it.messageIdHex == parent }
                                        Text(
                                            text = "⤷ ${quoted?.senderNickname ?: "..."}: ${quoted?.text?.take(60) ?: parent.take(8)}",
                                            color = colors.textMuted,
                                            fontSize = 11.sp,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }

                                // Feature 12: reactions on channel broadcasts.
                                // Relay-only messages carry synthetic ids (not a
                                // 32-byte message id), so they cannot be targetted.
                                if (MessageIds.isValidMessageIdHex(m.messageIdHex)) {
                                    ReactionBar(
                                        reactions = reactions[m.messageIdHex] ?: emptyList(),
                                        onTap = { emoji -> onToggleReaction(m.messageIdHex, emoji) },
                                    )
                                }
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
                listOf("/help", "/join", "/who", "/clear", "/slap").forEach { cmd ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                            .clickable { onExecuteCommand(cmd, activeChannel) }
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
                                Text("Message ${currentChannel.name} or /cmd…", color = colors.textMuted, fontSize = 14.sp)
                            }
                            innerTextField()
                        },
                    )
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (draft.isNotBlank()) colors.primary else colors.surfaceElevated)
                        .clickable(enabled = draft.isNotBlank()) {
                            val text = draft.trim()
                            if (text.startsWith("/")) {
                                onExecuteCommand(text, activeChannel)
                            } else {
                                onSendMessage(activeChannel, text)
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

    if (showJoinDialog) {
        JoinChannelDialog(
            onDismiss = { showJoinDialog = false },
            onJoin = { chName ->
                onJoinChannel(chName)
                showJoinDialog = false
            },
        )
    }
}

private fun buildAnnotatedGroupText(
    text: String,
    mentionColor: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val regex = Regex("@(\\w+)")
    var lastEnd = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > lastEnd) builder.append(text.substring(lastEnd, match.range.first))
        builder.pushStyle(
            androidx.compose.ui.text.SpanStyle(
                color = mentionColor,
                fontWeight = FontWeight.Bold,
            )
        )
        builder.append(match.value)
        builder.pop()
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) builder.append(text.substring(lastEnd))
    return builder.toAnnotatedString()
}

@Composable
private fun JoinChannelDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    val colors = MeshlineDesign.colors
    var channelInput by remember { mutableStateOf("#") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Join Topic Channel",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    "Enter a channel name beginning with '#' (e.g. #alerts, #tech).",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                    )
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(colors.primary)
                    .clickable {
                        val clean = channelInput.trim()
                        if (clean.isNotEmpty()) onJoin(clean)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Join", color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}
