package io.github.hashirakb.penumbra.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.hashirakb.penumbra.core.BrushPresets
import io.github.hashirakb.penumbra.core.BrushSpec
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.StrokeKind
import io.github.hashirakb.penumbra.core.SymmetryConfig
import io.github.hashirakb.penumbra.core.SymmetryMode
import io.github.hashirakb.penumbra.engine.Document
import io.github.hashirakb.penumbra.engine.Engine
import io.github.hashirakb.penumbra.engine.History
import io.github.hashirakb.penumbra.engine.UndoEntry
import java.util.concurrent.Executors

enum class Tool { BRUSH, ERASER, SMUDGE, FILL, EYEDROPPER, TRANSFORM }

enum class FingerMode(val label: String) {
    AUTO("Automatic (pen draws, fingers navigate once a pen is seen)"),
    DRAW("Fingers always draw"),
    NAVIGATE("Fingers only pan & zoom"),
}

enum class PenButtonAction(val label: String) {
    ERASER("Erase while held"),
    EYEDROPPER("Pick color while held"),
    NONE("Do nothing"),
}

class ToolSettings(var brush: BrushSpec, var size: Float, var opacity: Float)

/** User preferences, persisted across sessions. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("penumbra", Context.MODE_PRIVATE)

    var fingerMode: FingerMode
        get() = FingerMode.values().getOrElse(prefs.getInt("fingerMode", 0)) { FingerMode.AUTO }
        set(v) = prefs.edit().putInt("fingerMode", v.ordinal).apply()
    var penButton: PenButtonAction
        get() = PenButtonAction.values().getOrElse(prefs.getInt("penButton", 0)) { PenButtonAction.ERASER }
        set(v) = prefs.edit().putInt("penButton", v.ordinal).apply()
    /** 0 = soft, 1 = linear, 2 = firm. */
    var pressureCurve: Int
        get() = prefs.getInt("pressureCurve", 1)
        set(v) = prefs.edit().putInt("pressureCurve", v).apply()
    val pressureGamma: Float get() = when (pressureCurve) { 0 -> 0.6f; 2 -> 1.6f; else -> 1f }
    var fingerSpeedPressure: Boolean
        get() = prefs.getBoolean("fingerSpeedPressure", true)
        set(v) = prefs.edit().putBoolean("fingerSpeedPressure", v).apply()
    var quickShape: Boolean
        get() = prefs.getBoolean("quickShape", true)
        set(v) = prefs.edit().putBoolean("quickShape", v).apply()
    var tapGestures: Boolean
        get() = prefs.getBoolean("tapGestures", true)
        set(v) = prefs.edit().putBoolean("tapGestures", v).apply()
    var leftHanded: Boolean
        get() = prefs.getBoolean("leftHanded", false)
        set(v) = prefs.edit().putBoolean("leftHanded", v).apply()
    var stylusSeen: Boolean
        get() = prefs.getBoolean("stylusSeen", false)
        set(v) = prefs.edit().putBoolean("stylusSeen", v).apply()
    var fillGap: Int
        get() = prefs.getInt("fillGap", 3)
        set(v) = prefs.edit().putInt("fillGap", v).apply()
    var fillTolerance: Int
        get() = prefs.getInt("fillTolerance", 48)
        set(v) = prefs.edit().putInt("fillTolerance", v).apply()
    var fillSampleAll: Boolean
        get() = prefs.getBoolean("fillSampleAll", true)
        set(v) = prefs.edit().putBoolean("fillSampleAll", v).apply()
    var stabilizerBoost: Float
        get() = prefs.getFloat("stabilizerBoost", 0f)
        set(v) = prefs.edit().putFloat("stabilizerBoost", v).apply()
    var recentColors: IntArray
        get() = prefs.getString("recentColors", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }.toIntArray()
        set(v) = prefs.edit().putString("recentColors", v.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
    var lastColor: Int
        get() = prefs.getInt("lastColor", 0xFF1B1B1F.toInt())
        set(v) = prefs.edit().putInt("lastColor", v).apply()
}

/** Everything the editor is working with: the document, its history, tools and color. */
class EditorSession(context: Context, val doc: Document, val docId: String) {
    val settings = Settings(context)
    val engine = Engine()
    val history = History(doc)
    val main = Handler(Looper.getMainLooper())
    val worker = Executors.newSingleThreadExecutor()

    var tool = Tool.BRUSH
    val brushTool = ToolSettings(BrushPresets.INK, BrushPresets.INK.defaultSize, 1f)
    val eraserTool = ToolSettings(BrushPresets.HARD_ERASER, BrushPresets.HARD_ERASER.defaultSize, 1f)
    val smudgeTool = ToolSettings(BrushPresets.BLENDER, BrushPresets.BLENDER.defaultSize, 0.6f)
    var color: Int = settings.lastColor
    var symmetry = SymmetryConfig(SymmetryMode.OFF, 8, doc.width / 2f, doc.height / 2f)
    var busy = false
        private set

    var listener: (() -> Unit)? = null

    /** The size/opacity/brush the current tool uses (fill and eyedropper borrow the brush). */
    val current: ToolSettings
        get() = when (tool) {
            Tool.ERASER -> eraserTool
            Tool.SMUDGE -> smudgeTool
            else -> brushTool
        }

    fun strokeKind(t: Tool = tool) = when (t) {
        Tool.ERASER -> StrokeKind.ERASE
        Tool.SMUDGE -> StrokeKind.SMUDGE
        else -> StrokeKind.PAINT
    }

    fun setColor(c: Int, remember: Boolean = true) {
        color = c or 0xFF000000.toInt()
        settings.lastColor = color
        if (remember) {
            val recent = settings.recentColors.toMutableList()
            recent.remove(color)
            recent.add(0, color)
            settings.recentColors = recent.take(16).toIntArray()
        }
        listener?.invoke()
    }

    fun record(entry: UndoEntry?, event: JEvent) {
        if (entry != null) history.push(entry, event)
    }

    /** Applies a structural/one-shot event and records it. */
    fun apply(event: JEvent): Boolean {
        val entry = engine.apply(doc, event) ?: return false
        history.push(entry, event)
        return true
    }

    /** Runs a fill off the main thread, then commits it. */
    fun fill(event: JEvent.Fill, done: (Boolean) -> Unit) {
        if (busy) return
        busy = true
        val px = engine.fillSource(doc, event)
        worker.execute {
            val result = try { engine.computeFill(px, doc, event) } catch (t: OutOfMemoryError) { null }
            main.post {
                busy = false
                val entry = result?.let { engine.commitFill(doc, event, it) }
                if (entry != null) history.push(entry, event)
                done(entry != null)
            }
        }
    }

    fun runInBackground(work: () -> Unit, then: () -> Unit) {
        busy = true
        worker.execute {
            try { work() } finally { main.post { busy = false; then() } }
        }
    }

    fun close() { worker.shutdown() }
}
