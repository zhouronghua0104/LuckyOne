package com.example.travel.service

import com.example.travel.data.TravelHabits

/**
 * Encapsulates the habit produced from a cluster of historical trips as well
 * as the session IDs that were consumed during aggregation.
 */
data class AggregationResult(
    val habit: TravelHabits,
    val sessionIds: List<Long>
)
