package me.calvin.healthconnectgenerator

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var repository: GeneratorRepository
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var connection: TextView
    private lateinit var connectButton: Button
    private lateinit var dateButton: Button
    private lateinit var sessions: LinearLayout
    private lateinit var generateButton: Button
    private lateinit var backgroundSwitch: MaterialSwitch
    private lateinit var scheduleDetail: TextView
    private lateinit var backgroundStatus: TextView
    private lateinit var backgroundResult: LinearLayout
    private lateinit var batteryDetail: TextView
    private lateinit var batteryButton: Button
    private lateinit var status: TextView
    private lateinit var lastResultDetails: LinearLayout
    private lateinit var deleteButton: Button
    private lateinit var settingsButton: Button
    private var selectedDate: LocalDate? = null
    private var latest: GeneratorSnapshot? = null
    private var busy = false
    private var updatingSwitch = false
    private var refreshJob: Job? = null
    private var clockJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { refresh() }

    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // This settings action has no result payload. Read the actual exemption on return.
        renderBatteryStatus()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = GeneratorRepository(applicationContext)
        selectedDate = savedInstanceState?.getString("date")?.let(LocalDate::parse)
        createContent()
        // Fragment dialogs survive configuration changes; reconnect the selection callback.
        @Suppress("UNCHECKED_CAST")
        val restoredPicker = supportFragmentManager.findFragmentByTag("activity-date") as? MaterialDatePicker<Long>
        restoredPicker?.addOnPositiveButtonClickListener(::selectDate)
    }

    override fun onResume() {
        super.onResume()
        refresh(reconcileSchedule = true)
        clockJob = lifecycleScope.launch {
            while (true) {
                delay(60_000)
                refresh()
            }
        }
    }

    override fun onPause() {
        clockJob?.cancel()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedDate?.let { outState.putString("date", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun createContent() {
        scroll = ScrollView(this).apply { setBackgroundColor(MaterialColors.getColor(this, MaterialR.attr.colorSurface)); isFillViewport = true }
        content = column().apply { setPadding(dp(16), dp(24), dp(16), dp(24)) }
        scroll.addView(content)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content.addView(label("Health Connect", MaterialR.style.TextAppearance_Material3_LabelLarge, androidx.appcompat.R.attr.colorPrimary))
        content.addView(label(getString(R.string.app_name), MaterialR.style.TextAppearance_Material3_HeadlineLarge, MaterialR.attr.colorOnSurface).apply { setPadding(0, dp(10), 0, dp(8)) })
        content.addView(label("Your daily presets, saved on your phone.", MaterialR.style.TextAppearance_Material3_BodyLarge, MaterialR.attr.colorOnSurfaceVariant))

        val connectionCard = cardContent()
        connection = label("◷ Checking Health Connect…", MaterialR.style.TextAppearance_Material3_TitleMedium, MaterialR.attr.colorOnSurface)
        connectionCard.addView(connection)
        connectionCard.addView(label("Save your daily exercise, steps, distance and meditation to Health Connect.", MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurfaceVariant).spaced(8))
        connectButton = button("Connect Health Connect", primary = true) { connect() }
        connectionCard.addView(connectButton.spaced(12))
        connectionCard.addView(button("Open Health Connect") { openHealthConnect() })
        content.addView(card(connectionCard).spaced(24))

        val planCard = cardContent()
        planCard.addView(label("Daily plan", MaterialR.style.TextAppearance_Material3_TitleMedium, MaterialR.attr.colorOnSurface))
        dateButton = button("Today") { chooseDate() }
        planCard.addView(dateButton.spaced(4))
        sessions = column()
        planCard.addView(sessions)
        planCard.addView(label("One hour per session. Sessions become available after their end time. Repeated runs reuse the same records.", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant).spaced(12))
        generateButton = button("Generate available sessions", primary = true) {
            perform("Writing to Health Connect…") {
                displayLastResult(repository.generate(displayDate()))
                null
            }
        }
        planCard.addView(generateButton.spaced(12))
        content.addView(card(planCard).spaced(16))

        val scheduleCard = cardContent()
        backgroundSwitch = MaterialSwitch(this).apply {
            text = "Automatic generation"
            setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleMedium)
            minHeight = dp(52)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingSwitch) {
                    perform(
                        if (checked) "Enabling schedule…" else "Pausing schedule…",
                        onSuccess = { if (checked) suggestBatterySettings() },
                    ) {
                        repository.setBackgroundEnabled(checked)
                        if (checked) "Automatic generation enabled." else "Automatic generation paused."
                    }
                }
            }
        }
        scheduleCard.addView(backgroundSwitch)
        scheduleDetail = label("", MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurfaceVariant)
        scheduleCard.addView(scheduleDetail.spaced(4))
        backgroundStatus = label("Checking schedule…", MaterialR.style.TextAppearance_Material3_TitleSmall, MaterialR.attr.colorOnSurface)
        scheduleCard.addView(backgroundStatus.spaced(12))
        backgroundResult = column()
        scheduleCard.addView(backgroundResult.spaced(8))
        batteryDetail = label("", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant)
        scheduleCard.addView(batteryDetail.spaced(12))
        batteryButton = button("Allow background activity") { requestBatteryExemption() }
        scheduleCard.addView(batteryButton.spaced(4))
        settingsButton = button("Edit time zone & interval") { editSettings() }
        scheduleCard.addView(settingsButton.spaced(8))
        content.addView(card(scheduleCard).spaced(16))

        val statusCard = cardContent()
        statusCard.addView(label("Last result", MaterialR.style.TextAppearance_Material3_TitleMedium, MaterialR.attr.colorOnSurface))
        status = label("No activities generated yet.", MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurface).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusCard.addView(status.spaced(10))
        lastResultDetails = column()
        statusCard.addView(lastResultDetails.spaced(8))
        statusCard.addView(label("Saved status is this app’s write receipt. Open Health Connect to inspect the stored records.", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant).spaced(10))
        deleteButton = button("Delete this day’s records") { confirmDelete() }
        statusCard.addView(deleteButton.spaced(8))
        content.addView(card(statusCard).spaced(16))
        content.addView(button("Privacy & permissions") { startActivity(Intent(this, PrivacyActivity::class.java)) }.spaced(12))
        content.addView(label("Activity Gen  ·  1.4.1", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant).apply { gravity = Gravity.CENTER })
        setControlsEnabled(false)
    }

    private fun displayDate(): LocalDate = selectedDate ?: repository.today()

    private fun refresh(reconcileSchedule: Boolean = false) {
        if (busy) return
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                var scheduleError: String? = null
                if (reconcileSchedule) {
                    // Show running work immediately; reconciliation waits for any active batch.
                    render(repository.snapshot(displayDate()))
                    try {
                        repository.reconcileBackgroundSchedule()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        scheduleError = "Could not restore schedule: ${error.message ?: "please reopen the app"}"
                    }
                }
                render(repository.snapshot(displayDate()))
                scheduleError?.let { backgroundStatus.append("\n$it") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                connection.text = "⚠ Could not check Health Connect"
                showStatusMessage(error.message ?: "Please reopen the app and try again.")
                setControlsEnabled(true)
            }
        }
    }

    private fun render(snapshot: GeneratorSnapshot) {
        latest = snapshot
        connection.text = when (snapshot.availability) {
            HealthConnectAvailability.UNAVAILABLE -> "✕ Health Connect unavailable"
            HealthConnectAvailability.NEEDS_UPDATE -> "↻ Health Connect needs an update"
            HealthConnectAvailability.AVAILABLE -> if (snapshot.missingPermissions.isEmpty()) "✓ Connected to Health Connect" else "⚠ Health permissions needed"
        }
        connectButton.text = when (snapshot.availability) {
            HealthConnectAvailability.UNAVAILABLE -> "Check Health Connect"
            HealthConnectAvailability.NEEDS_UPDATE -> "Open system settings"
            HealthConnectAvailability.AVAILABLE -> if (snapshot.missingPermissions.isEmpty()) "Manage permissions" else "Grant write permissions"
        }
        dateButton.text = "${snapshot.date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))}  ·  Change"
        sessions.removeAllViews()
        snapshot.sessions.forEach { session ->
            val row = column().apply { setPadding(0, dp(12), 0, dp(12)) }
            val name = if (session.plan.activity == ActivityKind.MEDITATE) "Meditation" else session.plan.activity.label
            row.addView(label(name, MaterialR.style.TextAppearance_Material3_TitleMedium, MaterialR.attr.colorOnSurface))
            val time = DateTimeFormatter.ofPattern("HH:mm")
            row.addView(label("${session.plan.start.format(time)}–${session.plan.end.format(time)}  ·  ${session.plan.activity.metrics}", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant).spaced(3))
            val state = when (session.state) {
                SessionState.WAITING -> "Waiting for end time"
                SessionState.READY -> "Ready to generate"
                SessionState.PENDING -> "Pending retry"
                SessionState.WRITTEN -> "Saved to Health Connect"
                SessionState.ERROR -> "Needs attention"
                SessionState.DELETED -> "Deleted"
            }
            row.addView(label(state, MaterialR.style.TextAppearance_Material3_LabelMedium, if (session.state == SessionState.ERROR) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary).spaced(4))
            if (session.mindfulnessFallback) row.addView(label("Uses Other workout on this device", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant).spaced(3))
            if (session.state == SessionState.ERROR || session.state == SessionState.PENDING) {
                row.addView(label(session.detail, MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant).spaced(3))
            }
            sessions.addView(row)
            sessions.addView(MaterialDivider(this), LinearLayout.LayoutParams(-1, -2))
        }
        updatingSwitch = true
        backgroundSwitch.isChecked = snapshot.settings.backgroundEnabled
        updatingSwitch = false
        scheduleDetail.text = "Checks every ${snapshot.settings.intervalMinutes} minutes · ${snapshot.settings.zoneId}\nCompleted sessions catch up from enabling, within the last 30 calendar days. Paused dates are not backfilled. Android may delay runs; reopen the app after force-stopping it."
        renderBackgroundStatus(snapshot)
        renderBatteryStatus()
        when {
            snapshot.statusMessage != null -> showStatusMessage(snapshot.statusMessage)
            snapshot.lastRun != null -> displayLastResult(snapshot.lastRun)
            else -> showStatusMessage("No activities generated yet.")
        }
        if (snapshot.backgroundSuppressedForDate) lastResultDetails.addView(label(
            "⏸ This day is excluded after deletion. Generate manually to restore it.",
            MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant,
        ).spaced(12))
        setControlsEnabled(!busy)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val ready = latest?.let { it.availability == HealthConnectAvailability.AVAILABLE && it.missingPermissions.isEmpty() } == true
        connectButton.isEnabled = enabled
        dateButton.isEnabled = enabled
        settingsButton.isEnabled = enabled
        batteryButton.isEnabled = enabled
        generateButton.isEnabled = enabled && ready
        deleteButton.isEnabled = enabled && ready
        backgroundSwitch.isEnabled = enabled && (ready || backgroundSwitch.isChecked)
    }

    private fun perform(message: String, onSuccess: (() -> Unit)? = null, action: suspend () -> String?) {
        if (busy) return
        busy = true
        refreshJob?.cancel()
        setControlsEnabled(false)
        showStatusMessage(message)
        lifecycleScope.launch {
            var result: String? = null
            var succeeded = false
            try {
                result = action()
                succeeded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                result = error.message ?: "The operation failed. Please try again."
            } finally {
                busy = false
            }
            try {
                render(repository.snapshot(displayDate()))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                setControlsEnabled(true)
            }
            if (result != null) showStatusMessage(result)
            if (succeeded) onSuccess?.invoke()
        }
    }

    private fun renderBackgroundStatus(snapshot: GeneratorSnapshot) {
        val zone = ZoneId.of(snapshot.settings.zoneId)
        fun time(value: Instant): String = value.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
        backgroundStatus.text = buildString {
            append(when (snapshot.backgroundWork.state) {
                BackgroundWorkState.PAUSED -> "Schedule paused"
                BackgroundWorkState.ENQUEUED -> "Scheduled with Android"
                BackgroundWorkState.RUNNING -> "Automatic generation running"
                BackgroundWorkState.BLOCKED -> "Schedule waiting on Android"
                BackgroundWorkState.MISSING -> "Schedule missing — reopen the app to restore it"
                BackgroundWorkState.ERROR -> "Schedule needs attention"
            })
            snapshot.backgroundWork.nextEligibleAt?.let {
                append("\nEarliest next run: ${time(it)}; Android may run it later.")
            }
            snapshot.backgroundWork.detail?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
            if (snapshot.settings.backgroundEnabled) {
                snapshot.backgroundPendingSince?.let {
                    append("\nChecking incomplete days from ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}.")
                }
            }
        }
        backgroundResult.removeAllViews()
        val attempt = snapshot.lastBackgroundAttemptAt
        val result = snapshot.lastBackgroundRun
        backgroundResult.addView(label(
            attempt?.let { "Last automatic attempt · ${time(it)}" } ?: "No automatic attempt yet.",
            MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant,
        ))
        if (result != null) {
            backgroundResult.addView(label(
                presentRunResult(result).title,
                MaterialR.style.TextAppearance_Material3_TitleSmall, MaterialR.attr.colorOnSurface,
            ).spaced(12))
            backgroundResult.addView(resultDetails(result, automatic = true).spaced(4))
        }
        if (attempt != null && (result == null || attempt.isAfter(result.completedAt))) {
            backgroundResult.addView(label(
                if (snapshot.backgroundWork.state == BackgroundWorkState.RUNNING) {
                    "◷ This attempt is still running."
                } else {
                    "⚠ No result saved for this attempt. Android may have interrupted it."
                },
                MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant,
            ).spaced(8))
        }
    }

    private fun showStatusMessage(message: String) {
        status.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
        status.text = message
        lastResultDetails.removeAllViews()
    }

    private fun displayLastResult(result: RunResult) {
        status.setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleMedium)
        status.text = presentRunResult(result).title
        lastResultDetails.removeAllViews()
        lastResultDetails.addView(resultDetails(result, automatic = result == latest?.lastBackgroundRun))
    }

    private fun resultDetails(result: RunResult, automatic: Boolean = false) = column().apply {
        val presentation = presentRunResult(result)
        val zone = ZoneId.of(latest?.settings?.zoneId ?: repository.settings().zoneId)
        val time = result.completedAt.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm"))
        if (!automatic) {
            val activityDate = result.date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
            addView(label("Activity date · $activityDate", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant))
        }
        addView(label("Completed · $time", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant))
        if (presentation.showSessionCounts) {
            addView(resultCount("✓ Newly saved", result.writtenSessions).spaced(12))
            addView(resultCount("✓ Already saved", result.alreadyWrittenSessions).spaced(4))
            addView(resultCount("◷ Not finished yet", result.waitingSessions).spaced(4))
        }
        presentation.detail?.let {
            addView(label(it, MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurface).spaced(12))
        }
    }

    private fun resultCount(title: String, count: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurfaceVariant),
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(count.toString(), MaterialR.style.TextAppearance_Material3_TitleLarge, MaterialR.attr.colorOnSurface).apply {
            setPadding(dp(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2))
    }

    private data class BatteryStatus(val restricted: Boolean, val optimizationExempt: Boolean)

    private fun batteryStatus(): BatteryStatus = BatteryStatus(
        restricted = getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true,
        optimizationExempt = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true,
    )

    private fun renderBatteryStatus() {
        val battery = batteryStatus()
        batteryDetail.text = when {
            battery.restricted -> "Background battery use is restricted. Allow background use in app settings so Android can run the schedule."
            battery.optimizationExempt -> "Battery optimization exemption is enabled. Android can still delay background runs."
            else -> "Battery optimization is enabled and may delay automatic runs. Tap Allow background activity to request an exemption."
        }
        batteryButton.text = if (battery.optimizationExempt) "Review battery settings" else "Allow background activity"
    }

    private fun suggestBatterySettings() {
        if (!repository.settings().backgroundEnabled) return
        val battery = batteryStatus()
        if (!battery.optimizationExempt) {
            requestBatteryExemption()
            return
        }
        if (!battery.restricted) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Review battery settings?")
            .setMessage("Automatic generation is enabled, but Android restricts this app’s background battery use. In app settings, open Battery and allow background use. Scheduled times can still be delayed.")
            .setNegativeButton("Continue", null)
            .setPositiveButton("Review settings") { _, _ -> openBatterySettings() }
            .show()
    }

    // Personal sideload build: the user explicitly requests an exemption for automatic generation.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (batteryStatus().optimizationExempt) {
            openBatterySettings()
            return
        }
        try {
            batteryExemptionLauncher.launch(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            ))
        } catch (_: ActivityNotFoundException) {
            openBatterySettings()
        } catch (_: SecurityException) {
            openBatterySettings()
        }
    }

    private fun openBatterySettings() {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        val batterySettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val destinations = if (batteryStatus().restricted) listOf(appDetails, batterySettings) else listOf(batterySettings, appDetails)
        for (destination in destinations + Intent(Settings.ACTION_SETTINGS)) {
            try {
                startActivity(destination)
                return
            } catch (_: ActivityNotFoundException) {
                // Some phone manufacturers omit individual settings screens.
            } catch (_: SecurityException) {
                // Fall back to an accessible settings screen.
            }
        }
        showStatusMessage("Open Settings → Apps → Activity Gen → Battery and allow background use.")
    }

    private fun connect() {
        if (latest?.availability == HealthConnectAvailability.AVAILABLE) {
            if (latest?.missingPermissions?.isEmpty() == true) {
                openHealthConnect()
            } else {
                try {
                    permissionLauncher.launch(repository.requiredPermissions())
                } catch (error: Exception) {
                    showStatusMessage("Could not open permissions: ${error.message}")
                }
            }
        } else openHealthConnect()
    }

    private fun openHealthConnect() {
        try {
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            openSystemSettings()
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            showStatusMessage("Search Settings for Health Connect. Check for a Google Play system update if it is unavailable.")
        } catch (_: ActivityNotFoundException) {
            showStatusMessage("Open Settings and search for Health Connect. Update your phone’s Google Play system if needed.")
        }
    }

    private fun chooseDate() {
        if (supportFragmentManager.findFragmentByTag("activity-date") != null) return
        val today = repository.today()
        val first = today.minusDays(29)
        // MaterialDatePicker represents calendar dates at UTC midnight, independent of the schedule zone.
        fun utcDate(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val selection = utcDate(displayDate().coerceIn(first, today))
        val constraints = CalendarConstraints.Builder()
            .setStart(utcDate(first))
            .setEnd(utcDate(today))
            .setOpenAt(selection)
            .setValidator(CompositeDateValidator.allOf(listOf(
                DateValidatorPointForward.from(utcDate(first)),
                DateValidatorPointBackward.before(utcDate(today)),
            )))
            .build()
        MaterialDatePicker.Builder.datePicker()
            .setTitleText("Choose activity date")
            .setSelection(selection)
            .setCalendarConstraints(constraints)
            .build().apply {
                addOnPositiveButtonClickListener(::selectDate)
                show(supportFragmentManager, "activity-date")
            }
    }

    private fun selectDate(utcMillis: Long) {
        val chosen = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        selectedDate = if (chosen == repository.today()) null else chosen
        refresh()
    }

    private fun editSettings() {
        val settings = repository.settings()
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val zoneField = TextInputLayout(this).apply { hint = "Time zone" }
        val zoneInput = MaterialAutoCompleteTextView(zoneField.context).apply {
            setText(settings.zoneId)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, ZoneId.getAvailableZoneIds().sorted()))
            threshold = 1
        }
        zoneField.addView(zoneInput, LinearLayout.LayoutParams(-1, -2))
        form.addView(zoneField)
        val intervalField = TextInputLayout(this).apply {
            hint = "Check interval (minutes)"
            helperText = "15–1,440 minutes"
        }
        val intervalInput = TextInputEditText(intervalField.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(settings.intervalMinutes.toString())
        }
        intervalField.addView(intervalInput, LinearLayout.LayoutParams(-1, -2))
        form.addView(intervalField.spaced(16))
        form.addView(label("New dates use these settings. Already saved sessions keep their original times.", MaterialR.style.TextAppearance_Material3_BodySmall, MaterialR.attr.colorOnSurfaceVariant).spaced(12))
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Schedule settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val zone = zoneInput.text.toString().trim()
                try {
                    ZoneId.of(zone)
                } catch (_: Exception) {
                    zoneField.error = "Use a time zone such as Australia/Sydney"
                    return@setOnClickListener
                }
                zoneField.error = null
                val minutes = intervalInput.text.toString().toLongOrNull()
                if (minutes == null || minutes !in 15..1440) {
                    intervalField.error = "Enter 15 to 1,440 minutes"
                    return@setOnClickListener
                }
                intervalField.error = null
                dialog.dismiss()
                perform("Saving settings…") {
                    repository.saveSettings(settings.copy(zoneId = zone, intervalMinutes = minutes))
                    "Schedule settings saved."
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete() {
        val day = displayDate()
        MaterialAlertDialogBuilder(this).setTitle("Delete generated records?")
            .setMessage("Delete this app’s exercise, steps, distance and mindfulness records for $day? Automatic generation will be paused. Other apps’ records are unaffected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                perform("Deleting generated records…") {
                    displayLastResult(repository.deleteGeneratedDay(day))
                    null
                }
            }.show()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun cardContent() = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
    private fun card(body: View) = MaterialCardView(this).apply {
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }
    private fun label(value: String, appearance: Int, colorAttribute: Int) = MaterialTextView(this).apply {
        text = value
        setTextAppearance(appearance)
        setTextColor(MaterialColors.getColor(this, colorAttribute))
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = MaterialButton(
        this, null,
        if (primary) MaterialR.attr.materialButtonStyle else MaterialR.attr.materialButtonTonalStyle,
    ).apply {
        text = value
        setOnClickListener { action() }
    }
    private fun <T : View> T.spaced(top: Int): T = apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
