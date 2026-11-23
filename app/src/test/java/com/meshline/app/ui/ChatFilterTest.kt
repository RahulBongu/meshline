package com.meshline.app.ui

import com.meshline.app.groups.GroupItem
import com.meshline.app.protocol.GroupPayloads
import com.meshline.app.service.PrivatePeer
import com.meshline.app.storage.MessageRetention
import com.meshline.app.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test suite validating UI search filtering, category filtering,
 * theme serialization, and retention formatting.
 */
class ChatFilterTest {

    private val samplePeers = listOf(
        PrivatePeer(
            peerIdHex = "e8a2b34fc19d88aa",
            nickname = "Alice",
            avatarIndex = 0,
            verified = true,
            online = true,
            sessionReady = true,
            onHold = false,
        ),
        PrivatePeer(
            peerIdHex = "0102030405060708",
            nickname = "Bob",
            avatarIndex = 1,
            verified = false,
            online = false,
            sessionReady = false,
            onHold = false,
        ),
        PrivatePeer(
            peerIdHex = "aabbccddeeff0011",
            nickname = "Charlie Security",
            avatarIndex = 2,
            verified = true,
            online = true,
            sessionReady = true,
            onHold = false,
        ),
    )

    private val sampleGroups = listOf(
        GroupItem(
            groupIdHex = "11223344",
            name = "Mesh Dev Squad",
            avatarIndex = 3,
            epoch = 1,
            epochKey = ByteArray(32),
            adminEd25519Pk = ByteArray(32),
            groupTagHex = "aabbccddeeff0011",
            roster = listOf(GroupPayloads.RosterMember(ByteArray(32), "Member1", 0.toByte())),
            isAdmin = true,
            unreadCount = 2,
        ),
        GroupItem(
            groupIdHex = "55667788",
            name = "Emergency Response",
            avatarIndex = 4,
            epoch = 3,
            epochKey = ByteArray(32),
            adminEd25519Pk = ByteArray(32),
            groupTagHex = "1122334455667788",
            roster = listOf(GroupPayloads.RosterMember(ByteArray(32), "Member2", 1.toByte())),
            isAdmin = false,
            unreadCount = 0,
        ),
    )

    @Test
    fun testSearchFilteringByNickname() {
        val query = "alice"
        val matched = samplePeers.filter {
            it.nickname.contains(query, ignoreCase = true) || it.peerIdHex.contains(query, ignoreCase = true)
        }
        assertEquals(1, matched.size)
        assertEquals("Alice", matched[0].nickname)
    }

    @Test
    fun testSearchFilteringByHexPrefix() {
        val query = "aabb"
        val matched = samplePeers.filter {
            it.nickname.contains(query, ignoreCase = true) || it.peerIdHex.contains(query, ignoreCase = true)
        }
        assertEquals(1, matched.size)
        assertEquals("Charlie Security", matched[0].nickname)
    }

    @Test
    fun testSearchFilteringByGroupName() {
        val query = "squad"
        val matched = sampleGroups.filter {
            it.name.contains(query, ignoreCase = true)
        }
        assertEquals(1, matched.size)
        assertEquals("Mesh Dev Squad", matched[0].name)
    }

    @Test
    fun testCategoryFilteringUnread() {
        val unreadGroups = sampleGroups.filter { it.unreadCount > 0 }
        assertEquals(1, unreadGroups.size)
        assertEquals("Mesh Dev Squad", unreadGroups[0].name)
    }

    @Test
    fun testThemeModeSerialization() {
        val dark = AppThemeMode.valueOf("DARK")
        assertEquals(AppThemeMode.DARK, dark)

        val light = AppThemeMode.valueOf("LIGHT")
        assertEquals(AppThemeMode.LIGHT, light)

        val system = AppThemeMode.valueOf("SYSTEM")
        assertEquals(AppThemeMode.SYSTEM, system)
    }

    @Test
    fun testRetentionLabels() {
        assertEquals("Off", retentionLabel(MessageRetention.OFF))
        assertEquals("1 day", retentionLabel(MessageRetention.ONE_DAY))
        assertEquals("7 days", retentionLabel(MessageRetention.SEVEN_DAYS))
        assertEquals("30 days", retentionLabel(MessageRetention.THIRTY_DAYS))
    }

    @Test
    fun testLightAndDarkDesignTokens() {
        // Verify Dark Theme tokens match Coral-DeepLavender Rx design specifications
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1C1917), com.meshline.app.ui.theme.DarkMeshlineColors.background)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF292524), com.meshline.app.ui.theme.DarkMeshlineColors.surface)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF5733), com.meshline.app.ui.theme.DarkMeshlineColors.primary)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF3D2A24), com.meshline.app.ui.theme.DarkMeshlineColors.primarySoft)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF22C55E), com.meshline.app.ui.theme.DarkMeshlineColors.statusConnected)
        org.junit.Assert.assertTrue(com.meshline.app.ui.theme.DarkMeshlineColors.isDark)

        // Verify Light Theme tokens match Coral-DeepLavender Rx design specifications
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFAF7F2), com.meshline.app.ui.theme.LightMeshlineColors.background)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF5F0E8), com.meshline.app.ui.theme.LightMeshlineColors.surface)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF5733), com.meshline.app.ui.theme.LightMeshlineColors.primary)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFE0D6), com.meshline.app.ui.theme.LightMeshlineColors.primarySoft)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF28A745), com.meshline.app.ui.theme.LightMeshlineColors.statusConnected)
        org.junit.Assert.assertFalse(com.meshline.app.ui.theme.LightMeshlineColors.isDark)
    }
}
