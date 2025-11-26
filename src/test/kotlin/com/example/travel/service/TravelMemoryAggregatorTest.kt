package com.example.travel.service

import com.example.travel.data.TravelHabits
import com.example.travel.data.TravelHistory
import com.example.travel.data.dao.TravelHabitsDao
import com.example.travel.data.dao.TravelHistoryDao
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TravelMemoryAggregatorTest {

    @Test
    fun `aggregates eligible histories into workday morning habit`() = runBlocking {
        val now = Instant.parse("2025-03-31T00:00:00Z")
        val zone = ZoneOffset.UTC

        val histories = listOf(
            history(sessionId = 1, userId = 100, destination = "公司", reach = "2025-03-03T09:02:00Z"),
            history(sessionId = 2, userId = 100, destination = "公司", reach = "2025-03-04T08:41:00Z"),
            history(sessionId = 3, userId = 100, destination = "公司", reach = "2025-03-05T08:30:00Z"),
            history(sessionId = 4, userId = 100, destination = "公司", reach = "2025-03-06T08:35:00Z"),
            history(sessionId = 5, userId = 100, destination = "公司", reach = "2025-03-07T08:48:00Z")
        )

        val historyDao = InMemoryHistoryDao(histories.toMutableList())
        val habitsDao = InMemoryHabitsDao()
        val aggregator = TravelMemoryAggregator(
            historyDao = historyDao,
            habitsDao = habitsDao,
            clock = Clock.fixed(now, zone),
            zoneId = zone
        )

        val report = aggregator.aggregateLongTermMemories()

        assertEquals(1, report.createdHabits.size)
        val habit = habitsDao.inserted.single()
        assertEquals("公司", habit.destination)
        assertEquals("工作日", habit.travelTag)
        assertEquals(1, historyDao.remainingHistories())
        assertTrue(report.consumedSessionIds.containsAll(listOf(1L, 2L, 3L, 4L, 5L)))
    }

    @Test
    fun `cleans up leftovers older than two cycles`() = runBlocking {
        val now = Instant.parse("2025-04-30T00:00:00Z")
        val zone = ZoneOffset.UTC
        val staleCreateTime = now.minus(Duration.ofDays(35)).toEpochMilli()

        val histories = mutableListOf(
            TravelHistory(
                sessionId = 10,
                createTime = staleCreateTime,
                userId = 200,
                destination = "老家",
                reachTime = staleCreateTime,
                relationShip = ""
            )
        )

        val historyDao = InMemoryHistoryDao(histories)
        val habitsDao = InMemoryHabitsDao()
        val aggregator = TravelMemoryAggregator(
            historyDao = historyDao,
            habitsDao = habitsDao,
            clock = Clock.fixed(now, zone),
            zoneId = zone
        )

        val report = aggregator.aggregateLongTermMemories()

        assertTrue(report.createdHabits.isEmpty())
        assertEquals(0, historyDao.remainingHistories())
        assertEquals(1, report.staleHistoryDeleted)
    }

    private fun history(
        sessionId: Long,
        userId: Long,
        destination: String,
        reach: String
    ): TravelHistory {
        val reachInstant = Instant.parse(reach)
        val createTime = reachInstant.minus(Duration.ofDays(1)).toEpochMilli()
        return TravelHistory(
            sessionId = sessionId,
            createTime = createTime,
            userId = userId,
            destination = destination,
            reachTime = reachInstant.toEpochMilli(),
            relationShip = "同事"
        )
    }

    private class InMemoryHistoryDao(
        private val histories: MutableList<TravelHistory>
    ) : TravelHistoryDao {

        override suspend fun loadHistoriesBefore(cutoffEpochMs: Long): List<TravelHistory> =
            histories.filter { it.createTime <= cutoffEpochMs }

        override suspend fun deleteBySessionIds(sessionIds: Collection<Long>) {
            histories.removeAll { it.sessionId in sessionIds.toSet() }
        }

        override suspend fun deleteOlderThan(cutoffEpochMs: Long): Int {
            val before = histories.size
            histories.removeAll { it.createTime < cutoffEpochMs }
            return before - histories.size
        }

        fun remainingHistories(): Int = histories.size
    }

    private class InMemoryHabitsDao : TravelHabitsDao {
        val inserted = mutableListOf<TravelHabits>()

        override suspend fun insertHabits(habits: Collection<TravelHabits>) {
            inserted += habits
        }
    }
}
