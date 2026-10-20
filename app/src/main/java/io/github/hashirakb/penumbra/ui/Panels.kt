package io.github.hashirakb.penumbra.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import io.github.hashirakb.penumbra.core.BrushPresets
import io.github.hashirakb.penumbra.core.BrushSpec
import io.github.hashirakb.penumbra.core.ColorMath
import io.github.hashirakb.penumbra.core.DabGenerator
import io.github.hashirakb.penumbra.core.DabList
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.LayerBlend
import io.github.hashirakb.penumbra.core.StrokeKind
import io.github.hashirakb.penumbra.engine.DabRenderer
import io.github.hashirakb.penumbra.engine.Engine
import io.github.hashirakb.penumbra.engine.Layer
import kotlin.math.PI
import kotlin.math.sin

fun dialogBuilder(ctx: Context): AlertDialog.Builder =
    AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)

/** Renders a sample S-curve stroke for a brush, so the picker shows what each brush does. */
object BrushPreviews {
    private val cache = HashMap<String, Bitmap>()
    private val renderer = DabRenderer()
    private val engine = Engine(renderer)

    fun get(ctx: Context, spec: BrushSpec, kind: StrokeKind): Bitmap = cache.getOrPut(spec.id + kind) {
        val w = Theme.dpi(ctx, 180f); val h = Theme.dpi(ctx, 40f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (kind == StrokeKind.ERASE) {
            // Erasers: show the brush cutting through a band of color.
            val p = Paint().apply { color = 0xFF8C8C9A.toInt() }
            c.drawRect(0f, h * 0.2f, w.toFloat(), h * 0.8f, p)
        }
        val size = (spec.defaultSize.coerceIn(3f, h * 0.55f))
        val dabs = DabList()
        val gen = DabGenerator(spec, size, 7L, listOf(io.github.hashirakb.penumbra.core.Affine.IDENTITY), dabs)
        val n = 80
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * 0.06f + t * w * 0.88f
            val y = h / 2f + sin(t * 2 * PI).toFloat() * h * 0.22f
            val p = if (t < 0.5f) 0.15f + t * 1.7f else 1f - (t - 0.5f) * 1.2f
            gen.add(x, y, p, if (spec.tiltShape) 0.5f else 0f, 0.6f)
        }
        val group = engine.groupPaintFor(if (kind == StrokeKind.ERASE) StrokeKind.ERASE else StrokeKind.PAINT,
            spec.strokeOpacity, false)
        c.saveLayer(RectF(0f, 0f, w.toFloat(), h.toFloat()), group)
        val dp = Paint()
        renderer.preparePaint(dp, spec, 0xFFF2F2F7.toInt())
        if (kind == StrokeKind.SMUDGE) {
            // Smudge preview: color stripes pulled along the curve.
            val stripes = Paint()
            for (k in 0 until 6) {
                stripes.color = intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFB454.toInt(), 0xFF4ECDC4.toInt())[k % 3]
                c.drawRect(k * w / 6f, 0f, (k + 1) * w / 6f, h.toFloat(), stripes)
            }
        } else renderer.draw(c, dabs, 0, dabs.size, spec, dp)
        c.restore()
        bmp
    }
}

/** Brush list with previews plus a smoothing slider. */
class BrushPanel(
    ctx: Context, private val session: EditorSession, private val tool: Tool,
    private val onPicked: () -> Unit,
) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = Theme.panel(ctx)
        val pad = Theme.dpi(ctx, 12f)
        setPadding(pad, pad, pad, pad)
        val ts = when (tool) { Tool.ERASER -> session.eraserTool; Tool.SMUDGE -> session.smudgeTool; else -> session.brushTool }
        val brushes = when (tool) { Tool.ERASER -> BrushPresets.ERASERS; Tool.SMUDGE -> BrushPresets.SMUDGERS; else -> BrushPresets.PAINT }
        val kind = session.strokeKind(tool)
        addView(Theme.text(ctx, when (tool) { Tool.ERASER -> "Eraser"; Tool.SMUDGE -> "Smudge"; else -> "Brushes" }, 16f, bold = true),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = Theme.dpi(ctx, 6f) })
        val list = LinearLayout(ctx).apply { orientation = VERTICAL }
        for (b in brushes) {
            val row = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Theme.dpi(ctx, 8f), Theme.dpi(ctx, 4f), Theme.dpi(ctx, 8f), Theme.dpi(ctx, 4f))
                background = if (b.id == ts.brush.id) Theme.panel(ctx, 12f, Theme.ACCENT_DIM) else null
                isClickable = true
                contentDescription = b.name
            }
            row.addView(Theme.text(ctx, b.name, 14f, if (b.id == ts.brush.id) Theme.ACCENT else Theme.ON_SURFACE),
                LayoutParams(Theme.dpi(ctx, 96f), LayoutParams.WRAP_CONTENT))
            row.addView(ImageView(ctx).apply { setImageBitmap(BrushPreviews.get(ctx, b, kind)) })
            row.setOnClickListener {
                if (ts.brush.id != b.id) {
                    ts.brush = b
                    ts.size = b.defaultSize
                }
                onPicked()
            }
            list.addView(row)
        }
        addView(ScrollView(ctx).apply { addView(list) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LabeledSlider(ctx, "Extra smoothing", 0f, 0.8f, session.settings.stabilizerBoost,
            { "${(it * 100).toInt()}%" }) { v, _ -> session.settings.stabilizerBoost = v }.apply {
            setPadding(Theme.dpi(ctx, 6f), Theme.dpi(ctx, 8f), Theme.dpi(ctx, 6f), 0)
        })
    }
}

/** Color wheel, hex entry, recent colors and palettes. */
class ColorPanel(
    ctx: Context, private val session: EditorSession,
    private val onEyedropper: () -> Unit, private val onColor: (Int) -> Unit,
) : LinearLayout(ctx) {
    private val wheel = ColorWheel(ctx)
    private val hex = EditText(ctx)
    private val preview = View(ctx)

    init {
        orientation = VERTICAL
        background = Theme.panel(ctx)
        val pad = Theme.dpi(ctx, 12f)
        setPadding(pad, pad, pad, pad)
        wheel.color = session.color
        wheel.onChange = { c, done -> setColor(c, done, fromWheel = true) }
        addView(wheel)

        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        preview.background = Theme.pill(ctx, session.color)
        row.addView(preview, LayoutParams(Theme.dpi(ctx, 36f), Theme.dpi(ctx, 36f)).apply { marginEnd = Theme.dpi(ctx, 10f) })
        hex.setText(ColorMath.toHex(session.color))
        hex.setTextColor(Theme.ON_SURFACE)
        hex.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        hex.setSingleLine()
        hex.setOnEditorActionListener { _, _, _ ->
            ColorMath.parseHex(hex.text.toString())?.let { setColor(it, true, fromWheel = false) }
            false
        }
        row.addView(hex, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(IconButton(ctx, Icon.EYEDROPPER, "Pick from canvas").apply { setOnClickListener { onEyedropper() } })
        addView(row)

        val recent = session.settings.recentColors
        if (recent.isNotEmpty()) {
            addView(Theme.text(ctx, "Recent", 12f, Theme.MUTED).apply { setPadding(0, Theme.dpi(ctx, 8f), 0, Theme.dpi(ctx, 4f)) })
            addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(SwatchRow(ctx, recent) { setColor(it, true, false) })
            })
        }
        val palettes = LinearLayout(ctx).apply { orientation = VERTICAL }
        for ((name, colors) in ColorMath.PALETTES) {
            palettes.addView(Theme.text(ctx, name, 12f, Theme.MUTED).apply { setPadding(0, Theme.dpi(ctx, 8f), 0, Theme.dpi(ctx, 4f)) })
            palettes.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(SwatchRow(ctx, colors) { setColor(it, true, false) })
            })
        }
        addView(ScrollView(ctx).apply { addView(palettes) }, LayoutParams(LayoutParams.MATCH_PARENT, Theme.dpi(ctx, 150f)))
    }

    private fun setColor(c: Int, done: Boolean, fromWheel: Boolean) {
        if (!fromWheel) wheel.color = c
        preview.background = Theme.pill(context, c or 0xFF000000.toInt())
        if (!hex.hasFocus()) hex.setText(ColorMath.toHex(c))
        session.setColor(c, remember = done)
        onColor(c)
    }
}

/** The layer stack (top-most first) with per-layer options. */
class LayersPanel(
    ctx: Context, private val session: EditorSession, private val onChanged: () -> Unit,
) : LinearLayout(ctx) {
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val doc get() = session.doc

    init {
        orientation = VERTICAL
        background = Theme.panel(ctx)
        val pad = Theme.dpi(ctx, 12f)
        setPadding(pad, pad, pad, pad)
        val header = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Theme.text(ctx, "Layers", 16f, bold = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(IconButton(ctx, Icon.PLUS, "Add layer").apply { setOnClickListener { addLayer() } })
        addView(header)
        addView(ScrollView(ctx).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val paper = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dpi(ctx, 8f), Theme.dpi(ctx, 8f), Theme.dpi(ctx, 8f), 0)
            isClickable = true
        }
        val paperSwatch = View(ctx).apply { background = Theme.panel(ctx, 6f, doc.background or 0xFF000000.toInt()) }
        paper.addView(paperSwatch, LayoutParams(Theme.dpi(ctx, 40f), Theme.dpi(ctx, 30f)).apply { marginEnd = Theme.dpi(ctx, 10f) })
        paper.addView(Theme.text(ctx, "Paper color", 14f))
        paper.setOnClickListener { pickPaper() }
        addView(paper)
        refresh()
    }

    fun refresh() {
        list.removeAllViews()
        for (layer in doc.layers.asReversed()) list.addView(row(layer))
    }

    private fun row(layer: Layer): View {
        val ctx = context
        val active = layer.id == doc.activeId
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dpi(ctx, 6f), Theme.dpi(ctx, 4f), Theme.dpi(ctx, 4f), Theme.dpi(ctx, 4f))
            background = if (active) Theme.panel(ctx, 12f, Theme.ACCENT_DIM) else null
            isClickable = true
            contentDescription = layer.name
        }
        row.addView(LayerThumb(ctx, layer, doc.width, doc.height),
            LayoutParams(Theme.dpi(ctx, 54f), Theme.dpi(ctx, 54f)).apply { marginEnd = Theme.dpi(ctx, 10f) })
        val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
        texts.addView(Theme.text(ctx, layer.name, 14f, if (active) Theme.ACCENT else Theme.ON_SURFACE, bold = active))
        val flags = buildList {
            if (layer.opacity < 0.999f) add("${(layer.opacity * 100).toInt()}%")
            if (layer.blend != LayerBlend.NORMAL) add(layer.blend.label)
            if (layer.alphaLock) add("alpha lock")
            if (layer.locked) add("locked")
        }
        if (flags.isNotEmpty()) texts.addView(Theme.text(ctx, flags.joinToString(" · "), 12f, Theme.MUTED))
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(IconButton(ctx, if (layer.visible) Icon.EYE else Icon.EYE_OFF, if (layer.visible) "Hide" else "Show").apply {
            setOnClickListener {
                session.apply(JEvent.LayerProps(layer.id, layer.name, !layer.visible, layer.opacity, layer.blend.ordinal, layer.alphaLock))
                changed()
            }
        })
        row.setOnClickListener {
            if (active) options(layer) else { doc.activeId = layer.id; changed() }
        }
        row.setOnLongClickListener { options(layer); true }
        return row
    }

    private fun changed() { refresh(); onChanged() }

    private fun addLayer() {
        if (doc.layers.size >= doc.maxLayers()) {
            android.widget.Toast.makeText(context, "Layer limit reached for this canvas size (${doc.maxLayers()})", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val idx = doc.indexOf(doc.activeId) + 1
        session.apply(JEvent.AddLayer(doc.allocateLayerId(), "Layer ${doc.layers.size + 1}", idx))
        changed()
    }

    private fun options(layer: Layer) {
        val ctx = context
        val i = doc.indexOf(layer.id)
        val items = linkedMapOf<String, () -> Unit>(
            "Rename" to { rename(layer) },
            "Opacity…" to { opacity(layer) },
            "Blend mode…" to { blend(layer) },
            (if (layer.alphaLock) "Turn off alpha lock" else "Alpha lock (paint inside existing pixels)") to {
                session.apply(JEvent.LayerProps(layer.id, layer.name, layer.visible, layer.opacity, layer.blend.ordinal, !layer.alphaLock))
                changed()
            },
            (if (layer.locked) "Unlock layer" else "Lock layer") to { layer.locked = !layer.locked; doc.markChanged(); changed() },
            "Duplicate" to {
                if (doc.layers.size < doc.maxLayers()) {
                    session.apply(JEvent.DuplicateLayer(layer.id, doc.allocateLayerId(), i + 1)); changed()
                }
            },
        )
        if (i > 0) items["Merge down"] = { session.apply(JEvent.MergeDown(layer.id, doc.layers[i - 1].id)); changed() }
        if (i < doc.layers.size - 1) items["Move up"] = { session.apply(JEvent.MoveLayer(layer.id, i + 1)); changed() }
        if (i > 0) items["Move down"] = { session.apply(JEvent.MoveLayer(layer.id, i - 1)); changed() }
        items["Clear"] = { session.apply(JEvent.ClearLayer(layer.id)); changed() }
        if (doc.layers.size > 1) items["Delete"] = { session.apply(JEvent.DeleteLayer(layer.id)); changed() }
        dialogBuilder(ctx).setTitle(layer.name)
            .setItems(items.keys.toTypedArray()) { _, which -> items.values.elementAt(which).invoke() }
            .show()
    }

    private fun rename(layer: Layer) {
        val input = EditText(context).apply { setText(layer.name); setSingleLine(); selectAll() }
        dialogBuilder(context).setTitle("Rename layer").setView(padded(input))
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { layer.name }
                session.apply(JEvent.LayerProps(layer.id, name, layer.visible, layer.opacity, layer.blend.ordinal, layer.alphaLock))
                changed()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun opacity(layer: Layer) {
        val start = layer.opacity
        val label = TextView(context).apply { setTextColor(Theme.ON_SURFACE); text = "${(start * 100).toInt()}%" }
        val bar = SeekBar(context).apply {
            max = 100; progress = (start * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    layer.opacity = p / 100f; label.text = "$p%"; onChanged()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val box = LinearLayout(context).apply { orientation = VERTICAL; addView(label); addView(bar) }
        dialogBuilder(context).setTitle("Layer opacity").setView(padded(box))
            .setPositiveButton("OK") { _, _ ->
                val v = layer.opacity
                layer.opacity = start
                session.apply(JEvent.LayerProps(layer.id, layer.name, layer.visible, v, layer.blend.ordinal, layer.alphaLock))
                changed()
            }
            .setNegativeButton("Cancel") { _, _ -> layer.opacity = start; changed() }
            .setOnCancelListener { layer.opacity = start; changed() }
            .show()
    }

    private fun blend(layer: Layer) {
        val modes = LayerBlend.values()
        dialogBuilder(context).setTitle("Blend mode")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), layer.blend.ordinal) { d, which ->
                session.apply(JEvent.LayerProps(layer.id, layer.name, layer.visible, layer.opacity, which, layer.alphaLock))
                changed()
                d.dismiss()
            }.show()
    }

    private fun pickPaper() {
        val wheel = ColorWheel(context).apply { color = doc.background }
        val start = doc.background
        wheel.onChange = { c, _ -> doc.background = c; onChanged() }
        dialogBuilder(context).setTitle("Paper color").setView(padded(wheel))
            .setPositiveButton("OK") { _, _ ->
                val c = doc.background
                doc.background = start
                session.apply(JEvent.Background(c or 0xFF000000.toInt()))
                changed()
            }
            .setNeutralButton("White") { _, _ -> doc.background = start; session.apply(JEvent.Background(0xFFFFFFFF.toInt())); changed() }
            .setNegativeButton("Cancel") { _, _ -> doc.background = start; onChanged() }
            .show()
    }
}

/** Small layer preview over a transparency checkerboard. */
class LayerThumb(ctx: Context, private val layer: Layer, private val w: Int, private val h: Int) : View(ctx) {
    private val checker = Paint().apply { shader = Theme.checkerShader(ctx) }
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    // A small copy, so the panel never uploads full-size layers to the GPU.
    private val small: Bitmap = run {
        val side = Theme.dpi(ctx, 108f)
        val s = minOf(1f, side.toFloat() / maxOf(w, h))
        Bitmap.createScaledBitmap(layer.bitmap, maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()), true)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x44FFFFFF }
    private val dst = RectF()

    override fun onDraw(c: Canvas) {
        val s = minOf(width.toFloat() / w, height.toFloat() / h)
        val dw = w * s; val dh = h * s
        dst.set((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2)
        c.drawRect(dst, checker)
        c.drawBitmap(small, null, dst, p)
        border.strokeWidth = Theme.dp(context, 1f)
        c.drawRect(dst, border)
    }
}

fun padded(v: View): View {
    val ctx = v.context
    return FrameLayout(ctx).apply {
        val p = Theme.dpi(ctx, 20f)
        setPadding(p, Theme.dpi(ctx, 8f), p, 0)
        addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
