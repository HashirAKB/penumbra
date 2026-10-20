package io.github.hashirakb.penumbra.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Built-in trace-along lessons. Each lesson is an ordered list of pen strokes generated from
 * the same procedural designs as the coloring pages, so there is always something new to learn.
 */
object Lessons {
    enum class Kind(val title: String, val blurb: String) {
        WARMUP("Warm-up drills", "Straight lines, curves, circles and spirals for a steady hand"),
        MANDALA("Draw a mandala", "Build a symmetrical design ring by ring"),
        BLOOM("Draw flowers", "Petals, leaves and stems"),
        ROSETTE("Spirograph rosette", "Long flowing loops — great for pen control"),
        GLASS("Stained glass", "Straight-edged panes inside a frame"),
    }

    fun strokes(kind: Kind, seed: Long, w: Int, h: Int): List<StrokePoints> {
        val prims = when (kind) {
            Kind.WARMUP -> return warmup(w, h)
            Kind.MANDALA -> ColoringPages.generate(ColoringPages.Style.MANDALA, seed, w, h).prims
            Kind.BLOOM -> ColoringPages.generate(ColoringPages.Style.BLOOM, seed, w, h).prims
            Kind.ROSETTE -> ColoringPages.generate(ColoringPages.Style.ROSETTE, seed, w, h).prims
            Kind.GLASS -> ColoringPages.generate(ColoringPages.Style.STAINED_GLASS, seed, w, h).prims
        }
        // Draw big shapes first, then details, like an artist blocking in a design.
        val ordered = prims.sortedByDescending { extent(it) }
        return ordered.mapNotNull { toStroke(it) }
    }

    private fun extent(p: ColoringPages.Prim): Float = when (p) {
        is ColoringPages.Prim.Circle -> p.r * 2f
        is ColoringPages.Prim.Poly -> {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var i = 0
            while (i < p.pts.size) {
                l = min(l, p.pts[i]); r = max(r, p.pts[i]); t = min(t, p.pts[i + 1]); b = max(b, p.pts[i + 1]); i += 2
            }
            max(r - l, b - t)
        }
    }

    fun toStroke(p: ColoringPages.Prim): StrokePoints? {
        val xy: FloatArray = when (p) {
            is ColoringPages.Prim.Circle -> {
                val n = (p.r / 3f).toInt().coerceIn(24, 240)
                FloatArray((n + 1) * 2) { k ->
                    val i = k / 2
                    val a = -PI / 2 + 2 * PI * i / n
                    if (k % 2 == 0) (p.cx + cos(a) * p.r).toFloat() else (p.cy + sin(a) * p.r).toFloat()
                }
            }
            is ColoringPages.Prim.Poly -> if (p.closed) p.pts + floatArrayOf(p.pts[0], p.pts[1]) else p.pts
        }
        if (xy.size < 4) return null
        return fromPolyline(densify(xy, 6f, 900))
    }

    /** Pen samples along a polyline with a natural pressure taper at both ends. */
    fun fromPolyline(xy: FloatArray): StrokePoints {
        val n = xy.size / 2
        val out = StrokePoints(n)
        var len = 0f
        val cum = FloatArray(n)
        for (i in 1 until n) {
            len += Mathx.hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
            cum[i] = len
        }
        val taper = min(40f, len * 0.2f)
        for (i in 0 until n) {
            val d = cum[i]
            val inP = if (taper > 0f) Mathx.clamp01(d / taper) else 1f
            val outP = if (taper > 0f) Mathx.clamp01((len - d) / taper) else 1f
            val p = 0.35f + 0.6f * min(inP, outP)
            out.add(xy[2 * i], xy[2 * i + 1], p, 0f, 0f, d / 0.6f) // ~0.6 px/ms drawing speed
        }
        return out
    }

    /** Inserts points so no segment is longer than [step]; caps the total to [maxPoints]. */
    fun densify(xy: FloatArray, step: Float, maxPoints: Int): FloatArray {
        val out = ArrayList<Float>()
        val n = xy.size / 2
        var total = 0f
        for (i in 1 until n) total += Mathx.hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
        val s = max(step, total / maxPoints)
        out += xy[0]; out += xy[1]
        for (i in 1 until n) {
            val x0 = xy[2 * i - 2]; val y0 = xy[2 * i - 1]; val x1 = xy[2 * i]; val y1 = xy[2 * i + 1]
            val seg = Mathx.hypot(x1 - x0, y1 - y0)
            val k = (seg / s).toInt()
            for (j in 1..k) { val t = j / (k + 1f); out += x0 + (x1 - x0) * t; out += y0 + (y1 - y0) * t }
            out += x1; out += y1
        }
        // Thin out if still over budget.
        if (out.size / 2 > maxPoints) {
            val keep = FloatArray(maxPoints * 2)
            for (i in 0 until maxPoints) {
                val src = (i.toLong() * (out.size / 2 - 1) / (maxPoints - 1)).toInt()
                keep[2 * i] = out[2 * src]; keep[2 * i + 1] = out[2 * src + 1]
            }
            return keep
        }
        return out.toFloatArray()
    }

    private fun warmup(w: Int, h: Int): List<StrokePoints> {
        val out = ArrayList<StrokePoints>()
        val m = min(w, h) * 0.08f
        val rowH = (h - 2 * m) / 6f
        // Row 1: horizontal lines.
        for (k in 0 until 4) {
            val y = m + rowH * 0.2f + k * rowH * 0.2f
            out += fromPolyline(densify(floatArrayOf(m, y, w - m, y), 6f, 400))
        }
        // Row 2: vertical hatching.
        for (k in 0 until 12) {
            val x = m + k * (w - 2 * m) / 11f
            out += fromPolyline(densify(floatArrayOf(x, m + rowH * 1.1f, x, m + rowH * 1.9f), 6f, 200))
        }
        // Row 3: waves.
        for (k in 0 until 2) {
            val base = m + rowH * (2.3f + k * 0.4f)
            val pts = FloatArray(202) { i ->
                val t = (i / 2) / 100f
                if (i % 2 == 0) m + t * (w - 2 * m) else base + sin(t * 6 * PI).toFloat() * rowH * 0.15f
            }
            out += fromPolyline(densify(pts, 6f, 500))
        }
        // Row 4: circles.
        val cr = rowH * 0.38f
        val count = ((w - 2 * m) / (cr * 2.6f)).toInt().coerceIn(3, 8)
        for (k in 0 until count) {
            val cx = m + cr + k * (w - 2 * m - 2 * cr) / max(1, count - 1)
            toStroke(ColoringPages.Prim.Circle(cx, m + rowH * 3.5f, cr))?.let { out += it }
        }
        // Row 5-6: spirals.
        for (k in 0 until 2) {
            val cx = w * (0.3f + 0.4f * k); val cy = m + rowH * 5f
            val turns = 4
            val pts = FloatArray(2 * 361 * turns / 3) { i ->
                val t = (i / 2) / (361f * turns / 3f) * turns * 2 * PI.toFloat()
                val r = rowH * 0.9f * t / (turns * 2 * PI.toFloat())
                if (i % 2 == 0) cx + cos(t) * r * (if (k == 0) 1 else -1) else cy + sin(t) * r
            }
            out += fromPolyline(densify(pts, 6f, 700))
        }
        return out
    }
}
