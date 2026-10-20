package io.github.hashirakb.penumbra.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Hold to snap": recognizes a roughly drawn line, circle or ellipse and returns a clean version.
 */
object ShapeRecognizer {
    enum class Kind { LINE, CIRCLE, ELLIPSE }

    class Result(val kind: Kind, val points: StrokePoints)

    fun recognize(s: StrokePoints): Result? {
        if (s.size < 4) return null
        val pathLen = pathLength(s)
        if (pathLen < 24f) return null

        var avgP = 0f; var avgTilt = 0f
        for (i in 0 until s.size) { avgP += s.p(i); avgTilt += s.tilt(i) }
        avgP /= s.size; avgTilt /= s.size
        val axis = s.axis(s.size - 1)
        val t0 = s.t(0); val t1 = s.t(s.size - 1)

        val x0 = s.x(0); val y0 = s.y(0)
        val x1 = s.x(s.size - 1); val y1 = s.y(s.size - 1)
        val chord = Mathx.hypot(x1 - x0, y1 - y0)

        // Line: every sample close to the chord.
        if (chord > 0.6f * pathLen) {
            var maxDev = 0f
            for (i in 0 until s.size) {
                val d = abs((x1 - x0) * (y0 - s.y(i)) - (x0 - s.x(i)) * (y1 - y0)) / chord
                if (d > maxDev) maxDev = d
            }
            if (maxDev < max(5f, 0.06f * chord)) {
                val out = StrokePoints(64)
                val n = max(2, (chord / 4f).toInt().coerceAtMost(400))
                for (k in 0..n) {
                    val t = k.toFloat() / n
                    out.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, avgP, avgTilt, axis, t0 + (t1 - t0) * t)
                }
                return Result(Kind.LINE, out)
            }
            return null
        }

        // Closed shape: endpoints meet.
        if (chord > 0.22f * pathLen || pathLen < 60f) return null
        val r = resample(s, 96)
        val n = r.size / 2
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += r[2 * i]; cy += r[2 * i + 1] }
        cx /= n; cy /= n
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 0 until n) {
            val dx = r[2 * i] - cx; val dy = r[2 * i + 1] - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= n; syy /= n; sxy /= n
        // Principal axes of the covariance matrix.
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0f, tr * tr / 4f - det))
        val l1 = tr / 2f + disc
        val l2 = max(1e-3f, tr / 2f - disc)
        val theta = 0.5f * atan2(2f * sxy, sxx - syy)
        var a = sqrt(2f * l1)
        var b = sqrt(2f * l2)
        if (b < 6f) return null

        // Validate: points lie near the ellipse.
        val ct = cos(theta); val st = sin(theta)
        var err = 0f
        for (i in 0 until n) {
            val dx = r[2 * i] - cx; val dy = r[2 * i + 1] - cy
            val u = dx * ct + dy * st
            val v = -dx * st + dy * ct
            val q = sqrt((u / a) * (u / a) + (v / b) * (v / b))
            err += abs(q - 1f)
        }
        err /= n
        if (err > 0.13f) return null

        val kind = if (a / b < 1.15f) Kind.CIRCLE else Kind.ELLIPSE
        if (kind == Kind.CIRCLE) { val m = (a + b) / 2f; a = m; b = m }
        val out = StrokePoints(160)
        val steps = (2 * PI * max(a, b) / 3.0).toInt().coerceIn(48, 720)
        // Start the shape where the user started drawing.
        val sdx = x0 - cx; val sdy = y0 - cy
        val startPhi = atan2((-sdx * st + sdy * ct) / b, (sdx * ct + sdy * st) / a)
        for (k in 0..steps) {
            val phi = startPhi + 2f * PI.toFloat() * k / steps
            val u = a * cos(phi); val v = b * sin(phi)
            out.add(cx + u * ct - v * st, cy + u * st + v * ct, avgP, avgTilt, axis,
                t0 + (t1 - t0) * k / steps)
        }
        return Result(kind, out)
    }

    fun pathLength(s: StrokePoints): Float {
        var len = 0f
        for (i in 1 until s.size) len += Mathx.hypot(s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1))
        return len
    }

    /** Resamples the stroke to [n] points evenly spaced by arc length; returns xy pairs. */
    fun resample(s: StrokePoints, n: Int): FloatArray {
        val total = pathLength(s)
        val out = FloatArray(n * 2)
        if (total <= 0f) {
            for (k in 0 until n) { out[2 * k] = s.x(0); out[2 * k + 1] = s.y(0) }
            return out
        }
        val step = total / (n - 1)
        var seg = 1
        var segStart = 0f
        for (k in 0 until n) {
            val target = k * step
            while (seg < s.size - 1 &&
                segStart + Mathx.hypot(s.x(seg) - s.x(seg - 1), s.y(seg) - s.y(seg - 1)) < target
            ) {
                segStart += Mathx.hypot(s.x(seg) - s.x(seg - 1), s.y(seg) - s.y(seg - 1))
                seg++
            }
            val segLen = Mathx.hypot(s.x(seg) - s.x(seg - 1), s.y(seg) - s.y(seg - 1))
            val t = if (segLen > 0f) Mathx.clamp01((target - segStart) / segLen) else 0f
            out[2 * k] = s.x(seg - 1) + (s.x(seg) - s.x(seg - 1)) * t
            out[2 * k + 1] = s.y(seg - 1) + (s.y(seg) - s.y(seg - 1)) * t
        }
        return out
    }
}
