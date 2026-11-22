package com.meshline.app.ui

import com.meshline.app.service.PrivateChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature 6: local message search over the private chat history
 * (the `searchPrivateMessages` helper used by the Chats search bar).
 */
class MessageSearchTest {

    private fun msg(text: String, ts: Long, mediaType: String? = null): PrivateChatMessage =
        PrivateChatMessage(
            messageIdHex = "id-${ts}-${text.hashCode()}",
            peerIdHex = "peer",
            outgoing = false,
            text = text,
            state = "DELIVERED",
            createdAtMs = ts,
            mediaType = mediaType,
        )

    @Test
    fun `blank query returns no results`() {
        val messages = listOf(msg("hello", 1))
        assertTrue(searchPrivateMessages(messages, "").isEmpty())
        assertTrue(searchPrivateMessages(messages, "   ").isEmpty())
    }

    @Test
    fun `search matches text case-insensitively and returns them newest last`() {
        val messages = listOf(
            msg("first match", 1),
            msg("nothing here", 2),
            msg("a SECOND MATCH", 3),
            msg("MATCH everywhere", 4),
        )
        val results = searchPrivateMessages(messages, "match")
        assertEquals(3, results.size)
        assertEquals("first match", results[0].first.text)
        assertEquals("a SECOND MATCH", results[1].first.text)
        assertEquals("MATCH everywhere", results[2].first.text)
    }

    @Test
    fun `search returns snippets for media messages with captions`() {
        val photo = msg("nice shot", 3, mediaType = "image/jpeg")
        val voice = msg("", 1, mediaType = "audio/aac")
        val results = searchPrivateMessages(listOf(voice, photo), "shot")
        assertEquals(1, results.size)
        assertEquals("nice shot", results[0].second)
    }

    @Test
    fun `results are capped and ordered newest last`() {
        val messages = (1..30).map { msg("common word", it.toLong()) }
        val results = searchPrivateMessages(messages, "common word")
        assertEquals(24, results.size)
        assertEquals(30, results.last().first.createdAtMs)
        assertEquals(7, results.first().first.createdAtMs)
    }
}
