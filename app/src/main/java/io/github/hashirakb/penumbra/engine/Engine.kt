package io.github.hashirakb.penumbra.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.github.hashirakb.penumbra.core.DabGenerator
import io.github.hashirakb.penumbra.core.DabList
import io.github.hashirakb.penumbra.core.FloodFill
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.LayerBlend
import io.github.hashirakb.penumbra.core.StrokeKind
import kotlin.math.ceil
import kotlin.math.floor

/** Draws the layer stack. Used for the screen, exports, thumbnails and fill sampling. */
object Compositor {
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun layerPaint(layer: Layer, out: Paint = layerPaint): Paint {
        out.reset()
        out.isFilterBitmap = true
        out.alpha = (layer.opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
        out.blendMode = Document.blendMode(layer.blend)
        return out
    }

    /**
     * Draws background + visible layers into [canvas] (already in document space).
     * [override] lets the caller draw one layer itself, e.g. with the live stroke on top.
     */
    fun draw(
        canvas: Canvas, doc: Document, background: Boolean = true,
        override: ((Layer, Canvas) -> Boolean)? = null,
    ) {
        if (background) canvas.drawColor(doc.background or 0xFF000000.toInt())
        for (layer in doc.layers) {
            if (!layer.visible) continue
            if (override != null && override(layer, canvas)) continue
            canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint(layer))
        }
    }

    /** Flattens the document into a new (or reused) bitmap, optionally downscaled. */
    fun flatten(doc: Document, maxSide: Int = 0, background: Boolean = true, reuse: Bitmap? = null): Bitmap {
        val scale = if (maxSide > 0) minOf(1f, maxSide.toFloat() / maxOf(doc.width, doc.height)) else 1f
        val w = maxOf(1, (doc.width * scale).toInt())
        val h = maxOf(1, (doc.height * scale).toInt())
        val out = if (reuse != null && reuse.width == w && reuse.height == h) reuse.also { it.eraseColor(0) }
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(scale, scale)
        draw(c, doc, background)
        return out
    }

    /** Color of the flattened image at a document point. */
    fun sample(doc: Document, x: Float, y: Float): Int {
        val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(one)
        c.translate(-floor(x), -floor(y))
        draw(c, doc, true)
        val v = one.getPixel(0, 0)
        one.recycle()
        return v
    }
}

/**
 * Applies journal events to a document. The editor and the replay player both go through
 * here, which is what keeps replays faithful.
 */
class Engine(val renderer: DabRenderer = DabRenderer()) {
    private val strokePaint = Paint()
    private val groupPaint = Paint()

    /** Builds the dab list for a recorded stroke. */
    fun dabsFor(e: JEvent.Stroke): DabList {
        val out = DabList(maxOf(64, e.points.size * 2))
        DabGenerator(e.brush, e.size, e.seed, e.symmetry.transforms(), out).addAll(e.points)
        return out
    }

    fun groupPaintFor(kind: StrokeKind, opacity: Float, alphaLock: Boolean, out: Paint = groupPaint): Paint {
        out.reset()
        out.alpha = (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
        out.blendMode = when {
            kind == StrokeKind.ERASE -> BlendMode.DST_OUT
            alphaLock -> BlendMode.SRC_ATOP
            else -> BlendMode.SRC_OVER
        }
        return out
    }

    fun dabBounds(dabs: DabList, from: Int, to: Int): Rect? {
        val b = FloatArray(4)
        if (!dabs.bounds(from, to, 2f, b)) return null
        return Rect(floor(b[0]).toInt(), floor(b[1]).toInt(), ceil(b[2]).toInt(), ceil(b[3]).toInt())
    }

    /**
     * Rasterizes a paint/erase stroke into its layer. [dabs] may be passed when the caller already
     * generated them (live drawing). Returns the undo entry, or null if nothing changed.
     */
    fun commitStroke(doc: Document, e: JEvent.Stroke, dabs: DabList = dabsFor(e)): UndoEntry? {
        val layer = doc.layer(e.layerId) ?: return null
        val bounds = dabBounds(dabs, 0, dabs.size) ?: return null
        val (r, before) = UndoEntry.capture(layer, bounds) ?: return null
        val c = Canvas(layer.bitmap)
        val strokeOpacity = e.opacity * e.brush.strokeOpacity
        c.saveLayer(RectF(r), groupPaintFor(e.kind, strokeOpacity, e.alphaLock))
        renderer.preparePaint(strokePaint, e.brush, e.color)
        renderer.draw(c, dabs, 0, dabs.size, e.brush, strokePaint)
        c.restore()
        layer.touch()
        val after = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        return UndoEntry.Pixels(layer.id, r.left, r.top, before, after)
    }

    /** Applies a smudge stroke in one go (used by replay; the editor smudges live). */
    fun applySmudge(doc: Document, e: JEvent.Stroke): UndoEntry? {
        val layer = doc.layer(e.layerId) ?: return null
        val dabs = dabsFor(e)
        val bounds = dabBounds(dabs, 0, dabs.size) ?: return null
        val (r, before) = UndoEntry.capture(layer, bounds) ?: return null
        renderer.smudge(layer.bitmap, dabs, 0, dabs.size, e.brush, e.opacity)
        layer.touch()
        val after = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        return UndoEntry.Pixels(layer.id, r.left, r.top, before, after)
    }

    /** Pixels the fill reads from. */
    fun fillSource(doc: Document, e: JEvent.Fill): IntArray {
        val px = IntArray(doc.width * doc.height)
        if (e.sampleAllLayers) {
            val flat = Compositor.flatten(doc)
            flat.getPixels(px, 0, doc.width, 0, 0, doc.width, doc.height)
            flat.recycle()
        } else {
            doc.layer(e.layerId)?.bitmap?.getPixels(px, 0, doc.width, 0, 0, doc.width, doc.height)
        }
        return px
    }

    /** The pure part of a fill, safe to run on a worker thread. */
    fun computeFill(px: IntArray, doc: Document, e: JEvent.Fill): FloodFill.Result? =
        FloodFill.compute(px, doc.width, doc.height, e.x, e.y, e.tolerance, e.gap, e.expand)

    fun commitFill(doc: Document, e: JEvent.Fill, result: FloodFill.Result): UndoEntry? {
        val layer = doc.layer(e.layerId) ?: return null
        val r = Rect(result.left, result.top, result.right, result.bottom)
        if (r.isEmpty) return null
        val (cr, before) = UndoEntry.capture(layer, r) ?: return null
        val w = cr.width(); val h = cr.height()
        val colors = IntArray(w * h)
        val color = e.color or 0xFF000000.toInt()
        for (y in 0 until h) {
            val row = (y + cr.top) * doc.width + cr.left
            for (x in 0 until w) if (result.mask[row + x].toInt() != 0) colors[y * w + x] = color
        }
        val patch = Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
        val p = Paint()
        p.blendMode = if (e.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
        Canvas(layer.bitmap).drawBitmap(patch, cr.left.toFloat(), cr.top.toFloat(), p)
        patch.recycle()
        layer.touch()
        val after = Bitmap.createBitmap(layer.bitmap, cr.left, cr.top, w, h)
        return UndoEntry.Pixels(layer.id, cr.left, cr.top, before, after)
    }

    /**
     * Applies any non-stroke, non-fill event synchronously. Strokes and fills also work here
     * (used by replay), running the fill on the calling thread.
     */
    fun apply(doc: Document, e: JEvent): UndoEntry? = when (e) {
        is JEvent.Stroke -> if (e.kind == StrokeKind.SMUDGE) applySmudge(doc, e) else commitStroke(doc, e)
        is JEvent.Fill -> computeFill(fillSource(doc, e), doc, e)?.let { commitFill(doc, e, it) }
        is JEvent.AddLayer -> structural(doc) {
            val layer = Layer(e.id, e.name, doc.newBitmap())
            doc.layers.add(e.index.coerceIn(0, doc.layers.size), layer)
            doc.activeId = e.id
            if (e.id >= doc.nextLayerId) doc.nextLayerId = e.id + 1
        }
        is JEvent.DeleteLayer -> structural(doc) {
            val i = doc.indexOf(e.id)
            if (i >= 0) {
                doc.layers.removeAt(i)
                if (doc.activeId == e.id) doc.activeId = doc.layers.getOrNull(maxOf(0, i - 1))?.id ?: 0
            }
        }
        is JEvent.MoveLayer -> structural(doc) {
            val i = doc.indexOf(e.id)
            if (i >= 0) {
                val l = doc.layers.removeAt(i)
                doc.layers.add(e.toIndex.coerceIn(0, doc.layers.size), l)
            }
        }
        is JEvent.DuplicateLayer -> structural(doc) {
            val src = doc.layer(e.srcId)
            if (src != null) {
                val copy = Layer(e.newId, src.name + " copy", src.bitmap.copy(Bitmap.Config.ARGB_8888, true))
                copy.setProps(src.props().copy(name = src.name + " copy"))
                doc.layers.add(e.index.coerceIn(0, doc.layers.size), copy)
                doc.activeId = e.newId
                if (e.newId >= doc.nextLayerId) doc.nextLayerId = e.newId + 1
            }
        }
        is JEvent.LayerProps -> structural(doc) {
            doc.layer(e.id)?.let {
                it.name = e.name; it.visible = e.visible; it.opacity = e.opacity
                it.blend = LayerBlend.of(e.blend); it.alphaLock = e.alphaLock
            }
        }
        is JEvent.MergeDown -> mergeDown(doc, e)
        is JEvent.ClearLayer -> doc.layer(e.id)?.let { layer -> replaceLayerPixels(layer) { it.eraseColor(0) } }
        is JEvent.PlaceImage -> {
            val layer = doc.layer(e.layerId)
            val img = doc.assets[e.asset]
            if (layer != null && img != null) {
                val dst = RectF(e.left, e.top, e.right, e.bottom)
                val r = Rect(floor(dst.left).toInt(), floor(dst.top).toInt(), ceil(dst.right).toInt(), ceil(dst.bottom).toInt())
                UndoEntry.capture(layer, r)?.let { (cr, before) ->
                    Canvas(layer.bitmap).drawBitmap(img, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                    layer.touch()
                    UndoEntry.Pixels(layer.id, cr.left, cr.top, before,
                        Bitmap.createBitmap(layer.bitmap, cr.left, cr.top, cr.width(), cr.height()))
                }
            } else null
        }
        is JEvent.TransformLayer -> doc.layer(e.layerId)?.let { layer ->
            replaceLayerPixels(layer) { bmp ->
                val m = Matrix(); m.setValues(e.matrix)
                val old = bmp.copy(Bitmap.Config.ARGB_8888, false)
                bmp.eraseColor(0)
                Canvas(bmp).drawBitmap(old, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                old.recycle()
            }
        }
        is JEvent.Background -> {
            val before = doc.background
            doc.background = e.color
            UndoEntry.Background(before, e.color)
        }
    }

    private inline fun structural(doc: Document, change: () -> Unit): UndoEntry {
        val before = StackState.of(doc)
        change()
        val after = StackState.of(doc)
        return UndoEntry.Stack(before, after)
    }

    private inline fun replaceLayerPixels(layer: Layer, change: (Bitmap) -> Unit): UndoEntry {
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        change(layer.bitmap)
        layer.touch()
        val after = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return UndoEntry.Pixels(layer.id, 0, 0, before, after)
    }

    private fun mergeDown(doc: Document, e: JEvent.MergeDown): UndoEntry? {
        val upper = doc.layer(e.upperId) ?: return null
        val lower = doc.layer(e.lowerId) ?: return null
        val full = Rect(0, 0, doc.width, doc.height)
        val (r, before) = UndoEntry.capture(lower, full) ?: return null
        if (upper.visible) Canvas(lower.bitmap).drawBitmap(upper.bitmap, 0f, 0f, Compositor.layerPaint(upper, Paint()))
        lower.touch()
        val after = Bitmap.createBitmap(lower.bitmap, r.left, r.top, r.width(), r.height())
        val pixels = UndoEntry.Pixels(lower.id, 0, 0, before, after)
        val stack = structural(doc) {
            doc.layers.remove(upper)
            doc.activeId = lower.id
        }
        return UndoEntry.Group(listOf(pixels, stack))
    }
}
