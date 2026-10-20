package io.github.hashirakb.penumbra.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import io.github.hashirakb.penumbra.core.BrushSpec
import io.github.hashirakb.penumbra.core.DabList
import io.github.hashirakb.penumbra.core.DabShape
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Draws dabs onto any Canvas. The same code paints the live preview (GPU canvas) and commits
 * the stroke into the layer (CPU canvas), so what you see while drawing is what you get.
 */
class DabRenderer {
    private val stamps = HashMap<String, Bitmap>()
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun stampFor(spec: BrushSpec): Bitmap? = when (spec.shape) {
        DabShape.SOFT -> stamp("soft_" + (spec.hardness * 20).toInt()) { softStamp(spec.hardness) }
        DabShape.GRAIN -> stamp("grain") { textureStamp(4f, fine = true) }
        DabShape.CHALK -> stamp("chalk") { textureStamp(1.4f, fine = false) }
        else -> null
    }

    private inline fun stamp(key: String, make: () -> Bitmap): Bitmap =
        stamps.getOrPut(key, make)

    /** Prepares [p] for painting dabs of [spec] in [color]. */
    fun preparePaint(p: Paint, spec: BrushSpec, color: Int) {
        p.reset()
        p.isAntiAlias = spec.shape != DabShape.PIXEL
        p.isFilterBitmap = true
        p.style = Paint.Style.FILL
        val opaque = color or 0xFF000000.toInt()
        p.color = opaque
        p.colorFilter = if (stampFor(spec) != null) PorterDuffColorFilter(opaque, PorterDuff.Mode.SRC_IN) else null
    }

    /** Draws dabs [from, to) from [dabs] with a paint prepared by [preparePaint]. */
    fun draw(canvas: Canvas, dabs: DabList, from: Int, to: Int, spec: BrushSpec, p: Paint) {
        val stamp = stampFor(spec)
        val src = if (stamp != null) Rect(0, 0, stamp.width, stamp.height) else null
        for (i in from until to) {
            val x = dabs.x(i); val y = dabs.y(i); val r = dabs.r(i)
            val a = (dabs.alpha(i) * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (a == 0) continue
            p.alpha = a
            val aspect = dabs.aspect(i)
            val angle = dabs.angle(i)
            when {
                spec.shape == DabShape.PIXEL -> {
                    val s = max(1, (r * 2f + 0.5f).toInt())
                    val left = (x - s / 2f + 0.5f).toInt().toFloat()
                    val top = (y - s / 2f + 0.5f).toInt().toFloat()
                    canvas.drawRect(left, top, left + s, top + s, p)
                }
                stamp == null -> {
                    if (aspect >= 0.999f) {
                        canvas.drawCircle(x, y, r, p)
                    } else {
                        canvas.save()
                        canvas.rotate((angle * 180.0 / PI).toFloat(), x, y)
                        rect.set(x - r, y - r * aspect, x + r, y + r * aspect)
                        canvas.drawOval(rect, p)
                        canvas.restore()
                    }
                }
                else -> {
                    val rotate = angle != 0f
                    if (rotate) {
                        canvas.save()
                        canvas.rotate((angle * 180.0 / PI).toFloat(), x, y)
                    }
                    rect.set(x - r, y - r * aspect, x + r, y + r * aspect)
                    canvas.drawBitmap(stamp, src, rect, p)
                    if (rotate) canvas.restore()
                }
            }
        }
    }

    /** Convenience: draw a whole dab list in one color. */
    fun drawAll(canvas: Canvas, dabs: DabList, spec: BrushSpec, color: Int) {
        preparePaint(paint, spec, color)
        draw(canvas, dabs, 0, dabs.size, spec, paint)
    }

    // ------------------------------------------------------------------ smudge

    private var smudgeTmp: Bitmap? = null
    private val smudgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = BlendMode.DST_IN
    }

    /**
     * Smudges [layer] in place: each dab picks up the paint under the previous dab and
     * deposits it, faded by [strength], at its own position.
     */
    fun smudge(layer: Bitmap, dabs: DabList, from: Int, to: Int, spec: BrushSpec, strength: Float) {
        val mask = stampFor(spec) ?: stamp("soft_4") { softStamp(0.2f) }
        val canvas = Canvas(layer)
        val start = max(1, from)
        for (i in start until to) {
            val r = dabs.r(i)
            val d = ceil(r * 2f).toInt().coerceIn(2, 1024)
            var tmp = smudgeTmp
            if (tmp == null || tmp.width < d || tmp.height < d) {
                tmp?.recycle()
                tmp = Bitmap.createBitmap(max(d, 64), max(d, 64), Bitmap.Config.ARGB_8888)
                smudgeTmp = tmp
            }
            tmp.eraseColor(0)
            val tc = Canvas(tmp)
            val px = dabs.x(i - 1); val py = dabs.y(i - 1)
            val srcRect = Rect((px - r).toInt(), (py - r).toInt(), (px - r).toInt() + d, (py - r).toInt() + d)
            tc.drawBitmap(layer, srcRect, Rect(0, 0, d, d), null)
            tc.drawBitmap(mask, null, Rect(0, 0, d, d), maskPaint)
            smudgePaint.alpha = (255f * strength * dabs.alpha(i)).toInt().coerceIn(0, 255)
            val x = dabs.x(i); val y = dabs.y(i)
            canvas.drawBitmap(tmp, Rect(0, 0, d, d),
                RectF(x - r, y - r, x - r + d, y - r + d), smudgePaint)
        }
    }

    // ------------------------------------------------------------------ stamps

    private fun softStamp(hardness: Float): Bitmap {
        val n = 128
        val px = IntArray(n * n)
        val c = (n - 1) / 2f
        val h = hardness.coerceIn(0f, 0.95f)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x - c) / c; val dy = (y - c) / c
            val t = sqrt(dx * dx + dy * dy)
            val a = when {
                t >= 1f -> 0f
                h < 0.05f -> {
                    // Airbrush: gaussian-like with a clean zero at the rim.
                    val g = exp(-t * t * 4.2f)
                    g * (1f - t * t)
                }
                else -> 1f - smoothstep(h, 1f, t)
            }
            px[y * n + x] = ((a * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Disc with paper-like grain; [scale] sets grain size, [fine] chooses pencil vs chalk. */
    private fun textureStamp(scale: Float, fine: Boolean): Bitmap {
        val n = 128
        val rnd = Random(if (fine) 11L else 23L)
        val g = 33
        val grid = FloatArray(g * g) { rnd.nextFloat() }
        val px = IntArray(n * n)
        val c = (n - 1) / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x - c) / c; val dy = (y - c) / c
            val t = sqrt(dx * dx + dy * dy)
            if (t >= 1f) { px[y * n + x] = 0; continue }
            val disc = 1f - smoothstep(if (fine) 0.55f else 0.8f, 1f, t)
            // Value noise sampled at two octaves.
            val v1 = valueNoise(grid, g, x / (n / (8f * scale)), y / (n / (8f * scale)))
            val v2 = valueNoise(grid, g, x / (n / (24f * scale)) + 7.3f, y / (n / (24f * scale)) + 3.1f)
            val noise = 0.6f * v1 + 0.4f * v2
            val a = if (fine) {
                disc * (0.25f + 0.75f * smoothstep(0.25f, 0.8f, noise))
            } else {
                disc * (if (noise > 0.42f) 1f else 0.12f)
            }
            px[y * n + x] = ((a * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun valueNoise(grid: FloatArray, g: Int, x: Float, y: Float): Float {
        val xi = x.toInt(); val yi = y.toInt()
        val fx = x - xi; val fy = y - yi
        fun at(i: Int, j: Int) = grid[(((j % (g - 1)) + (g - 1)) % (g - 1)) * g + (((i % (g - 1)) + (g - 1)) % (g - 1))]
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = at(xi, yi) + (at(xi + 1, yi) - at(xi, yi)) * sx
        val b = at(xi, yi + 1) + (at(xi + 1, yi + 1) - at(xi, yi + 1)) * sx
        return a + (b - a) * sy
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
