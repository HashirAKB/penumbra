package io.github.hashirakb.penumbra.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Layer blend modes, with their OpenRaster composite-op names so files open in Krita/GIMP. */
enum class LayerBlend(val label: String, val oraOp: String) {
    NORMAL("Normal", "svg:src-over"),
    MULTIPLY("Multiply", "svg:multiply"),
    SCREEN("Screen", "svg:screen"),
    OVERLAY("Overlay", "svg:overlay"),
    DARKEN("Darken", "svg:darken"),
    LIGHTEN("Lighten", "svg:lighten"),
    COLOR_DODGE("Color dodge", "svg:color-dodge"),
    COLOR_BURN("Color burn", "svg:color-burn"),
    HARD_LIGHT("Hard light", "svg:hard-light"),
    SOFT_LIGHT("Soft light", "svg:soft-light"),
    DIFFERENCE("Difference", "svg:difference"),
    EXCLUSION("Exclusion", "svg:exclusion"),
    HUE("Hue", "svg:hue"),
    SATURATION("Saturation", "svg:saturation"),
    COLOR("Color", "svg:color"),
    LUMINOSITY("Luminosity", "svg:luminosity"),
    ADD("Add", "svg:plus");

    companion object {
        fun fromOra(op: String?): LayerBlend = values().firstOrNull { it.oraOp == op } ?: NORMAL
        fun of(ordinal: Int): LayerBlend = values().getOrElse(ordinal) { NORMAL }
    }
}

object ColorMath {
    /** h in [0,360), s and v in [0,1]. */
    fun hsvToRgb(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1f - abs(hh % 2f - 1f))
        val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(alpha, ((r + m) * 255f).roundToInt(), ((g + m) * 255f).roundToInt(), ((b + m) * 255f).roundToInt())
    }

    /** Returns h, s, v. */
    fun rgbToHsv(color: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val r = ((color shr 16) and 0xff) / 255f
        val g = ((color shr 8) and 0xff) / 255f
        val b = (color and 0xff) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
        return out
    }

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun toHex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6 && t.length != 8) return null
        val v = t.toLongOrNull(16) ?: return null
        return if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /** Perceived brightness 0..1, to pick readable overlay colors. */
    fun brightness(c: Int): Float {
        val r = ((c shr 16) and 0xff) / 255f
        val g = ((c shr 8) and 0xff) / 255f
        val b = (c and 0xff) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    val PALETTES: List<Pair<String, IntArray>> = listOf(
        "Basics" to intArrayOf(
            0xFF000000.toInt(), 0xFF5A5A5A.toInt(), 0xFFA8A8A8.toInt(), 0xFFFFFFFF.toInt(),
            0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF5E35B1.toInt(), 0xFFD81B60.toInt(),
            0xFF6D4C41.toInt(), 0xFF8BC34A.toInt(), 0xFF26A69A.toInt(), 0xFFFF7043.toInt(),
        ),
        "Pastel" to intArrayOf(
            0xFFFFD1DC.toInt(), 0xFFFFE5B4.toInt(), 0xFFFFF5BA.toInt(), 0xFFCDEAC0.toInt(),
            0xFFB5EAD7.toInt(), 0xFFC7CEEA.toInt(), 0xFFE2C2FF.toInt(), 0xFFFFDAC1.toInt(),
            0xFFF8B7CD.toInt(), 0xFFA7C7E7.toInt(), 0xFFD4F0F0.toInt(), 0xFFFCE1E4.toInt(),
        ),
        "Skin tones" to intArrayOf(
            0xFFFFE0CC.toInt(), 0xFFF5CBA7.toInt(), 0xFFEAB38A.toInt(), 0xFFD8A07A.toInt(),
            0xFFC68863.toInt(), 0xFFA86F4C.toInt(), 0xFF8D5A3B.toInt(), 0xFF6E4430.toInt(),
            0xFF553424.toInt(), 0xFF3D2519.toInt(),
        ),
        "Earth" to intArrayOf(
            0xFF3E2723.toInt(), 0xFF6D4C41.toInt(), 0xFFA1887F.toInt(), 0xFFD7CCC8.toInt(),
            0xFF556B2F.toInt(), 0xFF8F9779.toInt(), 0xFFC2B280.toInt(), 0xFFB5651D.toInt(),
            0xFF8B0000.toInt(), 0xFF2F4F4F.toInt(), 0xFF708090.toInt(), 0xFFDEB887.toInt(),
        ),
        "Tropical" to intArrayOf(
            0xFF0B3D2E.toInt(), 0xFF1F6F50.toInt(), 0xFF5FAD56.toInt(), 0xFFF2C14E.toInt(),
            0xFFF78154.toInt(), 0xFFB4436C.toInt(), 0xFF5D2E8C.toInt(), 0xFF1B998B.toInt(),
            0xFFFFE8C2.toInt(), 0xFF2E294E.toInt(),
        ),
    )
}

/** Writes the OpenRaster stack.xml (layers top-most first). */
object OraStack {
    class Entry(
        val name: String, val src: String, val opacity: Float, val visible: Boolean,
        val blend: LayerBlend, val locked: Boolean,
    )

    fun write(width: Int, height: Int, entries: List<Entry>): String {
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='UTF-8'?>\n")
        sb.append("<image version=\"0.0.5\" w=\"$width\" h=\"$height\" xres=\"72\" yres=\"72\">\n")
        sb.append(" <stack>\n")
        for (e in entries) {
            sb.append("  <layer name=\"").append(escape(e.name)).append("\" src=\"").append(escape(e.src))
                .append("\" x=\"0\" y=\"0\" opacity=\"").append(String.format(java.util.Locale.US, "%.3f", e.opacity))
                .append("\" visibility=\"").append(if (e.visible) "visible" else "hidden")
                .append("\" composite-op=\"").append(e.blend.oraOp).append("\"")
                .append(if (e.locked) " edit-locked=\"true\"" else "")
                .append("/>\n")
        }
        sb.append(" </stack>\n</image>\n")
        return sb.toString()
    }

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
