package me.calvin.healthconnectgenerator

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.R as MaterialR

/** Also opened by Health Connect's APK rationale or platform permission usage intent. */
class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pad = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(MaterialTextView(this@PrivacyActivity).apply {
                text = "Privacy & permissions"
                setTextAppearance(MaterialR.style.TextAppearance_Material3_HeadlineMedium)
            })
            paragraph("Activity Gen saves the six daily presets directly to Health Connect on this phone. All records use actively recorded metadata with the phone device type. Values are generated presets, not sensor measurements.", pad)
            section("Permissions", pad)
            paragraph("Exercise writes swimming, yoga, running, CrossFit (HIIT), cycling and the meditation fallback. Steps writes 20,000 steps during the running session. Distance writes 2,000 metres during swimming. Mindfulness writes meditation on devices that support it.", pad / 3)
            paragraph("When you enable automatic generation, the app can request a battery optimization exemption through Android’s system dialog. You decide whether to allow it.", pad / 2)
            section("Local storage", pad)
            paragraph("The app stores your time zone, schedule, catch-up progress, generated session timestamps, record identifiers and the last manual and automatic results on this phone. When deleting records, it looks up only its own records to handle entries already removed in Health Connect. It does not request access to read other apps’ health records. The displayed saved status is a local receipt, not a readback from Health Connect.", pad / 3)
            section("Network and sharing", pad)
            paragraph("No account, server, analytics or internet permission is used. Other apps can read the generated Health Connect data only if you separately grant them access. Their handling of generated records is outside this app’s control.", pad / 3)
            section("Your controls", pad)
            paragraph("Automatic generation starts only when you enable it. You can pause it here or revoke permissions in Health Connect. Delete a day’s generated records from the main screen; this also pauses the schedule. You can delete all data from this app in Health Connect. Uninstalling or clearing this app’s storage may leave its Health Connect records in place.", pad / 3)
            addView(MaterialButton(this@PrivacyActivity).apply {
                text = "Close"
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad }
            })
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            setBackgroundColor(MaterialColors.getColor(this, MaterialR.attr.colorSurface))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun LinearLayout.section(value: String, topMargin: Int) {
        addText(value, MaterialR.style.TextAppearance_Material3_TitleMedium, topMargin)
    }

    private fun LinearLayout.paragraph(value: String, topMargin: Int) {
        addText(value, MaterialR.style.TextAppearance_Material3_BodyLarge, topMargin)
    }

    private fun LinearLayout.addText(value: String, appearance: Int, topMargin: Int) {
        addView(MaterialTextView(this@PrivacyActivity).apply {
            text = value
            setTextAppearance(appearance)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { this.topMargin = topMargin }
        })
    }
}
