package io.github.hashirakb.penumbra.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.hashirakb.penumbra.core.ColoringPages
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.LineArt
import io.github.hashirakb.penumbra.core.Lessons
import io.github.hashirakb.penumbra.core.StrokeKind
import io.github.hashirakb.penumbra.core.SymmetryMode
import io.github.hashirakb.penumbra.engine.Compositor
import io.github.hashirakb.penumbra.engine.Document
import io.github.hashirakb.penumbra.engine.ReplayPlayer
import io.github.hashirakb.penumbra.engine.Timelapse
import io.github.hashirakb.penumbra.io.Exporter
import io.github.hashirakb.penumbra.io.OraIO
import io.github.hashirakb.penumbra.io.Storage
import java.io.File
import java.util.concurrent.Executors

class EditorActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ID = "id"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_STYLE = "style"
        const val EXTRA_SEED = "seed"
        const val EXTRA_URI = "uri"
        const val EXTRA_LESSON = "lesson"
        const val EXTRA_SOURCE = "source"

        const val MODE_OPEN = "open"
        const val MODE_NEW = "new"
        const val MODE_COLORING = "coloring"
        const val MODE_PHOTO = "photo"
        const val MODE_IMPORT = "import"
        const val MODE_LESSON = "lesson"
        const val MODE_TRACE = "trace"

        private const val REQ_PHOTO_LAYER = 11
        private const val REQ_EXPORT_ORA = 12
    }

    private lateinit var storage: Storage
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private var session: EditorSession? = null
    private var canvas: CanvasView? = null
    private var docId = ""
    private var title = "Untitled"
    private val saveCache = OraIO.Cache()
    private var savedRevision = 0
    private var saving = false
    private var saveAgain = false

    // UI
    private lateinit var topBar: LinearLayout
    private lateinit var sideBar: LinearLayout
    private lateinit var hud: TextView
    private val toolButtons = HashMap<Tool, IconButton>()
    private lateinit var swatch: ColorSwatch
    private lateinit var sizeSlider: VerticalSlider
    private lateinit var opacitySlider: VerticalSlider
    private lateinit var undoBtn: IconButton
    private lateinit var redoBtn: IconButton
    private var bottomBar: View? = null
    private var popup: PopupWindow? = null
    private var insetTop = 0
    private var insetBottom = 0

    // Trace-along
    private var trace: Trace? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        setContentView(root)
        window.statusBarColor = 0
        window.navigationBarColor = 0
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 30) root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        val progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        load(progress)
    }

    // =========================================================== loading

    private fun load(progress: View) {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_NEW
        loader.execute {
            var traceSource: Document? = null
            var traceTitle = ""
            var openPhotoOptions = false
            val result: Result<Document> = runCatching {
                when (mode) {
                    MODE_OPEN -> {
                        docId = intent.getStringExtra(EXTRA_ID)!!
                        val loaded = storage.fileFor(docId).inputStream().buffered().use { OraIO.read(it) }
                        title = storage.titleOf(docId)
                        loaded.doc
                    }
                    MODE_COLORING -> {
                        docId = storage.newId()
                        val style = ColoringPages.Style.values()[intent.getIntExtra(EXTRA_STYLE, 0)]
                        title = style.label
                        DocFactory.coloring(style, intent.getLongExtra(EXTRA_SEED, 1L))
                    }
                    MODE_PHOTO -> {
                        docId = storage.newId()
                        title = "Photo drawing"
                        val img = DocFactory.decode(this, Uri.parse(intent.getStringExtra(EXTRA_URI)))
                            ?: throw IllegalStateException("Couldn't open that photo")
                        openPhotoOptions = true
                        DocFactory.fromImage(img, "Photo", drawingLayer = true)
                    }
                    MODE_IMPORT -> {
                        docId = storage.newId()
                        val uri = Uri.parse(intent.getStringExtra(EXTRA_URI))
                        val isOra = contentResolver.getType(uri) == OraIO.MIME || isZip(uri)
                        if (isOra) {
                            val loaded = contentResolver.openInputStream(uri)!!.buffered().use { OraIO.read(it) }
                            title = loaded.title ?: "Imported"
                            loaded.doc
                        } else {
                            title = "Imported image"
                            val img = DocFactory.decode(this, uri, 4096) ?: throw IllegalStateException("Couldn't open that image")
                            DocFactory.fromImage(img, "Image", drawingLayer = true, maxSide = 4096)
                        }
                    }
                    MODE_LESSON -> {
                        docId = storage.newId()
                        val kind = Lessons.Kind.values()[intent.getIntExtra(EXTRA_LESSON, 0)]
                        val seed = intent.getLongExtra(EXTRA_SEED, 1L)
                        title = kind.title
                        traceTitle = kind.title
                        traceSource = DocFactory.lesson(kind, seed)
                        DocFactory.blank(traceSource!!.width, traceSource!!.height)
                    }
                    MODE_TRACE -> {
                        docId = storage.newId()
                        val src = intent.getStringExtra(EXTRA_SOURCE)!!
                        traceSource = storage.fileFor(src).inputStream().buffered().use { OraIO.read(it) }.doc
                        traceTitle = storage.titleOf(src)
                        title = "Tracing: $traceTitle"
                        DocFactory.blank(traceSource!!.width, traceSource!!.height)
                    }
                    else -> {
                        docId = storage.newId()
                        val w = intent.getIntExtra(EXTRA_W, 2048)
                        val h = intent.getIntExtra(EXTRA_H, 2048)
                        DocFactory.blank(w, h)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                root.removeView(progress)
                result.onSuccess { doc ->
                    setup(doc)
                    if (mode == MODE_COLORING) {
                        selectTool(Tool.FILL)
                        hudShow("Tap a shape to fill it with color")
                    }
                    traceSource?.let { startTrace(it, traceTitle) }
                    if (openPhotoOptions) photoOptions()
                }.onFailure { t ->
                    val msg = if (t is OutOfMemoryError) "Not enough memory to open this drawing" else (t.message ?: "Couldn't open this drawing")
                    dialogBuilder(this).setTitle("Can't open").setMessage(msg)
                        .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                }
            }
        }
    }

    /** OpenRaster files are zip archives; images never start with "PK". */
    private fun isZip(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4
        } ?: false
    } catch (t: Throwable) { false }

    // =========================================================== setup

    private fun setup(doc: Document) {
        val s = EditorSession(this, doc, docId)
        session = s
        savedRevision = if (intent.getStringExtra(EXTRA_MODE) == MODE_OPEN) doc.revision else -1
        // A brand-new untouched document isn't worth saving; anything else is.
        if (intent.getStringExtra(EXTRA_MODE) == MODE_NEW || intent.getStringExtra(EXTRA_MODE) == MODE_LESSON ||
            intent.getStringExtra(EXTRA_MODE) == MODE_TRACE || intent.getStringExtra(EXTRA_MODE) == MODE_COLORING
        ) savedRevision = doc.revision

        val cv = CanvasView(this, s)
        canvas = cv
        root.addView(cv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cv.onHud = { hudShow(it) }
        cv.onColorPicked = { c -> s.setColor(c); syncUi(); if (s.tool == Tool.EYEDROPPER) selectTool(Tool.BRUSH) }
        cv.onStylusDetected = {
            hudShow("Pen detected — fingers now pan and zoom", 2600)
        }
        cv.onStrokeFinished = {
            syncUi()
            trace?.onUserStroke()
            scheduleSave()
        }
        cv.onUndoRedo = { syncUi(); scheduleSave() }
        s.history.onChange = { syncUndo() }
        s.listener = { syncUi() }

        buildTopBar()
        buildSideBar()
        hud = TextView(this).apply {
            setTextColor(Theme.ON_SURFACE); textSize = 14f
            background = Theme.pill(this@EditorActivity, 0xE6222228.toInt())
            setPadding(Theme.dpi(context, 14f), Theme.dpi(context, 6f), Theme.dpi(context, 14f), Theme.dpi(context, 6f))
            alpha = 0f
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = bars.top; insetBottom = bars.bottom
                topBar.setPadding(bars.left + Theme.dpi(this, 6f), bars.top + Theme.dpi(this, 4f), bars.right + Theme.dpi(this, 6f), 0)
            } else {
                @Suppress("DEPRECATION")
                insetTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetBottom = insets.systemWindowInsetBottom
                topBar.setPadding(Theme.dpi(this, 6f), insetTop + Theme.dpi(this, 4f), Theme.dpi(this, 6f), 0)
            }
            (hud.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop + Theme.dpi(this, 60f)
            bottomBar?.let { (it.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + Theme.dpi(this, 16f) }
            root.requestLayout()
            insets
        }
        root.requestApplyInsets()
        syncUi()
        syncUndo()
    }

    private fun buildTopBar() {
        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = Theme.panel(this@EditorActivity, 24f) }
        left.addView(IconButton(this, Icon.BACK, "Gallery").apply { setOnClickListener { leave() } })
        left.addView(IconButton(this, Icon.MORE, "Menu").apply { setOnClickListener { showMenu(this) } })
        topBar.addView(left)
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = Theme.panel(this@EditorActivity, 24f) }
        fun tool(t: Tool, icon: Icon, desc: String) = IconButton(this, icon, desc).also { b ->
            toolButtons[t] = b
            b.setOnClickListener {
                val s = session ?: return@setOnClickListener
                if (s.tool == t && (t == Tool.BRUSH || t == Tool.ERASER || t == Tool.SMUDGE)) showBrushPanel(b, t)
                else if (s.tool == t && t == Tool.FILL) fillSettings()
                else selectTool(t)
            }
            right.addView(b)
        }
        tool(Tool.BRUSH, Icon.BRUSH, "Brush (tap again for brushes)")
        tool(Tool.SMUDGE, Icon.SMUDGE, "Smudge")
        tool(Tool.ERASER, Icon.ERASER, "Eraser")
        tool(Tool.FILL, Icon.FILL, "Fill (tap again for settings)")
        right.addView(IconButton(this, Icon.LAYERS, "Layers").apply { setOnClickListener { showLayers(this) } })
        swatch = ColorSwatch(this).apply { setOnClickListener { showColor(this) } }
        right.addView(swatch)
        topBar.addView(right)
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun buildSideBar() {
        val s = session!!
        sideBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.panel(this@EditorActivity, 24f)
            setPadding(0, Theme.dpi(context, 6f), 0, Theme.dpi(context, 6f))
        }
        sizeSlider = VerticalSlider(this, 0.5f, 400f, true, "Brush size")
        sizeSlider.onChange = { v, _ ->
            s.current.size = v.coerceAtMost(s.current.brush.maxSize)
            hudShow("Size ${formatSize(s.current.size)}")
        }
        opacitySlider = VerticalSlider(this, 0.02f, 1f, false, "Opacity")
        opacitySlider.onChange = { v, _ ->
            s.current.opacity = v
            hudShow((if (s.tool == Tool.SMUDGE) "Strength " else "Opacity ") + "${(v * 100).toInt()}%")
        }
        sideBar.addView(sizeSlider)
        sideBar.addView(IconButton(this, Icon.EYEDROPPER, "Eyedropper").also { b ->
            toolButtons[Tool.EYEDROPPER] = b
            b.setOnClickListener { selectTool(if (s.tool == Tool.EYEDROPPER) Tool.BRUSH else Tool.EYEDROPPER) }
        })
        sideBar.addView(opacitySlider)
        undoBtn = IconButton(this, Icon.UNDO, "Undo").apply { setOnClickListener { undo() } }
        redoBtn = IconButton(this, Icon.REDO, "Redo").apply { setOnClickListener { redo() } }
        sideBar.addView(undoBtn)
        sideBar.addView(redoBtn)
        val gravity = (if (s.settings.leftHanded) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        root.addView(sideBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity).apply {
            marginStart = Theme.dpi(this@EditorActivity, 8f); marginEnd = Theme.dpi(this@EditorActivity, 8f)
        })
    }

    private fun formatSize(v: Float) = if (v < 10f) String.format("%.1f px", v) else "${v.toInt()} px"

    private fun selectTool(t: Tool) {
        val s = session ?: return
        if (s.tool == Tool.TRANSFORM && t != Tool.TRANSFORM) canvas?.endTransform()
        s.tool = t
        syncUi()
        hudShow(when (t) {
            Tool.BRUSH -> s.brushTool.brush.name
            Tool.ERASER -> "Eraser · " + s.eraserTool.brush.name
            Tool.SMUDGE -> "Smudge"
            Tool.FILL -> "Fill"
            Tool.EYEDROPPER -> "Tap to pick a color"
            Tool.TRANSFORM -> "Drag to move · pinch to scale and rotate"
        })
    }

    private fun syncUi() {
        val s = session ?: return
        for ((t, b) in toolButtons) b.selectedState = s.tool == t
        swatch.color = s.color
        val cur = s.current
        sizeSlider.value = cur.size
        opacitySlider.value = cur.opacity
        sizeSlider.previewColor = if (s.tool == Tool.BRUSH) s.color else null
        val showSliders = s.tool == Tool.BRUSH || s.tool == Tool.ERASER || s.tool == Tool.SMUDGE
        sizeSlider.alpha = if (showSliders) 1f else 0.35f
        opacitySlider.alpha = if (showSliders) 1f else 0.35f
        canvas?.invalidate()
    }

    private fun syncUndo() {
        val s = session ?: return
        undoBtn.enabledLook = s.history.canUndo
        redoBtn.enabledLook = s.history.canRedo
    }

    private fun undo() { if (session?.history?.undo() == true) { canvas?.invalidate(); scheduleSave() } }
    private fun redo() { if (session?.history?.redo() == true) { canvas?.invalidate(); scheduleSave() } }

    private val hideHud = Runnable { hud.animate().alpha(0f).setDuration(250).start() }

    private fun hudShow(text: String, ms: Long = 1100) {
        if (!::hud.isInitialized) return
        if (text.isEmpty()) { hud.removeCallbacks(hideHud); hud.post(hideHud); return }
        hud.text = text
        hud.animate().cancel()
        hud.alpha = 1f
        hud.removeCallbacks(hideHud)
        hud.postDelayed(hideHud, ms)
    }

    // =========================================================== panels

    private fun showPopup(anchor: View, content: View, widthDp: Float, maxHeightDp: Float) {
        popup?.dismiss()
        val maxH = minOf(Theme.dpi(this, maxHeightDp), root.height - insetTop - Theme.dpi(this, 80f))
        content.measure(View.MeasureSpec.makeMeasureSpec(Theme.dpi(this, widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        val p = PopupWindow(content, Theme.dpi(this, widthDp), minOf(content.measuredHeight, maxH), true)
        p.elevation = Theme.dp(this, 12f)
        p.isOutsideTouchable = true
        p.setOnDismissListener { syncUi() }
        popup = p
        p.showAsDropDown(anchor, 0, Theme.dpi(this, 6f), Gravity.END)
    }

    private fun showBrushPanel(anchor: View, tool: Tool) {
        val s = session ?: return
        val panel = BrushPanel(this, s, tool) { popup?.dismiss(); syncUi(); hudShow(s.current.brush.name) }
        showPopup(anchor, panel, 320f, 520f)
    }

    private fun showColor(anchor: View) {
        val s = session ?: return
        val panel = ColorPanel(this, s, onEyedropper = { popup?.dismiss(); selectTool(Tool.EYEDROPPER) }) { swatch.color = it }
        showPopup(anchor, panel, 300f, 600f)
    }

    private fun showLayers(anchor: View) {
        val s = session ?: return
        val panel = LayersPanel(this, s) { canvas?.invalidate() }
        showPopup(anchor, panel, 300f, 520f)
    }

    // =========================================================== menu

    private fun showMenu(anchor: View) {
        val s = session ?: return
        val themed = ContextThemeWrapper(this, android.R.style.Theme_Material)
        val menu = PopupMenu(themed, anchor)
        val actions = LinkedHashMap<String, () -> Unit>()
        actions["Replay drawing"] = { saveNow { startActivity(Intent(this, ReplayActivity::class.java).putExtra(ReplayActivity.EXTRA_ID, docId)) } }
        actions["Timelapse video…"] = { timelapse() }
        actions["Save image to gallery"] = { exportImage(share = false) }
        actions["Share image…"] = { exportImage(share = true) }
        actions["Export .ora (Krita, GIMP)…"] = { exportOra() }
        actions["Add photo as a layer…"] = { pickPhotoLayer() }
        actions["Photo → line art…"] = { photoOptions() }
        actions["Symmetry…"] = { symmetryDialog() }
        actions["Move / scale layer"] = { startTransform() }
        actions["Fit to screen"] = { canvas?.fitToScreen() }
        actions["Reset rotation"] = { canvas?.resetRotation() }
        actions["Actual pixels (100%)"] = { canvas?.actualPixels() }
        actions["Rename drawing"] = { renameDialog() }
        actions["Settings"] = { SettingsDialog.show(this, s.settings) { rebuildSideBar() } }
        actions["Gestures & shortcuts"] = { helpDialog() }
        for ((i, k) in actions.keys.withIndex()) menu.menu.add(0, i, i, k)
        menu.setOnMenuItemClickListener { item -> actions.values.elementAt(item.itemId).invoke(); true }
        menu.show()
    }

    private fun rebuildSideBar() {
        root.removeView(sideBar)
        buildSideBar()
        syncUi(); syncUndo()
    }

    private fun renameDialog() {
        val input = android.widget.EditText(this).apply { setText(title); setSingleLine(); selectAll() }
        dialogBuilder(this).setTitle("Rename drawing").setView(padded(input))
            .setPositiveButton("OK") { _, _ ->
                title = input.text.toString().trim().ifEmpty { title }
                if (storage.fileFor(docId).exists()) storage.setTitle(docId, title)
                else savedRevision = -1
            }.setNegativeButton("Cancel", null).show()
    }

    private fun helpDialog() {
        dialogBuilder(this).setTitle("Gestures & shortcuts").setMessage(
            """
            • Pen draws. Once a pen is detected, fingers pan, pinch-zoom and rotate (change in Settings).
            • Two-finger tap: undo · three-finger tap: redo.
            • Touch and hold with a finger: pick a color.
            • Hold the pen still at the end of a stroke: it snaps into a clean line, circle or ellipse.
            • S Pen button held: erase (or pick color — see Settings).
            • Hover the S Pen to preview the brush size.
            • Tap the active brush/eraser icon again to choose a different brush; tap Fill again for fill settings.

            Keyboard: Ctrl+Z undo, Ctrl+Shift+Z or Ctrl+Y redo, B brush, E eraser, S smudge, G fill, I eyedropper, [ and ] brush size, 0 fit to screen.
            """.trimIndent()
        ).setPositiveButton("Got it", null).show()
    }

    private fun symmetryDialog() {
        val s = session ?: return
        val modes = SymmetryMode.values()
        val labels = arrayOf("Off", "Mirror left/right", "Mirror top/bottom", "Four-way", "Radial", "Kaleidoscope")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val group = RadioGroup(this)
        for ((i, m) in modes.withIndex()) {
            group.addView(RadioButton(this).apply { id = i + 1; text = labels[i]; setTextColor(Theme.ON_SURFACE); isChecked = s.symmetry.mode == m })
        }
        box.addView(group)
        var segments = s.symmetry.segments
        box.addView(LabeledSlider(this, "Segments (radial)", 2f, 24f, segments.toFloat(), { "${it.toInt()}" }) { v, _ -> segments = v.toInt() })
        dialogBuilder(this).setTitle("Symmetry").setView(padded(box))
            .setPositiveButton("OK") { _, _ ->
                val mode = modes[(group.checkedRadioButtonId - 1).coerceIn(0, modes.size - 1)]
                s.symmetry = s.symmetry.withMode(mode).withSegments(segments)
                    .withCenter(s.doc.width / 2f, s.doc.height / 2f)
                canvas?.invalidate()
                hudShow(if (mode == SymmetryMode.OFF) "Symmetry off" else labels[mode.ordinal])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun fillSettings() {
        val s = session ?: return
        val st = s.settings
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(LabeledSlider(this, "Close gaps in lines", 0f, 8f, st.fillGap.toFloat(), { "${it.toInt()} px" }) { v, _ -> st.fillGap = v.toInt() })
        box.addView(LabeledSlider(this, "Color tolerance", 0f, 160f, st.fillTolerance.toFloat(), { "${it.toInt()}" }) { v, _ -> st.fillTolerance = v.toInt() })
        val all = android.widget.CheckBox(this).apply {
            text = "Use lines from all layers"; setTextColor(Theme.ON_SURFACE); isChecked = st.fillSampleAll
            setOnCheckedChangeListener { _, c -> st.fillSampleAll = c }
        }
        box.addView(all)
        dialogBuilder(this).setTitle("Fill").setView(padded(box)).setPositiveButton("Done", null).show()
    }

    // =========================================================== transform

    private fun startTransform() {
        val s = session ?: return
        val layer = s.doc.active ?: return
        if (layer.locked) { hudShow("This layer is locked"); return }
        selectTool(Tool.TRANSFORM)
        canvas?.beginTransform()
        showBottomBar(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Theme.panel(this@EditorActivity, 28f)
            setPadding(Theme.dpi(context, 6f), 0, Theme.dpi(context, 6f), 0)
            addView(IconButton(this@EditorActivity, Icon.CLOSE, "Cancel").apply {
                setOnClickListener { canvas?.endTransform(); hideBottomBar(); selectTool(Tool.BRUSH) }
            })
            addView(Theme.text(this@EditorActivity, "Move · scale · rotate", 14f).apply { setPadding(Theme.dpi(context, 8f), 0, Theme.dpi(context, 8f), 0) })
            addView(IconButton(this@EditorActivity, Icon.CHECK, "Apply").apply {
                setOnClickListener {
                    val m = canvas?.endTransform()
                    if (m != null && !m.isIdentity) {
                        val v = FloatArray(9); m.getValues(v)
                        s.apply(JEvent.TransformLayer(layer.id, v))
                        scheduleSave()
                    }
                    hideBottomBar(); selectTool(Tool.BRUSH)
                }
            })
        })
    }

    private fun showBottomBar(v: View) {
        hideBottomBar()
        bottomBar = v
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Theme.dpi(this, 56f),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = insetBottom + Theme.dpi(this@EditorActivity, 16f) })
    }

    private fun hideBottomBar() { bottomBar?.let { root.removeView(it) }; bottomBar = null }

    // =========================================================== trace-along

    private inner class Trace(val player: ReplayPlayer, val name: String) {
        var auto = true
        var followBrush = true
        var showGuide = true
        private val label = Theme.text(this@EditorActivity, "", 14f)
        private var guideBitmap: Bitmap? = null

        fun start() {
            val bar = LinearLayout(this@EditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Theme.panel(this@EditorActivity, 28f)
                setPadding(Theme.dpi(context, 6f), 0, Theme.dpi(context, 6f), 0)
            }
            bar.addView(IconButton(this@EditorActivity, Icon.PREV, "Previous step").apply { setOnClickListener { prev() } })
            label.setPadding(Theme.dpi(this@EditorActivity, 6f), 0, Theme.dpi(this@EditorActivity, 6f), 0)
            bar.addView(label)
            bar.addView(IconButton(this@EditorActivity, Icon.NEXT, "Next step").apply { setOnClickListener { next() } })
            val autoBtn = IconButton(this@EditorActivity, Icon.SKIP, "Advance automatically after each stroke")
            autoBtn.selectedState = auto
            autoBtn.setOnClickListener { auto = !auto; autoBtn.selectedState = auto; hudShow(if (auto) "Auto-advance on" else "Auto-advance off") }
            bar.addView(autoBtn)
            val eye = IconButton(this@EditorActivity, Icon.EYE, "Show or hide the guide")
            eye.setOnClickListener {
                showGuide = !showGuide
                eye.icon = if (showGuide) Icon.EYE else Icon.EYE_OFF; eye.invalidate()
                show()
            }
            bar.addView(eye)
            showBottomBar(bar)
            show()
            hudShow("Trace the dashed line, then lift your pen", 2400)
        }

        fun show() {
            val cv = canvas ?: return
            guideBitmap = player.flatten(2048, guideBitmap)
            cv.guide = if (showGuide) guideBitmap else null
            val e = player.peekStep()
            cv.guideStep = if (!showGuide) null else when (e) {
                is JEvent.Stroke -> ReplayPlayer.pathOf(e)
                is JEvent.Fill -> Path().apply { addCircle(e.x.toFloat(), e.y.toFloat(), 18f, Path.Direction.CW) }
                else -> null
            }
            val total = player.steps.size
            label.text = if (e == null) "Done! 🎉" else "Step ${player.stepsDone() + 1} of $total"
            if (e != null && followBrush) adopt(e)
            cv.invalidate()
        }

        private fun adopt(e: JEvent) {
            val s = session ?: return
            when (e) {
                is JEvent.Stroke -> {
                    val ts = when (e.kind) { StrokeKind.ERASE -> s.eraserTool; StrokeKind.SMUDGE -> s.smudgeTool; else -> s.brushTool }
                    ts.brush = e.brush; ts.size = e.size; ts.opacity = e.opacity
                    s.tool = when (e.kind) { StrokeKind.ERASE -> Tool.ERASER; StrokeKind.SMUDGE -> Tool.SMUDGE; else -> Tool.BRUSH }
                    if (e.kind == StrokeKind.PAINT) s.setColor(e.color, remember = false)
                    canvas?.guideStepColor = if (io.github.hashirakb.penumbra.core.ColorMath.brightness(e.color) > 0.6f) 0xFF0091EA.toInt() else 0xFF00B8D4.toInt()
                }
                is JEvent.Fill -> { s.tool = Tool.FILL; s.setColor(e.color, remember = false) }
                else -> {}
            }
            syncUi()
        }

        fun next() { if (player.peekStep() != null) { player.step(); show() } }

        fun prev() {
            val done = player.steps.filter { it < player.index }
            val target = done.lastOrNull() ?: return
            player.seek(target)
            show()
        }

        fun onUserStroke() { if (auto) next() }

        fun stop() {
            canvas?.guide = null; canvas?.guideStep = null
            player.recycle()
            guideBitmap?.recycle()
        }
    }

    private fun startTrace(source: Document, name: String) {
        val t = Trace(ReplayPlayer(source), name)
        trace = t
        t.start()
    }

    // =========================================================== photos

    private fun pickImageIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)

    private fun pickPhotoLayer() {
        try { startActivityForResult(pickImageIntent(), REQ_PHOTO_LAYER) }
        catch (t: Throwable) { Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PHOTO_LAYER -> addPhotoLayer(uri)
            REQ_EXPORT_ORA -> writeOraTo(uri)
        }
    }

    private fun addPhotoLayer(uri: Uri) {
        val s = session ?: return
        if (s.doc.layers.size >= s.doc.maxLayers()) { hudShow("Layer limit reached"); return }
        hudShow("Adding photo…")
        loader.execute {
            val img = try { DocFactory.decode(this, uri, maxOf(s.doc.width, s.doc.height)) } catch (t: Throwable) { null }
            runOnUiThread {
                if (img == null) { hudShow("Couldn't open that photo"); return@runOnUiThread }
                val name = "photo_${System.currentTimeMillis()}.png"
                s.doc.assets[name] = img
                val r = DocFactory.fitRect(img.width, img.height, s.doc.width, s.doc.height)
                val id = s.doc.allocateLayerId()
                s.apply(JEvent.AddLayer(id, "Photo", s.doc.indexOf(s.doc.activeId) + 1))
                s.apply(JEvent.PlaceImage(id, name, r.left, r.top, r.right, r.bottom))
                canvas?.invalidate()
                scheduleSave()
                photoOptions()
            }
        }
    }

    /** Offers what to do with the most recent photo: trace over it, or turn it into line art. */
    private fun photoOptions() {
        val s = session ?: return
        val placed = s.doc.journal.events.filterIsInstance<JEvent.PlaceImage>().lastOrNull { it.asset.startsWith("photo_") || it.asset.startsWith("image_") }
        val photo = placed?.let { s.doc.assets[it.asset] }
        if (placed == null || photo == null) {
            Toast.makeText(this, "Add a photo first (menu → Add photo as a layer)", Toast.LENGTH_LONG).show()
            return
        }
        val photoLayer = s.doc.layer(placed.layerId)
        val options = arrayOf(
            "Trace over it (fade the photo)",
            "Turn it into line art…",
            "Make a coloring page from it…",
            "Keep the photo as it is",
        )
        dialogBuilder(this).setTitle("Photo drawing").setItems(options) { _, which ->
            when (which) {
                0 -> photoLayer?.let {
                    s.apply(JEvent.LayerProps(it.id, it.name, true, 0.35f, it.blend.ordinal, false))
                    it.locked = true
                    ensureDrawingLayerAbove(it.id)
                    canvas?.invalidate()
                    hudShow("Photo faded and locked — draw on the layer above")
                }
                1 -> lineArtDialog(photo, placed, coloring = false)
                2 -> lineArtDialog(photo, placed, coloring = true)
            }
        }.show()
    }

    private fun ensureDrawingLayerAbove(layerId: Int) {
        val s = session ?: return
        val i = s.doc.indexOf(layerId)
        val above = s.doc.layers.getOrNull(i + 1)
        if (above == null) s.apply(JEvent.AddLayer(s.doc.allocateLayerId(), "Drawing", i + 1))
        else s.doc.activeId = above.id
    }

    private fun lineArtDialog(photo: Bitmap, placed: JEvent.PlaceImage, coloring: Boolean) {
        val s = session ?: return
        val styles = if (coloring) arrayOf("Clean outlines", "Paint-by-regions") else arrayOf("Clean outlines", "Pencil sketch", "Paint-by-regions")
        var style = 0
        var detail = 0.55f
        var strength = 0.6f
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val preview = ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt()) }
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dpi(this, 200f)))
        val small = OraIO.scaled(photo, 420)
        val smallPx = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        val worker = Executors.newSingleThreadExecutor()
        var pending = false
        var dirty = false
        fun styleKey() = if (coloring && style == 1) 2 else style
        fun refresh() {
            if (pending) { dirty = true; return }
            pending = true
            val k = styleKey(); val d = detail; val st = strength
            worker.execute {
                val ink = render(k, smallPx, small.width, small.height, d, st)
                val bmp = Bitmap.createBitmap(ink, small.width, small.height, Bitmap.Config.ARGB_8888)
                runOnUiThread {
                    preview.setImageBitmap(bmp)
                    pending = false
                    if (dirty) { dirty = false; refresh() }
                }
            }
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((i, n) in styles.withIndex()) group.addView(RadioButton(this).apply { id = i + 1; text = n; setTextColor(Theme.ON_SURFACE); isChecked = i == 0 })
        group.setOnCheckedChangeListener { _, id -> style = id - 1; refresh() }
        box.addView(android.widget.HorizontalScrollView(this).apply { addView(group) })
        box.addView(LabeledSlider(this, "Detail", 0f, 1f, detail, { "${(it * 100).toInt()}%" }) { v, done -> detail = v; if (done) refresh() })
        box.addView(LabeledSlider(this, "Line strength", 0f, 1f, strength, { "${(it * 100).toInt()}%" }) { v, done -> strength = v; if (done) refresh() })
        refresh()
        dialogBuilder(this).setTitle(if (coloring) "Coloring page from photo" else "Photo → line art").setView(padded(box))
            .setPositiveButton("Create") { _, _ ->
                worker.shutdown()
                hudShow("Drawing lines…", 4000)
                val k = styleKey(); val d = detail; val st = strength
                s.runInBackground({
                    val w = photo.width; val h = photo.height
                    val px = IntArray(w * h).also { photo.getPixels(it, 0, w, 0, 0, w, h) }
                    val ink = render(k, px, w, h, d, st)
                    val bmp = Bitmap.createBitmap(ink, w, h, Bitmap.Config.ARGB_8888)
                    runOnUiThread {
                        val name = "lineart_${System.currentTimeMillis()}.png"
                        s.doc.assets[name] = bmp
                        val photoIdx = s.doc.indexOf(placed.layerId)
                        val lineId = s.doc.allocateLayerId()
                        s.apply(JEvent.AddLayer(lineId, "Line art", photoIdx + 1))
                        s.apply(JEvent.PlaceImage(lineId, name, placed.left, placed.top, placed.right, placed.bottom))
                        s.doc.layer(lineId)?.locked = true
                        s.doc.layer(placed.layerId)?.let { pl ->
                            s.apply(JEvent.LayerProps(pl.id, pl.name, !coloring, if (coloring) 1f else 0.25f, pl.blend.ordinal, false))
                        }
                        // A layer to paint on, under the lines so colors don't cover them.
                        val paintId = s.doc.allocateLayerId()
                        s.apply(JEvent.AddLayer(paintId, if (coloring) "Colors" else "Drawing", s.doc.indexOf(lineId)))
                        s.doc.activeId = paintId
                        if (coloring) selectTool(Tool.FILL)
                        canvas?.invalidate()
                        hudShow(if (coloring) "Tap areas to color them" else "Line art ready")
                        scheduleSave()
                    }
                }) {}
            }
            .setNegativeButton("Cancel") { _, _ -> worker.shutdown() }
            .setOnCancelListener { worker.shutdown() }
            .show()
    }

    private fun render(style: Int, px: IntArray, w: Int, h: Int, detail: Float, strength: Float): IntArray = when (style) {
        1 -> LineArt.sketch(px, w, h, 1f - detail, strength)
        2 -> LineArt.regions(px, w, h, (3 + detail * 6).toInt())
        else -> LineArt.outline(px, w, h, detail, strength)
    }

    // =========================================================== export

    private fun exportImage(share: Boolean) {
        val s = session ?: return
        val flat = Compositor.flatten(s.doc)
        hudShow("Saving…")
        s.runInBackground({
            val uri = Exporter.saveImage(this, flat, safeName(), jpeg = false)
            flat.recycle()
            runOnUiThread {
                if (uri == null) hudShow("Couldn't save the image")
                else if (share) Exporter.share(this, uri, "image/png", "Share drawing")
                else hudShow("Saved to Pictures/Penumbra", 1800)
            }
        }) {}
    }

    private fun safeName() = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Penumbra" } +
        "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())

    private fun exportOra() {
        saveNow {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = OraIO.MIME
                putExtra(Intent.EXTRA_TITLE, safeName() + ".ora")
            }
            try { startActivityForResult(i, REQ_EXPORT_ORA) } catch (t: Throwable) {
                // No document picker (rare): share instead.
                Exporter.share(this, Exporter.shareableFile(this, storage.fileFor(docId), safeName() + ".ora"), OraIO.MIME, "Share .ora")
            }
        }
    }

    private fun writeOraTo(uri: Uri) {
        loader.execute {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { out -> storage.fileFor(docId).inputStream().use { it.copyTo(out) } } != null
            } catch (t: Throwable) { false }
            runOnUiThread { hudShow(if (ok) "Exported" else "Export failed") }
        }
    }

    private fun timelapse() {
        val s = session ?: return
        val options = arrayOf("15 seconds", "30 seconds", "60 seconds")
        dialogBuilder(this).setTitle("Timelapse video").setItems(options) { _, which ->
            val seconds = intArrayOf(15, 30, 60)[which]
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            var cancelled = false
            val dlg = dialogBuilder(this).setTitle("Rendering video…").setView(padded(bar))
                .setNegativeButton("Cancel") { _, _ -> cancelled = true }.setCancelable(false).show()
            val file = File(cacheDir, "timelapse.mp4")
            loader.execute {
                val result = runCatching {
                    Timelapse.render(s.doc, file, seconds) { p -> runOnUiThread { bar.progress = (p * 1000).toInt() }; !cancelled }
                }
                val uri = if (result.isSuccess && !cancelled && file.exists()) Exporter.saveVideo(this, file, safeName()) else null
                runOnUiThread {
                    dlg.dismiss()
                    when {
                        cancelled -> {}
                        uri == null -> dialogBuilder(this).setTitle("Video failed")
                            .setMessage(result.exceptionOrNull()?.message ?: "This device couldn't encode the video.")
                            .setPositiveButton("OK", null).show()
                        else -> dialogBuilder(this).setTitle("Timelapse saved").setMessage("Saved to Movies/Penumbra.")
                            .setPositiveButton("Share") { _, _ -> Exporter.share(this, uri, "video/mp4", "Share timelapse") }
                            .setNegativeButton("Close", null).show()
                    }
                }
            }
        }.show()
    }

    // =========================================================== saving

    private val autosave = Runnable { saveNow(null) }

    private fun scheduleSave() {
        root.removeCallbacks(autosave)
        root.postDelayed(autosave, 4000)
    }

    /** Saves if anything changed, then runs [then] on the main thread. */
    private fun saveNow(then: (() -> Unit)?) {
        root.removeCallbacks(autosave)
        val s = session ?: run { then?.invoke(); return }
        val needsFile = !storage.fileFor(docId).exists()
        if (s.doc.revision == savedRevision && !needsFile && then != null) { then(); return }
        if (s.doc.revision == savedRevision && then == null) return
        if (saving) { saveAgain = true; then?.let { root.postDelayed({ saveNow(it) }, 300) }; return }
        saving = true
        val rev = s.doc.revision
        val snap = try { OraIO.snapshot(s.doc, saveCache, title) } catch (t: OutOfMemoryError) { saving = false; hudShow("Not enough memory to save"); return }
        val thumb = Compositor.flatten(s.doc, 480)
        val id = docId
        val t = title
        Thread {
            val ok = try {
                OraIO.writeAtomically(storage.fileFor(id)) { snap.writeTo(it) }
                storage.saveThumb(id, thumb)
                storage.setTitle(id, t)
                true
            } catch (e: Throwable) { false } finally { thumb.recycle() }
            runOnUiThread {
                saving = false
                if (ok) savedRevision = rev else hudShow("Saving failed")
                if (saveAgain) { saveAgain = false; scheduleSave() }
                then?.invoke()
            }
        }.start()
    }

    private fun leave() {
        popup?.dismiss()
        saveNow { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (popup?.isShowing == true) { popup?.dismiss(); return }
        leave()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) saveNow(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        trace?.stop()
        session?.close()
        loader.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        popup?.dismiss()
    }

    // =========================================================== keyboard

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        val ctrl = event.isCtrlPressed
        when {
            ctrl && keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed -> redo()
            ctrl && keyCode == KeyEvent.KEYCODE_Z -> undo()
            ctrl && keyCode == KeyEvent.KEYCODE_Y -> redo()
            ctrl && keyCode == KeyEvent.KEYCODE_S -> saveNow { hudShow("Saved") }
            keyCode == KeyEvent.KEYCODE_B -> selectTool(Tool.BRUSH)
            keyCode == KeyEvent.KEYCODE_E -> selectTool(Tool.ERASER)
            keyCode == KeyEvent.KEYCODE_S -> selectTool(Tool.SMUDGE)
            keyCode == KeyEvent.KEYCODE_G -> selectTool(Tool.FILL)
            keyCode == KeyEvent.KEYCODE_I -> selectTool(Tool.EYEDROPPER)
            keyCode == KeyEvent.KEYCODE_0 -> canvas?.fitToScreen()
            keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> { s.current.size = (s.current.size / 1.15f).coerceAtLeast(0.5f); syncUi(); hudShow("Size ${formatSize(s.current.size)}") }
            keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> { s.current.size = (s.current.size * 1.15f).coerceAtMost(s.current.brush.maxSize); syncUi(); hudShow("Size ${formatSize(s.current.size)}") }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
