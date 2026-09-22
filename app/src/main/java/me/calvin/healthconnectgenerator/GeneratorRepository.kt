@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package me.calvin.healthconnectgenerator

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

class GeneratorRepository(context: Context, private val clock: Clock = Clock.systemUTC()) {
    private val context = context.applicationContext
    private val store = GeneratorStore(this.context)
    private val client by lazy { HealthConnectClient.getOrCreate(this.context) }

    fun settings(): GeneratorSettings = store.settings()

    fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of(settings().zoneId)))

    fun requiredPermissions(): Set<String> = permissions(mindfulnessSupported())

    suspend fun saveSettings(value: GeneratorSettings) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val cursor = BackgroundCatchUp.cursorForSettings(
                settings(), value, store.backgroundCursor(),
                LocalDate.now(clock.withZone(ZoneId.of(value.zoneId))),
            )
            store.saveSettings(value, cursor)
            try {
                GeneratorScheduler.update(context, value)
            } catch (error: Exception) {
                store.saveSettings(value.copy(backgroundEnabled = false), backgroundCursor = null)
                throw error
            }
        }
    }

    suspend fun setBackgroundEnabled(enabled: Boolean) = saveSettings(settings().copy(backgroundEnabled = enabled))

    suspend fun reconcileBackgroundSchedule() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val settings = settings()
            if (settings.backgroundEnabled && store.backgroundCursor() == null) {
                // Old versions did not record an opt-in date. Start catch-up today on upgrade.
                store.saveBackgroundCursor(today())
            }
            GeneratorScheduler.reconcile(context, settings)
        }
    }

    // Read individual atomic receipts without blocking behind a whole catch-up batch.
    suspend fun snapshot(date: LocalDate = today()): GeneratorSnapshot = withContext(Dispatchers.IO) {
        val settings = settings()
        val availability = availability()
        val mindfulness = mindfulnessSupported()
        var statusMessage: String? = null
        val missing = if (availability == HealthConnectAvailability.AVAILABLE) {
            try {
                permissions(mindfulness) - client.permissionController.getGrantedPermissions()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                statusMessage = errorMessage(error)
                permissions(mindfulness)
            }
        } else permissions(mindfulness)
        val receipts = store.receipts(date)
        val now = clock.instant()
        GeneratorSnapshot(
            settings = settings,
            date = date,
            availability = availability,
            mindfulnessSupported = mindfulness,
            missingPermissions = missing,
            sessions = ActivityPlan.forDay(date, ZoneId.of(settings.zoneId)).map { plan ->
                val receipt = receipts[plan.activity]
                if (receipt != null) {
                    val needsUpdate = receipt.state == SessionState.WRITTEN && !receipt.isWrittenAtCurrentVersion()
                    SessionStatus(
                        receipt.plan,
                        if (needsUpdate) SessionState.READY else receipt.state,
                        if (needsUpdate) "Ready to update with the current activity settings" else receipt.detail,
                        receipt.fallback,
                        receipt.remainingKinds.size,
                    )
                } else {
                    val due = plan.isDue(now)
                    SessionStatus(
                        plan,
                        if (due) SessionState.READY else SessionState.WAITING,
                        if (due) "Ready to write" else "Waiting until this session has ended",
                        mindfulnessFallback = plan.activity == ActivityKind.MEDITATE && !mindfulness,
                    )
                }
            },
            lastRun = store.lastRun(),
            backgroundSuppressedForDate = store.suppressed(date),
            statusMessage = statusMessage,
            backgroundWork = GeneratorScheduler.status(context, settings.backgroundEnabled),
            lastBackgroundAttemptAt = store.lastBackgroundAttemptAt(),
            lastBackgroundRun = store.lastBackgroundRun(),
            backgroundPendingSince = store.backgroundCursor(),
        )
    }

    suspend fun generate(date: LocalDate = today()): RunResult = withContext(Dispatchers.IO) {
        operationMutex.withLock { generateDayLocked(date, background = false) }
    }

    internal suspend fun generateInBackground(): RunResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val date = today()
            if (!settings().backgroundEnabled) {
                return@withLock RunResult(date, clock.instant(), true, 0, 0, 0, "Automatic generation is paused.")
            }
            store.saveBackgroundAttempt(clock.instant())
            try {
                val cursor = store.backgroundCursor() ?: date.also(store::saveBackgroundCursor)
                val result = BackgroundCatchUp(
                    generate = { generateDayLocked(it, background = true) },
                    suppressed = store::suppressed,
                    saveCursor = store::saveBackgroundCursor,
                    now = clock::instant,
                ).run(cursor, date)
                store.saveLastBackgroundRun(result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                store.saveLastBackgroundRun(RunResult(
                    date, clock.instant(), false, 0, 0, 0, errorMessage(error), isRetryable(error),
                ))
            }
        }
    }

    /** The caller holds operationMutex for the entire manual run or catch-up batch. */
    private suspend fun generateDayLocked(date: LocalDate, background: Boolean): RunResult {
        var written = 0
        var existing = 0
        var waiting = 0
        fun result(success: Boolean, message: String, retryable: Boolean = false): RunResult =
            store.saveLastRun(RunResult(date, clock.instant(), success, written, existing, waiting, message, retryable))

        if (background && (!settings().backgroundEnabled || store.suppressed(date))) {
            return RunResult(date, clock.instant(), true, 0, 0, 0, "Background generation is paused for this day.")
        }
        if (date.isAfter(today())) return result(false, "Future dates cannot be generated.")
        if (availability() != HealthConnectAvailability.AVAILABLE) {
            return result(false, "Health Connect is unavailable. Open Activity Gen and tap Open Health Connect to finish setup.")
        }
        val mindfulness = mindfulnessSupported()
        try {
            val missing = permissions(mindfulness) - client.permissionController.getGrantedPermissions()
            if (missing.isNotEmpty()) {
                val value = result(false, "Health Connect write permissions are missing. Grant access, then enable background generation again if wanted.")
                pauseBackgroundLocked()
                return value
            }
            if (!background) store.suppress(date, false)
            val receipts = store.receipts(date)
            for (planned in ActivityPlan.forDay(date, ZoneId.of(settings().zoneId))) {
                currentCoroutineContext().ensureActive()
                val previous = receipts[planned.activity]
                if (previous?.isWrittenAtCurrentVersion() == true) {
                    existing++
                    continue
                }
                // Preserve the original plan and record type across retries and preset updates.
                val plan = previous?.takeUnless { it.state == SessionState.DELETED }?.plan ?: planned
                if (!plan.isDue(clock.instant())) {
                    waiting++
                    continue
                }
                val fallback = if (plan.activity == ActivityKind.MEDITATE) {
                    previous?.takeUnless { it.state == SessionState.DELETED }?.fallback ?: !mindfulness
                } else false
                if (plan.activity == ActivityKind.MEDITATE && !fallback && !mindfulness) {
                    return result(false, "A previous meditation attempt used mindfulness. Update Health Connect before retrying; its type will not be changed and duplicated.")
                }
                val kinds = HealthRecordFactory.kinds(plan, fallback)
                val receipt = SessionReceipt(plan, fallback, SessionState.PENDING, "Write started; retry uses the same record IDs", kinds)
                store.save(receipt)
                try {
                    // Cancellation waits for this transaction and its receipt. A subsequent
                    // deletion cannot race an outstanding insert and recreate deleted data.
                    withContext(NonCancellable) {
                        // Stable client IDs and a higher clientRecordVersion update existing
                        // records in place; a retry cannot create a second copy.
                        client.insertRecords(HealthRecordFactory.records(plan, fallback))
                        store.save(receipt.copy(
                            state = SessionState.WRITTEN,
                            detail = "Health Connect accepted ${kinds.size} record${if (kinds.size == 1) "" else "s"}; local receipt",
                        ))
                    }
                    written++
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    store.save(receipt.copy(state = SessionState.ERROR, detail = errorMessage(error)))
                    throw error
                }
            }
            return result(true, "$written sessions written; $existing already recorded; $waiting waiting for their end time.")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val value = result(false, errorMessage(error), isRetryable(error))
            if (error is SecurityException) pauseBackgroundLocked()
            return value
        }
    }

    /** Only exact IDs saved in this app's journal are deleted; no broad time-range deletion. */
    suspend fun deleteGeneratedDay(date: LocalDate): RunResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            var deleted = 0
            fun result(success: Boolean, message: String) = store.saveLastRun(
                RunResult(date, clock.instant(), success, 0, 0, 0, message),
            )
            // Persist both guards before contacting Health Connect. Even a failed delete must not
            // be undone by a later periodic run. Explicit manual Generate clears the date guard.
            store.suppress(date, true)
            pauseBackgroundLocked()
            if (availability() != HealthConnectAvailability.AVAILABLE) {
                return@withLock result(false, "Background generation paused. Health Connect must be available to delete records.")
            }
            try {
                val receipts = store.receipts(date)
                if (receipts.isEmpty()) return@withLock result(true, "No local write receipts for this date. To remove data from a previous installation, use Health Connect's app data controls. Background generation paused.")
                val granted = client.permissionController.getGrantedPermissions()
                for (original in receipts.values) {
                    var receipt = original
                    for (kind in original.remainingKinds) {
                        currentCoroutineContext().ensureActive()
                        val type = HealthRecordFactory.recordType(kind)
                        if (HealthPermission.getWritePermission(type) !in granted) {
                            return@withLock result(false, "$deleted records deleted. Grant Health Connect write access to finish deletion. Background generation remains paused.")
                        }
                        if (kind == "mindfulness" && !mindfulnessSupported()) {
                            return@withLock result(false, "$deleted records deleted. Update Health Connect to delete this day's mindfulness record. Background generation remains paused.")
                        }
                        withContext(NonCancellable) {
                            // On Android 14+ the WRITE grant also allows reading our own records.
                            // Look up the exact client ID first: externally deleted records and
                            // failed write attempts must not cause repeated missing-ID failures.
                            val recordId = findOwnRecordId(type, receipt.plan, kind)
                            if (recordId != null) {
                                client.deleteRecords(
                                    recordType = type,
                                    recordIdsList = listOf(recordId),
                                    clientRecordIdsList = emptyList(),
                                )
                                deleted++
                            }
                            val remaining = receipt.remainingKinds - kind
                            receipt = receipt.copy(
                                remainingKinds = remaining,
                                state = if (remaining.isEmpty()) SessionState.DELETED else SessionState.ERROR,
                                detail = if (remaining.isEmpty()) "Deleted through this app; background paused for this date" else "Partly deleted; run deletion again to finish",
                            )
                            store.save(receipt)
                        }
                    }
                }
                result(true, "$deleted app-generated records deleted. Background generation paused; manual Generate can recreate this day.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                result(false, "$deleted records deleted. ${errorMessage(error)} Background generation remains paused; retry deletion to finish.")
            }
        }
    }

    private fun availability(): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NEEDS_UPDATE
        else -> HealthConnectAvailability.UNAVAILABLE
    }

    private fun mindfulnessSupported(): Boolean = availability() == HealthConnectAvailability.AVAILABLE &&
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private fun permissions(mindfulness: Boolean): Set<String> = buildSet {
        add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        add(HealthPermission.getWritePermission(StepsRecord::class))
        add(HealthPermission.getWritePermission(DistanceRecord::class))
        if (mindfulness) add(HealthPermission.getWritePermission(MindfulnessSessionRecord::class))
    }

    private suspend fun <T : Record> findOwnRecordId(type: KClass<T>, plan: PlannedSession, kind: String): String? {
        val clientId = ActivityPlan.clientId(plan.date, plan.activity, kind)
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(plan.start.toInstant(), plan.end.toInstant()),
                dataOriginFilter = setOf(DataOrigin(context.packageName)),
                pageSize = 1000,
                pageToken = pageToken,
            ))
            response.records.firstOrNull { it.metadata.clientRecordId == clientId }?.let { return it.metadata.id }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return null
    }

    private fun pauseBackgroundLocked() {
        store.saveSettings(settings().copy(backgroundEnabled = false), backgroundCursor = null)
        GeneratorScheduler.cancel(context)
    }

    private fun isRetryable(error: Exception): Boolean = error is IOException || error is android.os.RemoteException

    private fun errorMessage(error: Exception): String = when (error) {
        is SecurityException -> "Health Connect permission was denied or revoked. Grant write access, then try again."
        is UnsupportedOperationException -> "This record type is unavailable. Update Health Connect, then try again."
        is IOException -> "Health Connect or local storage is temporarily unavailable. ${error.message.orEmpty()}"
        is android.os.RemoteException -> "Health Connect did not complete the request. Retry uses the same record IDs."
        else -> "The operation failed: ${error.message ?: error.javaClass.simpleName}"
    }

    companion object {
        // The Activity and WorkManager construct separate repositories in the same process.
        private val operationMutex = Mutex()
    }
}
