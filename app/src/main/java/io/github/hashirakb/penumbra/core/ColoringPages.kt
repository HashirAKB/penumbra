package io.github.hashirakb.penumbra.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural coloring pages. Every seed gives a new page, so there is no fixed catalogue.
 * Output is resolution-independent line primitives; the engine strokes them in black.
 */
object ColoringPages {
    enum class Style(val label: String) {
        MANDALA("Mandala"),
        STAINED_GLASS("Stained glass"),
        QUILT("Geometric quilt"),
        ROSETTE("Spirograph rosette"),
        BLOOM("Flower bloom"),
    }

    sealed class Prim {
        /** Polyline as x,y pairs. */
        class Poly(val pts: FloatArray, val closed: Boolean) : Prim()
        class Circle(val cx: Float, val cy: Float, val r: Float) : Prim()
    }

    class Page(val prims: List<Prim>, val lineWidth: Float)

    fun generate(style: Style, seed: Long, w: Int, h: Int): Page {
        val rnd = Random(seed)
        val lw = max(2.5f, min(w, h) / 360f)
        val prims = when (style) {
            Style.MANDALA -> mandala(rnd, w, h)
            Style.STAINED_GLASS -> stainedGlass(rnd, w, h)
            Style.QUILT -> quilt(rnd, w, h)
            Style.ROSETTE -> rosette(rnd, w, h)
            Style.BLOOM -> bloom(rnd, w, h)
        }
        return Page(prims, lw)
    }

    // ---------------------------------------------------------------- mandala

    private fun mandala(rnd: Random, w: Int, h: Int): List<Prim> {
        val out = ArrayList<Prim>()
        val cx = w / 2f; val cy = h / 2f
        val rMax = min(w, h) * 0.46f
        val n = intArrayOf(8, 10, 12, 14, 16)[rnd.nextInt(5)]
        var r = rMax * (0.07f + rnd.nextFloat() * 0.05f)
        out += Prim.Circle(cx, cy, r)
        out += Prim.Circle(cx, cy, r * 0.45f)
        var ringIndex = 0
        while (r < rMax * 0.97f) {
            val band = min(rMax - r, rMax * (0.09f + rnd.nextFloat() * 0.1f))
            if (band < rMax * 0.05f) break
            val r1 = r + band
            val k = if (ringIndex % 2 == 0) n else n * (1 + rnd.nextInt(2))
            val offset = if (rnd.nextBoolean()) PI.toFloat() / k else 0f
            when (rnd.nextInt(7)) {
                0 -> petals(out, cx, cy, r, r1, k, offset, 0.9f)
                1 -> { petals(out, cx, cy, r, r1, k, offset, 0.75f); petals(out, cx, cy, r, r + band * 0.55f, k, offset, 0.5f) }
                2 -> scallops(out, cx, cy, r, r1, k * 2, offset)
                3 -> { petals(out, cx, cy, r, r1, k, offset, 0.8f); petals(out, cx, cy, r, r1 * 0.97f, k, offset + PI.toFloat() / k, 0.6f) }
                4 -> zigzag(out, cx, cy, r, r1, k * 2, offset)
                5 -> { scallops(out, cx, cy, r, r1, k, offset); dots(out, cx, cy, r + band * 0.35f, k, band * 0.12f, offset) }
                else -> leaves(out, cx, cy, r, r1, k, offset)
            }
            out += Prim.Circle(cx, cy, r1)
            r = r1
            ringIndex++
        }
        return out
    }

    private fun petals(out: MutableList<Prim>, cx: Float, cy: Float, r0: Float, r1: Float, k: Int, off: Float, width: Float) {
        val half = PI.toFloat() / k * width
        for (i in 0 until k) {
            val a = off + 2f * PI.toFloat() * i / k
            val pts = ArrayList<Float>()
            val steps = 24
            // Left edge from base to tip, then right edge back: a pointed petal.
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val rr = r0 + (r1 - r0) * t
                val ang = a - half * (1f - t * t)
                pts += cx + cos(ang) * rr; pts += cy + sin(ang) * rr
            }
            for (s in steps downTo 0) {
                val t = s.toFloat() / steps
                val rr = r0 + (r1 - r0) * t
                val ang = a + half * (1f - t * t)
                pts += cx + cos(ang) * rr; pts += cy + sin(ang) * rr
            }
            out += Prim.Poly(pts.toFloatArray(), true)
        }
    }

    private fun scallops(out: MutableList<Prim>, cx: Float, cy: Float, r0: Float, r1: Float, k: Int, off: Float) {
        val pts = ArrayList<Float>()
        val per = 16
        for (i in 0 until k) {
            for (s in 0..per) {
                val t = s.toFloat() / per
                val a = off + 2f * PI.toFloat() * (i + t) / k
                val rr = r0 + (r1 - r0) * 0.92f * sin(PI.toFloat() * t)
                pts += cx + cos(a) * rr; pts += cy + sin(a) * rr
            }
        }
        out += Prim.Poly(pts.toFloatArray(), true)
    }

    private fun zigzag(out: MutableList<Prim>, cx: Float, cy: Float, r0: Float, r1: Float, k: Int, off: Float) {
        val pts = ArrayList<Float>()
        for (i in 0..k * 2) {
            val a = off + PI.toFloat() * i / k
            val rr = if (i % 2 == 0) r0 else r1
            pts += cx + cos(a) * rr; pts += cy + sin(a) * rr
        }
        out += Prim.Poly(pts.toFloatArray(), true)
        val mid = (r0 + r1) / 2f
        out += Prim.Circle(cx, cy, mid)
    }

    private fun dots(out: MutableList<Prim>, cx: Float, cy: Float, r: Float, k: Int, dr: Float, off: Float) {
        for (i in 0 until k) {
            val a = off + PI.toFloat() / k + 2f * PI.toFloat() * i / k
            out += Prim.Circle(cx + cos(a) * r, cy + sin(a) * r, max(3f, dr))
        }
    }

    private fun leaves(out: MutableList<Prim>, cx: Float, cy: Float, r0: Float, r1: Float, k: Int, off: Float) {
        for (i in 0 until k) {
            val a = off + 2f * PI.toFloat() * i / k
            val half = PI.toFloat() / k * 0.95f
            val pts = ArrayList<Float>()
            val steps = 28
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val ang = a - half * sin(PI.toFloat() * t)
                val rr = r0 + (r1 - r0) * t
                pts += cx + cos(ang) * rr; pts += cy + sin(ang) * rr
            }
            for (s in steps downTo 0) {
                val t = s.toFloat() / steps
                val ang = a + half * sin(PI.toFloat() * t)
                val rr = r0 + (r1 - r0) * t
                pts += cx + cos(ang) * rr; pts += cy + sin(ang) * rr
            }
            out += Prim.Poly(pts.toFloatArray(), true)
            // Center vein.
            out += Prim.Poly(floatArrayOf(
                cx + cos(a) * r0, cy + sin(a) * r0,
                cx + cos(a) * (r0 + (r1 - r0) * 0.8f), cy + sin(a) * (r0 + (r1 - r0) * 0.8f),
            ), false)
        }
    }

    // ---------------------------------------------------------- stained glass

    private fun stainedGlass(rnd: Random, w: Int, h: Int): List<Prim> {
        val out = ArrayList<Prim>()
        val cell = min(w, h) / (5f + rnd.nextInt(4))
        val seeds = ArrayList<FloatArray>()
        var y = cell / 2f
        var row = 0
        while (y < h + cell / 2f) {
            var x = if (row % 2 == 0) cell / 2f else cell
            while (x < w + cell / 2f) {
                seeds += floatArrayOf(
                    x + (rnd.nextFloat() - 0.5f) * cell * 0.8f,
                    y + (rnd.nextFloat() - 0.5f) * cell * 0.8f,
                )
                x += cell
            }
            y += cell * 0.87f
            row++
        }
        val m = min(w, h) * 0.03f
        val frame = floatArrayOf(m, m, w - m, m, w - m, h - m, m, h - m)
        for (s in seeds) {
            var poly = frame
            for (o in seeds) {
                if (o === s) continue
                val dx = o[0] - s[0]; val dy = o[1] - s[1]
                if (dx * dx + dy * dy > cell * cell * 9f) continue
                val mx = (s[0] + o[0]) / 2f; val my = (s[1] + o[1]) / 2f
                // Keep the half-plane on s's side of the bisector.
                poly = clip(poly, dx, dy, dx * mx + dy * my)
                if (poly.size < 6) break
            }
            if (poly.size >= 6) out += Prim.Poly(poly, true)
        }
        out += Prim.Poly(frame, true)
        // A centerpiece circle cutting through the panes adds more regions.
        val cr = min(w, h) * (0.18f + rnd.nextFloat() * 0.12f)
        out += Prim.Circle(w / 2f, h / 2f, cr)
        out += Prim.Circle(w / 2f, h / 2f, cr * 0.55f)
        return out
    }

    /** Sutherland–Hodgman clip keeping points with nx*x + ny*y <= c. */
    private fun clip(poly: FloatArray, nx: Float, ny: Float, c: Float): FloatArray {
        val n = poly.size / 2
        val out = ArrayList<Float>(poly.size + 4)
        for (i in 0 until n) {
            val ax = poly[2 * i]; val ay = poly[2 * i + 1]
            val j = (i + 1) % n
            val bx = poly[2 * j]; val by = poly[2 * j + 1]
            val da = nx * ax + ny * ay - c
            val db = nx * bx + ny * by - c
            if (da <= 0f) { out += ax; out += ay }
            if ((da < 0f && db > 0f) || (da > 0f && db < 0f)) {
                val t = da / (da - db)
                out += ax + (bx - ax) * t; out += ay + (by - ay) * t
            }
        }
        return out.toFloatArray()
    }

    // ------------------------------------------------------------------ quilt

    private fun quilt(rnd: Random, w: Int, h: Int): List<Prim> {
        val out = ArrayList<Prim>()
        val cols = 4 + rnd.nextInt(3)
        val margin = min(w, h) * 0.04f
        val size = (min(w, h) - 2 * margin) / cols
        val rows = max(1, ((h - 2 * margin) / size).toInt())
        val ox = (w - cols * size) / 2f
        val oy = (h - rows * size) / 2f
        val motifs = IntArray(4) { rnd.nextInt(8) }
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = ox + c * size; val y0 = oy + r * size
            val x1 = x0 + size; val y1 = y0 + size
            out += rect(x0, y0, x1, y1)
            val motif = motifs[(r + c) % motifs.size]
            val flip = (r + c) % 2 == 1
            val cxm = (x0 + x1) / 2f; val cym = (y0 + y1) / 2f
            when (motif) {
                0 -> out += Prim.Poly(if (flip) floatArrayOf(x0, y0, x1, y1) else floatArrayOf(x1, y0, x0, y1), false)
                1 -> { out += rect(x0 + size * 0.2f, y0 + size * 0.2f, x1 - size * 0.2f, y1 - size * 0.2f)
                    out += rect(x0 + size * 0.38f, y0 + size * 0.38f, x1 - size * 0.38f, y1 - size * 0.38f) }
                2 -> { out += arc(if (flip) x0 else x1, y0, size * 0.5f, 0f, 2f * PI.toFloat())
                    out += arc(if (flip) x1 else x0, y1, size * 0.5f, 0f, 2f * PI.toFloat()) }
                3 -> out += Prim.Poly(floatArrayOf(cxm, y0, x1, cym, cxm, y1, x0, cym), true)
                4 -> { out += Prim.Circle(cxm, cym, size * 0.36f); out += Prim.Circle(cxm, cym, size * 0.18f) }
                5 -> { out += Prim.Poly(floatArrayOf(x0, y0, x1, y1), false); out += Prim.Poly(floatArrayOf(x1, y0, x0, y1), false)
                    out += Prim.Circle(cxm, cym, size * 0.18f) }
                6 -> for (k in 1..3) out += arc(x0, y0, size * k / 3.2f, 0f, PI.toFloat() / 2f)
                else -> { out += Prim.Poly(floatArrayOf(x0, cym, x1, cym), false); out += Prim.Poly(floatArrayOf(cxm, y0, cxm, y1), false)
                    out += Prim.Poly(floatArrayOf(cxm, y0 + size * 0.15f, x1 - size * 0.15f, cym, cxm, y1 - size * 0.15f, x0 + size * 0.15f, cym), true) }
            }
        }
        return out.map { clipPrimToRect(it, ox, oy, ox + cols * size, oy + rows * size) }
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        Prim.Poly(floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), true)

    private fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float): Prim.Poly {
        val steps = max(8, (abs(a1 - a0) * r / 4f).toInt().coerceAtMost(240))
        val pts = FloatArray((steps + 1) * 2)
        for (s in 0..steps) {
            val a = a0 + (a1 - a0) * s / steps
            pts[2 * s] = cx + cos(a) * r; pts[2 * s + 1] = cy + sin(a) * r
        }
        return Prim.Poly(pts, false)
    }

    /** Clamps points into the quilt area so motifs never spill past the border. */
    private fun clipPrimToRect(p: Prim, l: Float, t: Float, r: Float, b: Float): Prim = when (p) {
        is Prim.Poly -> Prim.Poly(FloatArray(p.pts.size) { i ->
            if (i % 2 == 0) p.pts[i].coerceIn(l, r) else p.pts[i].coerceIn(t, b)
        }, p.closed)
        is Prim.Circle -> {
            if (p.cx - p.r >= l && p.cx + p.r <= r && p.cy - p.r >= t && p.cy + p.r <= b) p
            else clipPrimToRect(arc(p.cx, p.cy, p.r, 0f, 2f * PI.toFloat()), l, t, r, b)
        }
    }

    // --------------------------------------------------------------- rosette

    private fun rosette(rnd: Random, w: Int, h: Int): List<Prim> {
        val out = ArrayList<Prim>()
        val cx = w / 2f; val cy = h / 2f
        val rMax = min(w, h) * 0.45f
        val layers = 3 + rnd.nextInt(3)
        for (l in 0 until layers) {
            // Hypotrochoid with R = 1 and r = p/q, so the curve closes after p turns.
            val bigR = 1f
            val small = floatArrayOf(0.25f, 0.2f, 0.3f, 0.375f, 0.4f, 0.2857f, 0.1667f, 0.1429f)[rnd.nextInt(8)]
            val d = small * (0.7f + rnd.nextFloat() * 1.1f)
            val turns = closingTurns(small)
            val steps = 1600 * turns
            val pts = FloatArray(steps * 2)
            var maxR = 0f
            for (s in 0 until steps) {
                val t = 2f * PI.toFloat() * turns * s / steps
                val x = (bigR - small) * cos(t) + d * cos((bigR - small) / small * t)
                val y = (bigR - small) * sin(t) - d * sin((bigR - small) / small * t)
                pts[2 * s] = x; pts[2 * s + 1] = y
                maxR = max(maxR, sqrt(x * x + y * y))
            }
            val scale = rMax * (1f - l * 0.17f) / maxR
            val rot = rnd.nextFloat() * 2f * PI.toFloat()
            val co = cos(rot); val si = sin(rot)
            for (s in 0 until steps) {
                val x = pts[2 * s] * scale; val y = pts[2 * s + 1] * scale
                pts[2 * s] = cx + x * co - y * si; pts[2 * s + 1] = cy + x * si + y * co
            }
            out += Prim.Poly(pts, true)
        }
        // Concentric guide rings slice the loops into many more small regions.
        val rings = 2 + rnd.nextInt(3)
        for (k in 1..rings) out += Prim.Circle(cx, cy, rMax * k / (rings + 1f))
        out += Prim.Circle(cx, cy, rMax * 1.04f)
        out += Prim.Circle(cx, cy, rMax * 0.06f)
        // Corner flourishes so the page doesn't feel empty around the circle.
        val cr = min(w, h) * 0.12f
        for ((qx, qy) in listOf(0f to 0f, w.toFloat() to 0f, 0f to h.toFloat(), w.toFloat() to h.toFloat())) {
            for (k in 1..3) out += clipPrimToRect(Prim.Circle(qx, qy, cr * k / 1.5f), 0f, 0f, w.toFloat(), h.toFloat())
        }
        return out
    }

    /** For R = 1 and r = p/q in lowest terms, the curve closes after p turns. */
    private fun closingTurns(r: Float): Int {
        for (q in 1..12) {
            val p = r * q
            if (abs(p - Math.round(p)) < 1e-3) return max(1, Math.round(p))
        }
        return 12
    }

    // ----------------------------------------------------------------- bloom

    private fun bloom(rnd: Random, w: Int, h: Int): List<Prim> {
        val out = ArrayList<Prim>()
        val count = 3 + rnd.nextInt(4)
        val placed = ArrayList<FloatArray>()
        val minDim = min(w, h)
        var tries = 0
        while (placed.size < count && tries < 200) {
            tries++
            val r = minDim * (0.12f + rnd.nextFloat() * 0.14f)
            val x = r + rnd.nextFloat() * (w - 2 * r)
            val y = r + rnd.nextFloat() * (h - 2 * r)
            if (placed.any { Mathx.hypot(it[0] - x, it[1] - y) < (it[2] + r) * 0.95f }) continue
            placed += floatArrayOf(x, y, r)
        }
        for ((idx, f) in placed.withIndex()) {
            val (x, y, r) = Triple(f[0], f[1], f[2])
            val k = 5 + (idx + rnd.nextInt(4)) % 5
            // Stem toward the bottom edge, only if no other flower sits in its way.
            val sway = (rnd.nextFloat() - 0.5f) * r
            val bottom = h * 0.99f
            val blocked = placed.any { o ->
                o !== f && o[1] > y && abs(o[0] - x) < o[2] + abs(sway) + r * 0.1f
            }
            if (!blocked) {
                val stemPts = ArrayList<Float>()
                for (s in 0..30) {
                    val t = s / 30f
                    stemPts += x + sway * sin(PI.toFloat() * t); stemPts += y + r + (bottom - y - r) * t
                }
                out += Prim.Poly(stemPts.toFloatArray(), false)
                // A leaf on the stem.
                val ly = y + r + (bottom - y - r) * 0.45f
                val lx = x + sway * sin(PI.toFloat() * 0.45f)
                val dir = if (rnd.nextBoolean()) 1f else -1f
                val lp = ArrayList<Float>()
                for (s in 0..20) { val t = s / 20f; lp += lx + dir * r * 0.7f * t; lp += ly - r * 0.25f * sin(PI.toFloat() * t) - r * 0.2f * t }
                for (s in 20 downTo 0) { val t = s / 20f; lp += lx + dir * r * 0.7f * t; lp += ly + r * 0.12f * sin(PI.toFloat() * t) - r * 0.2f * t }
                out += Prim.Poly(lp.toFloatArray(), true)
            }
            val off = rnd.nextFloat() * PI.toFloat()
            leaves(out, x, y, r * 0.28f, r, k, off)
            petals(out, x, y, r * 0.28f, r * 0.72f, k, off + PI.toFloat() / k, 0.7f)
            out += Prim.Circle(x, y, r * 0.28f)
            dots(out, x, y, r * 0.15f, k, r * 0.04f, off)
        }
        return out
    }
}
