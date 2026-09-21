package me.calvin.healthconnectgenerator

internal data class RunResultPresentation(
    val title: String,
    val detail: String?,
    val showSessionCounts: Boolean,
)

/** Keep stored operational messages intact; omit only prose duplicated by the result's count rows. */
internal fun presentRunResult(result: RunResult): RunResultPresentation {
    val manualSummary = "${result.writtenSessions} sessions written; ${result.alreadyWrittenSessions} already recorded; ${result.waitingSessions} waiting for their end time."
    val automaticCompleted = "Automatic generation completed."
    val automaticUpToDate = "Automatic generation is up to date."
    val automaticWaiting = "Waiting for ${result.waitingSessions} ${if (result.waitingSessions == 1) "session" else "sessions"} on ${result.date} to finish."
    val automaticCounts = "${result.writtenSessions} sessions written; ${result.alreadyWrittenSessions} already written."
    val automaticPrefix = listOf(automaticCompleted, automaticUpToDate, automaticWaiting)
        .firstOrNull { result.message == it || result.message.startsWith("$it ") }

    val detail = when {
        !result.success -> result.message
        result.message == manualSummary -> ""
        automaticPrefix != null -> {
            val remaining = result.message.removePrefix(automaticPrefix).trimStart()
            val withoutCounts = when {
                remaining == automaticCounts -> ""
                remaining.startsWith("$automaticCounts ") -> remaining.removePrefix("$automaticCounts ")
                else -> remaining
            }
            // The waiting sentence carries the unfinished date, which isn't represented by a count.
            if (automaticPrefix == automaticWaiting) {
                listOf(automaticWaiting, withoutCounts).filter(String::isNotBlank).joinToString(" ")
            } else withoutCounts
        }
        else -> result.message
    }.takeUnless(String::isBlank)

    val title = when {
        !result.success -> "⚠ Needs attention"
        result.writtenSessions > 0 -> "✓ Saved to Health Connect"
        result.waitingSessions > 0 -> "⏳ Waiting for sessions"
        result.alreadyWrittenSessions > 0 -> "✓ Up to date"
        result.message == manualSummary || automaticPrefix == automaticUpToDate || automaticPrefix == automaticCompleted -> "✓ Up to date"
        else -> "✓ Completed"
    }
    return RunResultPresentation(
        title = title,
        detail = detail,
        showSessionCounts = result.writtenSessions > 0 || result.alreadyWrittenSessions > 0 || result.waitingSessions > 0,
    )
}
