package com.meshline.app.groups

/**
 * Durable storage for groups and their history.
 *
 * [GroupManager] is a pure-Kotlin state machine (no Android, no Room), so
 * persistence is expressed as this small port and implemented by the storage
 * layer (`com.meshline.app.storage.RoomGroupPersistence`).
 *
 * The manager serialises every call through a single consumer coroutine, in
 * the same order the state changed, so implementations do not need to be
 * thread-safe.
 *
 * [GroupItem.isAdmin] is **not** authoritative when it comes back from
 * [loadGroups]: the local identity is what decides it, and [GroupManager]
 * recomputes it while hydrating.
 */
interface GroupPersistence {

    /** Every stored group. History is loaded separately via [loadMessages]. */
    suspend fun loadGroups(): List<GroupItem>

    /** Inserts or replaces one group (create, invite accepted, epoch rotation, roster change). */
    suspend fun upsertGroup(group: GroupItem)

    /** Removes a group and its history (leave, removal by the admin, panic wipe). */
    suspend fun deleteGroup(groupIdHex: String)

    /** Removes every group and all of its history (panic wipe). */
    suspend fun deleteAll()

    /** Every stored group message, oldest first. */
    suspend fun loadMessages(): List<GroupMessageUi>

    suspend fun insertMessage(message: GroupMessageUi)

    suspend fun deleteMessages(groupIdHex: String)

    /** Retention sweep: drops history older than [thresholdMs] (epoch millis). */
    suspend fun deleteMessagesOlderThan(thresholdMs: Long)
}
