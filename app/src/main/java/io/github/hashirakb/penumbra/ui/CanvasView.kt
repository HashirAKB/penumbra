package io.github.hashirakb.penumbra.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.MotionPredictor
import android.view.View
import android.view.ViewConfiguration
import io.github.hashirakb.penumbra.core.BrushSpec
import io.github.hashirakb.penumbra.core.DabGenerator
import io.github.hashirakb.penumbra.core.DabList
import io.github.hashirakb.penumbra.core.DabShape
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.Mathx
import io.github.hashirakb.penumbra.core.PressureCurve
import io.github.hashirakb.penumbra.core.ShapeRecognizer
import io.github.hashirakb.penumbra.core.Stabilizer
import io.github.hashirakb.penumbra.core.StrokeKind
import io.github.hashirakb.penumbra.core.StrokePoints
import io.github.hashirakb.penumbra.core.SymmetryConfig
import io.github.hashirakb.penumbra.core.SymmetryMode
import io.github.hashirakb.penumbra.engine.Compositor
import io.github.hashirakb.penumbra.engine.Layer
import io.github.hashirakb.penumbra.engine.UndoEntry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The drawing surface: viewport (pan/zoom/rotate), pen and touch input, the live stroke
 * preview, and on-canvas overlays (hover cursor, color loupe, symmetry guides, trace guide).
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context, val session: EditorSession) : View(context) {

    // ------------------------------------------------------------------ callbacks
    var onHud: ((String) -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var onStylusDetected: (() -> Unit)? = null
    var onStrokeFinished: (() -> Unit)? = null
    /** Undo/redo done by a tap gesture on the canvas. */
    var onUndoRedo: (() -> Unit)? = null

    private val doc get() = session.doc
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ------------------------------------------------------------------ viewport
    private val view = Matrix()
    private val inv = Matrix()
    private var fitted = false
    private val docRect = RectF()
    private val pt = FloatArray(2)

    val zoom: Float get() { val v = FloatArray(9); view.getValues(v); return sqrt(v[0] * v[0] + v[3] * v[3]) }
    private val rotationRad: Float get() { val v = FloatArray(9); view.getValues(v); return atan2(v[3], v[0]) }

    // ------------------------------------------------------------------ paints
    private val workspacePaint = Paint().apply { color = 0xFF2A2A30.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000; setShadowLayer(18f, 0f, 4f, 0x99000000.toInt())
    }
    private val layerPaint = Paint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val groupPaint = Paint()
    private val dabPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xCC00E5FF.toInt()
    }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tracePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val traceStepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    // ------------------------------------------------------------------ trace guide
    /** Faded picture of the lesson so far, drawn over the canvas in trace mode. */
    var guide: Bitmap? = null
        set(v) { field = v; invalidate() }
    var guideAlpha = 0.28f
        set(v) { field = v; invalidate() }
    /** Path of the next stroke to copy, in document coordinates. */
    var guideStep: Path? = null
        set(v) { field = v; invalidate() }
    var guideStepColor = 0xFF00B8D4.toInt()

    // ------------------------------------------------------------------ transform tool
    var layerTransform: Matrix? = null
        private set
    private val transformStart = Matrix()

    fun beginTransform() { layerTransform = Matrix(); invalidate() }
    fun endTransform(): Matrix? { val m = layerTransform; layerTransform = null; invalidate(); return m }

    // ------------------------------------------------------------------ live stroke
    private inner class Live(
        val kind: StrokeKind,
        val brush: BrushSpec,
        val color: Int,
        val size: Float,
        val opacity: Float,
        val seed: Long,
        val symmetry: SymmetryConfig,
        val layer: Layer,
        val alphaLock: Boolean,
        stabilization: Float,
        val pointerId: Int,
        val downTime: Long,
        val isFinger: Boolean,
    ) {
        var points = StrokePoints()
        var dabs = DabList()
        var gen = DabGenerator(brush, size, seed, symmetry.transforms(), dabs)
        val stabilizer = Stabilizer(stabilization)
        val nodes = ArrayList<RenderNode>()
        var recorded = 0
        var smudged = 0
        val smudgeBefore: Bitmap? =
            if (kind == StrokeKind.SMUDGE) layer.bitmap.copy(Bitmap.Config.ARGB_8888, false) else null
        val predicted = DabList(64)
        var predictedFrom = 0
        var snapped = false
        val smoothed = FloatArray(3)
        // finger pseudo-pressure
        var lastSx = Float.NaN; var lastSy = 0f; var lastSt = 0L; var fingerP = 0.65f
        // hold-to-snap
        var holdX = 0f; var holdY = 0f

        fun feed(x: Float, y: Float, p: Float, tilt: Float, axis: Float, time: Long) {
            if (snapped) return
            val t = (time - downTime).toFloat()
            stabilizer.push(x, y, p, t, smoothed)
            points.add(smoothed[0], smoothed[1], smoothed[2], tilt, axis, t)
            gen.add(smoothed[0], smoothed[1], smoothed[2], tilt, axis)
            if (kind == StrokeKind.SMUDGE && dabs.size > smudged) {
                session.engine.renderer.smudge(layer.bitmap, dabs, smudged, dabs.size, brush, opacity)
                smudged = dabs.size
                layer.touch()
            }
        }

        fun replacePoints(newPoints: StrokePoints) {
            releaseNodes()
            points = newPoints
            dabs = DabList(max(64, newPoints.size * 2))
            gen = DabGenerator(brush, size, seed, symmetry.transforms(), dabs)
            gen.addAll(points)
            recorded = 0
            predicted.clear()
        }

        fun releaseNodes() {
            for (n in nodes) n.discardDisplayList()
            nodes.clear()
            recorded = 0
        }
    }

    private var live: Live? = null

    // ------------------------------------------------------------------ gesture state
    private enum class Mode { IDLE, DRAW, NAV, PICK, TAP_TOOL, TRANSFORM }
    private var mode = Mode.IDLE
    private val nav0 = Matrix()
    private var navIds = IntArray(0)
    private val navAnchor = FloatArray(4)
    private var gestureStart = 0L
    private var gestureMaxPointers = 0
    private var gestureTravel = 0f
    private val lastPos = HashMap<Int, FloatArray>()
    private var tapX = 0f; private var tapY = 0f
    private var pickX = 0f; private var pickY = 0f
    private var pickColor = 0
    private var penButtonPick = false

    private var hoverX = Float.NaN
    private var hoverY = Float.NaN

    private val predictor: MotionPredictor? =
        if (Build.VERSION.SDK_INT >= 34) try { MotionPredictor(context) } catch (t: Throwable) { null } else null

    private val longPress = Runnable {
        if (mode == Mode.DRAW && live?.isFinger == true && (live?.points?.size ?: 0) < 6 ||
            mode == Mode.NAV && navIds.size == 1
        ) {
            cancelLive()
            beginPick(tapX, tapY)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val holdToSnap = Runnable { trySnapShape() }

    // =================================================================== layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted || oldw == 0) fitToScreen()
        else {
            // Keep the same document point at the center when the screen rotates.
            view.postTranslate((w - oldw) / 2f, (h - oldh) / 2f)
            view.invert(inv)
        }
    }

    fun fitToScreen() {
        if (width == 0 || height == 0) return
        val margin = 24f * density
        val top = 64f * density
        val s = min((width - 2 * margin) / doc.width, (height - top - margin) / doc.height)
        view.reset()
        view.postScale(s, s)
        view.postTranslate((width - doc.width * s) / 2f, top + (height - top - margin - doc.height * s) / 2f)
        view.invert(inv)
        fitted = true
        invalidate()
    }

    fun resetRotation() {
        val r = rotationRad
        view.postRotate((-r * 180f / PI).toFloat(), width / 2f, height / 2f)
        view.invert(inv)
        invalidate()
    }

    /** Zoom to 100% (one document pixel per screen pixel) around the center. */
    fun actualPixels() {
        val z = zoom
        view.postScale(1f / z, 1f / z, width / 2f, height / 2f)
        view.invert(inv)
        invalidate()
    }

    private fun toDoc(x: Float, y: Float): FloatArray {
        pt[0] = x; pt[1] = y
        inv.mapPoints(pt)
        return pt
    }

    // =================================================================== drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), workspacePaint)
        docRect.set(0f, 0f, doc.width.toFloat(), doc.height.toFloat())

        canvas.save()
        canvas.concat(view)
        canvas.drawRect(docRect, shadowPaint)
        canvas.clipRect(docRect)
        val crisp = zoom >= 2.5f
        val l = live
        val transform = layerTransform
        val active = doc.active
        Compositor.draw(canvas, doc, true) { layer, c ->
            when {
                l != null && layer === l.layer && l.kind != StrokeKind.SMUDGE -> { drawLiveLayer(c, layer, l, crisp); true }
                transform != null && layer === active -> {
                    c.save(); c.concat(transform)
                    c.drawBitmap(layer.bitmap, 0f, 0f, paintFor(layer, crisp)); c.restore(); true
                }
                crisp -> { c.drawBitmap(layer.bitmap, 0f, 0f, paintFor(layer, true)); true }
                else -> false
            }
        }
        guide?.let {
            tracePaint.alpha = (guideAlpha * 255).toInt()
            canvas.drawBitmap(it, null, docRect, tracePaint)
        }
        guideStep?.let {
            traceStepPaint.color = guideStepColor
            traceStepPaint.strokeWidth = 3f * density / zoom
            traceStepPaint.pathEffect = DashPathEffect(floatArrayOf(10f * density / zoom, 6f * density / zoom), 0f)
            canvas.drawPath(it, traceStepPaint)
        }
        canvas.restore()

        drawSymmetryGuides(canvas)
        drawHoverCursor(canvas)
        if (mode == Mode.PICK) drawLoupe(canvas)
    }

    private fun paintFor(layer: Layer, crisp: Boolean): Paint {
        Compositor.layerPaint(layer, layerPaint)
        layerPaint.isFilterBitmap = !crisp
        return layerPaint
    }

    private fun drawLiveLayer(c: Canvas, layer: Layer, l: Live, crisp: Boolean) {
        c.saveLayer(docRect, paintFor(layer, crisp))
        bitmapPaint.isFilterBitmap = !crisp
        c.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
        session.engine.groupPaintFor(l.kind, l.opacity * l.brush.strokeOpacity, l.alphaLock, groupPaint)
        c.saveLayer(docRect, groupPaint)
        val renderer = session.engine.renderer
        renderer.preparePaint(dabPaint, l.brush, l.color)
        if (c.isHardwareAccelerated) {
            // Freeze finished chunks of dabs into display lists so long strokes stay cheap.
            while (l.dabs.size - l.recorded >= CHUNK) {
                val node = RenderNode("stroke")
                node.setPosition(0, 0, doc.width, doc.height)
                val rc = node.beginRecording()
                renderer.draw(rc, l.dabs, l.recorded, l.recorded + CHUNK, l.brush, dabPaint)
                node.endRecording()
                l.nodes += node
                l.recorded += CHUNK
            }
            for (n in l.nodes) c.drawRenderNode(n)
            renderer.draw(c, l.dabs, l.recorded, l.dabs.size, l.brush, dabPaint)
        } else {
            renderer.draw(c, l.dabs, 0, l.dabs.size, l.brush, dabPaint)
        }
        if (l.predicted.size > l.predictedFrom) renderer.draw(c, l.predicted, l.predictedFrom, l.predicted.size, l.brush, dabPaint)
        c.restore()
        c.restore()
    }

    private fun drawHoverCursor(canvas: Canvas) {
        if (hoverX.isNaN() || mode != Mode.IDLE) return
        val t = session.tool
        if (t != Tool.BRUSH && t != Tool.ERASER && t != Tool.SMUDGE) return
        val r = max(1.5f, session.current.size * 0.5f * zoom)
        cursorPaint.strokeWidth = 1.5f * density
        cursorPaint.color = 0xAAFFFFFF.toInt()
        canvas.drawCircle(hoverX, hoverY, r + density, cursorPaint)
        cursorPaint.color = 0xAA000000.toInt()
        canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
        if (t == Tool.BRUSH && r < 6 * density) {
            loupePaint.color = session.color
            canvas.drawCircle(hoverX, hoverY, 2.5f * density, loupePaint)
        }
    }

    private fun drawLoupe(canvas: Canvas) {
        val r = 44f * density
        val cx = pickX
        val cy = max(r + 8 * density, pickY - 96f * density)
        loupePaint.style = Paint.Style.FILL
        loupePaint.color = pickColor or 0xFF000000.toInt()
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, 180f, true, loupePaint)
        loupePaint.color = session.color
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 0f, 180f, true, loupePaint)
        cursorPaint.strokeWidth = 4f * density
        cursorPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, cursorPaint)
        cursorPaint.strokeWidth = 1f * density
        cursorPaint.color = 0x66000000
        canvas.drawCircle(cx, cy, r + 2.5f * density, cursorPaint)
        canvas.drawCircle(pickX, pickY, 6f * density, cursorPaint)
    }

    private fun drawSymmetryGuides(canvas: Canvas) {
        val s = session.symmetry
        if (s.mode == SymmetryMode.OFF) return
        guidePaint.strokeWidth = 1.5f * density
        guidePaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        val w = doc.width.toFloat(); val h = doc.height.toFloat()
        val lines = ArrayList<FloatArray>()
        when (s.mode) {
            SymmetryMode.VERTICAL -> lines += floatArrayOf(s.cx, 0f, s.cx, h)
            SymmetryMode.HORIZONTAL -> lines += floatArrayOf(0f, s.cy, w, s.cy)
            SymmetryMode.QUAD -> { lines += floatArrayOf(s.cx, 0f, s.cx, h); lines += floatArrayOf(0f, s.cy, w, s.cy) }
            else -> {
                val n = s.segments.coerceIn(2, 32)
                val len = w + h
                for (k in 0 until n) {
                    val a = -PI / 2 + 2 * PI * k / n
                    lines += floatArrayOf(s.cx, s.cy, s.cx + (cos(a) * len).toFloat(), s.cy + (sin(a) * len).toFloat())
                }
            }
        }
        for (line in lines) {
            val p = floatArrayOf(line[0], line[1], line[2], line[3])
            view.mapPoints(p)
            canvas.drawLine(p[0], p[1], p[2], p[3], guidePaint)
        }
        val c = floatArrayOf(s.cx, s.cy)
        view.mapPoints(c)
        guidePaint.pathEffect = null
        canvas.drawCircle(c[0], c[1], 6f * density, guidePaint)
    }

    // =================================================================== input

    override fun onHoverEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { hoverX = e.x; hoverY = e.y }
            MotionEvent.ACTION_HOVER_EXIT -> { hoverX = Float.NaN; hoverY = Float.NaN }
        }
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) noteStylus()
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            // Mouse wheel / trackpad (e.g. Samsung DeX): zoom around the pointer.
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                val f = if (v > 0) 1.15f else 1f / 1.15f
                zoomBy(f, e.x, e.y)
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private fun zoomBy(f: Float, px: Float, py: Float) {
        val z = zoom
        val target = (z * f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        view.postScale(target / z, target / z, px, py)
        view.invert(inv)
        onHud?.invoke("${(target * 100).roundToInt()}%")
        invalidate()
    }

    private fun noteStylus() {
        if (!session.settings.stylusSeen) {
            session.settings.stylusSeen = true
            if (session.settings.fingerMode == FingerMode.AUTO) onStylusDetected?.invoke()
        }
    }

    private fun fingersDraw(): Boolean = when (session.settings.fingerMode) {
        FingerMode.DRAW -> true
        FingerMode.NAVIGATE -> false
        FingerMode.AUTO -> !session.settings.stylusSeen
    }

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isMouse(e: MotionEvent, i: Int) = e.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.DRAW && e.actionMasked != MotionEvent.ACTION_DOWN) recordPrediction(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { onDown(e); if (mode == Mode.DRAW) recordPrediction(e) }
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun recordPrediction(e: MotionEvent) {
        val p = predictor ?: return
        try { p.record(e) } catch (_: Throwable) {}
    }

    private fun onDown(e: MotionEvent) {
        hoverX = Float.NaN
        gestureStart = e.eventTime
        gestureMaxPointers = 1
        gestureTravel = 0f
        lastPos.clear()
        lastPos[e.getPointerId(0)] = floatArrayOf(e.x, e.y)
        tapX = e.x; tapY = e.y
        val pen = isPen(e, 0)
        if (pen) noteStylus()
        if (pen && Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)

        if (session.tool == Tool.TRANSFORM) {
            mode = Mode.TRANSFORM
            anchorNav(e, transform = true)
            return
        }

        val drawingInput = pen || isMouse(e, 0) || fingersDraw()
        val penButton = pen && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        penButtonPick = penButton && session.settings.penButton == PenButtonAction.EYEDROPPER

        when {
            penButtonPick || (drawingInput && session.tool == Tool.EYEDROPPER) -> beginPick(e.x, e.y)
            drawingInput && (session.tool == Tool.FILL) -> mode = Mode.TAP_TOOL
            drawingInput -> {
                val eraseOverride = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                    (penButton && session.settings.penButton == PenButtonAction.ERASER)
                if (beginStroke(e, eraseOverride)) {
                    mode = Mode.DRAW
                    if (!pen) postDelayed(longPress, LONG_PRESS_MS)
                } else {
                    mode = Mode.NAV
                    anchorNav(e)
                }
            }
            else -> {
                // Finger in navigate mode: pan, or tap for fill/eyedropper, or long-press to pick.
                mode = Mode.NAV
                anchorNav(e)
                postDelayed(longPress, LONG_PRESS_MS)
            }
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        gestureMaxPointers = max(gestureMaxPointers, e.pointerCount)
        val idx = e.actionIndex
        lastPos[e.getPointerId(idx)] = floatArrayOf(e.getX(idx), e.getY(idx))
        removeCallbacks(longPress)
        when (mode) {
            Mode.DRAW -> {
                val l = live ?: return
                // A second finger shortly after a finger stroke began means "navigate", not "draw".
                if (l.isFinger && e.eventTime - l.downTime < FINGER_GRACE_MS + 150 && l.points.size < 12) {
                    cancelLive()
                    mode = Mode.NAV
                    anchorNav(e)
                }
                // Otherwise it's a palm or resting finger while the pen draws: ignore it.
            }
            Mode.NAV, Mode.TAP_TOOL -> { mode = Mode.NAV; anchorNav(e) }
            Mode.TRANSFORM -> anchorNav(e, transform = true)
            else -> {}
        }
    }

    private fun onMove(e: MotionEvent) {
        trackTravel(e)
        when (mode) {
            Mode.DRAW -> {
                val l = live ?: return
                val i = e.findPointerIndex(l.pointerId)
                if (i < 0) return
                for (h in 0 until e.historySize) {
                    feedSample(l, e, i, h)
                }
                feedSample(l, e, i, -1)
                updatePrediction(l, e)
                if (Mathx.hypot(e.getX(i) - l.holdX, e.getY(i) - l.holdY) > 6f * density) {
                    l.holdX = e.getX(i); l.holdY = e.getY(i)
                    removeCallbacks(holdToSnap)
                    if (session.settings.quickShape && l.kind != StrokeKind.SMUDGE) postDelayed(holdToSnap, SNAP_HOLD_MS)
                }
                if (gestureTravel > slop) removeCallbacks(longPress)
                invalidate()
            }
            Mode.NAV, Mode.TRANSFORM -> {
                if (gestureTravel > slop) removeCallbacks(longPress)
                updateNav(e, mode == Mode.TRANSFORM)
            }
            Mode.PICK -> {
                pickX = e.x; pickY = e.y
                val d = toDoc(e.x, e.y)
                pickColor = Compositor.sample(doc, d[0], d[1])
                invalidate()
            }
            else -> {}
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        val id = e.getPointerId(e.actionIndex)
        when (mode) {
            Mode.DRAW -> if (live?.pointerId == id) { finishStroke(); mode = Mode.IDLE }
            Mode.NAV, Mode.TRANSFORM -> {
                // Re-anchor with the remaining pointers so the canvas doesn't jump.
                val remaining = (0 until e.pointerCount).filter { it != e.actionIndex }
                anchorNavWith(e, remaining.toIntArray(), mode == Mode.TRANSFORM)
            }
            else -> {}
        }
        lastPos.remove(id)
    }

    private fun onUp(e: MotionEvent) {
        removeCallbacks(longPress)
        removeCallbacks(holdToSnap)
        val duration = e.eventTime - gestureStart
        val tap = duration < TAP_MS && gestureTravel < slop * 2
        when (mode) {
            Mode.DRAW -> finishStroke()
            Mode.PICK -> {
                val d = toDoc(e.x, e.y)
                val c = Compositor.sample(doc, d[0], d[1])
                onColorPicked?.invoke(c)
            }
            Mode.TAP_TOOL -> if (gestureTravel < slop * 2) tapTool(e.x, e.y)
            Mode.NAV -> {
                if (session.settings.tapGestures && tap && gestureMaxPointers == 2) {
                    if (session.history.undo()) { onHud?.invoke("Undo"); onUndoRedo?.invoke() }
                } else if (session.settings.tapGestures && tap && gestureMaxPointers == 3) {
                    if (session.history.redo()) { onHud?.invoke("Redo"); onUndoRedo?.invoke() }
                } else if (tap && gestureMaxPointers == 1 &&
                    (session.tool == Tool.FILL || session.tool == Tool.EYEDROPPER)
                ) {
                    tapTool(e.x, e.y)
                }
            }
            else -> {}
        }
        mode = Mode.IDLE
        live?.predicted?.clear()
        invalidate()
    }

    private fun onCancel() {
        removeCallbacks(longPress)
        removeCallbacks(holdToSnap)
        // The system decided this was a palm (or the gesture was taken over): drop the stroke.
        if (mode == Mode.DRAW) cancelLive()
        mode = Mode.IDLE
        invalidate()
    }

    private fun trackTravel(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            val last = lastPos[id] ?: continue
            gestureTravel += Mathx.hypot(e.getX(i) - last[0], e.getY(i) - last[1])
            last[0] = e.getX(i); last[1] = e.getY(i)
        }
    }

    // ------------------------------------------------------------------ tap tools

    private fun tapTool(x: Float, y: Float) {
        val d = toDoc(x, y)
        val dx = d[0].toInt(); val dy = d[1].toInt()
        if (dx !in 0 until doc.width || dy !in 0 until doc.height) return
        when (session.tool) {
            Tool.FILL -> {
                val layer = doc.active ?: return
                if (!checkLayerUsable(layer)) return
                if (session.busy) return
                onHud?.invoke("Filling…")
                val s = session.settings
                val ev = JEvent.Fill(layer.id, dx, dy, session.color, s.fillTolerance, s.fillGap, 1,
                    s.fillSampleAll, layer.alphaLock)
                session.fill(ev) { ok ->
                    onHud?.invoke(if (ok) "" else "Nothing to fill there")
                    invalidate()
                    onStrokeFinished?.invoke()
                }
            }
            Tool.EYEDROPPER -> onColorPicked?.invoke(Compositor.sample(doc, d[0], d[1]))
            else -> {}
        }
    }

    private fun beginPick(x: Float, y: Float) {
        mode = Mode.PICK
        pickX = x; pickY = y
        val d = toDoc(x, y)
        pickColor = Compositor.sample(doc, d[0], d[1])
        invalidate()
    }

    private fun checkLayerUsable(layer: Layer): Boolean {
        if (!layer.visible) { onHud?.invoke("This layer is hidden"); return false }
        if (layer.locked) { onHud?.invoke("This layer is locked"); return false }
        return true
    }

    // ------------------------------------------------------------------ strokes

    private fun beginStroke(e: MotionEvent, eraseOverride: Boolean): Boolean {
        val layer = doc.active ?: return false
        if (!checkLayerUsable(layer)) return false
        val tool = if (eraseOverride) Tool.ERASER else session.tool
        if (tool != Tool.BRUSH && tool != Tool.ERASER && tool != Tool.SMUDGE) return false
        val ts = when (tool) {
            Tool.ERASER -> session.eraserTool
            Tool.SMUDGE -> session.smudgeTool
            else -> session.brushTool
        }
        val stab = Mathx.clamp01(ts.brush.stabilizer + session.settings.stabilizerBoost)
        val l = Live(
            kind = session.strokeKind(tool), brush = ts.brush, color = session.color,
            size = ts.size, opacity = ts.opacity, seed = SystemClock.elapsedRealtimeNanos(),
            symmetry = session.symmetry, layer = layer, alphaLock = layer.alphaLock,
            stabilization = stab, pointerId = e.getPointerId(0), downTime = e.eventTime,
            isFinger = !isPen(e, 0) && !isMouse(e, 0),
        )
        l.holdX = e.x; l.holdY = e.y
        live = l
        feedSample(l, e, 0, -1)
        if (session.settings.quickShape && l.kind != StrokeKind.SMUDGE) postDelayed(holdToSnap, SNAP_HOLD_MS)
        invalidate()
        return true
    }

    /** Reads one sample (historical index h, or current if h < 0) and feeds it to the stroke. */
    private fun feedSample(l: Live, e: MotionEvent, i: Int, h: Int) {
        val sx = if (h < 0) e.getX(i) else e.getHistoricalX(i, h)
        val sy = if (h < 0) e.getY(i) else e.getHistoricalY(i, h)
        val time = if (h < 0) e.eventTime else e.getHistoricalEventTime(h)
        val pen = isPen(e, i)
        val rawP = if (h < 0) e.getPressure(i) else e.getHistoricalPressure(i, h)
        val p: Float = when {
            pen -> PressureCurve.apply(rawP, session.settings.pressureGamma)
            isMouse(e, i) -> 0.75f
            session.settings.fingerSpeedPressure -> fingerPressure(l, sx, sy, time)
            else -> 0.7f
        }
        val tilt = if (pen) (if (h < 0) e.getAxisValue(MotionEvent.AXIS_TILT, i)
            else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h)) else 0f
        val orient = if (pen) (if (h < 0) e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i)
            else e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, h)) else 0f
        val axis = orient - (PI / 2).toFloat() - rotationRad
        val d = toDoc(sx, sy)
        l.feed(d[0], d[1], p, tilt, axis, time)
    }

    /** Simulates pen pressure for fingers: slow strokes are bold, quick flicks taper. */
    private fun fingerPressure(l: Live, x: Float, y: Float, t: Long): Float {
        if (l.lastSx.isNaN()) { l.lastSx = x; l.lastSy = y; l.lastSt = t; return l.fingerP }
        val dt = max(1L, t - l.lastSt).toFloat()
        val speed = Mathx.hypot(x - l.lastSx, y - l.lastSy) / dt / density // dp per ms
        l.lastSx = x; l.lastSy = y; l.lastSt = t
        val target = (1.05f - speed * 0.22f).coerceIn(0.2f, 1f)
        l.fingerP += (target - l.fingerP) * 0.25f
        return l.fingerP
    }

    private fun updatePrediction(l: Live, e: MotionEvent) {
        l.predicted.clear()
        val pred = predictor ?: return
        if (l.kind == StrokeKind.SMUDGE || l.brush.shape == DabShape.PIXEL || l.points.size == 0) return
        if (Build.VERSION.SDK_INT < 34) return
        val pe = try { pred.predict(e.eventTimeNanos + 16_000_000L) } catch (_: Throwable) { null } ?: return
        val i = pe.findPointerIndex(l.pointerId)
        if (i >= 0) {
            val last = l.points.size - 1
            val gen = DabGenerator(l.brush, l.size, l.seed xor 0x5DEECE66DL, l.symmetry.transforms(), l.predicted)
            gen.add(l.points.x(last), l.points.y(last), l.points.p(last), l.points.tilt(last), l.points.axis(last))
            // The first dab sits on the last real one; skip it so the tip doesn't darken.
            l.predictedFrom = l.predicted.size
            val d = toDoc(pe.getX(i), pe.getY(i))
            gen.add(d[0], d[1], l.points.p(last), l.points.tilt(last), l.points.axis(last))
        }
        pe.recycle()
    }

    private fun trySnapShape() {
        val l = live ?: return
        if (mode != Mode.DRAW || l.snapped || l.kind == StrokeKind.SMUDGE) return
        val r = ShapeRecognizer.recognize(l.points) ?: return
        l.replacePoints(r.points)
        l.snapped = true
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onHud?.invoke(when (r.kind) {
            ShapeRecognizer.Kind.LINE -> "Line"
            ShapeRecognizer.Kind.CIRCLE -> "Circle"
            ShapeRecognizer.Kind.ELLIPSE -> "Ellipse"
        })
        invalidate()
    }

    private fun finishStroke() {
        removeCallbacks(holdToSnap)
        val l = live ?: return
        live = null
        l.predicted.clear()
        if (l.points.size == 0 || l.dabs.size == 0) { l.releaseNodes(); restoreSmudge(l); return }
        val event = JEvent.Stroke(l.layer.id, l.kind, l.brush, l.color, l.size, l.opacity, l.seed,
            l.symmetry, l.alphaLock, l.points.copy())
        val entry: UndoEntry? = if (l.kind == StrokeKind.SMUDGE) {
            val before = l.smudgeBefore
            val b = session.engine.dabBounds(l.dabs, 0, l.dabs.size)
            if (before != null && b != null && b.intersect(0, 0, doc.width, doc.height)) {
                val bb = Bitmap.createBitmap(before, b.left, b.top, b.width(), b.height())
                val ab = Bitmap.createBitmap(l.layer.bitmap, b.left, b.top, b.width(), b.height())
                // createBitmap may return `before` itself when the stroke covered the whole layer.
                if (bb !== before) before.recycle()
                UndoEntry.Pixels(l.layer.id, b.left, b.top, bb, ab)
            } else null
        } else {
            session.engine.commitStroke(doc, event, l.dabs)
        }
        l.releaseNodes()
        session.record(entry, event)
        invalidate()
        onStrokeFinished?.invoke()
    }

    private fun cancelLive() {
        removeCallbacks(holdToSnap)
        val l = live ?: return
        live = null
        l.releaseNodes()
        restoreSmudge(l)
        invalidate()
    }

    private fun restoreSmudge(l: Live) {
        val before = l.smudgeBefore ?: return
        Canvas(l.layer.bitmap).drawBitmap(before, 0f, 0f, UndoEntry.REPLACE)
        l.layer.touch()
        before.recycle()
    }

    // ------------------------------------------------------------------ navigation

    private fun anchorNav(e: MotionEvent, transform: Boolean = false) {
        anchorNavWith(e, IntArray(min(2, e.pointerCount)) { it }, transform)
    }

    private fun anchorNavWith(e: MotionEvent, indices: IntArray, transform: Boolean) {
        val use = indices.take(2)
        navIds = IntArray(use.size) { e.getPointerId(use[it]) }
        for ((k, i) in use.withIndex()) {
            navAnchor[2 * k] = e.getX(i); navAnchor[2 * k + 1] = e.getY(i)
        }
        if (transform) transformStart.set(layerTransform ?: Matrix()) else nav0.set(view)
    }

    private fun updateNav(e: MotionEvent, transform: Boolean) {
        if (navIds.isEmpty()) return
        val i0 = e.findPointerIndex(navIds[0])
        if (i0 < 0) return
        val d = Matrix()
        if (navIds.size >= 2) {
            val i1 = e.findPointerIndex(navIds[1])
            if (i1 < 0) return
            val ax0 = navAnchor[0]; val ay0 = navAnchor[1]; val ax1 = navAnchor[2]; val ay1 = navAnchor[3]
            val bx0 = e.getX(i0); val by0 = e.getY(i0); val bx1 = e.getX(i1); val by1 = e.getY(i1)
            val d0 = max(1f, Mathx.hypot(ax1 - ax0, ay1 - ay0))
            val d1 = max(1f, Mathx.hypot(bx1 - bx0, by1 - by0))
            val mx0 = (ax0 + ax1) / 2; val my0 = (ay0 + ay1) / 2
            val mx1 = (bx0 + bx1) / 2; val my1 = (by0 + by1) / 2
            val ang = atan2(by1 - by0, bx1 - bx0) - atan2(ay1 - ay0, ax1 - ax0)
            d.postTranslate(mx1 - mx0, my1 - my0)
            d.postScale(d1 / d0, d1 / d0, mx1, my1)
            d.postRotate((ang * 180f / PI).toFloat(), mx1, my1)
            if (!transform) {
                val m = Matrix(nav0); m.postConcat(d)
                // Clamp zoom.
                val v = FloatArray(9); m.getValues(v)
                val z = sqrt(v[0] * v[0] + v[3] * v[3])
                if (z < MIN_ZOOM || z > MAX_ZOOM) {
                    val f = z.coerceIn(MIN_ZOOM, MAX_ZOOM) / z
                    m.postScale(f, f, mx1, my1)
                }
                // Snap rotation to right angles when close.
                m.getValues(v)
                val rot = Math.toDegrees(atan2(v[3], v[0]).toDouble()).toFloat()
                val nearest = (rot / 90f).roundToInt() * 90f
                if (abs(rot - nearest) < 4f) m.postRotate(nearest - rot, mx1, my1)
                view.set(m)
                view.invert(inv)
                onHud?.invoke("${(zoom * 100).roundToInt()}%" +
                    (if (abs(rot - nearest) >= 4f) "  ${rot.roundToInt()}°" else ""))
            }
        } else {
            d.postTranslate(e.getX(i0) - navAnchor[0], e.getY(i0) - navAnchor[1])
            if (!transform) {
                view.set(nav0); view.postConcat(d); view.invert(inv)
            }
        }
        if (transform) {
            // Layer transform in document space: V⁻¹ · D · V · start
            val m = Matrix(transformStart)
            m.postConcat(view)
            m.postConcat(d)
            m.postConcat(inv)
            layerTransform = m
        }
        invalidate()
    }

    companion object {
        private const val CHUNK = 192
        private const val MIN_ZOOM = 0.05f
        private const val MAX_ZOOM = 64f
        private const val LONG_PRESS_MS = 480L
        private const val SNAP_HOLD_MS = 550L
        private const val TAP_MS = 260L
        private const val FINGER_GRACE_MS = 120L
    }
}
