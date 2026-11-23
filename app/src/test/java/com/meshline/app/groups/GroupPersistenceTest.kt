package com.meshline.app.groups

import com.meshline.app.security.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class GroupPersistenceTest {

    private val random = SecureRandom()

    private fun testIdentity(): Identity {
        val edGen = Ed25519KeyPairGenerator()
        edGen.init(Ed25519KeyGenerationParameters(random))
        val edPair = edGen.generateKeyPair()
        val edPriv = (edPair.private as Ed25519PrivateKeyParameters).encoded
        val edPub = (edPair.public as Ed25519PublicKeyParameters).encoded

        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val xPair = xGen.generateKeyPair()
        val xPriv = (xPair.private as X25519PrivateKeyParameters).encoded
        val xPub = (xPair.public as X25519PublicKeyParameters).encoded

        return Identity(ed25519PublicKey = edPub, x25519PublicKey = xPub, edPrivate = edPriv, xPrivate = xPriv)
    }

    private class FakeStore : GroupPersistence {
        val groups = linkedMapOf<String, GroupItem>()
        val messages = linkedMapOf<String, MutableList<GroupMessageUi>>()

        override suspend fun loadGroups(): List<GroupItem> = groups.values.toList()
        override suspend fun upsertGroup(group: GroupItem) { groups[group.groupIdHex] = group }
        override suspend fun deleteGroup(groupIdHex: String) { groups.remove(groupIdHex) }
        override suspend fun loadMessages(): List<GroupMessageUi> = messages.values.flatten()
        override suspend fun insertMessage(message: GroupMessageUi) {
            messages.getOrPut(message.groupIdHex) { mutableListOf() }.add(message)
        }
        override suspend fun deleteMessages(groupIdHex: String) { messages.remove(groupIdHex) }
        override suspend fun markMessageDeleted(messageIdHex: String) {
            for (g in messages.keys.toList()) {
                val idx = messages[g]?.indexOfFirst { it.messageIdHex == messageIdHex } ?: -1
                if (idx >= 0) messages[g]!![idx] = messages[g]!![idx].copy(deleted = true, text = "")
            }
        }
        override suspend fun editMessage(messageIdHex: String, newText: String, editedAtMs: Long) {
            for (g in messages.keys.toList()) {
                val idx = messages[g]?.indexOfFirst { it.messageIdHex == messageIdHex } ?: -1
                if (idx >= 0) messages[g]!![idx] = messages[g]!![idx].copy(text = newText, editedAtMs = editedAtMs)
            }
        }
        override suspend fun deleteMessagesOlderThan(thresholdMs: Long) {
            for (g in messages.keys.toList()) {
                messages[g]?.removeAll { it.timestampMs < thresholdMs }
            }
        }
        override suspend fun deleteExpiredMessages(nowMs: Long) {
            for (g in messages.keys.toList()) {
                messages[g]?.removeAll { it.expiresAtMs != null && it.expiresAtMs <= nowMs }
            }
        }
        override suspend fun deleteAll() { groups.clear(); messages.clear() }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun createGroupIsRestoredByANewManager() {
        val id = testIdentity()
        val store = FakeStore()
        val scope = newScope()
        val gm1 = GroupManager(id, { "A" }, { 0.toByte() }, persistence = store, scope = scope)
        val (group, _) = gm1.createGroup("team", 0.toByte(), emptyList())
        scope.cancel()

        assertTrue(store.groups.containsKey(group.groupIdHex))

        val scope2 = newScope()
        val gm2 = GroupManager(id, { "A" }, { 0.toByte() }, persistence = store, scope = scope2)
        val restored = gm2.getGroup(group.groupIdHex)
        assertNotNull(restored)
        assertEquals(group, restored)
        assertTrue(restored!!.isAdmin)
        scope2.cancel()
    }

    @Test
    fun messageHistoryIsRestored() {
        val id = testIdentity()
        val store = FakeStore()
        val scope = newScope()
        val gm1 = GroupManager(id, { "A" }, { 0.toByte() }, persistence = store, scope = scope)
        val (group, _) = gm1.createGroup("team", 0.toByte(), emptyList())
        gm1.createOutgoingGroupPacket(group.groupIdHex, "hello")
        scope.cancel()

        assertTrue(store.messages[group.groupIdHex]?.isNotEmpty() == true)

        val scope2 = newScope()
        val gm2 = GroupManager(id, { "A" }, { 0.toByte() }, persistence = store, scope = scope2)
        val restoredMsgs = runBlocking { gm2.messages.first()[group.groupIdHex] }
        assertNotNull(restoredMsgs)
        assertEquals(1, restoredMsgs!!.size)
        assertEquals("hello", restoredMsgs[0].text)
        scope2.cancel()
    }

    @Test
    fun leaveGroupRemovesPersistedState() {
        val id = testIdentity()
        val store = FakeStore()
        val scope = newScope()
        val gm = GroupManager(id, { "A" }, { 0.toByte() }, persistence = store, scope = scope)
        val (group, _) = gm.createGroup("team", 0.toByte(), emptyList())
        gm.leaveGroup(group.groupIdHex)
        scope.cancel()

        assertNull(store.groups[group.groupIdHex])
        assertNull(store.messages[group.groupIdHex])
    }

    @Test
    fun deleteAllClearsStore() {
        val id = testIdentity()
        val store = FakeStore()
        val scope = newScope()
        val gm = GroupManager(id, { "A" }, { 0.toByte() }, persistence = store, scope = scope)
        gm.createGroup("team", 0.toByte(), emptyList())
        scope.cancel()
        runBlocking { store.deleteAll() }
        assertTrue(store.groups.isEmpty())
        assertTrue(store.messages.isEmpty())
    }
}
