package io.github.hashirakb.penumbra.core

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a single dab is drawn. */
enum class DabShape {
    /** Crisp anti-aliased disc (or ellipse). */
    ROUND,
    /** Radial falloff controlled by hardness. */
    SOFT,
    /** Fine paper-grain texture, pencil-like. */
    GRAIN,
    /** Coarse broken texture, chalk / crayon-like. */
    CHALK,
    /** Hard square aligned to the pixel grid, no anti-aliasing. */
    PIXEL,
    /** Cloud of tiny particles. */
    SPRAY,
}

/** What a stroke does to its layer. */
enum class StrokeKind { PAINT, ERASE, SMUDGE }

/**
 * Complete, serializable description of a brush. Everything that affects the pixels a stroke
 * produces lives here, so a recorded stroke can be replayed identically on another device.
 */
data class BrushSpec(
    val id: String,
    val name: String,
    val shape: DabShape,
    /** 0 = very soft, 1 = hard edge (SOFT shape only). */
    val hardness: Float = 1f,
    /** Dab spacing as a fraction of dab diameter. */
    val spacing: Float = 0.1f,
    /** Size multiplier at zero pressure (1 = pressure doesn't affect size). */
    val minSize: Float = 1f,
    /** Base per-dab opacity. */
    val flow: Float = 1f,
    /** Flow multiplier at zero pressure (1 = pressure doesn't affect flow). */
    val minFlow: Float = 1f,
    /** How much tilting the pen enlarges the dab (0..3). */
    val tiltSize: Float = 0f,
    /** How much tilting the pen lightens the dab (0..1). */
    val tiltFlow: Float = 0f,
    /** Tilt flattens the dab along the pen's shadow, like the side of a pencil lead. */
    val tiltShape: Boolean = false,
    /** Fixed nib aspect (minor/major) for calligraphy; 1 = round. */
    val aspect: Float = 1f,
    /** Fixed nib angle in degrees, used when aspect < 1 and tiltShape is off. */
    val angleDeg: Float = 0f,
    val sizeJitter: Float = 0f,
    /** Random positional offset as a fraction of the radius. */
    val scatter: Float = 0f,
    val randomRotate: Boolean = false,
    /** Stroke-level opacity multiplier (marker-like strokes stay even within a stroke). */
    val strokeOpacity: Float = 1f,
    /** Particles per pixel of radius (SPRAY only). */
    val sprayDensity: Float = 0f,
    /** Default stroke stabilization (0..1). */
    val stabilizer: Float = 0.15f,
    val defaultSize: Float = 8f,
    val maxSize: Float = 400f,
) {
    fun write(out: DataOutputStream) {
        out.writeUTF(id); out.writeUTF(name); out.writeByte(shape.ordinal)
        out.writeFloat(hardness); out.writeFloat(spacing); out.writeFloat(minSize)
        out.writeFloat(flow); out.writeFloat(minFlow); out.writeFloat(tiltSize)
        out.writeFloat(tiltFlow); out.writeBoolean(tiltShape); out.writeFloat(aspect)
        out.writeFloat(angleDeg); out.writeFloat(sizeJitter); out.writeFloat(scatter)
        out.writeBoolean(randomRotate); out.writeFloat(strokeOpacity); out.writeFloat(sprayDensity)
        out.writeFloat(stabilizer); out.writeFloat(defaultSize); out.writeFloat(maxSize)
    }

    companion object {
        fun read(inp: DataInputStream): BrushSpec = BrushSpec(
            id = inp.readUTF(), name = inp.readUTF(),
            shape = DabShape.values()[inp.readByte().toInt().coerceIn(0, DabShape.values().size - 1)],
            hardness = inp.readFloat(), spacing = inp.readFloat(), minSize = inp.readFloat(),
            flow = inp.readFloat(), minFlow = inp.readFloat(), tiltSize = inp.readFloat(),
            tiltFlow = inp.readFloat(), tiltShape = inp.readBoolean(), aspect = inp.readFloat(),
            angleDeg = inp.readFloat(), sizeJitter = inp.readFloat(), scatter = inp.readFloat(),
            randomRotate = inp.readBoolean(), strokeOpacity = inp.readFloat(),
            sprayDensity = inp.readFloat(), stabilizer = inp.readFloat(),
            defaultSize = inp.readFloat(), maxSize = inp.readFloat(),
        )
    }
}

object BrushPresets {
    val INK = BrushSpec("ink", "Ink pen", DabShape.ROUND, spacing = 0.06f, minSize = 0.12f,
        stabilizer = 0.2f, defaultSize = 6f)
    val FINELINER = BrushSpec("fineliner", "Fineliner", DabShape.ROUND, spacing = 0.05f,
        minSize = 0.85f, stabilizer = 0.25f, defaultSize = 4f)
    val PENCIL = BrushSpec("pencil", "Pencil", DabShape.GRAIN, spacing = 0.12f, minSize = 0.45f,
        flow = 0.55f, minFlow = 0.15f, tiltSize = 2.2f, tiltFlow = 0.55f, tiltShape = true,
        randomRotate = true, stabilizer = 0.05f, defaultSize = 5f)
    val SKETCH = BrushSpec("sketch", "Soft graphite", DabShape.GRAIN, spacing = 0.1f, minSize = 0.6f,
        flow = 0.3f, minFlow = 0.05f, tiltSize = 3f, tiltFlow = 0.4f, tiltShape = true,
        randomRotate = true, stabilizer = 0.0f, defaultSize = 14f)
    val MARKER = BrushSpec("marker", "Marker", DabShape.SOFT, hardness = 0.92f, spacing = 0.05f,
        minSize = 0.9f, strokeOpacity = 0.6f, stabilizer = 0.15f, defaultSize = 22f)
    val BRUSH_PEN = BrushSpec("brushpen", "Brush pen", DabShape.SOFT, hardness = 0.85f,
        spacing = 0.04f, minSize = 0.05f, stabilizer = 0.3f, defaultSize = 16f)
    val CALLIGRAPHY = BrushSpec("calligraphy", "Calligraphy", DabShape.ROUND, spacing = 0.03f,
        minSize = 0.55f, aspect = 0.22f, angleDeg = -40f, stabilizer = 0.3f, defaultSize = 24f)
    val AIRBRUSH = BrushSpec("airbrush", "Airbrush", DabShape.SOFT, hardness = 0f, spacing = 0.05f,
        flow = 0.09f, minFlow = 0.05f, stabilizer = 0.1f, defaultSize = 120f)
    val WATERCOLOR = BrushSpec("watercolor", "Watercolor", DabShape.SOFT, hardness = 0.35f,
        spacing = 0.06f, minSize = 0.6f, flow = 0.16f, minFlow = 0.4f, sizeJitter = 0.12f,
        scatter = 0.06f, strokeOpacity = 0.75f, stabilizer = 0.2f, defaultSize = 60f)
    val CRAYON = BrushSpec("crayon", "Crayon", DabShape.CHALK, spacing = 0.14f, minSize = 0.7f,
        flow = 0.8f, minFlow = 0.35f, tiltSize = 1f, randomRotate = true, stabilizer = 0.05f,
        defaultSize = 18f)
    val SPRAY = BrushSpec("spray", "Spray can", DabShape.SPRAY, spacing = 0.18f, flow = 0.7f,
        minFlow = 0.3f, sprayDensity = 0.5f, stabilizer = 0f, defaultSize = 70f)
    val PIXEL = BrushSpec("pixel", "Pixel", DabShape.PIXEL, spacing = 0.25f, stabilizer = 0f,
        defaultSize = 1f, maxSize = 64f)
    val BLENDER = BrushSpec("blender", "Blender", DabShape.SOFT, hardness = 0.2f, spacing = 0.12f,
        minFlow = 0.3f, stabilizer = 0.1f, defaultSize = 40f)
    val HARD_ERASER = BrushSpec("eraser_hard", "Hard eraser", DabShape.ROUND, spacing = 0.05f,
        stabilizer = 0.05f, defaultSize = 24f)
    val SOFT_ERASER = BrushSpec("eraser_soft", "Soft eraser", DabShape.SOFT, hardness = 0.1f,
        spacing = 0.05f, flow = 0.35f, minFlow = 0.3f, stabilizer = 0.05f, defaultSize = 80f)

    /** Brushes offered for painting (and as eraser shapes). */
    val PAINT: List<BrushSpec> = listOf(
        INK, FINELINER, PENCIL, SKETCH, MARKER, BRUSH_PEN, CALLIGRAPHY,
        WATERCOLOR, AIRBRUSH, CRAYON, SPRAY, PIXEL,
    )
    val ERASERS: List<BrushSpec> = listOf(HARD_ERASER, SOFT_ERASER, PENCIL, AIRBRUSH, PIXEL)
    val SMUDGERS: List<BrushSpec> = listOf(BLENDER, AIRBRUSH, CRAYON)

    fun byId(id: String): BrushSpec? =
        (PAINT + ERASERS + SMUDGERS).firstOrNull { it.id == id }
}

/**
 * Turns a stream of stroke samples into evenly spaced dabs. Deterministic for a given seed,
 * so live drawing and replay produce identical output.
 */
class DabGenerator(
    private val spec: BrushSpec,
    private val baseSize: Float,
    seed: Long,
    private val symmetry: List<Affine>,
    private val out: DabList,
) {
    private val rnd = java.util.Random(seed)
    private var has = false
    private var lx = 0f; private var ly = 0f; private var lp = 0f
    private var lt = 0f; private var la = 0f
    private var distToNext = 0f
    private val nibAngle = (spec.angleDeg * PI / 180.0).toFloat()

    fun addAll(points: StrokePoints, from: Int = 0) {
        for (i in from until points.size) {
            add(points.x(i), points.y(i), points.p(i), points.tilt(i), points.axis(i))
        }
    }

    fun add(x: Float, y: Float, p: Float, tilt: Float, axis: Float) {
        if (!has) {
            has = true
            lx = x; ly = y; lp = p; lt = tilt; la = axis
            emit(x, y, p, tilt, axis)
            distToNext = spacingAt(p, tilt)
            return
        }
        val dx = x - lx; val dy = y - ly
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) {
            lp = p; lt = tilt; la = axis
            return
        }
        var traveled = 0f
        var guard = 0
        while (traveled + distToNext <= len && guard < 100_000) {
            traveled += distToNext
            val t = traveled / len
            val ip = Mathx.lerp(lp, p, t)
            val it = Mathx.lerp(lt, tilt, t)
            val ia = lerpAngle(la, axis, t)
            emit(lx + dx * t, ly + dy * t, ip, it, ia)
            distToNext = spacingAt(ip, it)
            guard++
        }
        distToNext -= (len - traveled)
        lx = x; ly = y; lp = p; lt = tilt; la = axis
    }

    private fun tiltNorm(tilt: Float) = Mathx.clamp01(tilt / (PI.toFloat() * 0.5f * 0.85f))

    private fun sizeFactor(p: Float, tilt: Float): Float {
        var f = Mathx.lerp(spec.minSize, 1f, Mathx.clamp01(p))
        if (spec.tiltSize > 0f) f *= 1f + spec.tiltSize * tiltNorm(tilt)
        return f
    }

    private fun spacingAt(p: Float, tilt: Float): Float {
        val minSpacing = if (spec.shape == DabShape.PIXEL) 0.5f else 0.35f
        return max(minSpacing, baseSize * sizeFactor(p, tilt) * spec.spacing)
    }

    private fun emit(x: Float, y: Float, p: Float, tilt: Float, axis: Float) {
        // Draw every random number in a fixed order so replay stays in sync.
        val rJitter = rnd.nextFloat()
        val rScatterA = rnd.nextFloat()
        val rScatterD = rnd.nextFloat()
        val rRot = rnd.nextFloat()

        var size = baseSize * sizeFactor(p, tilt)
        if (spec.sizeJitter > 0f) size *= 1f + spec.sizeJitter * (rJitter * 2f - 1f)
        val radius = max(0.25f, size * 0.5f)

        val tn = tiltNorm(tilt)
        var alpha = spec.flow * Mathx.lerp(spec.minFlow, 1f, Mathx.clamp01(p))
        if (spec.tiltFlow > 0f) alpha *= 1f - spec.tiltFlow * tn
        alpha = Mathx.clamp01(alpha)

        var angle = 0f
        var aspect = 1f
        if (spec.tiltShape && tn > 0.05f) {
            aspect = 1f - 0.7f * tn
            angle = axis
        } else if (spec.aspect < 1f) {
            aspect = spec.aspect
            angle = nibAngle
        }
        if (spec.randomRotate && aspect >= 0.999f) angle = rRot * 2f * PI.toFloat()

        var cx = x; var cy = y
        if (spec.scatter > 0f) {
            val a = rScatterA * 2f * PI.toFloat()
            val d = sqrt(rScatterD) * radius * spec.scatter * 2f
            cx += cos(a) * d; cy += sin(a) * d
        }

        if (spec.shape == DabShape.SPRAY) {
            val count = (radius * spec.sprayDensity).toInt().coerceIn(2, 80)
            for (k in 0 until count) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val d = sqrt(rnd.nextFloat()) * radius
                val pr = max(0.5f, min(radius * 0.05f, 3f) * (0.5f + rnd.nextFloat()))
                put(cx + cos(a) * d, cy + sin(a) * d, pr, alpha, 0f, 1f)
            }
        } else {
            put(cx, cy, radius, alpha, angle, aspect)
        }
    }

    private fun put(x: Float, y: Float, r: Float, a: Float, angle: Float, aspect: Float) {
        for (m in symmetry) {
            if (m.isIdentity) out.add(x, y, r, a, angle, aspect)
            else out.add(m.mapX(x, y), m.mapY(x, y), r, a, m.mapAngle(angle), aspect)
        }
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = b - a
        val twoPi = 2f * PI.toFloat()
        while (d > PI) d -= twoPi
        while (d < -PI) d += twoPi
        return a + d * t
    }
}

/** Exponential smoothing that follows the pen like a pulled string. */
class Stabilizer(strength: Float) {
    private val tau = (Mathx.clamp01(strength).let { it * it } * 140f)
    private var has = false
    private var sx = 0f; private var sy = 0f; private var sp = 0f
    private var lastT = 0f

    val enabled get() = tau > 0.5f

    /** Feeds a raw sample and returns the smoothed position in [out] (x, y, p). */
    fun push(x: Float, y: Float, p: Float, t: Float, out: FloatArray) {
        if (!has || !enabled) {
            has = true
            sx = x; sy = y; sp = p; lastT = t
        } else {
            val dt = max(0.5f, t - lastT)
            lastT = t
            val k = 1f - kotlin.math.exp(-dt / tau)
            sx += (x - sx) * k
            sy += (y - sy) * k
            sp += (p - sp) * min(1f, k * 2f)
        }
        out[0] = sx; out[1] = sy; out[2] = sp
    }
}

object PressureCurve {
    /** gamma < 1 = soft (light touch goes dark quickly), > 1 = firm. */
    fun apply(p: Float, gamma: Float): Float {
        val c = Mathx.clamp01(p)
        return if (gamma == 1f) c else Math.pow(c.toDouble(), gamma.toDouble()).toFloat()
    }
}
