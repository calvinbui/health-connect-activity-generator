package me.calvin.healthconnectgenerator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

internal data class SessionReceipt(
    val plan: PlannedSession,
    val fallback: Boolean,
    val state: SessionState,
    val detail: String,
    val remainingKinds: List<String>,
    val recordVersion: Long = ActivityPlan.CLIENT_RECORD_VERSION,
) {
    // An interrupted attempt must retry even when its journal already has the new version.
    fun isWrittenAtCurrentVersion(): Boolean =
        state == SessionState.WRITTEN && recordVersion >= ActivityPlan.CLIENT_RECORD_VERSION
}

/** One small journal per date avoids rewriting the full history for every operation. */
internal class GeneratorStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("generator-settings", Context.MODE_PRIVATE)

    fun settings(): GeneratorSettings = GeneratorSettings(
        zoneId = preferences.getString("zone", "Australia/Sydney")!!,
        intervalMinutes = preferences.getLong("interval", 120),
        backgroundEnabled = preferences.getBoolean("background", false),
    )

    fun saveSettings(value: GeneratorSettings, backgroundCursor: LocalDate? = backgroundCursor()) {
        checkSaved(preferences.edit()
            .putString("zone", value.zoneId)
            .putLong("interval", value.intervalMinutes)
            .putBoolean("background", value.backgroundEnabled)
            .putString("backgroundCursor", backgroundCursor?.toString())
            .commit())
    }

    fun backgroundCursor(): LocalDate? = preferences.getString("backgroundCursor", null)?.let(LocalDate::parse)

    fun saveBackgroundCursor(date: LocalDate) {
        checkSaved(preferences.edit().putString("backgroundCursor", date.toString()).commit())
    }

    fun lastBackgroundAttemptAt(): Instant? = preferences.getString("backgroundAttemptAt", null)?.let(Instant::parse)

    fun saveBackgroundAttempt(instant: Instant) {
        checkSaved(preferences.edit().putString("backgroundAttemptAt", instant.toString()).commit())
    }

    fun suppressed(date: LocalDate): Boolean = dayPreferences(date).getBoolean("suppressed", false)

    fun suppress(date: LocalDate, value: Boolean) {
        checkSaved(dayPreferences(date).edit().putBoolean("suppressed", value).commit())
    }

    fun receipts(date: LocalDate): Map<ActivityKind, SessionReceipt> = buildMap {
        val prefs = dayPreferences(date)
        for (activity in ActivityKind.entries) {
            val value = prefs.getString(activity.name, null) ?: continue
            val json = JSONObject(value)
            val kinds = json.getJSONArray("remainingKinds")
            put(activity, SessionReceipt(
                plan = PlannedSession(date, activity, ZonedDateTime.parse(json.getString("start")), ZonedDateTime.parse(json.getString("end"))),
                fallback = json.getBoolean("fallback"),
                state = SessionState.valueOf(json.getString("state")),
                detail = json.getString("detail"),
                remainingKinds = List(kinds.length()) { kinds.getString(it) },
                recordVersion = json.optLong("recordVersion", 1L),
            ))
        }
    }

    fun save(receipt: SessionReceipt) {
        val value = JSONObject()
            .put("start", receipt.plan.start.toString())
            .put("end", receipt.plan.end.toString())
            .put("fallback", receipt.fallback)
            .put("state", receipt.state.name)
            .put("detail", receipt.detail)
            .put("remainingKinds", JSONArray(receipt.remainingKinds))
            .put("recordVersion", receipt.recordVersion)
        checkSaved(dayPreferences(receipt.plan.date).edit().putString(receipt.plan.activity.name, value.toString()).commit())
    }

    fun lastRun(): RunResult? = readRun("lastRun")

    fun lastBackgroundRun(): RunResult? = readRun("lastBackgroundRun")

    private fun readRun(key: String): RunResult? {
        val json = preferences.getString(key, null)?.let(::JSONObject) ?: return null
        return RunResult(
            date = LocalDate.parse(json.getString("date")),
            completedAt = Instant.parse(json.getString("completedAt")),
            success = json.getBoolean("success"),
            writtenSessions = json.getInt("written"),
            alreadyWrittenSessions = json.getInt("existing"),
            waitingSessions = json.getInt("waiting"),
            message = json.getString("message"),
            retryable = json.getBoolean("retryable"),
        )
    }

    fun saveLastRun(result: RunResult): RunResult = saveRun(result, background = false)

    fun saveLastBackgroundRun(result: RunResult): RunResult = saveRun(result, background = true)

    private fun saveRun(result: RunResult, background: Boolean): RunResult {
        val value = JSONObject()
            .put("date", result.date.toString())
            .put("completedAt", result.completedAt.toString())
            .put("success", result.success)
            .put("written", result.writtenSessions)
            .put("existing", result.alreadyWrittenSessions)
            .put("waiting", result.waitingSessions)
            .put("message", result.message)
            .put("retryable", result.retryable)
        val edit = preferences.edit().putString("lastRun", value.toString())
        if (background) edit.putString("lastBackgroundRun", value.toString())
        checkSaved(edit.commit())
        return result
    }

    private fun dayPreferences(date: LocalDate) = context.getSharedPreferences("generator-day-$date", Context.MODE_PRIVATE)

    private fun checkSaved(saved: Boolean) {
        if (!saved) throw IOException("Unable to save the local write journal. Check available phone storage.")
    }
}
