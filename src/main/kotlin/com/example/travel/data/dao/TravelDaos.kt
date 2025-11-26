package com.example.travel.data.dao

import com.example.travel.data.TravelHabits
import com.example.travel.data.TravelHistory

/**
 * DAO abstraction for reading and mutating [TravelHistory] entries.
 *
 * The actual implementation can be backed by Room, Realm, or any custom store.
 */
interface TravelHistoryDao {
    /**
     * Returns all histories created on or before [cutoffEpochMs], ordered however the caller prefers.
     */
    suspend fun loadHistoriesBefore(cutoffEpochMs: Long): List<TravelHistory>

    /**
     * Removes histories with session IDs in [sessionIds].
     */
    suspend fun deleteBySessionIds(sessionIds: Collection<Long>)

    /**
     * Removes histories whose [TravelHistory.createTime] is earlier than [cutoffEpochMs].
     *
     * Returns number of deleted rows to help with observability.
     */
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int
}

/**
 * DAO abstraction for persisting newly created [TravelHabits].
 */
interface TravelHabitsDao {
    suspend fun insertHabits(habits: Collection<TravelHabits>)
}
