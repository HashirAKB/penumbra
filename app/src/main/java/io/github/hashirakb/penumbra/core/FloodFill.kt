package io.github.hashirakb.penumbra.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Flood fill with gap closing, made for coloring line art:
 *  1. pixels similar to the seed color form the fillable area;
 *  2. the boundary is temporarily thickened by [gap] pixels so small breaks in the lines
 *     don't let color leak into the next region;
 *  3. the fill then grows back up to the real lines and [expand] pixels under them,
 *     so no white halo is left along anti-aliased edges.
 */
object FloodFill {
    class Result(
        val mask: ByteArray,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val count: Int,
    )

    fun compute(
        px: IntArray, w: Int, h: Int, sx: Int, sy: Int,
        tolerance: Int, gap: Int, expand: Int,
    ): Result? {
        if (sx !in 0 until w || sy !in 0 until h) return null
        val n = w * h
        val seed = px[sy * w + sx]
        val similar = ByteArray(n)
        val tol = tolerance.coerceIn(0, 255)
        for (i in 0 until n) if (dist(px[i], seed) <= tol) similar[i] = 1

        val mask = ByteArray(n)
        val queue = IntArray(n)
        var filled = 0
        val g = gap.coerceIn(0, 32)
        if (g > 0) {
            // Passable = similar pixels not within g of any barrier pixel.
            val passable = erode(similar, w, h, g)
            if (passable[sy * w + sx].toInt() == 1) {
                filled = scanFill(passable, w, h, sx, sy, mask)
                // Grow back toward the true lines (only through similar pixels).
                filled += grow(mask, w, h, g + 1, similar, queue)
            }
        }
        if (filled == 0) filled = scanFill(similar, w, h, sx, sy, mask)
        if (filled == 0) return null
        if (expand > 0) filled += grow(mask, w, h, expand.coerceAtMost(8), null, queue)

        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (mask[row + x].toInt() != 0) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return Result(mask, l, t, r + 1, b + 1, filled)
    }

    /** Max per-channel difference including alpha. */
    fun dist(a: Int, b: Int): Int {
        val da = abs((a ushr 24) - (b ushr 24))
        val dr = abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
        val dg = abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
        val db = abs((a and 0xff) - (b and 0xff))
        return max(max(da, dr), max(dg, db))
    }

    /** Scanline fill of 1-pixels in [area] connected to the seed; marks [mask]. */
    fun scanFill(area: ByteArray, w: Int, h: Int, sx: Int, sy: Int, mask: ByteArray): Int {
        if (area[sy * w + sx].toInt() == 0 || mask[sy * w + sx].toInt() != 0) return 0
        var stack = IntArray(1024)
        var sp = 0
        var count = 0
        stack[sp++] = sy * w + sx
        while (sp > 0) {
            val idx = stack[--sp]
            val y = idx / w
            var x = idx - y * w
            val row = y * w
            if (mask[row + x].toInt() != 0) continue
            while (x > 0 && area[row + x - 1].toInt() != 0 && mask[row + x - 1].toInt() == 0) x--
            var spanUp = false
            var spanDown = false
            while (x < w && area[row + x].toInt() != 0 && mask[row + x].toInt() == 0) {
                mask[row + x] = 1
                count++
                if (y > 0) {
                    val up = row - w + x
                    val ok = area[up].toInt() != 0 && mask[up].toInt() == 0
                    if (ok && !spanUp) {
                        if (sp + 2 >= stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = up; spanUp = true
                    } else if (!ok) spanUp = false
                }
                if (y < h - 1) {
                    val dn = row + w + x
                    val ok = area[dn].toInt() != 0 && mask[dn].toInt() == 0
                    if (ok && !spanDown) {
                        if (sp + 2 >= stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = dn; spanDown = true
                    } else if (!ok) spanDown = false
                }
                x++
            }
        }
        return count
    }

    /** Erodes a binary area by a square of radius r (separable running min). */
    fun erode(area: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h)
        // Horizontal: a pixel survives if no zero within r on its row.
        // Forward pass gives distance to the previous zero, backward pass to the next.
        val prevRow = IntArray(w)
        for (y in 0 until h) {
            val row = y * w
            var lastZero = -100000
            val prev = prevRow
            for (x in 0 until w) {
                if (area[row + x].toInt() == 0) lastZero = x
                prev[x] = x - lastZero
            }
            var nextZero = 100000
            for (x in w - 1 downTo 0) {
                if (area[row + x].toInt() == 0) nextZero = x
                val d = minOf(prev[x], nextZero - x)
                tmp[row + x] = if (d > r) 1 else 0
            }
        }
        val out = ByteArray(w * h)
        val prev = IntArray(h)
        for (x in 0 until w) {
            var lastZero = -100000
            for (y in 0 until h) {
                if (tmp[y * w + x].toInt() == 0) lastZero = y
                prev[y] = y - lastZero
            }
            var nextZero = 100000
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x].toInt() == 0) nextZero = y
                val d = minOf(prev[y], nextZero - y)
                out[y * w + x] = if (d > r) 1 else 0
            }
        }
        return out
    }

    /**
     * Grows the mask outward by up to [steps] pixels (4-connected BFS rings).
     * When [allowed] is given, growth only enters allowed pixels. Returns pixels added.
     */
    fun grow(mask: ByteArray, w: Int, h: Int, steps: Int, allowed: ByteArray?, queue: IntArray): Int {
        var head = 0
        var tail = 0
        // Seed the frontier with mask pixels that touch a non-mask neighbour.
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (mask[i].toInt() == 0) continue
                if ((x > 0 && mask[i - 1].toInt() == 0) || (x < w - 1 && mask[i + 1].toInt() == 0) ||
                    (y > 0 && mask[i - w].toInt() == 0) || (y < h - 1 && mask[i + w].toInt() == 0)
                ) queue[tail++] = i
            }
        }
        var added = 0
        for (step in 0 until steps) {
            val end = tail
            if (head == end) break
            while (head < end) {
                val i = queue[head++]
                val y = i / w
                val x = i - y * w
                if (x > 0) added += tryAdd(i - 1, mask, allowed, queue, tail).also { if (it > 0) tail++ }
                if (x < w - 1) added += tryAdd(i + 1, mask, allowed, queue, tail).also { if (it > 0) tail++ }
                if (y > 0) added += tryAdd(i - w, mask, allowed, queue, tail).also { if (it > 0) tail++ }
                if (y < h - 1) added += tryAdd(i + w, mask, allowed, queue, tail).also { if (it > 0) tail++ }
            }
            // Compact the queue when it nears the end of the buffer.
            if (tail >= queue.size - 4 * w - 8 && head > 0) {
                System.arraycopy(queue, head, queue, 0, tail - head)
                tail -= head; head = 0
            }
        }
        return added
    }

    private fun tryAdd(j: Int, mask: ByteArray, allowed: ByteArray?, queue: IntArray, tail: Int): Int {
        if (mask[j].toInt() != 0) return 0
        if (allowed != null && allowed[j].toInt() == 0) return 0
        if (tail >= queue.size) return 0
        mask[j] = 1
        queue[tail] = j
        return 1
    }
}
