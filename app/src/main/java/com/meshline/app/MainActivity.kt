package com.meshline.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meshline.app.groups.GroupItem
import com.meshline.app.profile.Profile
import com.meshline.app.profile.ProfileStore
import com.meshline.app.protocol.MessageIds
import com.meshline.app.security.AndroidKeystoreIdentityStore
import com.meshline.app.security.Identity
import com.meshline.app.security.QrPayload
import com.meshline.app.security.SecureStorageStatus
import com.meshline.app.service.BleMeshService
import com.meshline.app.service.KeyChangeEvent
import com.meshline.app.service.MeshStatsSnapshot
import com.meshline.app.service.PrivateChatMessage
import com.meshline.app.service.PrivatePeer
import com.meshline.app.storage.ContactEntity
import com.meshline.app.storage.ContactsRepository
import com.meshline.app.storage.MessageRetention
import com.meshline.app.storage.RetentionStore
import com.meshline.app.ui.ChannelsScreen
import com.meshline.app.ui.ChatsUnifiedScreen
import com.meshline.app.ui.ContactsScreen
import com.meshline.app.ui.GroupChatScreen
import com.meshline.app.ui.KeyChangeDialog
import com.meshline.app.ui.MyProfileScreen
import com.meshline.app.ui.NearbyScreen
import com.meshline.app.ui.PanicWipeDialog
import com.meshline.app.ui.PrivateChatScreen
import com.meshline.app.ui.ProfileAndSettingsScreen
import com.meshline.app.ui.ScanConfirmScreen
import com.meshline.app.ui.ScanQRScreen
import com.meshline.app.ui.SecureStorageResetDialog
import com.meshline.app.ui.theme.AppThemeMode
import com.meshline.app.ui.theme.MeshlineDesign
import com.meshline.app.ui.theme.MeshlineTheme
import com.meshline.app.ui.theme.ThemeStore
import kotlinx.coroutines.launch

/**
 * Meshline Main Activity.
 *
 * Hosts the top-level 4-tab interface:
 *  1. Chats: Unified 1-on-1 direct E2EE & group chats.
 *  2. Nearby: Live BLE mesh status, radio diagnostics, public broadcast stream, and slash commands.
 *  3. Channels: Topic-based broadcast rooms (#general, #mesh, #emergency, #geo-local).
 *  4. Settings: Cryptographic identity, QR contact card exchange, network relays, and panic wipe.
 */
class MainActivity : ComponentActivity() {

    private var retention by mutableStateOf(MessageRetention.OFF)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissionsIfNeeded()
        retention = RetentionStore(applicationContext).load()
        val themeStore = ThemeStore(applicationContext)

        setContent {
            var themeMode by remember { mutableStateOf(themeStore.load()) }

            MeshlineTheme(mode = themeMode) {
                App(
                    themeMode = themeMode,
                    onSetThemeMode = { newMode ->
                        themeMode = newMode
                        themeStore.save(newMode)
                    },
                    onStart = { startMesh() },
                    onSendPublic = { text -> sendPublic(text) },
                    onSendPrivate = { peer, text -> sendPrivate(peer, text) },
                    onOpenChat = { peer -> openChat(peer) },
                    onAcceptKey = { peer -> acceptKey(peer) },
                    onRejectKey = { peer -> rejectKey(peer) },
                    onPanicWipe = { panicWipe() },
                    onResend = { messageId -> resend(messageId) },
                    onClearChat = { peer -> clearChat(peer) },
                    onGrantPermissions = { requestBlePermissionsIfNeeded() },
                    retention = retention,
                    onSetRetention = { option ->
                        retention = option
                        applyRetention(option)
                    },
                    onCreateGroup = { name, icon, members -> createGroup(name, icon, members) },
                    onSendGroupMessage = { groupId, text -> sendGroupMessage(groupId, text) },
                    onAcceptGroupInvite = { groupId -> acceptGroupInvite(groupId) },
                    onDeclineGroupInvite = { groupId -> declineGroupInvite(groupId) },
                    onLeaveGroup = { groupId -> leaveGroup(groupId) },
                    onClearGroupUnread = { groupId -> clearGroupUnread(groupId) },
                    onSendChannelMessage = { channel, text -> sendChannelMessage(channel, text) },
                    onSetActiveChannel = { channel -> setActiveChannel(channel) },
                    onSetInternetRelay = { enabled -> setInternetRelay(enabled) },
                    onExecuteCommand = { cmdText, ch -> executeCommand(cmdText, ch) },
                )
            }
        }
    }

    private fun startMesh() {
        startForegroundService(Intent(this, BleMeshService::class.java))
    }

    private fun sendPublic(text: String) {
        if (text.isBlank()) return
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_SEND_PUBLIC)
                .putExtra(BleMeshService.EXTRA_TEXT, text)
        )
    }

    private fun sendPrivate(peerIdHex: String, text: String) {
        if (text.isBlank()) return
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_SEND_PRIVATE)
                .putExtra(BleMeshService.EXTRA_PEER_ID, peerIdHex)
                .putExtra(BleMeshService.EXTRA_TEXT, text)
        )
    }

    private fun openChat(peerIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_OPEN_CHAT)
                .putExtra(BleMeshService.EXTRA_PEER_ID, peerIdHex)
        )
    }

    private fun acceptKey(peerIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_ACCEPT_KEY)
                .putExtra(BleMeshService.EXTRA_PEER_ID, peerIdHex)
        )
    }

    private fun rejectKey(peerIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_REJECT_KEY)
                .putExtra(BleMeshService.EXTRA_PEER_ID, peerIdHex)
        )
    }

    private fun panicWipe() {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_PANIC_WIPE)
        )
    }

    private fun resend(messageIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_RESEND)
                .putExtra(BleMeshService.EXTRA_MESSAGE_ID, messageIdHex)
        )
    }

    private fun clearChat(peerIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_CLEAR_CHAT)
                .putExtra(BleMeshService.EXTRA_PEER_ID, peerIdHex)
        )
    }

    private fun applyRetention(retention: MessageRetention) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_SET_RETENTION)
                .putExtra(BleMeshService.EXTRA_RETENTION_DAYS, retention.days)
        )
    }

    private fun createGroup(name: String, icon: Byte, members: List<ContactEntity>) {
        val pubkeys = members.map { it.ed25519PublicKey.joinToString("") { b -> "%02x".format(b) } }.toTypedArray()
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_CREATE_GROUP)
                .putExtra(BleMeshService.EXTRA_GROUP_NAME, name)
                .putExtra(BleMeshService.EXTRA_AVATAR_INDEX, icon)
                .putExtra(BleMeshService.EXTRA_MEMBERS_PUBLIC_KEYS, pubkeys)
        )
    }

    private fun sendGroupMessage(groupIdHex: String, text: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_SEND_GROUP_MESSAGE)
                .putExtra(BleMeshService.EXTRA_GROUP_ID, groupIdHex)
                .putExtra(BleMeshService.EXTRA_TEXT, text)
        )
    }

    private fun acceptGroupInvite(groupIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_ACCEPT_GROUP_INVITE)
                .putExtra(BleMeshService.EXTRA_GROUP_ID, groupIdHex)
        )
    }

    private fun declineGroupInvite(groupIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_DECLINE_GROUP_INVITE)
                .putExtra(BleMeshService.EXTRA_GROUP_ID, groupIdHex)
        )
    }

    private fun leaveGroup(groupIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_LEAVE_GROUP)
                .putExtra(BleMeshService.EXTRA_GROUP_ID, groupIdHex)
        )
    }

    private fun clearGroupUnread(groupIdHex: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_CLEAR_GROUP_UNREAD)
                .putExtra(BleMeshService.EXTRA_GROUP_ID, groupIdHex)
        )
    }

    private fun sendChannelMessage(channel: String, text: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_SEND_CHANNEL_MESSAGE)
                .putExtra(BleMeshService.EXTRA_CHANNEL, channel)
                .putExtra(BleMeshService.EXTRA_TEXT, text)
        )
    }

    private fun setActiveChannel(channel: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_SET_ACTIVE_CHANNEL)
                .putExtra(BleMeshService.EXTRA_CHANNEL, channel)
        )
    }

    private fun setInternetRelay(enabled: Boolean) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_SET_INTERNET_RELAY)
                .putExtra(BleMeshService.EXTRA_ENABLED, enabled)
        )
    }

    private fun executeCommand(commandText: String, activeChannel: String) {
        startForegroundService(
            Intent(this, BleMeshService::class.java)
                .setAction(BleMeshService.ACTION_EXECUTE_COMMAND)
                .putExtra(BleMeshService.EXTRA_COMMAND_TEXT, commandText)
                .putExtra(BleMeshService.EXTRA_CHANNEL, activeChannel)
        )
    }

    private fun requestBlePermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 0)
        }
    }
}

private enum class MainTab { CHATS, NEARBY, CHANNELS, SETTINGS }
private enum class Screen { MAIN, MY_QR, SCAN, CONTACTS, PRIVATE_CHAT, GROUP_CHAT }

@Composable
private fun App(
    themeMode: AppThemeMode,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onStart: () -> Unit,
    onSendPublic: (String) -> Unit,
    onSendPrivate: (String, String) -> Unit,
    onOpenChat: (String) -> Unit,
    onAcceptKey: (String) -> Unit,
    onRejectKey: (String) -> Unit,
    onPanicWipe: () -> Unit,
    onResend: (String) -> Unit,
    onClearChat: (String) -> Unit,
    onGrantPermissions: () -> Unit,
    retention: MessageRetention,
    onSetRetention: (MessageRetention) -> Unit,
    onCreateGroup: (name: String, icon: Byte, members: List<ContactEntity>) -> Unit,
    onSendGroupMessage: (groupId: String, text: String) -> Unit,
    onAcceptGroupInvite: (groupId: String) -> Unit,
    onDeclineGroupInvite: (groupId: String) -> Unit,
    onLeaveGroup: (groupId: String) -> Unit,
    onClearGroupUnread: (groupId: String) -> Unit,
    onSendChannelMessage: (channel: String, text: String) -> Unit,
    onSetActiveChannel: (channel: String) -> Unit,
    onSetInternetRelay: (enabled: Boolean) -> Unit,
    onExecuteCommand: (commandText: String, activeChannel: String) -> Unit,
) {
    val colors = MeshlineDesign.colors
    val app = LocalContext.current.applicationContext
    val identityStore = remember { AndroidKeystoreIdentityStore(app) }
    val profileStore = remember { ProfileStore(app) }
    val contactsRepo = remember { ContactsRepository(app) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var identity by remember { mutableStateOf<Identity?>(null) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var resetEvent by remember { mutableStateOf<SecureStorageStatus.Event?>(null) }
    var currentTab by remember { mutableStateOf(MainTab.CHATS) }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var pendingCard by remember { mutableStateOf<QrPayload.ContactCard?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var openPeerId by remember { mutableStateOf<String?>(null) }
    var openGroupId by remember { mutableStateOf<String?>(null) }
    var wipeArmed by remember { mutableStateOf(false) }

    // Intercept hardware/gesture Back button when inside any sub-screen (Defect B-01 fix)
    BackHandler(enabled = screen != Screen.MAIN) {
        if (screen == Screen.GROUP_CHAT && openGroupId != null) {
            onClearGroupUnread(openGroupId!!)
        }
        screen = Screen.MAIN
    }

    // State flows from BleMeshService
    val privateMessages by BleMeshService.privateMessages.collectAsState()
    val privatePeers by BleMeshService.privatePeers.collectAsState()
    val keyChange by BleMeshService.keyChange.collectAsState()
    val publicChatMessages by BleMeshService.publicChatMessages.collectAsState()
    val groups by BleMeshService.groups.collectAsState()
    val groupMessages by BleMeshService.groupMessages.collectAsState()
    val pendingGroupInvites by BleMeshService.pendingGroupInvites.collectAsState()
    val channels by BleMeshService.channels.collectAsState()
    val activeChannel by BleMeshService.activeChannel.collectAsState()
    val channelMessages by BleMeshService.channelMessages.collectAsState()
    val relayStatus by BleMeshService.relayStatus.collectAsState()
    val systemNotification by BleMeshService.systemNotification.collectAsState()
    val peers by BleMeshService.peers.collectAsState(initial = emptyList())
    val stats by BleMeshService.stats.collectAsState(initial = MeshStatsSnapshot())
    val startError by BleMeshService.startError.collectAsState(initial = null)
    val lastGattError by BleMeshService.lastGattError.collectAsState(initial = null)
    val advertiseSupported by BleMeshService.advertiseSupported.collectAsState(initial = true)

    val contacts by remember(contactsRepo) { contactsRepo.observeAll() }
        .collectAsState(initial = emptyList())

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanError = null
            screen = Screen.SCAN
        } else {
            scanError = "Camera permission denied — allow it in Settings to scan a QR."
        }
    }

    LaunchedEffect(Unit) {
        identity = identityStore.load() ?: AndroidKeystoreIdentityStore.generateAndStore(app)
        profile = profileStore.load()
        resetEvent = SecureStorageStatus.consume(app)
        onStart() // Ensure mesh foreground service is active
    }

    LaunchedEffect(contacts) {
        if (resetEvent == null) resetEvent = SecureStorageStatus.consume(app)
    }

    LaunchedEffect(systemNotification) {
        systemNotification?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            BleMeshService.clearSystemNotification()
        }
    }

    resetEvent?.let { event ->
        SecureStorageResetDialog(event) { resetEvent = null }
    }

    keyChange?.let { event ->
        KeyChangeDialog(
            event = event,
            onAccept = { onAcceptKey(event.peerIdHex) },
            onReject = { onRejectKey(event.peerIdHex) },
        )
    }

    if (wipeArmed) {
        PanicWipeDialog(
            onConfirm = {
                wipeArmed = false
                onPanicWipe()
            },
            onCancel = { wipeArmed = false },
        )
    }

    val id = identity
    val prof = profile

    if (id == null || prof == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.background),
            contentAlignment = Alignment.Center,
        ) {
            Text("Initializing Meshline cryptographic identity…", color = colors.textSecondary)
        }
        return
    }

    when {
        pendingCard != null -> {
            val card = pendingCard!!
            ScanConfirmScreen(
                card = card,
                fingerprintShort = MessageIds
                    .fingerprint(card.ed25519PublicKey, card.x25519PublicKey)
                    .substring(0, 4).uppercase(),
                onConfirm = {
                    scope.launch {
                        contactsRepo.addVerified(card)
                        pendingCard = null
                        screen = Screen.CONTACTS
                    }
                },
                onCancel = {
                    pendingCard = null
                    screen = Screen.MAIN
                },
            )
        }
        screen == Screen.SCAN -> {
            ScanQRScreen(
                error = scanError,
                onScanned = { text ->
                    val card = runCatching { QrPayload.decode(text) }.getOrNull()
                    if (card == null) {
                        scanError = "Not a valid Meshline contact QR code."
                    } else {
                        scanError = null
                        pendingCard = card
                    }
                },
                onBack = { screen = Screen.MAIN },
            )
        }
        screen == Screen.MY_QR -> {
            MyProfileScreen(
                profile = prof,
                fingerprintShort = id.fingerprintShort,
                qrText = QrPayload.encode(
                    QrPayload.ContactCard(
                        ed25519PublicKey = id.ed25519PublicKey,
                        x25519PublicKey = id.x25519PublicKey,
                        avatarIndex = prof.avatarIndex,
                        nickname = prof.nickname,
                    )
                ),
                onBack = { screen = Screen.MAIN },
            )
        }
        screen == Screen.CONTACTS -> {
            ContactsScreen(
                contacts = contacts,
                onOpen = { c ->
                    openPeerId = c.senderIdHex
                    onOpenChat(c.senderIdHex)
                    screen = Screen.PRIVATE_CHAT
                },
                onBack = { screen = Screen.MAIN },
            )
        }
        screen == Screen.PRIVATE_CHAT -> {
            val peerId = openPeerId
            val peer = privatePeers.firstOrNull { it.peerIdHex == peerId }
            if (peer == null) {
                screen = Screen.MAIN
            } else {
                PrivateChatScreen(
                    peer = peer,
                    messages = privateMessages.filter { it.peerIdHex == peer.peerIdHex },
                    onSend = { text -> onSendPrivate(peer.peerIdHex, text) },
                    onResend = { messageId -> onResend(messageId) },
                    onClearChat = { onClearChat(peer.peerIdHex) },
                    onBack = { screen = Screen.MAIN },
                )
            }
        }
        screen == Screen.GROUP_CHAT -> {
            val groupId = openGroupId
            val group = groups.firstOrNull { it.groupIdHex == groupId }
            if (group == null) {
                screen = Screen.MAIN
            } else {
                val groupMsgList = groupMessages[group.groupIdHex] ?: emptyList()
                GroupChatScreen(
                    group = group,
                    messages = groupMsgList,
                    onSend = { text -> onSendGroupMessage(group.groupIdHex, text) },
                    onLeaveGroup = {
                        onLeaveGroup(group.groupIdHex)
                        screen = Screen.MAIN
                    },
                    onBack = {
                        onClearGroupUnread(group.groupIdHex)
                        screen = Screen.MAIN
                    },
                )
            }
        }
        else -> {
            // Main Top-Level Scaffold with 4 Tabs
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        containerColor = colors.surface,
                        contentColor = colors.textPrimary,
                        tonalElevation = 0.dp,
                        modifier = Modifier.border(
                            width = 0.5.dp,
                            color = colors.border,
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        ),
                    ) {
                        val totalGroupBadge = groups.sumOf { it.unreadCount } + pendingGroupInvites.size
                        NavigationBarItem(
                            selected = currentTab == MainTab.CHATS,
                            onClick = { currentTab = MainTab.CHATS },
                            icon = {
                                Box {
                                    Text("💬", fontSize = 18.sp)
                                    if (totalGroupBadge > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(colors.primary)
                                        )
                                    }
                                }
                            },
                            label = { Text("Chats", fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.primary,
                                selectedTextColor = colors.primary,
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary,
                                indicatorColor = colors.primarySoft,
                            ),
                        )
                        NavigationBarItem(
                            selected = currentTab == MainTab.NEARBY,
                            onClick = { currentTab = MainTab.NEARBY },
                            icon = {
                                Box {
                                    Text("📡", fontSize = 18.sp)
                                    if (peers.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(colors.statusConnected)
                                        )
                                    }
                                }
                            },
                            label = { Text("Nearby (${peers.size})", fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.primary,
                                selectedTextColor = colors.primary,
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary,
                                indicatorColor = colors.primarySoft,
                            ),
                        )
                        NavigationBarItem(
                            selected = currentTab == MainTab.CHANNELS,
                            onClick = { currentTab = MainTab.CHANNELS },
                            icon = {
                                val chBadge = channels.sumOf { it.unreadCount }
                                Box {
                                    Text("#", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (currentTab == MainTab.CHANNELS) colors.primary else colors.textSecondary)
                                    if (chBadge > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(colors.primary)
                                        )
                                    }
                                }
                            },
                            label = { Text("Channels", fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.primary,
                                selectedTextColor = colors.primary,
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary,
                                indicatorColor = colors.primarySoft,
                            ),
                        )
                        NavigationBarItem(
                            selected = currentTab == MainTab.SETTINGS,
                            onClick = { currentTab = MainTab.SETTINGS },
                            icon = { Text("⚙", fontSize = 18.sp) },
                            label = { Text("Settings", fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.primary,
                                selectedTextColor = colors.primary,
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary,
                                indicatorColor = colors.primarySoft,
                            ),
                        )
                    }
                },
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                    when (currentTab) {
                        MainTab.CHATS -> {
                            ChatsUnifiedScreen(
                                peers = privatePeers,
                                groups = groups,
                                pendingInvites = pendingGroupInvites,
                                contacts = contacts,
                                onOpenPeer = { peer ->
                                    openPeerId = peer.peerIdHex
                                    onOpenChat(peer.peerIdHex)
                                    screen = Screen.PRIVATE_CHAT
                                },
                                onOpenGroup = { group ->
                                    openGroupId = group.groupIdHex
                                    onClearGroupUnread(group.groupIdHex)
                                    screen = Screen.GROUP_CHAT
                                },
                                onAcceptInvite = { gid -> onAcceptGroupInvite(gid) },
                                onDeclineInvite = { gid -> onDeclineGroupInvite(gid) },
                                onCreateGroup = { name, icon, selected ->
                                    onCreateGroup(name, icon, selected)
                                },
                            )
                        }
                        MainTab.NEARBY -> {
                            NearbyScreen(
                                profileLabel = ProfileStore.displayName(prof, id.fingerprintShort),
                                peers = peers,
                                messages = publicChatMessages,
                                stats = stats,
                                lastGattError = lastGattError,
                                advertiseSupported = advertiseSupported,
                                startError = startError,
                                relayStatus = relayStatus,
                                onStartMesh = onStart,
                                onGrantPermissions = onGrantPermissions,
                                onSendPublic = onSendPublic,
                                onExecuteCommand = { cmd -> onExecuteCommand(cmd, "#general") },
                            )
                        }
                        MainTab.CHANNELS -> {
                            val activeMsgs = channelMessages[activeChannel] ?: emptyList()
                            ChannelsScreen(
                                channels = channels,
                                activeChannel = activeChannel,
                                messages = activeMsgs,
                                onSelectChannel = { ch -> onSetActiveChannel(ch) },
                                onJoinChannel = { ch -> onSetActiveChannel(ch) },
                                onSendMessage = { ch, text -> onSendChannelMessage(ch, text) },
                                onExecuteCommand = { cmd, ch -> onExecuteCommand(cmd, ch) },
                            )
                        }
                        MainTab.SETTINGS -> {
                            ProfileAndSettingsScreen(
                                profile = prof,
                                fingerprintShort = id.fingerprintShort,
                                contactsCount = contacts.size,
                                retention = retention,
                                relayStatus = relayStatus,
                                themeMode = themeMode,
                                onSetThemeMode = onSetThemeMode,
                                onSaveProfile = { newNick, newAvatar ->
                                    profileStore.save(Profile(newNick, newAvatar))
                                    profile = profileStore.load()
                                },
                                onSetRetention = onSetRetention,
                                onSetInternetRelay = onSetInternetRelay,
                                onMyQr = { screen = Screen.MY_QR },
                                onScanQr = {
                                    if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        scanError = null
                                        screen = Screen.SCAN
                                    } else {
                                        cameraPermission.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                onViewContacts = { screen = Screen.CONTACTS },
                                onPanicWipeArmed = { wipeArmed = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
