package io.github.hashirakb.penumbra.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Growable store of stroke samples in document space.
 * Each sample: x, y, pressure (0..1, already curved), tilt (radians, 0 = upright),
 * axis angle (radians, direction of the pen's shadow on the page), time (ms from stroke start).
 */
class StrokePoints(capacity: Int = 256) {
    var data = FloatArray(maxOf(1, capacity) * STRIDE)
        private set
    var size = 0
        private set

    fun add(x: Float, y: Float, p: Float, tilt: Float, axis: Float, t: Float) {
        ensure(size + 1)
        val o = size * STRIDE
        data[o] = x; data[o + 1] = y; data[o + 2] = p
        data[o + 3] = tilt; data[o + 4] = axis; data[o + 5] = t
        size++
    }

    fun x(i: Int) = data[i * STRIDE]
    fun y(i: Int) = data[i * STRIDE + 1]
    fun p(i: Int) = data[i * STRIDE + 2]
    fun tilt(i: Int) = data[i * STRIDE + 3]
    fun axis(i: Int) = data[i * STRIDE + 4]
    fun t(i: Int) = data[i * STRIDE + 5]

    fun clear() { size = 0 }

    fun copy(): StrokePoints {
        val c = StrokePoints(size)
        System.arraycopy(data, 0, c.data, 0, size * STRIDE)
        c.size = size
        return c
    }

    /** Copy of the first [n] samples. */
    fun prefix(n: Int): StrokePoints {
        val k = n.coerceIn(0, size)
        val c = StrokePoints(k)
        System.arraycopy(data, 0, c.data, 0, k * STRIDE)
        c.size = k
        return c
    }

    private fun ensure(n: Int) {
        if (n * STRIDE > data.size) data = data.copyOf(maxOf(n * STRIDE, data.size * 2))
    }

    companion object {
        const val STRIDE = 6
        fun fromRaw(raw: FloatArray, count: Int): StrokePoints {
            val s = StrokePoints(count)
            System.arraycopy(raw, 0, s.data, 0, count * STRIDE)
            s.size = count
            return s
        }
    }
}

/**
 * Growable list of brush dabs: x, y, radius, alpha (0..1), angle (radians), aspect (minor/major).
 */
class DabList(capacity: Int = 512) {
    var data = FloatArray(capacity * STRIDE)
        private set
    var size = 0
        private set

    fun add(x: Float, y: Float, r: Float, a: Float, angle: Float, aspect: Float) {
        if ((size + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
        val o = size * STRIDE
        data[o] = x; data[o + 1] = y; data[o + 2] = r
        data[o + 3] = a; data[o + 4] = angle; data[o + 5] = aspect
        size++
    }

    fun x(i: Int) = data[i * STRIDE]
    fun y(i: Int) = data[i * STRIDE + 1]
    fun r(i: Int) = data[i * STRIDE + 2]
    fun alpha(i: Int) = data[i * STRIDE + 3]
    fun angle(i: Int) = data[i * STRIDE + 4]
    fun aspect(i: Int) = data[i * STRIDE + 5]

    fun clear() { size = 0 }

    /** Bounding box of dabs [from, to) expanded by [pad]; returns false if empty. */
    fun bounds(from: Int, to: Int, pad: Float, out: FloatArray): Boolean {
        if (to <= from) return false
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in from until to) {
            val rr = r(i) + pad
            val x = x(i); val y = y(i)
            if (x - rr < l) l = x - rr
            if (y - rr < t) t = y - rr
            if (x + rr > r) r = x + rr
            if (y + rr > b) b = y + rr
        }
        out[0] = l; out[1] = t; out[2] = r; out[3] = b
        return true
    }

    companion object { const val STRIDE = 6 }
}

/** 2D affine transform: x' = a*x + c*y + tx, y' = b*x + d*y + ty. */
class Affine(
    val a: Float, val b: Float, val c: Float, val d: Float,
    val tx: Float, val ty: Float,
) {
    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** Maps a direction angle through the linear part. */
    fun mapAngle(angle: Float): Float {
        val dx = cos(angle); val dy = sin(angle)
        return atan2(b * dx + d * dy, a * dx + c * dy)
    }

    val isIdentity get() = a == 1f && b == 0f && c == 0f && d == 1f && tx == 0f && ty == 0f

    /** Composition: apply this transform first, then [o]. */
    fun then(o: Affine): Affine = Affine(
        a = o.a * a + o.c * b, b = o.b * a + o.d * b,
        c = o.a * c + o.c * d, d = o.b * c + o.d * d,
        tx = o.a * tx + o.c * ty + o.tx, ty = o.b * tx + o.d * ty + o.ty,
    )

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)
        fun rotation(theta: Float, cx: Float, cy: Float): Affine {
            val co = cos(theta); val si = sin(theta)
            return Affine(co, si, -si, co, cx - co * cx + si * cy, cy - si * cx - co * cy)
        }
        fun mirrorX(cx: Float) = Affine(-1f, 0f, 0f, 1f, 2 * cx, 0f)
        fun mirrorY(cy: Float) = Affine(1f, 0f, 0f, -1f, 0f, 2 * cy)
    }
}

enum class SymmetryMode { OFF, VERTICAL, HORIZONTAL, QUAD, RADIAL, KALEIDOSCOPE }

class SymmetryConfig(
    val mode: SymmetryMode = SymmetryMode.OFF,
    val segments: Int = 8,
    val cx: Float = 0f,
    val cy: Float = 0f,
) {
    fun transforms(): List<Affine> = when (mode) {
        SymmetryMode.OFF -> listOf(Affine.IDENTITY)
        SymmetryMode.VERTICAL -> listOf(Affine.IDENTITY, Affine.mirrorX(cx))
        SymmetryMode.HORIZONTAL -> listOf(Affine.IDENTITY, Affine.mirrorY(cy))
        SymmetryMode.QUAD -> listOf(
            Affine.IDENTITY, Affine.mirrorX(cx), Affine.mirrorY(cy),
            Affine.rotation(Math.PI.toFloat(), cx, cy),
        )
        SymmetryMode.RADIAL -> (0 until segments.coerceIn(2, 32)).map {
            Affine.rotation((2 * Math.PI * it / segments.coerceIn(2, 32)).toFloat(), cx, cy)
        }
        SymmetryMode.KALEIDOSCOPE -> {
            val n = segments.coerceIn(2, 32)
            val m = Affine.mirrorX(cx)
            (0 until n).flatMap {
                val r = Affine.rotation((2 * Math.PI * it / n).toFloat(), cx, cy)
                listOf(r, m.then(r))
            }
        }
    }

    fun withCenter(x: Float, y: Float) = SymmetryConfig(mode, segments, x, y)
    fun withMode(m: SymmetryMode) = SymmetryConfig(m, segments, cx, cy)
    fun withSegments(n: Int) = SymmetryConfig(mode, n, cx, cy)
}

object Mathx {
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    fun hypot(x: Float, y: Float) = kotlin.math.sqrt(x * x + y * y)
}
