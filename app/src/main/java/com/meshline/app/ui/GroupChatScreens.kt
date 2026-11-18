package com.meshline.app.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.meshline.app.groups.GroupItem
import com.meshline.app.groups.GroupMessageUi
import com.meshline.app.groups.PendingGroupInvite
import com.meshline.app.protocol.GroupPayloads
import com.meshline.app.storage.ContactEntity
import com.meshline.app.ui.theme.MeshlineDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val FRUIT_EMOJIS = listOf(
    "🍎", "🍏", "🍐", "🍊", "🍋", "🍌",
    "🍉", "🍇", "🍓", "🍈", "🍒", "🍑",
    "🍍", "🥝", "🍅", "🥑"
)

fun fruitEmoji(index: Int): String = FRUIT_EMOJIS.getOrElse(index) { "🍈" }

/**
 * Modernized Create Group Dialog with fruit avatar selector and verified contact checkboxes.
 */
@Composable
fun CreateGroupDialog(
    availableContacts: List<ContactEntity>,
    onDismiss: () -> Unit,
    onCreate: (name: String, avatarIndex: Byte, selectedContacts: List<ContactEntity>) -> Unit,
) {
    val colors = MeshlineDesign.colors
    var groupName by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableIntStateOf(0) }
    val selectedMembers = remember { mutableStateListOf<ContactEntity>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Create Encrypted Group",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text("Group Name", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = groupName,
                        onValueChange = { if (it.length <= GroupPayloads.MAX_GROUP_NAME_CHARS) groupName = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        decorationBox = { innerTextField ->
                            if (groupName.isEmpty()) {
                                Text("e.g. Squad / Ops Mesh", color = colors.textMuted, fontSize = 14.sp)
                            }
                            innerTextField()
                        },
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("Group Avatar Icon", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    (0..7).forEach { idx ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (selectedAvatar == idx) colors.primarySoft else colors.surface)
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
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (selectedAvatar == idx) colors.primarySoft else colors.surface)
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

                Spacer(Modifier.height(14.dp))
                Text(
                    "Invite Verified Contacts (${selectedMembers.size}):",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))

                if (availableContacts.isEmpty()) {
                    Text(
                        "No verified contacts yet. You can create the group now and invite peers later.",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .background(colors.surfaceContainerLow, RoundedCornerShape(10.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(6.dp),
                    ) {
                        items(availableContacts) { contact ->
                            val isSelected = selectedMembers.contains(contact)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) selectedMembers.remove(contact)
                                        else selectedMembers.add(contact)
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp),
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedMembers.add(contact) else selectedMembers.remove(contact)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = colors.primary,
                                        checkmarkColor = colors.onPrimary,
                                    ),
                                )
                                Text(fruitEmoji(contact.avatarIndex), fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    contact.nickname,
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(if (groupName.isNotBlank()) colors.primary else colors.surfaceElevated)
                    .clickable(enabled = groupName.isNotBlank()) {
                        onCreate(groupName.trim(), selectedAvatar.toByte(), selectedMembers.toList())
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "Create Group",
                    color = if (groupName.isNotBlank()) colors.onPrimary else colors.textMuted,
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

/**
 * Modernized Group Chat Screen matching Stitch specifications.
 */
@Composable
fun GroupChatScreen(
    group: GroupItem,
    messages: List<GroupMessageUi>,
    onSend: (String) -> Unit,
    onLeaveGroup: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MeshlineDesign.colors
    var draft by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceElevated)
                    .border(0.5.dp, colors.border, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Text("←", color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(fruitEmoji(group.avatarIndex.toInt()), fontSize = 22.sp)
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${group.roster.size} members · epoch ${group.epoch} · ChaCha20 AEAD",
                        color = colors.primary,
                        fontSize = 11.sp,
                    )
                }

                TextButton(onClick = onLeaveGroup) {
                    Text("Leave", color = colors.danger, fontSize = 12.sp)
                }
            }

            // Group E2EE Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9999.dp))
                        .background(colors.surfaceContainerLow)
                        .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "🔒 ChaCha20-Poly1305 AEAD • Authenticated 18-byte AD",
                        color = colors.textSecondary,
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
                items(messages, key = { it.messageIdHex }) { m ->
                    val isSelf = m.isSelf
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
                    ) {
                        // Asymmetric Group Bubble
                        Box(
                            modifier = Modifier
                                .clip(
                                    if (isSelf) {
                                        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                                    } else {
                                        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                                    }
                                )
                                .background(if (isSelf) colors.bubbleOutgoing else colors.bubbleIncoming)
                                .border(
                                    width = if (isSelf) 0.dp else 1.dp,
                                    color = if (isSelf) Color.Transparent else colors.border,
                                    shape = if (isSelf) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column {
                                if (!isSelf) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(bottom = 3.dp),
                                    ) {
                                        Text(fruitEmoji(m.senderAvatarIndex), fontSize = 13.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = m.senderNickname,
                                            color = colors.secondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }

                                Text(
                                    text = m.text,
                                    color = if (isSelf) colors.bubbleOutgoingText else colors.bubbleIncomingText,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                )

                                Spacer(Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = timeFormat.format(Date(m.timestampMs)),
                                        color = if (isSelf) colors.bubbleOutgoingText.copy(alpha = 0.65f) else colors.textMuted,
                                        fontSize = 10.sp,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "ep${m.epoch}",
                                        color = if (isSelf) colors.bubbleOutgoingText.copy(alpha = 0.65f) else colors.textMuted,
                                        fontSize = 9.sp,
                                    )
                                }
                            }
                        }
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
                                Text(
                                    "Group message (encrypted)…",
                                    color = colors.textMuted,
                                    fontSize = 14.sp,
                                )
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
                            onSend(draft.trim())
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
