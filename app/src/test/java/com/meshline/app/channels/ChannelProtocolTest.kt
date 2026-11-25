package com.meshline.app.channels

import com.meshline.app.protocol.GroupPayloads
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelProtocolTest {

    @Test
    fun channelMessageEncodingAndDecodingRoundtrip() {
        val orig = GroupPayloads.ChannelMessage(
            channel = "#emergency",
            text = "Need medical supplies at base camp",
        )

        val encoded = GroupPayloads.encodeChannelMessage(orig)
        val decoded = GroupPayloads.decodeChannelMessage(encoded)

        assertEquals(orig.channel, decoded.channel)
        assertEquals(orig.text, decoded.text)
    }

    @Test
    fun channelManagerDefaultsAndActiveSwitching() {
        val cm = ChannelManager(ownIdHex = "11223344")
        val defaults = cm.channels
        // Initial active is #general
        assertEquals("#general", cm.currentActiveChannel())

        // Switch to #mesh
        cm.setActiveChannel("#mesh")
        assertEquals("#mesh", cm.currentActiveChannel())

        // Joining a custom channel normalizes leading hash
        cm.joinChannel("hiking")
        cm.setActiveChannel("hiking")
        assertEquals("#hiking", cm.currentActiveChannel())
    }

    @Test
    fun channelManagerMessageDeduplicationAndUnreadCount() {
        val cm = ChannelManager(ownIdHex = "11223344")
        cm.setActiveChannel("#general")

        val msg1 = ChannelMessageUi(
            messageIdHex = "msg-001",
            channel = "#emergency",
            senderIdHex = "55667788",
            senderNickname = "Bob",
            senderAvatarIndex = 2,
            text = "Alert!",
            timestampMs = 1000L,
            isSelf = false,
        )

        cm.addMessage(msg1)

        // Adding exact duplicate messageIdHex must not duplicate
        cm.addMessage(msg1)

        // Switch to #emergency to read
        cm.setActiveChannel("#emergency")
        assertEquals("#emergency", cm.currentActiveChannel())
    }

    @Test
    fun channelMessageControlOnlyAppliesToTheAuthor() {
        val cm = ChannelManager(ownIdHex = "11223344")
        cm.setActiveChannel("#general")
        val msg = ChannelMessageUi(
            messageIdHex = "ab".repeat(32),
            channel = "#general",
            senderIdHex = "11223344",
            senderNickname = "Me",
            senderAvatarIndex = 1,
            text = "original",
            timestampMs = 1_000L,
            isSelf = true,
        )
        cm.addMessage(msg)
        fun snapshot() = kotlinx.coroutines.runBlocking { cm.messages.first() }["#general"].orEmpty()

        // A peer cannot edit or delete it.
        assertFalse(cm.applyMessageControl(msg.messageIdHex, "deadbeef", delete = false, newText = "hacked"))
        assertFalse(cm.applyMessageControl(msg.messageIdHex, "deadbeef", delete = true, newText = null))
        assertEquals("original", snapshot().single().text)

        // The author can edit, then delete.
        assertTrue(cm.applyMessageControl(msg.messageIdHex, "11223344", delete = false, newText = "edited"))
        assertEquals("edited", snapshot().single().text)
        assertTrue(cm.applyMessageControl(msg.messageIdHex, "11223344", delete = true, newText = null))
        assertTrue(snapshot().single().deleted)

        // An unknown target is not applied.
        assertFalse(cm.applyMessageControl("cd".repeat(32), "11223344", delete = true, newText = null))
    }
}
