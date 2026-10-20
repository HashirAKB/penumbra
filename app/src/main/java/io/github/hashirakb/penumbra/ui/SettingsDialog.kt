package io.github.hashirakb.penumbra.ui

import android.app.Activity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView

object SettingsDialog {
    fun show(activity: Activity, s: Settings, onChanged: () -> Unit) {
        val ctx = activity
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun header(t: String) = box.addView(Theme.text(ctx, t, 13f, Theme.ACCENT, bold = true).apply {
            setPadding(0, Theme.dpi(ctx, 14f), 0, Theme.dpi(ctx, 4f))
        })

        fun <T> choice(options: Array<T>, label: (T) -> String, selected: T, set: (T) -> Unit) {
            val g = RadioGroup(ctx)
            for ((i, o) in options.withIndex()) {
                g.addView(RadioButton(ctx).apply {
                    id = i + 1; text = label(o); setTextColor(Theme.ON_SURFACE); isChecked = o == selected
                })
            }
            g.setOnCheckedChangeListener { _, id -> set(options[(id - 1).coerceIn(0, options.size - 1)]) }
            box.addView(g)
        }

        fun check(label: String, value: Boolean, set: (Boolean) -> Unit) {
            box.addView(CheckBox(ctx).apply {
                text = label; setTextColor(Theme.ON_SURFACE); isChecked = value
                setOnCheckedChangeListener { _, c -> set(c) }
            })
        }

        header("Fingers")
        choice(FingerMode.values(), { it.label }, s.fingerMode) { s.fingerMode = it }
        check("Simulate pressure from finger speed", s.fingerSpeedPressure) { s.fingerSpeedPressure = it }

        header("Pen")
        box.addView(Theme.text(ctx, "Side button", 14f, Theme.MUTED))
        choice(PenButtonAction.values(), { it.label }, s.penButton) { s.penButton = it }
        box.addView(Theme.text(ctx, "Pressure response", 14f, Theme.MUTED).apply { setPadding(0, Theme.dpi(ctx, 8f), 0, 0) })
        choice(arrayOf(0, 1, 2), { arrayOf("Soft (light touch goes dark fast)", "Linear", "Firm (press harder for dark lines)")[it] },
            s.pressureCurve) { s.pressureCurve = it }

        header("Drawing")
        check("Hold at the end of a stroke to snap lines and circles", s.quickShape) { s.quickShape = it }
        check("Two-finger tap to undo, three-finger tap to redo", s.tapGestures) { s.tapGestures = it }
        check("Sliders on the right (left-handed)", s.leftHanded) { s.leftHanded = it }

        val scroll = ScrollView(ctx).apply { addView(box) }
        dialogBuilder(ctx).setTitle("Settings").setView(padded(scroll))
            .setPositiveButton("Done") { _, _ -> onChanged() }
            .setOnCancelListener { onChanged() }
            .show()
    }
}
