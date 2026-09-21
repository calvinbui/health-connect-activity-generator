package me.calvin.healthconnectgenerator

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class GeneratorSettings(
    val zoneId: String = "Australia/Sydney",
    val intervalMinutes: Long = 120,
    val backgroundEnabled: Boolean = false,
) {
    init {
        ZoneId.of(zoneId)
        require(intervalMinutes in 15..1440) { "The interval must be between 15 and 1,440 minutes." }
    }
}

enum class ActivityKind(val label: String, val hour: Int, val metrics: String) {
    SWIMMING("Swimming", 1, "2,000 metres"),
    MEDITATE("Meditate", 2, "Meditation"),
    YOGA("Yoga", 3, "Yoga"),
    RUNNING("Running", 4, "20,000 steps"),
    CROSSFIT("CrossFit", 5, "High intensity interval training"),
    CYCLING("Cycling", 6, "Cycling"),
}

data class PlannedSession(
    val date: LocalDate,
    val activity: ActivityKind,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    fun isDue(now: Instant): Boolean = !end.toInstant().isAfter(now)
}

enum class SessionState { WAITING, READY, PENDING, WRITTEN, ERROR, DELETED }

data class SessionStatus(
    val plan: PlannedSession,
    val state: SessionState,
    val detail: String,
    val mindfulnessFallback: Boolean = false,
    val recordCount: Int = 0,
)

enum class HealthConnectAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

data class RunResult(
    val date: LocalDate,
    val completedAt: Instant,
    val success: Boolean,
    val writtenSessions: Int,
    val alreadyWrittenSessions: Int,
    val waitingSessions: Int,
    val message: String,
    val retryable: Boolean = false,
)

enum class BackgroundWorkState { PAUSED, ENQUEUED, RUNNING, BLOCKED, MISSING, ERROR }

data class BackgroundWorkStatus(
    val state: BackgroundWorkState,
    val nextEligibleAt: Instant? = null,
    val detail: String? = null,
)

data class GeneratorSnapshot(
    val settings: GeneratorSettings,
    val date: LocalDate,
    val availability: HealthConnectAvailability,
    val mindfulnessSupported: Boolean,
    val missingPermissions: Set<String>,
    val sessions: List<SessionStatus>,
    val lastRun: RunResult?,
    val backgroundSuppressedForDate: Boolean,
    val statusMessage: String? = null,
    val backgroundWork: BackgroundWorkStatus = BackgroundWorkStatus(BackgroundWorkState.PAUSED),
    val lastBackgroundAttemptAt: Instant? = null,
    val lastBackgroundRun: RunResult? = null,
    val backgroundPendingSince: LocalDate? = null,
)

/** Pure scheduling logic; every session lasts one elapsed hour, including DST transition days. */
object ActivityPlan {
    const val CLIENT_RECORD_VERSION = 3L
    const val SWIMMING_DISTANCE_METRES = 2_000.0
    const val RUNNING_STEPS = 20_000L

    fun forDay(date: LocalDate, zone: ZoneId): List<PlannedSession> = ActivityKind.entries.map {
        // java.time moves nonexistent local times forward and chooses the earlier offset for overlaps.
        val start = date.atTime(it.hour, 0).atZone(zone)
        PlannedSession(date, it, start, start.plusHours(1))
    }

    /** Zone-independent identity: changing timezone cannot create a second copy for the same day. */
    fun clientId(date: LocalDate, activity: ActivityKind, recordKind: String): String {
        require(recordKind in setOf("exercise", "mindfulness", "steps", "distance"))
        return "health-connect-generator:v1:$date:${activity.name.lowercase()}:$recordKind"
    }
}
