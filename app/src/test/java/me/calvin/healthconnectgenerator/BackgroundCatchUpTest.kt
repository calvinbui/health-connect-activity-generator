package me.calvin.healthconnectgenerator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BackgroundCatchUpTest {
    private val today = LocalDate.of(2026, 9, 21)
    private val completion = Instant.parse("2026-09-21T02:00:00Z")
    private val enabled = GeneratorSettings(backgroundEnabled = true)

    @Test fun `a delayed worker catches up yesterday before today`() = runBlocking {
        val calls = mutableListOf<LocalDate>()
        var cursor = today.minusDays(1)
        val result = worker(
            generate = { calls += it; success(it) },
            saveCursor = { cursor = it },
        ).run(cursor, today)

        assertEquals(listOf(today.minusDays(1), today), calls)
        assertEquals(today.plusDays(1), cursor)
        assertEquals(12, result.writtenSessions)
        assertTrue(result.success)
        assertTrue(result.message.contains("Caught up 1 earlier day."))
        assertEquals(completion, result.completedAt)
    }

    @Test fun `daily run before seven resumes the unfinished previous day next time`() = runBlocking {
        var cursor = today
        val calls = mutableListOf<LocalDate>()
        val morning = today.atTime(5, 30).atZone(ZoneId.of("Australia/Sydney")).toInstant()
        val first = worker(
            generate = { date ->
                calls += date
                val due = ActivityPlan.forDay(date, ZoneId.of("Australia/Sydney")).count { it.isDue(morning) }
                success(date, written = due, waiting = ActivityKind.entries.size - due)
            },
            saveCursor = { cursor = it },
        ).run(cursor, today)
        assertEquals(today, cursor)
        assertEquals(4, first.writtenSessions)
        assertEquals(2, first.waitingSessions)

        val tomorrow = today.plusDays(1)
        val second = worker(
            generate = { date ->
                calls += date
                if (date == today) success(date, written = 2, existing = 4)
                else success(date, written = 4, waiting = 2)
            },
            saveCursor = { cursor = it },
        ).run(cursor, tomorrow)

        assertEquals(listOf(today, today, tomorrow), calls)
        assertEquals(tomorrow, cursor)
        assertEquals(6, second.writtenSessions)
        assertEquals(4, second.alreadyWrittenSessions)
        assertEquals(2, second.waitingSessions)
        assertTrue(second.message.contains("Caught up 1 earlier day."))
        assertTrue(second.message.contains("Waiting for 2 sessions on $tomorrow"))
    }

    @Test fun `a failed date keeps its cursor and resumes without revisiting completed dates`() = runBlocking {
        var cursor = today.minusDays(2)
        val failedDay = today.minusDays(1)
        val calls = mutableListOf<LocalDate>()
        val failed = worker(
            generate = { date ->
                calls += date
                if (date == failedDay) {
                    success(date, written = 2).copy(success = false, retryable = true, message = "Provider unavailable.")
                } else success(date)
            },
            saveCursor = { cursor = it },
        ).run(cursor, today)

        assertFalse(failed.success)
        assertTrue(failed.retryable)
        assertEquals(failedDay, cursor)
        assertEquals(failedDay, failed.date)
        assertEquals(8, failed.writtenSessions)
        assertTrue(failed.message.contains("stopped on $failedDay: Provider unavailable."))

        val resumed = worker(
            generate = { date ->
                calls += date
                if (date == failedDay) success(date, written = 4, existing = 2) else success(date)
            },
            saveCursor = { cursor = it },
        ).run(cursor, today)

        assertEquals(listOf(today.minusDays(2), failedDay, failedDay, today), calls)
        assertEquals(today.plusDays(1), cursor)
        assertEquals(10, resumed.writtenSessions)
        assertEquals(2, resumed.alreadyWrittenSessions)
    }

    @Test fun `nonretryable errors remain nonretryable and do not advance cursor`() = runBlocking {
        val saved = mutableListOf<LocalDate>()
        val result = worker(
            generate = { success(it, written = 0).copy(success = false, message = "Permission required.") },
            saveCursor = { saved += it },
        ).run(today, today)

        assertFalse(result.success)
        assertFalse(result.retryable)
        assertTrue(saved.isEmpty())
    }

    @Test fun `unfinished successful dates stop traversal and keep the cursor`() = runBlocking {
        val calls = mutableListOf<LocalDate>()
        val saved = mutableListOf<LocalDate>()
        val cursor = today.minusDays(1)
        val result = worker(
            generate = { calls += it; success(it, written = 1, waiting = 5) },
            saveCursor = { saved += it },
        ).run(cursor, today)

        assertEquals(listOf(cursor), calls)
        assertTrue(saved.isEmpty())
        assertEquals(cursor, result.date)
        assertTrue(result.success)
        assertEquals(5, result.waitingSessions)
    }

    @Test fun `catch up includes at most thirty days and durably discards the older range`() = runBlocking {
        val calls = mutableListOf<LocalDate>()
        val saved = mutableListOf<LocalDate>()
        val result = worker(
            generate = { calls += it; success(it) },
            saveCursor = { saved += it },
        ).run(today.minusDays(50), today)

        assertEquals(30, calls.size)
        assertEquals(today.minusDays(29), calls.first())
        assertEquals(today, calls.last())
        assertEquals(today.minusDays(29), saved.first())
        assertEquals(today.plusDays(1), saved.last())
        assertTrue(result.message.contains("Omitted 21 older days"))
    }

    @Test fun `suppressed dates advance the cursor without creating records`() = runBlocking {
        val calls = mutableListOf<LocalDate>()
        val saved = mutableListOf<LocalDate>()
        val deletedDay = today.minusDays(1)
        val result = worker(
            generate = { calls += it; success(it) },
            suppressed = { it == deletedDay },
            saveCursor = { saved += it },
        ).run(today.minusDays(2), today)

        assertEquals(listOf(today.minusDays(2), today), calls)
        assertEquals(listOf(deletedDay, today, today.plusDays(1)), saved)
        assertEquals(12, result.writtenSessions)
        assertTrue(result.message.contains("Skipped 1 date"))
        assertTrue(result.message.contains("Caught up 1 earlier day."))
    }

    @Test fun `completed today advances to tomorrow and repeat execution writes nothing`() = runBlocking {
        var cursor = today
        var generated = 0
        val worker = worker(
            generate = { generated++; success(it) },
            saveCursor = { cursor = it },
        )
        worker.run(cursor, today)
        val repeated = worker.run(cursor, today)

        assertEquals(today.plusDays(1), cursor)
        assertEquals(1, generated)
        assertEquals(0, repeated.writtenSessions)
        assertTrue(repeated.message.contains("up to date"))
    }

    @Test fun `a future cursor never generates future or earlier dates`() = runBlocking {
        val result = worker(
            generate = { error("No date should be generated.") },
            saveCursor = { error("The future cursor should be retained.") },
        ).run(today.plusDays(5), today)

        assertTrue(result.success)
        assertEquals(0, result.writtenSessions)
        assertEquals(0, result.waitingSessions)
    }

    @Test fun `cursor persistence failure prevents generation of later dates`() = runBlocking {
        val calls = mutableListOf<LocalDate>()
        var failed = false
        try {
            worker(
                generate = { calls += it; success(it) },
                saveCursor = { throw IllegalStateException("Disk full.") },
            ).run(today.minusDays(1), today)
        } catch (expected: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(listOf(today.minusDays(1)), calls)
    }

    @Test fun `disabling clears the cursor and re-enabling starts today without filling paused days`() {
        val disabled = enabled.copy(backgroundEnabled = false)
        assertNull(BackgroundCatchUp.cursorForSettings(enabled, disabled, today.minusDays(5), today))
        assertEquals(today, BackgroundCatchUp.cursorForSettings(disabled, enabled, today.minusDays(5), today))
    }

    @Test fun `first opt in and migration start today without generating older dates`() {
        assertEquals(today, BackgroundCatchUp.cursorForSettings(GeneratorSettings(), enabled, null, today))
        assertEquals(today, BackgroundCatchUp.cursorForSettings(enabled, enabled, null, today))
    }

    @Test fun `interval changes preserve the unfinished date`() {
        val cursor = today.minusDays(2)
        assertEquals(cursor, BackgroundCatchUp.cursorForSettings(enabled, enabled.copy(intervalMinutes = 1440), cursor, today))
    }

    @Test fun `timezone changes reset safely to the current local day`() {
        assertEquals(today, BackgroundCatchUp.cursorForSettings(enabled, enabled.copy(zoneId = "Pacific/Honolulu"), today.minusDays(2), today))
    }

    private fun success(date: LocalDate, written: Int = 6, existing: Int = 0, waiting: Int = 0) =
        RunResult(date, completion.minusSeconds(30), true, written, existing, waiting, "Day generated.")

    private fun worker(
        generate: suspend (LocalDate) -> RunResult,
        suppressed: (LocalDate) -> Boolean = { false },
        saveCursor: (LocalDate) -> Unit = {},
    ) = BackgroundCatchUp(generate, suppressed, saveCursor) { completion }
}
