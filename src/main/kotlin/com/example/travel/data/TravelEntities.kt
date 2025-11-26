package com.example.travel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single historical travel record captured on device.
 */
@Entity
data class TravelHistory(
    /**
     * Session ID of the trip.
     */
    @PrimaryKey
    var sessionId: Long = 0L,

    /**
     * When the record was created on device (epoch millis).
     */
    var createTime: Long = 0L,

    /**
     * User identifier.
     */
    var userId: Long = 0L,

    /**
     * Destination name (exact match required).
     */
    var destination: String = "",

    /**
     * Arrival time (epoch millis).
     */
    var reachTime: Long = 0L,

    /**
     * Relationship with the driver, optional.
     */
    var relationShip: String = ""
)

/**
 * A long-term travel habit distilled from multiple historical trips.
 */
@Entity
data class TravelHabits(
    /**
     * Record primary key.
     */
    @PrimaryKey(autoGenerate = true)
    var msgId: Long = 0L,

    /**
     * When the habit was created (epoch millis).
     */
    var createTime: Long = 0L,

    /**
     * Related user identifier.
     */
    var userId: Long = 0L,

    /**
     * Destination for the habit.
     */
    var destination: String = "",

    /**
     * Representative arrival time (epoch millis, normalized to half hour).
     */
    var reachTime: Long = 0L,

    /**
     * Relationship with the driver, optional.
     */
    var relationShip: String = "",

    /**
     * User confirmed tag, initially empty and filled in by UI later.
     */
    var travelTag: String = "",

    /**
     * Related contacts (comma separated), reserved for future enrichment.
     */
    var associatedContacts: String = ""
)
