package me.calvin.healthconnectgenerator

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

internal object GeneratorScheduler {
    private const val WORK_NAME = "health-connect-generator-periodic"

    /** Called on Dispatchers.IO; wait for scheduling persistence before reporting it enabled. */
    fun update(context: Context, settings: GeneratorSettings) {
        schedule(context, settings, ExistingPeriodicWorkPolicy.UPDATE)
    }

    /** Recover missing work without moving an existing job's next eligible time. */
    fun reconcile(context: Context, settings: GeneratorSettings) {
        if (settings.backgroundEnabled) schedule(context, settings, ExistingPeriodicWorkPolicy.KEEP)
    }

    private fun schedule(context: Context, settings: GeneratorSettings, policy: ExistingPeriodicWorkPolicy) {
        val manager = WorkManager.getInstance(context)
        val operation = if (settings.backgroundEnabled) {
            val request = PeriodicWorkRequestBuilder<GeneratorWorker>(settings.intervalMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        } else {
            manager.cancelUniqueWork(WORK_NAME)
        }
        operation.result.get(15, TimeUnit.SECONDS)
    }

    fun status(context: Context, enabled: Boolean): BackgroundWorkStatus {
        if (!enabled) return BackgroundWorkStatus(BackgroundWorkState.PAUSED)
        return try {
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME)
                .get(15, TimeUnit.SECONDS).firstOrNull { !it.state.isFinished }
                ?: return BackgroundWorkStatus(BackgroundWorkState.MISSING)
            val state = when (work.state) {
                WorkInfo.State.RUNNING -> BackgroundWorkState.RUNNING
                WorkInfo.State.BLOCKED -> BackgroundWorkState.BLOCKED
                else -> BackgroundWorkState.ENQUEUED
            }
            BackgroundWorkStatus(
                state,
                nextEligibleAt = work.nextScheduleTimeMillis.takeIf {
                    state == BackgroundWorkState.ENQUEUED && it > 0 && it != Long.MAX_VALUE
                }?.let(Instant::ofEpochMilli),
            )
        } catch (error: Exception) {
            BackgroundWorkStatus(BackgroundWorkState.ERROR, detail = error.message ?: "Unable to read the Android schedule")
        }
    }

    fun cancel(context: Context) {
        // Do not await cancellation from the worker itself. The persisted opt-in guard is off.
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class GeneratorWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val outcome = GeneratorRepository(applicationContext).generateInBackground()
            when {
                outcome.success -> Result.success(workDataOf("message" to outcome.message))
                outcome.retryable && runAttemptCount < 3 -> Result.retry()
                // A periodic run may fail while future days must remain eligible.
                else -> Result.success(workDataOf("message" to outcome.message, "generationSucceeded" to false))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else Result.success(workDataOf("message" to (error.message ?: "Background generation failed"), "generationSucceeded" to false))
        }
    }
}
