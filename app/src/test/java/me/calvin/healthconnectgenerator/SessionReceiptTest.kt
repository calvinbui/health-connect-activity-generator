package me.calvin.healthconnectgenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SessionReceiptTest {
    private val plan = ActivityPlan.forDay(LocalDate.of(2026, 9, 21), ZoneId.of("Australia/Sydney"))
        .first { it.activity == ActivityKind.SWIMMING }

    private fun receipt(state: SessionState = SessionState.WRITTEN) = SessionReceipt(
        plan = plan,
        fallback = false,
        state = state,
        detail = "Test receipt",
        remainingKinds = listOf("exercise", "distance"),
    )

    @Test fun `previous preset version is updated once then skipped`() {
        val previous = receipt().copy(recordVersion = 1L)
        assertFalse(previous.isWrittenAtCurrentVersion())

        val updated = previous.copy(recordVersion = ActivityPlan.CLIENT_RECORD_VERSION)
        assertTrue(updated.isWrittenAtCurrentVersion())
        assertEquals(previous.plan, updated.plan)
    }

    @Test fun `new receipts use the current preset version`() {
        assertEquals(ActivityPlan.CLIENT_RECORD_VERSION, receipt(SessionState.PENDING).recordVersion)
    }

    @Test fun `version two completed receipt is eligible for recording method update`() {
        val previous = receipt().copy(recordVersion = 2L)
        assertFalse(previous.isWrittenAtCurrentVersion())
        val updated = previous.copy(recordVersion = ActivityPlan.CLIENT_RECORD_VERSION)
        assertTrue(updated.isWrittenAtCurrentVersion())
        assertEquals(previous.plan, updated.plan)
        assertEquals(previous.fallback, updated.fallback)
    }

    @Test fun `interrupted current version update is retried until marked written`() {
        val pending = receipt(SessionState.PENDING)
        assertFalse(pending.isWrittenAtCurrentVersion())
        assertFalse(pending.copy(state = SessionState.ERROR).isWrittenAtCurrentVersion())
        assertTrue(pending.copy(state = SessionState.WRITTEN).isWrittenAtCurrentVersion())
    }

    @Test fun `deleted receipt permits explicit regeneration`() {
        assertFalse(receipt(SessionState.DELETED).isWrittenAtCurrentVersion())
    }

    @Test fun `future completed version is not downgraded`() {
        assertTrue(receipt().copy(recordVersion = ActivityPlan.CLIENT_RECORD_VERSION + 1).isWrittenAtCurrentVersion())
    }
}
