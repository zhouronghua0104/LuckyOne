package com.example.travel.service

import com.example.travel.data.TravelHabits
import com.example.travel.data.TravelHistory
import com.example.travel.data.dao.TravelHabitsDao
import com.example.travel.data.dao.TravelHistoryDao
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.text.Charsets
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.usermodel.Row
import org.json.JSONArray
import org.json.JSONObject

private const val MINUTES_IN_DAY = 24 * 60
private const val QUARTER_HOUR_MINUTES = 15
private const val MAX_TIME_DIFFERENCE_MINUTES = 60
private const val MIN_OCCURRENCES_FOR_HABIT = 2

private val WORKDAY_SET = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY
)

private val WEEKEND_SET = setOf(
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY
)

/**
 * Summarises [TravelHistory] rows into [TravelHabits] every 14 natural days.
 *
 * The algorithm follows the business rules shared by the product team:
 * - Histories are collected for a 14-day cycle and turned into habits afterwards.
 * - Histories can only be merged when user id, destination, day-of-week pattern, and
 *   arrival time (same 1-hour window) match.
 * - Arrival times are normalised to the nearest half-hour marker.
 * - Dates are summarised per weekday. Full work-week => "工作日", weekend => "周末",
 *   all seven days => "每天".
 * - Aggregated histories are deleted from the history table; non-aggregated histories
 *   are kept for at most two cycles (28 days) before being purged.
 */
class TravelMemoryAggregator(
    private val historyDao: TravelHistoryDao,
    private val habitsDao: TravelHabitsDao,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault()
    // TODO: inject contact resolver once TravelHistory carries contact metadata.
) {

    private val cycleLength: Duration = Duration.ofDays(14)
    private var aggregationReferenceInstant: Instant = Instant.EPOCH

    /**
     * Performs one aggregation pass and returns a report describing the outcome.
     */
    suspend fun aggregateLongTermMemories(nowOverride: Instant? = null): AggregationReport {
        val now = nowOverride ?: Instant.now(clock)
        val eligibleCutoff = now.minus(cycleLength).toEpochMilli()
        val staleCutoff = now.minus(cycleLength.multipliedBy(2)).toEpochMilli()

        val eligibleHistories = historyDao.loadHistoriesBefore(eligibleCutoff)

        if (eligibleHistories.isEmpty()) {
            val staleRemoved = historyDao.deleteOlderThan(staleCutoff)
            return AggregationReport(emptyList(), emptyList(), staleRemoved, analyzedHistoryCount = 0)
        }

        aggregationReferenceInstant = now
        val aggregationResults = sumUpHistoryRecords(eligibleHistories)

        val createdHabits = aggregationResults.map { it.habit }
        val consumedSessionIds = aggregationResults.flatMap { it.sessionIds }

        if (createdHabits.isNotEmpty()) {
            habitsDao.insertHabits(createdHabits)
            historyDao.deleteBySessionIds(consumedSessionIds)
        }

        val staleRemoved = historyDao.deleteOlderThan(staleCutoff)

        return AggregationReport(
            createdHabits = createdHabits,
            consumedSessionIds = consumedSessionIds,
            staleHistoryDeleted = staleRemoved,
            analyzedHistoryCount = eligibleHistories.size
        )
    }

    /**
     * 归纳历史行程记录列表
     *
     * 根据用户 ID、目的地、到达时间（日期 + 时刻）三要素进行聚合，
     * 生成对应的长期行程记忆。
     */
    private fun sumUpHistoryRecords(historyList: List<TravelHistory>): List<AggregationResult> {
        return historyList
            .groupBy { it.userId to it.destination }
            .flatMap { (key, groupedHistories) ->
                aggregateGroup(
                    userId = key.first,
                    destination = key.second,
                    histories = groupedHistories
                )
            }
    }

    private fun aggregateGroup(
        userId: Long,
        destination: String,
        histories: List<TravelHistory>
    ): List<AggregationResult> {
        val now = aggregationReferenceInstant
        val snapshots = histories.map { history ->
            val reachDateTime = Instant.ofEpochMilli(history.reachTime).atZone(zoneId)
            HistorySnapshot(
                sessionId = history.sessionId,
                userId = userId,
                destination = destination,
                reachEpochMs = history.reachTime,
                relationShip = history.relationShip,
                dayOfWeek = reachDateTime.dayOfWeek,
                minutesOfDay = reachDateTime.hour * 60 + reachDateTime.minute
            )
        }

        val clusters = clusterByTimeWindow(snapshots)

        return clusters.mapNotNull { cluster ->
            if (cluster.size < MIN_OCCURRENCES_FOR_HABIT) {
                return@mapNotNull null
            }

            val canonicalTime = determineCanonicalTime(cluster)
            val weekSummary = summarizeWeekdays(cluster)
            val relationShip = pickDominantRelationship(cluster)

            val habitReachEpoch = canonicalInstantFromTime(canonicalTime, now)

            val habit = TravelHabits(
                createTime = now.toEpochMilli(),
                userId = userId,
                destination = destination,
                reachTime = habitReachEpoch,
                relationShip = relationShip,
                travelTag = weekSummary,
                associatedContacts = "" // reserved for future use
            )

            AggregationResult(
                habit = habit,
                sessionIds = cluster.map { it.sessionId }
            )
        }
    }

    private fun clusterByTimeWindow(histories: List<HistorySnapshot>): List<List<HistorySnapshot>> {
        if (histories.isEmpty()) return emptyList()

        val sorted = histories.sortedBy { it.minutesOfDay }
        val clusters = mutableListOf<MutableList<HistorySnapshot>>()
        var currentCluster = mutableListOf(sorted.first())
        var clusterMin = sorted.first().minutesOfDay

        for (snapshot in sorted.drop(1)) {
            val minutes = snapshot.minutesOfDay
            if (minutes - clusterMin <= MAX_TIME_DIFFERENCE_MINUTES) {
                currentCluster.add(snapshot)
            } else {
                clusters.add(currentCluster)
                currentCluster = mutableListOf(snapshot)
                clusterMin = minutes
            }
        }
        clusters.add(currentCluster)
        return clusters
    }

    private fun determineCanonicalTime(cluster: List<HistorySnapshot>): LocalTime {
        val latest = cluster.maxByOrNull { it.minutesOfDay } ?: return LocalTime.MIDNIGHT
        return normalizeToNearestQuarterHour(latest.minutesOfDay)
    }

    private fun normalizeToNearestQuarterHour(minutesOfDay: Int): LocalTime {
        val remainder = minutesOfDay % QUARTER_HOUR_MINUTES
        val roundedMinutes = if (remainder >= (QUARTER_HOUR_MINUTES / 2)) {
            minutesOfDay + (QUARTER_HOUR_MINUTES - remainder)
        } else {
            minutesOfDay - remainder
        }
        val normalizedMinutes = Math.floorMod(roundedMinutes, MINUTES_IN_DAY)
        return LocalTime.ofSecondOfDay((normalizedMinutes * 60).toLong())
    }

    private fun summarizeWeekdays(cluster: List<HistorySnapshot>): String {
        val daySet = cluster.map { it.dayOfWeek }.toSet()
        if (daySet.isEmpty()) return ""

        val allDays = DayOfWeek.values().toSet()
        return when {
            daySet.containsAll(allDays) -> "每天"
            daySet.containsAll(WORKDAY_SET) && daySet.subtract(WORKDAY_SET).isEmpty() -> "工作日"
            daySet.containsAll(WEEKEND_SET) && daySet.subtract(WEEKEND_SET).isEmpty() -> "周末"
            else -> daySet.sortedBy { it.value }
                .joinToString(separator = "、") { weekdayLabels[it] ?: it.name.lowercase(Locale.getDefault()) }
        }
    }

    private fun pickDominantRelationship(cluster: List<HistorySnapshot>): String {
        val counts = cluster
            .mapNotNull { it.relationShip.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()

        return counts.maxByOrNull { it.value }?.key.orEmpty()
    }

    private fun canonicalInstantFromTime(time: LocalTime, referenceInstant: Instant): Long {
        val referenceDate: LocalDate = referenceInstant.atZone(zoneId).toLocalDate()
        return referenceDate
            .atTime(time)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    private data class HistorySnapshot(
        val sessionId: Long,
        val userId: Long,
        val destination: String,
        val reachEpochMs: Long,
        val relationShip: String,
        val dayOfWeek: DayOfWeek,
        val minutesOfDay: Int
    )

    data class AggregationReport(
        val createdHabits: List<TravelHabits>,
        val consumedSessionIds: List<Long>,
        val staleHistoryDeleted: Int,
        val analyzedHistoryCount: Int
    )

    /**
     * 从外部文件导入历史行程记录。
     */
    private fun importTripHistory(importFilePath: String): List<TravelHistory> {
        val path = Paths.get(importFilePath)
        require(Files.exists(path)) { "Import file not found: $importFilePath" }

        val extension = path.fileName.toString()
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)

        return when (extension) {
            "csv", "txt" -> parseDelimitedFile(path)
            "json" -> parseJsonFile(path)
            "xlsx" -> parseXlsxFile(path)
            else -> parseDelimitedFile(path) // fallback for unknown, treat as text
        }
    }

    private fun parseDelimitedFile(path: Path): List<TravelHistory> {
        if (Files.size(path) == 0L) return emptyList()
        return Files.newBufferedReader(path).use { reader ->
            reader.lineSequence()
                .mapNotNull { parseDelimitedLine(it) }
                .toList()
        }
    }

    private fun parseDelimitedLine(rawLine: String): TravelHistory? {
        val cleaned = rawLine.trim()
            .trimStart('[')
            .trimEnd(']', ',')
        if (cleaned.isBlank()) return null
        val pieces = cleaned.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return buildHistoryFromFields(pieces)
    }

    private fun parseJsonFile(path: Path): List<TravelHistory> {
        val bytes = Files.readAllBytes(path)
        if (bytes.isEmpty()) return emptyList()
        val content = String(bytes, Charsets.UTF_8).trim()
        if (content.isEmpty()) return emptyList()

        val result = mutableListOf<TravelHistory>()
        when {
            content.startsWith("[") -> {
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    when (val entry = array.get(i)) {
                        is JSONObject -> buildHistoryFromJson(entry)?.let(result::add)
                        is JSONArray -> {
                            val fields = mutableListOf<String>()
                            for (j in 0 until entry.length()) {
                                fields += entry.get(j).toString().trim()
                            }
                            buildHistoryFromFields(fields)?.let(result::add)
                        }
                    }
                }
            }
            content.startsWith("{") -> {
                buildHistoryFromJson(JSONObject(content))?.let(result::add)
            }
        }
        return result
    }

    private fun parseXlsxFile(path: Path): List<TravelHistory> {
        val formatter = DataFormatter()
        return Files.newInputStream(path).use { input ->
            WorkbookFactory.create(input).use { workbook ->
                val histories = mutableListOf<TravelHistory>()
                for (sheetIndex in 0 until workbook.numberOfSheets) {
                    val sheet = workbook.getSheetAt(sheetIndex)
                    for (rowIndex in sheet.firstRowNum..sheet.lastRowNum) {
                        val row = sheet.getRow(rowIndex) ?: continue
                        val fields = rowToFields(row, formatter)
                        buildHistoryFromFields(fields)?.let(histories::add)
                    }
                }
                histories
            }
        }
    }

    private fun rowToFields(row: Row, formatter: DataFormatter): List<String> {
        val result = mutableListOf<String>()
        for (cellIndex in 0..3) {
            val cell = row.getCell(cellIndex) ?: continue
            val value = formatter.formatCellValue(cell).trim()
            if (value.isNotEmpty()) {
                result.add(value)
            }
        }
        return result
    }

    private fun buildHistoryFromJson(json: JSONObject): TravelHistory? {
        val fields = listOf(
            json.optString("userId"),
            json.optString("destination"),
            json.optString("reachTime", json.optString("reach_time")),
            json.optString("relationShip", json.optString("relationship"))
        )
        return buildHistoryFromFields(fields)
    }

    private fun buildHistoryFromFields(fields: List<String>): TravelHistory? {
        if (fields.size < 3) return null
        val userId = fields[0].toLongOrNull() ?: return null
        val destination = fields[1]
        val reachTime = parseEpochMillis(fields[2]) ?: return null
        val relationship = fields.getOrNull(3).orEmpty()

        return TravelHistory(
            sessionId = generateSessionId(),
            createTime = System.currentTimeMillis(),
            userId = userId,
            destination = destination,
            reachTime = reachTime,
            relationShip = relationship
        )
    }

    private fun parseEpochMillis(raw: String?): Long? {
        raw ?: return null
        val normalized = raw.trim()
        if (normalized.isEmpty()) return null

        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm"
        )

        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                sdf.isLenient = false
                return sdf.parse(normalized)?.time
            } catch (_: ParseException) {
                // try next pattern
            }
        }
        return null
    }

    private fun generateSessionId(): Long {
        val uuid = UUID.randomUUID()
        val candidate = uuid.mostSignificantBits xor uuid.leastSignificantBits
        return abs(candidate)
    }
}

private val weekdayLabels = mapOf(
    DayOfWeek.MONDAY to "周一",
    DayOfWeek.TUESDAY to "周二",
    DayOfWeek.WEDNESDAY to "周三",
    DayOfWeek.THURSDAY to "周四",
    DayOfWeek.FRIDAY to "周五",
    DayOfWeek.SATURDAY to "周六",
    DayOfWeek.SUNDAY to "周日"
)
