package io.github.hashirakb.penumbra.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Photo-to-drawing filters. All work on ARGB pixel arrays and return black ink with alpha,
 * so the result sits on a transparent layer and can be colored underneath.
 */
object LineArt {

    /** Luminance in 0..1, with transparent pixels treated as white paper. */
    fun luminance(px: IntArray): FloatArray {
        val out = FloatArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) / 255f
            val r = ((c shr 16) and 0xff) / 255f
            val g = ((c shr 8) and 0xff) / 255f
            val b = (c and 0xff) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            out[i] = l * a + (1f - a)
        }
        return out
    }

    /**
     * Clean outlines (difference-of-Gaussians with soft threshold).
     * [detail] 0..1 picks finer edges as it rises; [strength] 0..1 makes lines darker and bolder.
     */
    fun outline(px: IntArray, w: Int, h: Int, detail: Float, strength: Float): IntArray {
        // Run the edge detector on luminance and on each color channel, keeping the strongest
        // response, so edges between equally bright colors (yellow on light blue) still show.
        val sigma = Mathx.lerp(2.4f, 0.7f, Mathx.clamp01(detail))
        val tau = 0.985f
        val phi = Mathx.lerp(40f, 260f, Mathx.clamp01(strength))
        val eps = Mathx.lerp(0.002f, -0.004f, Mathx.clamp01(strength))
        val ink = FloatArray(px.size)
        val channels = listOf(luminance(px), channel(px, 16), channel(px, 8), channel(px, 0))
        for ((ci, ch) in channels.withIndex()) {
            val g1 = gaussian(ch, w, h, sigma)
            val g2 = gaussian(ch, w, h, sigma * 1.6f)
            val weight = if (ci == 0) 1f else 0.85f
            for (i in ink.indices) {
                val d = g1[i] - tau * g2[i]
                val e = if (d >= eps) 1f else 1f + tanh(phi * (d - eps))
                val v = Mathx.clamp01(1f - e) * weight
                if (v > ink[i]) ink[i] = v
            }
        }
        return IntArray(px.size) { ink(ink[it]) }
    }

    /** One color channel (bit shift 16 = red, 8 = green, 0 = blue) in 0..1 over white. */
    fun channel(px: IntArray, shift: Int): FloatArray {
        val out = FloatArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) / 255f
            out[i] = ((c shr shift) and 0xff) / 255f * a + (1f - a)
        }
        return out
    }

    /** Graphite sketch with soft shading (inverted-blur color dodge). */
    fun sketch(px: IntArray, w: Int, h: Int, softness: Float, darkness: Float): IntArray {
        val lum = luminance(px)
        val inv = FloatArray(lum.size) { 1f - lum[it] }
        val blurred = gaussian(inv, w, h, Mathx.lerp(2f, 12f, Mathx.clamp01(softness)))
        val gamma = Mathx.lerp(1.6f, 0.6f, Mathx.clamp01(darkness))
        val out = IntArray(px.size)
        for (i in out.indices) {
            val denom = max(1e-3f, 1f - blurred[i])
            val dodge = min(1f, lum[i] / denom)
            val ink = Math.pow((1f - dodge).toDouble(), gamma.toDouble()).toFloat()
            out[i] = ink(Mathx.clamp01(ink * 1.15f))
        }
        return out
    }

    /** Posterized paint-by-numbers style regions with outlines, for coloring photos. */
    fun regions(px: IntArray, w: Int, h: Int, levels: Int): IntArray {
        val lum = gaussian(luminance(px), w, h, 2.2f)
        val q = IntArray(lum.size) { (lum[it] * levels).toInt().coerceIn(0, levels - 1) }
        val out = IntArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val v = q[i]
                val edge = (x + 1 < w && q[i + 1] != v) || (y + 1 < h && q[i + w] != v) ||
                    (x > 0 && q[i - 1] != v) || (y > 0 && q[i - w] != v)
                out[i] = if (edge) ink(1f) else 0
            }
        }
        return out
    }

    private fun ink(a: Float): Int = ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0x111111

    /** Gaussian blur approximated with three box blurs. */
    fun gaussian(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val boxes = boxesForGauss(sigma, 3)
        var a = src.copyOf()
        var b = FloatArray(src.size)
        for (r in boxes) {
            boxBlurH(a, b, w, h, r)
            boxBlurV(b, a, w, h, r)
        }
        return a
    }

    private fun boxesForGauss(sigma: Float, n: Int): IntArray {
        val wIdeal = sqrt((12f * sigma * sigma / n) + 1f)
        var wl = wIdeal.toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - n * wl * wl - 4f * n * wl - 3f * n) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) (wl - 1) / 2 else (wu - 1) / 2 }
    }

    private fun boxBlurH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, src.size); return }
        val iarr = 1f / (r + r + 1)
        for (y in 0 until h) {
            val row = y * w
            val first = src[row]
            var acc = (r + 1) * first
            for (j in 0 until r) acc += src[row + min(j, w - 1)]
            for (x in 0 until w) {
                acc += src[row + min(x + r, w - 1)] - (if (x - r - 1 >= 0) src[row + x - r - 1] else first)
                dst[row + x] = acc * iarr
            }
        }
    }

    private fun boxBlurV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, src.size); return }
        val iarr = 1f / (r + r + 1)
        for (x in 0 until w) {
            val first = src[x]
            var acc = (r + 1) * first
            for (j in 0 until r) acc += src[min(j, h - 1) * w + x]
            for (y in 0 until h) {
                acc += src[min(y + r, h - 1) * w + x] - (if (y - r - 1 >= 0) src[(y - r - 1) * w + x] else first)
                dst[y * w + x] = acc * iarr
            }
        }
    }
}
