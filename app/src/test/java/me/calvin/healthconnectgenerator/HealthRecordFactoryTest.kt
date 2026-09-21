@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package me.calvin.healthconnectgenerator

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HealthRecordFactoryTest {
    private val plans = ActivityPlan.forDay(LocalDate.of(2026, 9, 21), ZoneId.of("Australia/Sydney"))

    @Test fun `supported devices get mindfulness plus five workouts and updated metric records`() {
        val records = plans.flatMap { HealthRecordFactory.records(it, mindfulnessFallback = false) }
        assertEquals(8, records.size)
        assertEquals(5, records.filterIsInstance<ExerciseSessionRecord>().size)
        assertEquals(1, records.filterIsInstance<MindfulnessSessionRecord>().size)
        assertEquals(2_000.0, records.filterIsInstance<DistanceRecord>().single().distance.inMeters, 0.0)
        assertEquals(20_000L, records.filterIsInstance<StepsRecord>().single().count)
        assertTrue(records.all { it.metadata.recordingMethod == Metadata.RECORDING_METHOD_ACTIVELY_RECORDED })
        assertTrue(records.all { it.metadata.device?.type == Device.TYPE_PHONE })
        assertTrue(records.all { it.metadata.clientRecordVersion == 3L })
        assertEquals(8, records.map { it.metadata.clientRecordId }.toSet().size)
    }

    @Test fun `unsupported meditation is explicitly other workout with no mindfulness record`() {
        val records = HealthRecordFactory.records(plans[1], mindfulnessFallback = true)
        val session = records.single() as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, session.exerciseType)
        assertTrue(session.title!!.contains("fallback"))
        assertTrue(session.notes!!.contains("does not support mindfulness"))
        assertTrue(session.metadata.clientRecordId!!.endsWith(":exercise"))
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, session.metadata.recordingMethod)
        assertEquals(Device.TYPE_PHONE, session.metadata.device!!.type)
    }

    @Test fun `CrossFit maps to high intensity interval training`() {
        val session = HealthRecordFactory.records(plans.single { it.activity == ActivityKind.CROSSFIT }, false).single() as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, session.exerciseType)
    }

    @Test fun `cycling is a one-hour biking session after CrossFit`() {
        val plan = plans.single { it.activity == ActivityKind.CYCLING }
        val session = HealthRecordFactory.records(plan, false).single() as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, session.exerciseType)
        assertEquals("Cycling", session.title)
        assertEquals(6, plan.start.hour)
        assertEquals(7, plan.end.hour)
        assertEquals(java.time.Duration.ofHours(1), java.time.Duration.between(session.startTime, session.endTime))
    }

    @Test fun `retries preserve all record IDs and do not increment the conflict version`() {
        val first = plans.flatMap { HealthRecordFactory.records(it, false) }
        val second = plans.flatMap { HealthRecordFactory.records(it, false) }
        assertEquals(first.map { it.metadata.clientRecordId }, second.map { it.metadata.clientRecordId })
        assertEquals(first.map { it.metadata.clientRecordVersion }, second.map { it.metadata.clientRecordVersion })
    }

    @Test fun `deletion kinds exactly match each written type including fallback`() {
        for (fallback in listOf(false, true)) {
            for (plan in plans) {
                val records = HealthRecordFactory.records(plan, fallback)
                assertEquals(
                    records.map { it::class },
                    HealthRecordFactory.kinds(plan, fallback).map { HealthRecordFactory.recordType(it) },
                )
                assertEquals(
                    records.map { it.metadata.clientRecordId },
                    HealthRecordFactory.kinds(plan, fallback).map { ActivityPlan.clientId(plan.date, plan.activity, it) },
                )
            }
        }
    }
}
