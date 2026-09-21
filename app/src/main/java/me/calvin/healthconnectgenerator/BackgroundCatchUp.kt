package me.calvin.healthconnectgenerator

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Tracks unfinished dates so delayed periodic work can resume after midnight. */
internal class BackgroundCatchUp(
    private val generate: suspend (LocalDate) -> RunResult,
    private val suppressed: (LocalDate) -> Boolean,
    private val saveCursor: (LocalDate) -> Unit,
    private val now: () -> Instant,
) {
    suspend fun run(cursor: LocalDate, today: LocalDate): RunResult {
        val earliest = today.minusDays(MAX_DAYS - 1L)
        var day = maxOf(cursor, earliest)
        val omittedDays = if (cursor.isBefore(earliest)) ChronoUnit.DAYS.between(cursor, earliest) else 0L
        if (omittedDays > 0) saveCursor(day)

        var written = 0
        var existing = 0
        var waiting = 0
        var caughtUpDays = 0
        var suppressedDays = 0
        var processedDays = 0

        fun outcome(date: LocalDate, success: Boolean, detail: String, retryable: Boolean = false): RunResult {
            val message = buildList {
                add(detail)
                if (processedDays > 0) add("$written sessions written; $existing already written.")
                if (caughtUpDays > 0) add("Caught up $caughtUpDays earlier ${plural(caughtUpDays, "day")}.")
                if (suppressedDays > 0) {
                    add("Skipped $suppressedDays ${plural(suppressedDays, "date")} with automatic generation suppressed.")
                }
                if (omittedDays > 0) {
                    add("Omitted $omittedDays older ${plural(omittedDays, "day")} outside the $MAX_DAYS-day catch-up window.")
                }
            }.joinToString(" ")
            return RunResult(date, now(), success, written, existing, waiting, message, retryable)
        }

        while (!day.isAfter(today)) {
            if (suppressed(day)) {
                suppressedDays++
                day = day.plusDays(1)
                saveCursor(day)
                continue
            }

            val result = generate(day)
            processedDays++
            written += result.writtenSessions
            existing += result.alreadyWrittenSessions
            waiting += result.waitingSessions
            if (!result.success) {
                return outcome(
                    day,
                    success = false,
                    detail = "Automatic generation stopped on $day: ${result.message}",
                    retryable = result.retryable,
                )
            }
            if (result.waitingSessions > 0) {
                return outcome(
                    day,
                    success = true,
                    detail = "Waiting for ${result.waitingSessions} ${plural(result.waitingSessions, "session")} on $day to finish.",
                )
            }

            if (day.isBefore(today)) caughtUpDays++
            day = day.plusDays(1)
            saveCursor(day)
        }
        return outcome(
            today,
            success = true,
            detail = if (processedDays > 0) "Automatic generation completed." else "Automatic generation is up to date.",
        )
    }

    companion object {
        const val MAX_DAYS = 30

        /** An opt-in or timezone change starts today; ordinary setting changes retain unfinished dates. */
        fun cursorForSettings(
            previous: GeneratorSettings,
            next: GeneratorSettings,
            cursor: LocalDate?,
            today: LocalDate,
        ): LocalDate? = when {
            !next.backgroundEnabled -> null
            !previous.backgroundEnabled || previous.zoneId != next.zoneId || cursor == null -> today
            else -> cursor
        }

        private fun plural(count: Number, word: String): String = if (count.toLong() == 1L) word else "${word}s"
    }
}
