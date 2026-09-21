@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package me.calvin.healthconnectgenerator

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import kotlin.reflect.KClass

internal object HealthRecordFactory {
    const val NOTES = "Generated manually by Health Connect Generator; not measured by a sensor."

    fun kinds(plan: PlannedSession, mindfulnessFallback: Boolean): List<String> = buildList {
        add(if (plan.activity == ActivityKind.MEDITATE && !mindfulnessFallback) "mindfulness" else "exercise")
        if (plan.activity == ActivityKind.SWIMMING) add("distance")
        if (plan.activity == ActivityKind.RUNNING) add("steps")
    }

    fun recordType(kind: String): KClass<out Record> = when (kind) {
        "exercise" -> ExerciseSessionRecord::class
        "mindfulness" -> MindfulnessSessionRecord::class
        "steps" -> StepsRecord::class
        "distance" -> DistanceRecord::class
        else -> error("Unknown record kind: $kind")
    }

    fun records(plan: PlannedSession, mindfulnessFallback: Boolean): List<Record> {
        fun metadata(kind: String) = Metadata.activelyRecorded(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = ActivityPlan.clientId(plan.date, plan.activity, kind),
            clientRecordVersion = ActivityPlan.CLIENT_RECORD_VERSION,
        )
        val start = plan.start.toInstant()
        val end = plan.end.toInstant()
        return buildList {
            if (plan.activity == ActivityKind.MEDITATE && !mindfulnessFallback) {
                add(MindfulnessSessionRecord(
                    startTime = start,
                    startZoneOffset = plan.start.offset,
                    endTime = end,
                    endZoneOffset = plan.end.offset,
                    metadata = metadata("mindfulness"),
                    mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION,
                    title = "Meditate",
                    notes = NOTES,
                ))
            } else {
                add(ExerciseSessionRecord(
                    startTime = start,
                    startZoneOffset = plan.start.offset,
                    endTime = end,
                    endZoneOffset = plan.end.offset,
                    metadata = metadata("exercise"),
                    exerciseType = when (plan.activity) {
                        ActivityKind.SWIMMING -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
                        ActivityKind.MEDITATE -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
                        ActivityKind.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
                        ActivityKind.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                        ActivityKind.CROSSFIT -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
                        ActivityKind.CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                    },
                    title = if (plan.activity == ActivityKind.MEDITATE) "Meditate (other workout fallback)" else plan.activity.label,
                    notes = NOTES + if (plan.activity == ActivityKind.MEDITATE) {
                        " This device does not support mindfulness records; stored as other workout."
                    } else "",
                ))
            }
            when (plan.activity) {
                ActivityKind.SWIMMING -> add(DistanceRecord(
                    startTime = start,
                    startZoneOffset = plan.start.offset,
                    endTime = end,
                    endZoneOffset = plan.end.offset,
                    metadata = metadata("distance"),
                    distance = Length.meters(ActivityPlan.SWIMMING_DISTANCE_METRES),
                ))
                ActivityKind.RUNNING -> add(StepsRecord(
                    startTime = start,
                    startZoneOffset = plan.start.offset,
                    endTime = end,
                    endZoneOffset = plan.end.offset,
                    metadata = metadata("steps"),
                    count = ActivityPlan.RUNNING_STEPS,
                ))
                else -> Unit
            }
        }
    }
}
