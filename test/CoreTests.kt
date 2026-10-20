import io.github.hashirakb.penumbra.core.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

var failures = 0
fun check(cond: Boolean, msg: String) {
    if (cond) println("  ok   $msg") else { println("  FAIL $msg"); failures++ }
}

fun renderPage(page: ColoringPages.Page, w: Int, h: Int): BufferedImage {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color.WHITE; g.fillRect(0, 0, w, h)
    g.color = Color.BLACK
    g.stroke = BasicStroke(page.lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    for (p in page.prims) when (p) {
        is ColoringPages.Prim.Circle -> g.draw(Ellipse2D.Float(p.cx - p.r, p.cy - p.r, 2 * p.r, 2 * p.r))
        is ColoringPages.Prim.Poly -> {
            val path = Path2D.Float()
            path.moveTo(p.pts[0], p.pts[1])
            var i = 2
            while (i < p.pts.size) { path.lineTo(p.pts[i], p.pts[i + 1]); i += 2 }
            if (p.closed) path.closePath()
            g.draw(path)
        }
    }
    g.dispose()
    return img
}

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "out" }).apply { mkdirs() }

    println("DabGenerator")
    run {
        val pts = StrokePoints()
        for (i in 0..100) pts.add(i * 2f, 50f, i / 100f, 0f, 0f, i * 8f)
        val a = DabList(); DabGenerator(BrushPresets.INK, 10f, 42L, listOf(Affine.IDENTITY), a).addAll(pts)
        val b = DabList(); DabGenerator(BrushPresets.INK, 10f, 42L, listOf(Affine.IDENTITY), b).addAll(pts)
        check(a.size > 50, "emits dabs along a 200px stroke (${a.size})")
        check(a.size == b.size && (0 until a.size * DabList.STRIDE).all { a.data[it] == b.data[it] }, "deterministic for same seed")
        check(a.r(0) < a.r(a.size - 1), "pressure grows size")
        var maxGap = 0f
        for (i in 1 until a.size) maxGap = maxOf(maxGap, a.x(i) - a.x(i - 1))
        check(maxGap <= 10f * 0.06f + 0.01f || maxGap <= 0.36f, "spacing respected (max gap $maxGap)")
        // Incremental feeding equals batch feeding.
        val c = DabList(); val gen = DabGenerator(BrushPresets.WATERCOLOR, 30f, 7L, listOf(Affine.IDENTITY), c)
        for (i in 0 until pts.size) gen.add(pts.x(i), pts.y(i), pts.p(i), pts.tilt(i), pts.axis(i))
        val d = DabList(); DabGenerator(BrushPresets.WATERCOLOR, 30f, 7L, listOf(Affine.IDENTITY), d).addAll(pts)
        check(c.size == d.size && (0 until c.size * 6).all { c.data[it] == d.data[it] }, "incremental == batch (jittered brush)")
        val sym = SymmetryConfig(SymmetryMode.RADIAL, 6, 100f, 100f).transforms()
        val e = DabList(); DabGenerator(BrushPresets.INK, 10f, 1L, sym, e).add(150f, 100f, 1f, 0f, 0f)
        check(e.size == 6, "radial symmetry emits 6 copies")
        val e2x = e.x(3); val e2y = e.y(3)
        check(kotlin.math.abs(e2x - 50f) < 0.01f && kotlin.math.abs(e2y - 100f) < 0.01f, "180° copy lands opposite ($e2x,$e2y)")
        val k = SymmetryConfig(SymmetryMode.KALEIDOSCOPE, 4, 0f, 0f).transforms()
        check(k.size == 8, "kaleidoscope 4 → 8 transforms")
        val m = Affine.mirrorX(10f)
        check(kotlin.math.abs(m.mapX(12f, 0f) - 8f) < 1e-5, "mirror maps x=12 → 8 around 10")
    }

    println("Stabilizer")
    run {
        val s = Stabilizer(0.8f); val o = FloatArray(3)
        s.push(0f, 0f, 1f, 0f, o)
        s.push(100f, 0f, 1f, 10f, o)
        check(o[0] in 1f..99f, "lags behind a jump (${o[0]})")
        val s0 = Stabilizer(0f); s0.push(0f, 0f, 1f, 0f, o); s0.push(100f, 0f, 1f, 10f, o)
        check(o[0] == 100f, "strength 0 is passthrough")
    }

    println("ShapeRecognizer")
    run {
        val line = StrokePoints()
        for (i in 0..60) line.add(10f + i * 5f, 20f + i * 2f + (if (i % 2 == 0) 1.5f else -1.5f), 0.5f, 0f, 0f, i * 10f)
        check(ShapeRecognizer.recognize(line)?.kind == ShapeRecognizer.Kind.LINE, "wobbly line → LINE")
        val circ = StrokePoints()
        for (i in 0..100) {
            val a = 2 * PI * i / 100; val r = 80f + (if (i % 3 == 0) 3f else -2f)
            circ.add(200f + (cos(a) * r).toFloat(), 200f + (sin(a) * r).toFloat(), 0.5f, 0f, 0f, i * 10f)
        }
        val cr = ShapeRecognizer.recognize(circ)
        check(cr?.kind == ShapeRecognizer.Kind.CIRCLE, "rough circle → CIRCLE (${cr?.kind})")
        val ell = StrokePoints()
        for (i in 0..100) {
            val a = 2 * PI * i / 100
            val x = cos(a) * 140; val y = sin(a) * 60
            val rot = 0.5
            ell.add(300f + (x * cos(rot) - y * sin(rot)).toFloat(), 300f + (x * sin(rot) + y * cos(rot)).toFloat(), 0.5f, 0f, 0f, i * 10f)
        }
        check(ShapeRecognizer.recognize(ell)?.kind == ShapeRecognizer.Kind.ELLIPSE, "rotated ellipse → ELLIPSE")
        val squiggle = StrokePoints()
        for (i in 0..100) squiggle.add(i * 4f, (sin(i * 0.3) * 60).toFloat() + 100f, 0.5f, 0f, 0f, i * 10f)
        check(ShapeRecognizer.recognize(squiggle) == null, "squiggle is left alone")
    }

    println("FloodFill")
    run {
        val w = 200; val h = 120
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        // Vertical black wall at x=100 with a 2px gap at y=60..61.
        for (y in 0 until h) if (y != 60 && y != 61) { px[y * w + 100] = 0xFF000000.toInt(); px[y * w + 101] = 0xFF000000.toInt() }
        val noGap = FloodFill.compute(px, w, h, 10, 10, 30, 0, 0)!!
        check(noGap.mask[10 * w + 150].toInt() == 1, "without gap closing, fill leaks through the gap")
        val withGap = FloodFill.compute(px, w, h, 10, 10, 30, 3, 0)!!
        check(withGap.mask[10 * w + 150].toInt() == 0, "gap closing stops the leak")
        check(withGap.mask[10 * w + 99].toInt() == 1, "fill grows back up to the wall")
        check(withGap.mask[60 * w + 30].toInt() == 1, "region far from gap filled")
        val exp = FloodFill.compute(px, w, h, 10, 10, 30, 3, 1)!!
        check(exp.mask[10 * w + 100].toInt() == 1, "expand tucks under the line")
        check(withGap.left == 0 && withGap.top == 0, "bounds start at 0,0")
        // Perf on a 2048² canvas.
        val big = 2048
        val bp = IntArray(big * big) { 0xFFFFFFFF.toInt() }
        for (i in 0 until big) bp[i * big + 1024] = 0xFF000000.toInt()
        val t0 = System.nanoTime()
        val r = FloodFill.compute(bp, big, big, 5, 5, 40, 4, 2)!!
        val ms = (System.nanoTime() - t0) / 1e6
        check(r.count > big * 1000, "2048² fill covers left half (${r.count})")
        println("       2048² gap-closing fill: ${"%.0f".format(ms)} ms on JVM")
    }

    println("Journal round-trip")
    run {
        val j = Journal(800, 600, 0xFFFFFFFF.toInt())
        val pts = StrokePoints(); for (i in 0..20) pts.add(i.toFloat(), i * 2f, 0.5f, 0.2f, 1f, i * 16f)
        j.add(JEvent.AddLayer(1, "Layer 1", 0))
        j.add(JEvent.Stroke(1, StrokeKind.PAINT, BrushPresets.PENCIL, 0xFF112233.toInt(), 12f, 0.8f, 99L,
            SymmetryConfig(SymmetryMode.QUAD, 4, 400f, 300f), false, pts))
        j.add(JEvent.Fill(1, 5, 6, 0xFFFF0000.toInt(), 20, 3, 1, true, false))
        j.add(JEvent.PlaceImage(1, "photo1.png", 0f, 0f, 800f, 600f))
        j.add(JEvent.LayerProps(1, "Ink & \"stuff\"", true, 0.5f, LayerBlend.MULTIPLY.ordinal, true))
        j.add(JEvent.TransformLayer(1, floatArrayOf(1f, 0f, 5f, 0f, 1f, 6f, 0f, 0f, 1f)))
        val bos = ByteArrayOutputStream(); j.write(bos)
        val back = Journal.read(ByteArrayInputStream(bos.toByteArray()))
        check(back.width == 800 && back.height == 600 && back.events.size == 6, "header + 6 events")
        val s = back.events[1] as JEvent.Stroke
        check(s.brush == BrushPresets.PENCIL && s.points.size == 21 && s.points.y(20) == 40f, "stroke survives")
        check(s.symmetry.mode == SymmetryMode.QUAD && s.seed == 99L, "symmetry + seed survive")
        check((back.events[4] as JEvent.LayerProps).name == "Ink & \"stuff\"", "layer props survive")
        check(back.referencedAssets() == setOf("photo1.png"), "asset references")
    }

    println("Colors")
    run {
        val c = ColorMath.hsvToRgb(210f, 0.5f, 0.8f)
        val hsv = ColorMath.rgbToHsv(c)
        check(kotlin.math.abs(hsv[0] - 210f) < 1.5f && kotlin.math.abs(hsv[1] - 0.5f) < 0.01f, "HSV round-trip")
        check(ColorMath.parseHex("#FF8800") == 0xFFFF8800.toInt(), "hex parse")
        check(ColorMath.toHex(0xFF123456.toInt()) == "#123456", "hex format")
        check(LayerBlend.fromOra("svg:multiply") == LayerBlend.MULTIPLY, "ORA op mapping")
        val xml = OraStack.write(10, 20, listOf(OraStack.Entry("A<b>", "data/1.png", 0.5f, false, LayerBlend.SCREEN, false)))
        check(xml.contains("name=\"A&lt;b&gt;\"") && xml.contains("svg:screen") && xml.contains("hidden"), "stack.xml")
    }

    println("Coloring pages (rendered to $outDir)")
    for (style in ColoringPages.Style.values()) {
        val t0 = System.nanoTime()
        val page = ColoringPages.generate(style, 1234L, 1200, 1600)
        val ms = (System.nanoTime() - t0) / 1e6
        val img = renderPage(page, 1200, 1600)
        ImageIO.write(img, "png", File(outDir, "page_${style.name.lowercase()}.png"))
        // How many fillable regions? Count with flood fill over a downscaled copy.
        val px = img.getRGB(0, 0, 1200, 1600, null, 0, 1200)
        val seen = ByteArray(px.size)
        var regions = 0
        var y = 4
        while (y < 1600) {
            var x = 4
            while (x < 1200) {
                val i = y * 1200 + x
                if (seen[i].toInt() == 0 && px[i] == 0xFFFFFFFF.toInt()) {
                    val r = FloodFill.compute(px, 1200, 1600, x, y, 40, 0, 0)
                    if (r != null) { for (k in seen.indices) if (r.mask[k].toInt() == 1) seen[k] = 1; regions++ }
                }
                x += 24
            }
            y += 24
        }
        check(regions >= 15, "${style.label}: ${page.prims.size} shapes, $regions colorable regions, ${"%.0f".format(ms)} ms")
    }

    println("Line art")
    run {
        // Synthetic "photo": gradient sky, a sun, a house.
        val w = 600; val h = 400
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = java.awt.GradientPaint(0f, 0f, Color(120, 170, 230), 0f, h.toFloat(), Color(230, 240, 255))
        g.fillRect(0, 0, w, h)
        g.color = Color(250, 200, 60); g.fillOval(420, 40, 100, 100)
        g.color = Color(180, 90, 60); g.fillRect(120, 200, 200, 150)
        g.color = Color(120, 50, 40); g.fillPolygon(intArrayOf(100, 220, 340), intArrayOf(200, 110, 200), 3)
        g.color = Color(90, 140, 70); g.fillRect(0, 350, w, 50)
        g.color = Color(60, 60, 80); g.fillRect(200, 270, 40, 80)
        g.dispose()
        ImageIO.write(img, "png", File(outDir, "photo_in.png"))
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        fun save(name: String, ink: IntArray) {
            val o = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2 = o.createGraphics(); g2.color = Color.WHITE; g2.fillRect(0, 0, w, h)
            val l = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); l.setRGB(0, 0, w, h, ink, 0, w)
            g2.drawImage(l, 0, 0, null); g2.dispose()
            ImageIO.write(o, "png", File(outDir, name))
        }
        val t0 = System.nanoTime()
        val outline = LineArt.outline(px, w, h, 0.6f, 0.6f)
        println("       outline: ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
        save("photo_outline.png", outline)
        save("photo_sketch.png", LineArt.sketch(px, w, h, 0.4f, 0.5f))
        save("photo_regions.png", LineArt.regions(px, w, h, 6))
        val inkAt = { x: Int, y: Int -> outline[y * w + x] ushr 24 }
        check(inkAt(120, 280) > 120, "house wall edge is inked (${inkAt(120, 280)})")
        check(inkAt(60, 60) < 30, "flat sky stays clean (${inkAt(60, 60)})")
    }

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    if (failures > 0) System.exit(1)
}
