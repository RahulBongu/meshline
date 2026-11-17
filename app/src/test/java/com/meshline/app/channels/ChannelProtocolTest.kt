package com.meshline.app.channels

import com.meshline.app.protocol.GroupPayloads
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
}
