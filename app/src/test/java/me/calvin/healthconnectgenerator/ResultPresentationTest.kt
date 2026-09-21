package me.calvin.healthconnectgenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ResultPresentationTest {
    private val date = LocalDate.of(2026, 9, 21)

    @Test fun `stored manual summaries become a heading and count rows`() {
        val result = result(4, 1, 1, "4 sessions written; 1 already recorded; 1 waiting for their end time.")
        val presentation = presentRunResult(result)

        assertEquals("✓ Saved to Health Connect", presentation.title)
        assertTrue(presentation.showSessionCounts)
        assertNull(presentation.detail)
    }

    @Test fun `existing sessions are up to date while unfinished sessions show waiting`() {
        val existing = presentRunResult(result(0, 6, 0, "0 sessions written; 6 already recorded; 0 waiting for their end time."))
        val waiting = presentRunResult(result(0, 2, 4, "0 sessions written; 2 already recorded; 4 waiting for their end time."))

        assertEquals("✓ Up to date", existing.title)
        assertEquals("⏳ Waiting for sessions", waiting.title)
        assertTrue(waiting.showSessionCounts)
        assertNull(waiting.detail)
    }

    @Test fun `partial failures preserve the error and successful count information`() {
        val message = "Automatic generation stopped on $date: Permission denied. 2 sessions written; 0 already written. Caught up 1 earlier day."
        val presentation = presentRunResult(result(2, 0, 0, message).copy(success = false))

        assertEquals("⚠ Needs attention", presentation.title)
        assertEquals(message, presentation.detail)
        assertTrue(presentation.showSessionCounts)
    }

    @Test fun `a failure without writes still displays its error without generation counts`() {
        val presentation = presentRunResult(result(0, 0, 0, "Health Connect is unavailable.").copy(success = false))

        assertEquals("⚠ Needs attention", presentation.title)
        assertEquals("Health Connect is unavailable.", presentation.detail)
        assertFalse(presentation.showSessionCounts)
    }

    @Test fun `deletion results preserve the removed record count and paused background state`() {
        val message = "8 app-generated records deleted. Background generation paused; manual Generate can recreate this day."
        val presentation = presentRunResult(result(0, 0, 0, message))

        assertEquals("✓ Completed", presentation.title)
        assertEquals(message, presentation.detail)
        assertFalse(presentation.showSessionCounts)
    }

    @Test fun `automatic success preserves catch up suppressed dates and omissions`() {
        val detail = "Caught up 29 earlier days. Skipped 1 date with automatic generation suppressed. Omitted 7 older days outside the 30-day catch-up window."
        val presentation = presentRunResult(result(174, 0, 0, "Automatic generation completed. 174 sessions written; 0 already written. $detail"))

        assertEquals("✓ Saved to Health Connect", presentation.title)
        assertEquals(detail, presentation.detail)
        assertTrue(presentation.showSessionCounts)
    }

    @Test fun `automatic waiting keeps its date and catch up detail`() {
        val detail = "Waiting for 1 session on $date to finish."
        val presentation = presentRunResult(result(0, 5, 1, "$detail 0 sessions written; 5 already written. Caught up 1 earlier day."))

        assertEquals("⏳ Waiting for sessions", presentation.title)
        assertEquals("$detail Caught up 1 earlier day.", presentation.detail)
        assertTrue(presentation.showSessionCounts)
    }

    @Test fun `an automatic result already up to date does not invent generation counts`() {
        val presentation = presentRunResult(result(0, 0, 0, "Automatic generation is up to date."))

        assertEquals("✓ Up to date", presentation.title)
        assertNull(presentation.detail)
        assertFalse(presentation.showSessionCounts)
    }

    @Test fun `an up to date result retains skipped and omitted date information`() {
        val detail = "Skipped 2 dates with automatic generation suppressed. Omitted 10 older days outside the 30-day catch-up window."
        val presentation = presentRunResult(result(0, 0, 0, "Automatic generation is up to date. $detail"))

        assertEquals(detail, presentation.detail)
        assertFalse(presentation.showSessionCounts)
    }

    @Test fun `unknown or mismatched summaries are never discarded`() {
        val message = "1 sessions written; 2 already recorded; 3 waiting for their end time."
        assertEquals(message, presentRunResult(result(6, 0, 0, message)).detail)
        val unknown = "Import succeeded. 6 sessions written; 0 already written. Check source details."
        assertEquals(unknown, presentRunResult(result(6, 0, 0, unknown)).detail)
    }

    @Test fun `successful empty result can display only its completion heading`() {
        val presentation = presentRunResult(result(0, 0, 0, ""))

        assertEquals("✓ Completed", presentation.title)
        assertNull(presentation.detail)
        assertFalse(presentation.showSessionCounts)
    }

    private fun result(written: Int, existing: Int, waiting: Int, message: String) = RunResult(
        date = date,
        completedAt = Instant.parse("2026-09-21T02:00:00Z"),
        success = true,
        writtenSessions = written,
        alreadyWrittenSessions = existing,
        waitingSessions = waiting,
        message = message,
    )
}
