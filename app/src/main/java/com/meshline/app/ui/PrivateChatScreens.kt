package com.meshline.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshline.app.groups.GroupItem
import com.meshline.app.groups.PendingGroupInvite
import com.meshline.app.noise.PrivateEnvelope
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.service.KeyChangeEvent
import com.meshline.app.service.PrivateChatMessage
import com.meshline.app.service.PrivatePeer
import com.meshline.app.storage.ContactEntity
import com.meshline.app.ui.theme.MeshlineDesign
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Filter chip categories for the unified inbox.
 */
enum class ChatFilter {
    ALL,
    UNREAD,
    DIRECT,
    GROUPS,
}

/**
 * Redesigned Unified Chats inbox combining WhatsApp's chat efficiency
 * with Instagram's visual confidence and typography.
 */
@Composable
fun ChatsUnifiedScreen(
    peers: List<PrivatePeer>,
    groups: List<GroupItem>,
    pendingInvites: List<PendingGroupInvite>,
    contacts: List<ContactEntity>,
    privateMessages: List<PrivateChatMessage>,
    onOpenPeer: (PrivatePeer) -> Unit,
    onOpenGroup: (GroupItem) -> Unit,
    onAcceptInvite: (String) -> Unit,
    onDeclineInvite: (String) -> Unit,
    onCreateGroup: (name: String, avatarIndex: Byte, selectedContacts: List<ContactEntity>) -> Unit,
) {
    val colors = MeshlineDesign.colors
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(ChatFilter.ALL) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showSearchField by remember { mutableStateOf(false) }

    // Feature 6: message search results (in-memory over the private chat history).
    val messageResults = remember(searchQuery, privateMessages) {
        searchPrivateMessages(privateMessages, searchQuery)
    }

    // Filter peers and groups according to query
    val filteredPeers = remember(peers, searchQuery) {
        if (searchQuery.isBlank()) peers
        else peers.filter {
            it.nickname.contains(searchQuery, ignoreCase = true) ||
                it.peerIdHex.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val unreadPeersCount = peers.count { it.sessionReady } // Active ready peers
    val totalUnreadGroups = groups.sumOf { it.unreadCount } + pendingInvites.size

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar: "Meshline" title + Mesh Status Pill + Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "Meshline",
                            color = colors.textPrimary,
                            fontSize = 26.sp,
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
                                        .background(colors.statusConnected)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Mesh Active • ${peers.count { it.online }} Online",
                                    color = colors.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Search icon button
                        IconButton(onClick = { showSearchField = !showSearchField }) {
                            Text(text = "🔍", fontSize = 18.sp)
                        }
                        // Create group button
                        IconButton(onClick = { showCreateGroupDialog = true }) {
                            Text(text = "👥", fontSize = 18.sp)
                        }
                    }
                }

                // Sleek Pill Search Bar
                AnimatedVisibility(visible = showSearchField) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(colors.surfaceContainerLow, RoundedCornerShape(9999.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔍", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(colors.primary),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search conversations, contacts...",
                                            color = colors.textMuted,
                                            fontSize = 14.sp,
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    text = "✕",
                                    color = colors.textSecondary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { searchQuery = "" },
                                )
                            }
                        }
                    }
                }

                // Category Filter Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChipPill(
                        label = "All (${peers.size + groups.size})",
                        selected = activeFilter == ChatFilter.ALL,
                        onClick = { activeFilter = ChatFilter.ALL },
                    )
                    FilterChipPill(
                        label = "Unread ($totalUnreadGroups)",
                        selected = activeFilter == ChatFilter.UNREAD,
                        onClick = { activeFilter = ChatFilter.UNREAD },
                    )
                    FilterChipPill(
                        label = "Direct (${peers.size})",
                        selected = activeFilter == ChatFilter.DIRECT,
                        onClick = { activeFilter = ChatFilter.DIRECT },
                    )
                    FilterChipPill(
                        label = "Groups (${groups.size})",
                        selected = activeFilter == ChatFilter.GROUPS,
                        onClick = { activeFilter = ChatFilter.GROUPS },
                    )
                }

                // Pending Group Invites Banner
                if (pendingInvites.isNotEmpty() && (activeFilter == ChatFilter.ALL || activeFilter == ChatFilter.GROUPS || activeFilter == ChatFilter.UNREAD)) {
                    PendingInvitesSection(
                        pendingInvites = pendingInvites,
                        onAccept = onAcceptInvite,
                        onDecline = onDeclineInvite,
                    )
                }

                // Conversation List Stream
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // Feature 6: message search results, top of the list.
                    if (messageResults.isNotEmpty()) {
                        item {
                            Text(
                                text = "MESSAGE RESULTS (${messageResults.size})",
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        messageResults.forEach { (m, snippet) ->
                            val peer = peers.firstOrNull { it.peerIdHex == m.peerIdHex }
                            if (peer != null) {
                                item(key = "search_${m.messageIdHex}") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenPeer(peer) }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(fruitEmoji(peer.avatarIndex.toInt()), fontSize = 18.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = peer.nickname,
                                                    color = colors.textPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = snippet,
                                                color = colors.textSecondary,
                                                fontSize = 13.sp,
                                                maxLines = 2,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val showDirect = activeFilter == ChatFilter.ALL || activeFilter == ChatFilter.DIRECT
                    val showGroups = activeFilter == ChatFilter.ALL || activeFilter == ChatFilter.GROUPS

                    if (activeFilter == ChatFilter.UNREAD) {
                        val unreadGroupsList = filteredGroups.filter { it.unreadCount > 0 }
                        if (unreadGroupsList.isEmpty() && pendingInvites.isEmpty()) {
                            item {
                                EmptyInboxState("No unread messages.")
                            }
                        } else {
                            items(unreadGroupsList, key = { "grp_" + it.groupIdHex }) { group ->
                                GroupConversationItem(group = group, onClick = { onOpenGroup(group) })
                                HorizontalDivider(color = colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }
                    } else {
                        if (showGroups && filteredGroups.isNotEmpty()) {
                            items(filteredGroups, key = { "grp_" + it.groupIdHex }) { group ->
                                GroupConversationItem(group = group, onClick = { onOpenGroup(group) })
                                HorizontalDivider(color = colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }

                        if (showDirect && filteredPeers.isNotEmpty()) {
                            items(filteredPeers, key = { "peer_" + it.peerIdHex }) { peer ->
                                DirectConversationItem(peer = peer, onClick = { onOpenPeer(peer) })
                                HorizontalDivider(color = colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }

                        if ((showDirect && filteredPeers.isEmpty()) && (showGroups && filteredGroups.isEmpty())) {
                            item {
                                EmptyInboxState("No conversations found. Tap '+' to create a group or discover nearby peers on the mesh.")
                            }
                        }
                    }
                }
            }

            // Floating Action Button
            FloatingActionButton(
                onClick = { showCreateGroupDialog = true },
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            availableContacts = contacts.filter { it.verified },
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, icon, selected ->
                onCreateGroup(name, icon, selected)
                showCreateGroupDialog = false
            },
        )
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MeshlineDesign.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9999.dp))
            .background(if (selected) colors.primarySoft else colors.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = if (selected) colors.primary else colors.border,
                shape = RoundedCornerShape(9999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.primary else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun DirectConversationItem(
    peer: PrivatePeer,
    onClick: () -> Unit,
) {
    val colors = MeshlineDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar with online/mesh dot
        Box(modifier = Modifier.size(50.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fruitEmoji(peer.avatarIndex.toInt()),
                    fontSize = 24.sp,
                )
            }
            if (peer.online) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(colors.statusConnected)
                        .border(2.dp, colors.surface, CircleShape)
                        .align(Alignment.BottomEnd)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        // Center: Name + Preview
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = peer.nickname,
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (peer.verified) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "✓",
                        color = colors.statusConnected,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(3.dp))

            Text(
                text = if (peer.onHold) "Key changed — approval required"
                else if (peer.sessionReady) "Noise XX session active"
                else "Offline · Tap to message",
                color = if (peer.onHold) colors.danger else colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Trailing: Peer ID snippet & Status
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = peer.peerIdHex.take(6).uppercase(),
                color = colors.textMuted,
                fontSize = 11.sp,
            )
            if (peer.sessionReady) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9999.dp))
                        .background(colors.primarySoft)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "E2EE",
                        color = colors.primary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupConversationItem(
    group: GroupItem,
    onClick: () -> Unit,
) {
    val colors = MeshlineDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Group Avatar
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fruitEmoji(group.avatarIndex.toInt()),
                fontSize = 24.sp,
            )
        }

        Spacer(Modifier.width(14.dp))

        // Name + Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${group.roster.size} members · epoch ${group.epoch}",
                color = colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Unread Badge
        Column(horizontalAlignment = Alignment.End) {
            if (group.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${group.unreadCount}",
                        color = colors.onPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Text(
                    text = "ep${group.epoch}",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun PendingInvitesSection(
    pendingInvites: List<PendingGroupInvite>,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    val colors = MeshlineDesign.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = "PENDING GROUP INVITES",
            color = colors.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        pendingInvites.forEach { pending ->
            val gidHex = pending.invite.groupId.joinToString("") { "%02x".format(it) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(colors.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fruitEmoji(pending.invite.icon.toInt()), fontSize = 28.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pending.invite.name,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${pending.invite.roster.size} members · Encrypted with ChaCha20-Poly1305",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9999.dp))
                                    .background(colors.primary)
                                    .clickable { onAccept(gidHex) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text("Accept", color = colors.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9999.dp))
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                                    .clickable { onDecline(gidHex) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text("Decline", color = colors.danger, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInboxState(message: String) {
    val colors = MeshlineDesign.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("💬", fontSize = 42.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * Modernized 1-on-1 Private Chat Screen matching Stitch specifications:
 * - Asymmetric chat bubbles (Mint for outgoing, Charcoal Card for incoming).
 * - Day separator and Noise XX E2EE trust banner.
 * - Honest delivery state checkmarks.
 * - Floating keyboard-aware pill composer dock.
 */
@Composable
fun PrivateChatScreen(
    peer: PrivatePeer,
    messages: List<PrivateChatMessage>,
    onSend: (text: String, parentMessageIdHex: String?, ttlSeconds: Int?) -> Unit,
    onSendMedia: (mediaType: String, media: ByteArray, caption: String, parentMessageIdHex: String?) -> Unit,
    onResend: (String) -> Unit,
    onClearChat: () -> Unit,
    onBack: () -> Unit,
    onSendControl: (messageIdHex: String, delete: Boolean, newText: String?) -> Unit = { _, _, _ -> },
    isTyping: Boolean = false,
    onTyping: () -> Unit = {},
) {
    val colors = MeshlineDesign.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Feature 2: while the composer holds text, notify the peer we are typing
    // (debounced so we send once per typing burst).
    LaunchedEffect(draft) {
        if (draft.isNotBlank()) {
            delay(1_200)
            if (draft.isNotBlank()) onTyping()
        }
    }

    // Feature 5: the message being replied to (the parent of the next send).
    var replyTarget by remember { mutableStateOf<PrivateChatMessage?>(null) }

    // Edit dialog state
    var editTarget by remember { mutableStateOf<PrivateChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<PrivateChatMessage?>(null) }

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
                    onValueChange = { editText = it },
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

    // Feature 4: voice note recording (MediaRecorder over MPEG_4/AAC).
    var recording by remember { mutableStateOf(false) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var recStartMs by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        if (!recording) {
            recStartMs = 0L
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val start = recStartMs
        while (recording) {
            elapsedMs = System.currentTimeMillis() - start
            delay(200)
        }
    }

    fun stopVoiceRecording(send: Boolean) {
        val mr = mediaRecorder
        mediaRecorder = null
        recording = false
        if (mr != null) {
            try { mr.stop() } catch (_: Exception) { /* already stopped */ }
            runCatching { mr.release() }
        }
        val file = recFile
        recFile = null
        if (file == null) return
        if (!send) {
            runCatching { file.delete() }
            return
        }
        val data = runCatching { file.readBytes() }.getOrNull()
        runCatching { file.delete() }
        if (data != null) {
            val parent = replyTarget
            replyTarget = null
            if (data.isNotEmpty()) {
                scope.launch { onSendMedia("audio/aac", data, "", parent?.messageIdHex) }
            }
        }
    }

    fun startVoiceRecording() {
        val file = File(context.filesDir, "voice_${System.currentTimeMillis()}.aac")
        val recorder = MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(12_000)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(16_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.setMaxDuration(MAX_VOICE_SECONDS * 1_000)
            recorder.setMaxFileSize(PrivateEnvelope.MAX_MEDIA_BYTES.toLong())
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) stopVoiceRecording(send = true)
            }
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            recFile = file
            recStartMs = System.currentTimeMillis()
            elapsedMs = 0
            recording = true
        } catch (_: Exception) {
            runCatching { recorder.release() }
            runCatching { file.delete() }
        }
    }

    // Feature 4: received voice notes play from a temp file copy.
    var voicePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    fun releaseVoicePlayer() {
        runCatching { voicePlayer?.stop() }
        runCatching { voicePlayer?.release() }
        voicePlayer = null
    }
    fun playVoice(data: ByteArray) {
        val file = File(context.filesDir, "voice_play_${System.currentTimeMillis()}.aac")
        runCatching { file.writeBytes(data) }
        val mp = MediaPlayer()
        val started = runCatching {
            mp.setOnCompletionListener {
                if (voicePlayer === mp) releaseVoicePlayer()
                runCatching { file.delete() }
            }
            mp.setOnErrorListener { _, _, _ ->
                if (voicePlayer === mp) releaseVoicePlayer()
                runCatching { file.delete() }
                true
            }
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            true
        }.getOrDefault(false)
        if (!started) {
            runCatching { mp.release() }
            runCatching { file.delete() }
        } else {
            voicePlayer = mp
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopVoiceRecording(send = false)
            releaseVoicePlayer()
        }
    }
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (data != null) {
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bmp != null) {
                        val jpeg = compressToJpeg(bmp)
                        val parent = replyTarget
                        replyTarget = null
                        onSendMedia("image/jpeg", jpeg, parent?.text ?: "", parent?.messageIdHex)
                    }
                }
            }
        }
    }

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

                Box(modifier = Modifier.size(40.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(colors.surface)
                            .border(1.dp, colors.border, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(fruitEmoji(peer.avatarIndex.toInt()), fontSize = 20.sp)
                    }
                    if (peer.online) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(colors.primary)
                                .border(1.5.dp, colors.surfaceElevated, CircleShape)
                                .align(Alignment.BottomEnd)
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.nickname,
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (peer.verified) {
                            Spacer(Modifier.width(4.dp))
                            Text("✓", color = colors.secondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = if (peer.sessionReady) "Noise XX Verified • Direct Mesh" else "Waiting for handshake…",
                        color = if (peer.sessionReady) colors.primary else colors.textMuted,
                        fontSize = 11.sp,
                    )
                }

                TextButton(onClick = onClearChat) {
                    Text("Clear", color = colors.textSecondary, fontSize = 12.sp)
                }
            }

            // Feature 2: "X is typing…" line while the peer's composer is active.
            if (isTyping) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "👤 ${peer.nickname} is typing…",
                        color = colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // E2EE Trust Notice Pill
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
                        text = "🔒 End-to-End Encrypted via Noise XX • Zero servers",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            if (peer.onHold) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(colors.danger.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .border(1.dp, colors.danger, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = "⚠️ Key Changed: Private messages are held until you approve this peer's new identity.",
                        color = colors.danger,
                        fontSize = 12.sp,
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
                items(messages, key = { it.messageIdHex + it.outgoing }) { m ->
                    val isOutgoing = m.outgoing
                    val bubbleAlign = if (isOutgoing) Alignment.End else Alignment.Start
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalAlignment = bubbleAlign,
                    ) {
                        // Feature 5: quote of the replied-to message.
                        m.parentIdHex?.let { parentIdHex ->
                            val parent = messages.firstOrNull { it.messageIdHex == parentIdHex }
                            Text(
                                text = "⤷ " + replyQuoteText(parent),
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .align(bubbleAlign),
                            )
                        }

                        // Asymmetric Message Bubble
                        Box(
                            modifier = Modifier
                                .pointerInput(m.messageIdHex) {
                                    detectTapGestures(
                                        onLongPress = {
                                            if (m.outgoing && !m.deleted) {
                                                actionTarget = m
                                            }
                                        }
                                    )
                                }
                                .clip(
                                    if (isOutgoing) {
                                        RoundedCornerShape(
                                            topStart = 18.dp,
                                            topEnd = 18.dp,
                                            bottomStart = 18.dp,
                                            bottomEnd = 4.dp,
                                        )
                                    } else {
                                        RoundedCornerShape(
                                            topStart = 18.dp,
                                            topEnd = 18.dp,
                                            bottomStart = 4.dp,
                                            bottomEnd = 18.dp,
                                        )
                                    }
                                )
                                .background(if (isOutgoing) colors.bubbleOutgoing else colors.bubbleIncoming)
                                .border(
                                    width = if (isOutgoing) 0.dp else 1.dp,
                                    color = if (isOutgoing) Color.Transparent else colors.border,
                                    shape = if (isOutgoing) {
                                        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                                    } else {
                                        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                                    },
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column {
                                // Feature 4: media bubbles (voice notes / small images).
                                val mediaType = m.mediaType
                                val mediaBytes = m.media
                                if (mediaType != null && mediaBytes != null) {
                                    if (mediaType.startsWith("image")) {
                                        val bmp = remember(mediaBytes) {
                                            BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size)
                                        }
                                        bmp?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "attached image",
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .sizeIn(maxWidth = 260.dp, maxHeight = 200.dp),
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "🎙️ Voice message",
                                                color = if (isOutgoing) colors.bubbleOutgoingText else colors.bubbleIncomingText,
                                                fontSize = 14.sp,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(9999.dp))
                                                    .background(colors.primary)
                                                    .clickable {
                                                        if (voicePlayer != null) releaseVoicePlayer() else playVoice(mediaBytes)
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = "▶",
                                                    color = colors.onPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                    if (m.deleted) {
                                        Text(
                                            text = "🚫 This message was deleted",
                                            color = colors.textMuted,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        )
                                    } else if (m.text.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = buildAnnotatedMessage(m.text, colors.primary),
                                            color = if (isOutgoing) colors.bubbleOutgoingText else colors.bubbleIncomingText,
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
                                        if (m.expiresAtMs != null) {
                                            val remaining = (m.expiresAtMs - System.currentTimeMillis()) / 1000
                                            if (remaining > 0) {
                                                Text(
                                                    text = "⏱ ${remaining / 60}:${String.format("%02d", remaining % 60)}",
                                                    color = colors.textMuted,
                                                    fontSize = 10.sp,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = m.text,
                                        color = if (isOutgoing) colors.bubbleOutgoingText else colors.bubbleIncomingText,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = timeFormat.format(Date(m.createdAtMs)),
                                        color = if (isOutgoing) colors.bubbleOutgoingText.copy(alpha = 0.65f) else colors.textMuted,
                                        fontSize = 10.sp,
                                    )
                                    if (isOutgoing) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = when (m.state) {
                                                "READ" -> "✓✓"
                                                "DELIVERED" -> "✓✓"
                                                "SENT" -> "✓"
                                                "QUEUED" -> "🕒"
                                                "NOT_DELIVERED" -> "⚠️"
                                                else -> "✓"
                                            },
                                            color = if (m.state == "READ" || m.state == "DELIVERED") {
                                                if (colors.isDark) colors.primary else Color.White
                                            } else {
                                                if (colors.isDark) colors.textMuted else Color.White.copy(alpha = 0.7f)
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }

                        // Feature 5: reply affordance.
                        Box(
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .align(bubbleAlign)
                                .clickable { replyTarget = m },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("↩", color = colors.textMuted, fontSize = 10.sp)
                        }

                        if (m.resendable) {
                            TextButton(
                                onClick = { onResend(m.messageIdHex) },
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Text("Resend message", color = colors.danger, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Feature 5: reply banner above the composer.
            replyTarget?.let { target ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .background(colors.surfaceContainerLow, RoundedCornerShape(10.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "↩ Replying to ${replyQuoteText(target)}",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { replyTarget = null }) {
                            Text("✕", color = colors.textMuted, fontSize = 12.sp)
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
                        onValueChange = { if (it.length <= 2000) draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        decorationBox = { innerTextField ->
                            if (draft.isEmpty()) {
                                Text(
                                    "Encrypted message…",
                                    color = colors.textMuted,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        },
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Feature 4: attach a small image (gallery picker).
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(9999.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.border, RoundedCornerShape(9999.dp))
                        .clickable { pickImageLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🖼", color = colors.textSecondary, fontSize = 15.sp)
                }
                Spacer(Modifier.width(4.dp))

                // Feature 4: mic — record a voice note; tap again to send it.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(9999.dp))
                        .background(if (recording) colors.danger.copy(alpha = 0.15f) else colors.surfaceElevated)
                        .border(
                            1.dp,
                            if (recording) colors.danger else colors.border,
                            RoundedCornerShape(9999.dp),
                        )
                        .clickable {
                            if (recording) stopVoiceRecording(send = true) else startVoiceRecording()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (recording) "🛑" else "🎤",
                        color = if (recording) colors.danger else colors.textSecondary,
                        fontSize = 15.sp,
                    )
                }
                if (recording) {
                    Text(
                        text = formatDuration(elapsedMs),
                        color = colors.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))

                // Circular Send Button
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (draft.isNotBlank()) colors.primary else colors.surfaceElevated)
                        .clickable(enabled = draft.isNotBlank()) {
                            val parent = replyTarget
                            replyTarget = null
                            onSend(draft.trim(), parent?.messageIdHex, null)
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

/**
 * Feature 6: search the private chat history by text (case-insensitive).
 * Returns (message, snippet) pairs, newest last, capped at [SEARCH_RESULT_MAX].
 */
fun searchPrivateMessages(
    messages: List<PrivateChatMessage>,
    query: String,
): List<Pair<PrivateChatMessage, String>> {
    if (query.isBlank()) return emptyList()
    val q = query.trim()
    return messages
        .filter { m -> m.text.contains(q, ignoreCase = true) }
        .sortedBy { it.createdAtMs }
        .takeLast(SEARCH_RESULT_MAX)
        .map { m -> m to searchSnippet(m) }
}

private fun searchSnippet(m: PrivateChatMessage): String {
    val text = m.text
    if (text.isNotBlank()) return text.take(80)
    val mime = m.mediaType ?: return "Message"
    return when {
        mime.startsWith("audio") -> "🎙️ Voice message"
        mime.startsWith("image") -> "🖼️ Photo"
        else -> "Media"
    }
}

/** Quote shown above a bubble whose message has a reply target (Feature 5). */
private fun replyQuoteText(parent: PrivateChatMessage?): String {
    if (parent == null) return "message"
    val mime = parent.mediaType
    return if (mime != null) {
        when {
            mime.startsWith("audio") -> "🎙️ Voice message"
            mime.startsWith("image") -> "🖼️ Photo"
            else -> "Media"
        }
    } else {
        parent.text.take(60)
    }
}

/** Formats elapsed milliseconds as "MM:SS" (voice note timer). */
private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${String.format("%02d", total % 60)}"
}

private const val SEARCH_RESULT_MAX = 24

private fun buildAnnotatedMessage(text: String, mentionColor: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val regex = Regex("@(\\w+)")
    var lastEnd = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > lastEnd) {
            builder.append(text.substring(lastEnd, match.range.first))
        }
        builder.pushStyle(androidx.compose.ui.text.SpanStyle(color = mentionColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        builder.append(match.value)
        builder.pop()
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        builder.append(text.substring(lastEnd))
    }
    return builder.toAnnotatedString()
}

/** Max voice note length (30 s) and the JPEG quality for picked images. */
private const val MAX_VOICE_SECONDS: Int = 30

/** Compresses a picked image to JPEG, shrinking until it fits the private-message budget. */
private fun compressToJpeg(src: Bitmap): ByteArray {
    fun encode(b: Bitmap, quality: Int): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return baos.toByteArray()
    }
    var bmp = src
    var quality = 85
    var bytes = encode(bmp, quality)
    while (bytes.size > PrivateEnvelope.MAX_MEDIA_BYTES && quality > 40) {
        quality -= 15
        bytes = encode(bmp, quality)
    }
    if (bytes.size > PrivateEnvelope.MAX_MEDIA_BYTES && bmp.width >= 240) {
        val nw = (bmp.width * 0.8).toInt().coerceAtLeast(80)
        val nh = (bmp.height * 0.8).toInt().coerceAtLeast(80)
        bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        bytes = encode(bmp, 60)
    }
    return bytes.copyOf(minOf(bytes.size, PrivateEnvelope.MAX_MEDIA_BYTES))
}

/**
 * Key change dialog warning.
 */
@Composable
fun KeyChangeDialog(
    event: KeyChangeEvent,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = MeshlineDesign.colors
    AlertDialog(
        onDismissRequest = { /* explicit */ },
        title = { Text("Identity changed — possible impersonation", color = colors.danger) },
        text = {
            Column {
                Text("${event.nickname} presents a different cryptographic key than previously verified.")
                Spacer(Modifier.height(8.dp))
                Text("Previous: ${event.oldFingerprint}", color = colors.textSecondary, fontSize = 12.sp)
                Text("New: ${event.newFingerprint}", color = colors.primary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Their messages stay Queued until you accept. If unexpected, reject to block.",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("Accept new key", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("Reject & block", color = colors.danger) }
        },
    )
}

/**
 * Panic wipe confirmation dialog.
 */
@Composable
fun PanicWipeDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = MeshlineDesign.colors
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("🚨 Irrevocably Shred All Data?", color = colors.danger) },
        text = {
            Text(
                "This action shreds your database with random bytes, clears Android Keystore keys, and kills the app process. Your identity and message history will be destroyed permanently. This cannot be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Shred Everything", color = colors.danger, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
