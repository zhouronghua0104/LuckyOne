# Travel Memory Aggregation

This module provides a Kotlin implementation that converts historical travel records (`TravelHistory`) into long-term habits (`TravelHabits`) according to the business rules described in the product requirement:

- Histories are analysed every **14 natural days**.
- Entries can be merged only when the `userId` and `destination` match, the arrival times fall within the same one-hour window, and the weekday pattern aligns.
- Arrival times are normalised to the nearest half-hour mark before being written to `TravelHabits`.
- Weekday patterns collapse automatically:
  - Monday–Friday ⇒ `工作日`
  - Saturday–Sunday ⇒ `周末`
  - Monday–Sunday ⇒ `每天`
- Aggregated histories are removed from `TravelHistory`.
- Leftover histories that stay unaggregated for **two cycles (28 days)** are purged.

## Key classes

- `TravelHistory` / `TravelHabits`: Room entities declared under `com.example.travel.data`.
- `TravelHistoryDao` / `TravelHabitsDao`: DAO contracts the app must implement using Room or any other persistence layer.
- `TravelMemoryAggregator`: the service that orchestrates each aggregation pass.

## Usage

```kotlin
val aggregator = TravelMemoryAggregator(
    historyDao = roomHistoryDao,
    habitsDao = roomHabitsDao,
    clock = Clock.systemDefaultZone(),
    zoneId = ZoneId.of("Asia/Shanghai")
)

val report = aggregator.aggregateLongTermMemories()
println("Created habits: ${report.createdHabits.size}")
```

Call `aggregateLongTermMemories()` once per evaluation window (e.g. via WorkManager). The returned `AggregationReport` details how many histories were analysed, which session IDs were consumed, and how many stale rows were deleted.

## Assumptions & follow-ups

- The travel tag shown to users initially stores the weekday summary, but the UI should still request confirmation/input from the user as described in the requirement.
- `associatedContacts` is left empty because `TravelHistory` does not carry contact metadata yet. The aggregator exposes a TODO hook to plug in a resolver later.
- `MIN_OCCURRENCES_FOR_HABIT` is set to 2 to avoid creating a habit from a single trip.
- The included unit tests (`TravelMemoryAggregatorTest`) use in-memory DAO implementations and can serve as a template for app-side instrumentation tests.
