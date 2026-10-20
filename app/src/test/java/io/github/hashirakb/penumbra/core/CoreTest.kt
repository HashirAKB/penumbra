package io.github.hashirakb.penumbra.core

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DabGeneratorTest {
    private fun line(n: Int = 100): StrokePoints {
        val pts = StrokePoints()
        for (i in 0..n) pts.add(i * 2f, 50f, i / n.toFloat(), 0f, 0f, i * 8f)
        return pts
    }

    @Test fun deterministicAndIncremental() {
        val pts = line()
        val a = DabList(); DabGenerator(BrushPresets.WATERCOLOR, 30f, 7L, listOf(Affine.IDENTITY), a).addAll(pts)
        val b = DabList(); val g = DabGenerator(BrushPresets.WATERCOLOR, 30f, 7L, listOf(Affine.IDENTITY), b)
        for (i in 0 until pts.size) g.add(pts.x(i), pts.y(i), pts.p(i), pts.tilt(i), pts.axis(i))
        assertEquals(a.size, b.size)
        for (i in 0 until a.size * DabList.STRIDE) assertEquals(a.data[i], b.data[i])
    }

    @Test fun pressureControlsSize() {
        val d = DabList(); DabGenerator(BrushPresets.INK, 10f, 1L, listOf(Affine.IDENTITY), d).addAll(line())
        assertTrue(d.size > 50)
        assertTrue(d.r(0) < d.r(d.size - 1))
    }

    @Test fun radialSymmetry() {
        val t = SymmetryConfig(SymmetryMode.RADIAL, 6, 100f, 100f).transforms()
        val d = DabList(); DabGenerator(BrushPresets.INK, 10f, 1L, t, d).add(150f, 100f, 1f, 0f, 0f)
        assertEquals(6, d.size)
        assertTrue(abs(d.x(3) - 50f) < 0.01f && abs(d.y(3) - 100f) < 0.01f)
        assertEquals(8, SymmetryConfig(SymmetryMode.KALEIDOSCOPE, 4, 0f, 0f).transforms().size)
    }
}

class ShapeRecognizerTest {
    @Test fun recognizesLineCircleEllipse() {
        val line = StrokePoints()
        for (i in 0..60) line.add(10f + i * 5f, 20f + i * 2f + (if (i % 2 == 0) 1.5f else -1.5f), 0.5f, 0f, 0f, i * 10f)
        assertEquals(ShapeRecognizer.Kind.LINE, ShapeRecognizer.recognize(line)?.kind)

        val circ = StrokePoints()
        for (i in 0..100) {
            val a = 2 * PI * i / 100; val r = 80f + (if (i % 3 == 0) 3f else -2f)
            circ.add(200f + (cos(a) * r).toFloat(), 200f + (sin(a) * r).toFloat(), 0.5f, 0f, 0f, i * 10f)
        }
        assertEquals(ShapeRecognizer.Kind.CIRCLE, ShapeRecognizer.recognize(circ)?.kind)

        val ell = StrokePoints()
        for (i in 0..100) {
            val a = 2 * PI * i / 100; val x = cos(a) * 140; val y = sin(a) * 60; val rot = 0.5
            ell.add(300f + (x * cos(rot) - y * sin(rot)).toFloat(), 300f + (x * sin(rot) + y * cos(rot)).toFloat(), 0.5f, 0f, 0f, i * 10f)
        }
        assertEquals(ShapeRecognizer.Kind.ELLIPSE, ShapeRecognizer.recognize(ell)?.kind)

        val squiggle = StrokePoints()
        for (i in 0..100) squiggle.add(i * 4f, (sin(i * 0.3) * 60).toFloat() + 100f, 0.5f, 0f, 0f, i * 10f)
        assertNull(ShapeRecognizer.recognize(squiggle))
    }
}

class FloodFillTest {
    @Test fun gapClosingStopsLeaks() {
        val w = 200; val h = 120
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 0 until h) if (y != 60 && y != 61) { px[y * w + 100] = 0xFF000000.toInt(); px[y * w + 101] = 0xFF000000.toInt() }
        assertEquals(1, FloodFill.compute(px, w, h, 10, 10, 30, 0, 0)!!.mask[10 * w + 150].toInt())
        val closed = FloodFill.compute(px, w, h, 10, 10, 30, 3, 0)!!
        assertEquals(0, closed.mask[10 * w + 150].toInt())
        assertEquals(1, closed.mask[10 * w + 99].toInt())
        assertEquals(1, FloodFill.compute(px, w, h, 10, 10, 30, 3, 1)!!.mask[10 * w + 100].toInt())
    }
}

class JournalTest {
    @Test fun roundTrip() {
        val j = Journal(800, 600, 0xFFFFFFFF.toInt())
        val pts = StrokePoints(); for (i in 0..20) pts.add(i.toFloat(), i * 2f, 0.5f, 0.2f, 1f, i * 16f)
        j.add(JEvent.AddLayer(1, "Layer 1", 0))
        j.add(JEvent.Stroke(1, StrokeKind.PAINT, BrushPresets.PENCIL, 0xFF112233.toInt(), 12f, 0.8f, 99L,
            SymmetryConfig(SymmetryMode.QUAD, 4, 400f, 300f), false, pts))
        j.add(JEvent.Fill(1, 5, 6, 0xFFFF0000.toInt(), 20, 3, 1, true, false))
        j.add(JEvent.PlaceImage(1, "photo1.png", 0f, 0f, 800f, 600f))
        val bos = ByteArrayOutputStream(); j.write(bos)
        val back = Journal.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(4, back.events.size)
        val s = back.events[1] as JEvent.Stroke
        assertEquals(BrushPresets.PENCIL, s.brush)
        assertEquals(40f, s.points.y(20))
        assertEquals(setOf("photo1.png"), back.referencedAssets())
    }
}

class PagesAndLessonsTest {
    @Test fun everyStyleProducesShapes() {
        for (st in ColoringPages.Style.values()) {
            val page = ColoringPages.generate(st, 42L, 1200, 1600)
            assertTrue(page.prims.size >= 5, "${st.label} has ${page.prims.size} shapes")
        }
    }

    @Test fun lessonsProduceStrokes() {
        for (k in Lessons.Kind.values()) {
            val strokes = Lessons.strokes(k, 3L, 1536, 2048)
            assertTrue(strokes.size >= 5, "${k.title}: ${strokes.size} strokes")
            assertTrue(strokes.all { it.size in 2..900 })
        }
    }

    @Test fun lineArtInksEdgesOnly() {
        val w = 80; val h = 60
        val px = IntArray(w * h) { if (it % w < 40) 0xFF2050A0.toInt() else 0xFFF0F0F0.toInt() }
        val ink = LineArt.outline(px, w, h, 0.6f, 0.6f)
        assertTrue((ink[30 * w + 40] ushr 24) > 100 || (ink[30 * w + 39] ushr 24) > 100)
        assertTrue((ink[30 * w + 10] ushr 24) < 30)
    }

    @Test fun colorsRoundTrip() {
        val hsv = ColorMath.rgbToHsv(ColorMath.hsvToRgb(210f, 0.5f, 0.8f))
        assertTrue(abs(hsv[0] - 210f) < 1.5f)
        assertNotNull(ColorMath.parseHex("#FF8800"))
    }
}
