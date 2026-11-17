package com.meshline.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshline.app.MeshlineColors
import com.meshline.app.groups.GroupItem
import com.meshline.app.groups.GroupMessageUi
import com.meshline.app.groups.PendingGroupInvite
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.protocol.GroupPayloads
import com.meshline.app.storage.ContactEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val FRUIT_EMOJIS = listOf(
    "🍎", "🍏", "🍐", "🍊", "🍋", "🍌",
    "🍉", "🍇", "🍓", "🍈", "🍒", "🍑",
    "🍍", "🥝", "🍅", "🥑"
)

fun fruitEmoji(index: Int): String = FRUIT_EMOJIS.getOrElse(index) { "🍈" }

@Composable
fun GroupList(
    groups: List<GroupItem>,
    pendingInvites: List<PendingGroupInvite>,
    onOpenGroup: (GroupItem) -> Unit,
    onAcceptInvite: (String) -> Unit,
    onDeclineInvite: (String) -> Unit,
    onCreateGroupClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            // Pending group invites banner
            if (pendingInvites.isNotEmpty()) {
                Text(
                    "PENDING GROUP INVITES",
                    color = MeshlineColors.Accent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                pendingInvites.forEach { pending ->
                    val gidHex = pending.invite.groupId.joinToString("") { "%02x".format(it) }
                    Surface(
                        color = Color(0xFF1E2820),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    fruitEmoji(pending.invite.icon.toInt()),
                                    fontSize = 24.sp,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        pending.invite.name,
                                        color = MeshlineColors.TextPrimary,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "${pending.invite.roster.size} members · Encrypted with ChaCha20-Poly1305",
                                        color = MeshlineColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = { onDeclineInvite(gidHex) },
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    Text("Decline", color = Color(0xFFFF8A80))
                                }
                                Button(
                                    onClick = { onAcceptInvite(gidHex) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
                                ) {
                                    Text("Accept", color = Color.Black)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No group chats yet.",
                            color = MeshlineColors.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Create an encrypted group chat with nearby or verified peers using the + button.",
                            color = MeshlineColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(groups, key = { it.groupIdHex }) { group ->
                        GroupListItem(group = group, onClick = { onOpenGroup(group) })
                    }
                }
            }
        }

        // Floating Action Button to create a group
        FloatingActionButton(
            onClick = onCreateGroupClick,
            containerColor = MeshlineColors.Accent,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun GroupListItem(group: GroupItem, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF141914),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1F2921)),
                contentAlignment = Alignment.Center,
            ) {
                Text(fruitEmoji(group.avatarIndex.toInt()), fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        color = MeshlineColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (group.isAdmin) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "admin",
                            color = MeshlineColors.Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${group.roster.size} members · epoch ${group.epoch}",
                    color = MeshlineColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (group.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MeshlineColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${group.unreadCount}",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun CreateGroupDialog(
    availableContacts: List<ContactEntity>,
    onDismiss: () -> Unit,
    onCreate: (name: String, avatarIndex: Byte, selectedContacts: List<ContactEntity>) -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableIntStateOf(0) }
    val selectedMembers = remember { mutableStateListOf<ContactEntity>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Create Encrypted Group",
                color = MeshlineColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { if (it.length <= GroupPayloads.MAX_GROUP_NAME_CHARS) groupName = it },
                    label = { Text("Group Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Select Group Fruit Avatar:", color = MeshlineColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    (0..7).forEach { idx ->
                        Text(
                            fruitEmoji(idx),
                            fontSize = if (selectedAvatar == idx) 26.sp else 20.sp,
                            modifier = Modifier
                                .clickable { selectedAvatar = idx }
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
                            fontSize = if (selectedAvatar == idx) 26.sp else 20.sp,
                            modifier = Modifier
                                .clickable { selectedAvatar = idx }
                                .padding(2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Invite Verified Contacts (${selectedMembers.size}):",
                    color = MeshlineColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (availableContacts.isEmpty()) {
                    Text(
                        "No verified contacts available. You can create the group now and invite peers later.",
                        color = MeshlineColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                        items(availableContacts) { contact ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selectedMembers.contains(contact)) {
                                            selectedMembers.remove(contact)
                                        } else {
                                            selectedMembers.add(contact)
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                            ) {
                                Checkbox(
                                    checked = selectedMembers.contains(contact),
                                    onCheckedChange = { checked ->
                                        if (checked) selectedMembers.add(contact) else selectedMembers.remove(contact)
                                    },
                                )
                                Text(
                                    "${fruitEmoji(contact.avatarIndex)} ${contact.nickname}",
                                    color = MeshlineColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(groupName.trim(), selectedAvatar.toByte(), selectedMembers.toList()) },
                enabled = groupName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MeshlineColors.Accent),
            ) {
                Text("Create", color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun GroupChatScreen(
    group: GroupItem,
    messages: List<GroupMessageUi>,
    onSend: (String) -> Unit,
    onLeaveGroup: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(color = MeshlineColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← Chats") }
                Text(
                    fruitEmoji(group.avatarIndex.toInt()),
                    fontSize = 24.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.name,
                        color = MeshlineColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${group.roster.size} members · epoch ${group.epoch} · E2EE",
                        color = MeshlineColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onLeaveGroup) {
                    Text("Leave", color = Color(0xFFFF8A80))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Message list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
                            color = if (isSelf) Color(0xFF1E3A24) else Color(0xFF161F18),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (!isSelf) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(fruitEmoji(m.senderAvatarIndex), fontSize = 14.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            m.senderNickname,
                                            color = MeshlineColors.Accent,
                                            style = MaterialTheme.typography.labelMedium,
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(
                                        timeFormat.format(Date(m.timestampMs)),
                                        color = MeshlineColors.TextSecondary,
                                        fontSize = 10.sp,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "ep${m.epoch}",
                                        color = MeshlineColors.TextSecondary,
                                        fontSize = 9.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Input field
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 1000) draft = it },
                    placeholder = { Text("Group message (encrypted)…") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            onSend(text)
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
    }
}
