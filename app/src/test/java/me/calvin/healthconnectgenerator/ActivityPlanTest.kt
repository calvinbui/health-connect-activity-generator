package me.calvin.healthconnectgenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ActivityPlanTest {
    private val sydney = ZoneId.of("Australia/Sydney")

    @Test fun `daily plan includes six one-hour activities in order`() {
        val plans = ActivityPlan.forDay(LocalDate.of(2026, 9, 21), sydney)
        assertEquals(ActivityKind.entries, plans.map { it.activity })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), plans.map { it.start.hour })
        assertTrue(plans.all { Duration.between(it.start, it.end) == Duration.ofHours(1) })
    }

    @Test fun `sessions are only eligible after the entire hour ends`() {
        val plan = ActivityPlan.forDay(LocalDate.of(2026, 9, 21), sydney).first()
        assertFalse(plan.isDue(plan.start.toInstant()))
        assertFalse(plan.isDue(plan.end.toInstant().minusNanos(1)))
        assertTrue(plan.isDue(plan.end.toInstant()))
    }

    @Test fun `spring DST gap moves nonexistent times forward and preserves elapsed hour`() {
        val plans = ActivityPlan.forDay(LocalDate.of(2026, 10, 4), sydney)
        assertEquals(1, plans[0].start.hour)
        assertEquals(3, plans[0].end.hour)
        assertEquals(3, plans[1].start.hour)
        assertEquals(4, plans[1].end.hour)
        assertTrue(plans.all { Duration.between(it.start.toInstant(), it.end.toInstant()).toMinutes() == 60L })
        assertEquals(ZoneOffset.ofHours(10), plans[0].start.offset)
        assertEquals(ZoneOffset.ofHours(11), plans[0].end.offset)
    }

    @Test fun `fall DST overlap uses earlier offset and preserves elapsed hour`() {
        val meditation = ActivityPlan.forDay(LocalDate.of(2026, 4, 5), sydney)[1]
        assertEquals(2, meditation.start.hour)
        assertEquals(2, meditation.end.hour)
        assertEquals(ZoneOffset.ofHours(11), meditation.start.offset)
        assertEquals(ZoneOffset.ofHours(10), meditation.end.offset)
        assertEquals(Duration.ofHours(1), Duration.between(meditation.start, meditation.end))
    }

    @Test fun `daily client identities survive retries and timezone changes`() {
        val day = LocalDate.of(2026, 9, 21)
        val sydneyPlan = ActivityPlan.forDay(day, sydney).first()
        val utcPlan = ActivityPlan.forDay(day, ZoneOffset.UTC).first()
        assertNotEquals(sydneyPlan.start.toInstant(), utcPlan.start.toInstant())
        val id = ActivityPlan.clientId(sydneyPlan.date, sydneyPlan.activity, "exercise")
        assertEquals(id, ActivityPlan.clientId(utcPlan.date, utcPlan.activity, "exercise"))
        assertEquals("health-connect-generator:v1:2026-09-21:swimming:exercise", id)
        // The old client ID stays unchanged while the version advances for in-place updates.
        assertEquals(3L, ActivityPlan.CLIENT_RECORD_VERSION)
    }

    @Test fun `separate dates activities and record types have separate identities`() {
        val day = LocalDate.of(2026, 9, 21)
        val ids = (0..1).flatMap { offset ->
            ActivityKind.entries.flatMap { activity ->
                listOf("exercise", "mindfulness", "steps", "distance").map { kind ->
                    ActivityPlan.clientId(day.plusDays(offset.toLong()), activity, kind)
                }
            }
        }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject unsupported record identity kinds`() {
        ActivityPlan.clientId(LocalDate.of(2026, 9, 21), ActivityKind.RUNNING, "sensor")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject background intervals below WorkManager minimum`() {
        GeneratorSettings(intervalMinutes = 14)
    }

    @Test fun `automatic writes are opt in`() {
        assertFalse(GeneratorSettings().backgroundEnabled)
        assertEquals(120L, GeneratorSettings().intervalMinutes)
        assertEquals(sydney.id, GeneratorSettings().zoneId)
    }
}
