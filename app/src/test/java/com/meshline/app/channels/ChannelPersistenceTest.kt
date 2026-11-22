package com.meshline.app.channels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPersistenceTest {

    private class FakeStore : ChannelPersistence {
        val messages = mutableListOf<ChannelMessageUi>()
        override suspend fun loadMessages(): List<ChannelMessageUi> = messages.toList()
        override suspend fun insertMessage(message: ChannelMessageUi) { messages.add(message) }
        override suspend fun deleteChannel(channel: String) { messages.removeAll { it.channel == channel } }
        override suspend fun deleteAll() { messages.clear() }
        override suspend fun deleteMessagesOlderThan(thresholdMs: Long) {
            messages.removeAll { it.timestampMs < thresholdMs }
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun messagesSurviveRestart() {
        val store = FakeStore()
        val scope = newScope()
        val cm1 = ChannelManager("self", persistence = store, scope = scope)
        cm1.addMessage(ChannelMessageUi(
            messageIdHex = "m1", channel = "#general", senderIdHex = "self",
            senderNickname = "me", senderAvatarIndex = 0, text = "hi",
            timestampMs = 100L, isSelf = true,
        ))
        scope.cancel()

        val scope2 = newScope()
        val cm2 = ChannelManager("self", persistence = store, scope = scope2)
        val restored = runBlocking { cm2.messages.first()["#general"] }
        assertNotNull(restored)
        assertEquals(1, restored!!.size)
        assertEquals("hi", restored[0].text)
        scope2.cancel()
    }

    @Test
    fun clearChannelRemovesPersistedMessages() {
        val store = FakeStore()
        val scope = newScope()
        val cm = ChannelManager("self", persistence = store, scope = scope)
        cm.addMessage(ChannelMessageUi(
            messageIdHex = "m1", channel = "#general", senderIdHex = "self",
            senderNickname = "me", senderAvatarIndex = 0, text = "hi",
            timestampMs = 100L, isSelf = true,
        ))
        cm.clearChannelMessages("#general")
        scope.cancel()

        assertTrue(store.messages.isEmpty())
    }
}
