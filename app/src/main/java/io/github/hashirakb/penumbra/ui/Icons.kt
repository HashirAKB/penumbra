package io.github.hashirakb.penumbra.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Penumbra's own line icons, drawn on a 24×24 grid with round strokes. */
enum class Icon {
    BACK, MORE, UNDO, REDO, BRUSH, SMUDGE, ERASER, FILL, EYEDROPPER, LAYERS, PLUS, TRASH,
    EYE, EYE_OFF, LOCK, ALPHA_LOCK, PLAY, PAUSE, PREV, NEXT, SYMMETRY, MOVE, CHECK, CLOSE,
    IMAGE, SPARKLE, REPLAY, SHARE, GEAR, MERGE, COPY, UP, DOWN, PALETTE, VIDEO, FIT, LEARN,
    IMPORT, SHAPES, SKIP,
}

object Icons {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(s, s)
        stroke.color = color; stroke.strokeWidth = 1.9f
        fill.color = color
        path.reset()
        when (icon) {
            Icon.BACK -> { line(15f, 5f, 8f, 12f); lineTo(15f, 19f); c.drawPath(path, stroke) }
            Icon.MORE -> { dot(c, 6f, 12f); dot(c, 12f, 12f); dot(c, 18f, 12f) }
            Icon.UNDO -> {
                path.moveTo(9f, 6f); path.lineTo(5f, 10f); path.lineTo(9f, 14f)
                path.moveTo(5f, 10f); path.lineTo(14f, 10f)
                path.cubicTo(17.5f, 10f, 19.5f, 12.5f, 19.5f, 15f); path.cubicTo(19.5f, 17.5f, 17.5f, 19f, 14f, 19f)
                path.lineTo(10f, 19f); c.drawPath(path, stroke)
            }
            Icon.REDO -> {
                path.moveTo(15f, 6f); path.lineTo(19f, 10f); path.lineTo(15f, 14f)
                path.moveTo(19f, 10f); path.lineTo(10f, 10f)
                path.cubicTo(6.5f, 10f, 4.5f, 12.5f, 4.5f, 15f); path.cubicTo(4.5f, 17.5f, 6.5f, 19f, 10f, 19f)
                path.lineTo(14f, 19f); c.drawPath(path, stroke)
            }
            Icon.BRUSH -> {
                path.moveTo(19.5f, 4.5f); path.lineTo(11f, 13f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(10.5f, 13.5f)
                path.cubicTo(7f, 13f, 5.5f, 15.5f, 5.5f, 17.5f); path.cubicTo(5.5f, 19f, 4.5f, 19.5f, 3.5f, 20f)
                path.cubicTo(7.5f, 21f, 11.5f, 19.5f, 11.5f, 15.5f); path.close(); c.drawPath(path, fill)
            }
            Icon.SMUDGE -> {
                path.moveTo(8f, 20f); path.cubicTo(5f, 17f, 5f, 12f, 7f, 9f)
                path.lineTo(10f, 4.5f); path.cubicTo(11f, 3.2f, 12.8f, 4f, 12.4f, 5.5f)
                path.lineTo(11.5f, 9f); path.lineTo(17f, 9f); path.cubicTo(18.8f, 9f, 18.8f, 11.5f, 17f, 11.5f)
                path.lineTo(15f, 11.5f); path.cubicTo(16.6f, 11.5f, 16.6f, 14f, 15f, 14f)
                path.cubicTo(16.4f, 14f, 16.4f, 16.5f, 14.6f, 16.5f); path.cubicTo(15.8f, 16.5f, 15.6f, 19f, 14f, 19f)
                path.lineTo(12f, 20f); c.drawPath(path, stroke)
            }
            Icon.ERASER -> {
                path.moveTo(4f, 15.5f); path.lineTo(13.5f, 6f); path.lineTo(20f, 12.5f); path.lineTo(13f, 19.5f)
                path.lineTo(8f, 19.5f); path.close(); c.drawPath(path, stroke)
                line(9f, 10.5f, 15.5f, 17f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(13f, 20f); path.lineTo(20f, 20f); c.drawPath(path, stroke)
            }
            Icon.FILL -> {
                path.moveTo(5f, 11f); path.lineTo(11f, 5f); path.lineTo(18f, 12f); path.lineTo(12f, 18f); path.close()
                c.drawPath(path, stroke)
                path.reset(); path.moveTo(5f, 11f); path.lineTo(18f, 12f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(20f, 15f); path.cubicTo(21.5f, 17f, 21.5f, 19.5f, 20f, 19.5f)
                path.cubicTo(18.5f, 19.5f, 18.5f, 17f, 20f, 15f); c.drawPath(path, fill)
            }
            Icon.EYEDROPPER -> {
                path.moveTo(13f, 7f); path.lineTo(17f, 11f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(15f, 9f); path.lineTo(6f, 18f); path.lineTo(4.5f, 19.5f); c.drawPath(path, stroke)
                r.set(14.5f, 3.5f, 20.5f, 9.5f); c.drawOval(r, fill)
            }
            Icon.LAYERS -> {
                path.moveTo(12f, 4f); path.lineTo(21f, 9f); path.lineTo(12f, 14f); path.lineTo(3f, 9f); path.close()
                c.drawPath(path, stroke)
                path.reset(); path.moveTo(3f, 13f); path.lineTo(12f, 18f); path.lineTo(21f, 13f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(3f, 16.5f); path.lineTo(12f, 21.5f); path.lineTo(21f, 16.5f); c.drawPath(path, stroke)
            }
            Icon.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f); c.drawPath(path, stroke) }
            Icon.TRASH -> {
                line(4f, 7f, 20f, 7f); line(9.5f, 7f, 10f, 4f); lineTo(14f, 4f); lineTo(14.5f, 7f)
                path.moveTo(6f, 7f); path.lineTo(7f, 20f); path.lineTo(17f, 20f); path.lineTo(18f, 7f)
                line(10f, 11f, 10f, 16.5f); line(14f, 11f, 14f, 16.5f); c.drawPath(path, stroke)
            }
            Icon.EYE, Icon.EYE_OFF -> {
                path.moveTo(2.5f, 12f); path.cubicTo(5f, 7f, 8.5f, 5.5f, 12f, 5.5f)
                path.cubicTo(15.5f, 5.5f, 19f, 7f, 21.5f, 12f); path.cubicTo(19f, 17f, 15.5f, 18.5f, 12f, 18.5f)
                path.cubicTo(8.5f, 18.5f, 5f, 17f, 2.5f, 12f); c.drawPath(path, stroke)
                c.drawCircle(12f, 12f, 3f, stroke)
                if (icon == Icon.EYE_OFF) { path.reset(); line(4f, 20f, 20f, 4f); c.drawPath(path, stroke) }
            }
            Icon.LOCK -> {
                r.set(5f, 10.5f, 19f, 20.5f); c.drawRoundRect(r, 2.5f, 2.5f, stroke)
                path.moveTo(8f, 10.5f); path.lineTo(8f, 7.5f); path.cubicTo(8f, 2.5f, 16f, 2.5f, 16f, 7.5f)
                path.lineTo(16f, 10.5f); c.drawPath(path, stroke)
            }
            Icon.ALPHA_LOCK -> {
                r.set(4f, 4f, 20f, 20f); c.drawRoundRect(r, 2f, 2f, stroke)
                for (yy in 0 until 4) for (xx in 0 until 4) if ((xx + yy) % 2 == 0) {
                    c.drawRect(4f + xx * 4f, 4f + yy * 4f, 8f + xx * 4f, 8f + yy * 4f, fill)
                }
            }
            Icon.PLAY -> { path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close(); c.drawPath(path, fill) }
            Icon.PAUSE -> { c.drawRoundRect(RectF(6.5f, 5f, 10f, 19f), 1f, 1f, fill); c.drawRoundRect(RectF(14f, 5f, 17.5f, 19f), 1f, 1f, fill) }
            Icon.PREV -> { line(15f, 5f, 8f, 12f); lineTo(15f, 19f); line(6f, 5f, 6f, 19f); c.drawPath(path, stroke) }
            Icon.NEXT -> { line(9f, 5f, 16f, 12f); lineTo(9f, 19f); line(18f, 5f, 18f, 19f); c.drawPath(path, stroke) }
            Icon.SKIP -> { line(5f, 5f, 12f, 12f); lineTo(5f, 19f); line(12f, 5f, 19f, 12f); lineTo(12f, 19f); c.drawPath(path, stroke) }
            Icon.SYMMETRY -> {
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 2f), 0f)
                line(12f, 2.5f, 12f, 21.5f); c.drawPath(path, stroke); stroke.pathEffect = null
                path.reset(); path.moveTo(9.5f, 6f); path.lineTo(3.5f, 18f); path.lineTo(9.5f, 18f); path.close()
                c.drawPath(path, stroke)
                path.reset(); path.moveTo(14.5f, 6f); path.lineTo(20.5f, 18f); path.lineTo(14.5f, 18f); path.close()
                c.drawPath(path, fill)
            }
            Icon.MOVE -> {
                line(12f, 3f, 12f, 21f); line(3f, 12f, 21f, 12f)
                line(9f, 6f, 12f, 3f); lineTo(15f, 6f); line(9f, 18f, 12f, 21f); lineTo(15f, 18f)
                line(6f, 9f, 3f, 12f); lineTo(6f, 15f); line(18f, 9f, 21f, 12f); lineTo(18f, 15f)
                c.drawPath(path, stroke)
            }
            Icon.CHECK -> { line(5f, 12.5f, 10f, 17.5f); lineTo(19f, 7f); c.drawPath(path, stroke) }
            Icon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f); c.drawPath(path, stroke) }
            Icon.IMAGE -> {
                r.set(3.5f, 5f, 20.5f, 19f); c.drawRoundRect(r, 2f, 2f, stroke)
                c.drawCircle(9f, 10f, 1.6f, fill)
                path.moveTo(4f, 17f); path.lineTo(10f, 12.5f); path.lineTo(13.5f, 15.5f); path.lineTo(16f, 13.5f)
                path.lineTo(20f, 17f); c.drawPath(path, stroke)
            }
            Icon.SPARKLE -> {
                path.moveTo(11f, 3f); path.cubicTo(11.6f, 8.5f, 13.5f, 10.4f, 19f, 11f)
                path.cubicTo(13.5f, 11.6f, 11.6f, 13.5f, 11f, 19f); path.cubicTo(10.4f, 13.5f, 8.5f, 11.6f, 3f, 11f)
                path.cubicTo(8.5f, 10.4f, 10.4f, 8.5f, 11f, 3f); c.drawPath(path, fill)
                c.drawCircle(18.5f, 18.5f, 1.8f, fill)
            }
            Icon.REPLAY -> {
                path.addArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), -60f, 300f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(15f, 3.5f); path.lineTo(16.3f, 7.5f); path.lineTo(12.2f, 8.2f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(10f, 9f); path.lineTo(15f, 12f); path.lineTo(10f, 15f); path.close(); c.drawPath(path, fill)
            }
            Icon.SHARE -> {
                c.drawCircle(17.5f, 6f, 2.5f, stroke); c.drawCircle(6.5f, 12f, 2.5f, stroke); c.drawCircle(17.5f, 18f, 2.5f, stroke)
                line(8.7f, 10.8f, 15.3f, 7.2f); line(8.7f, 13.2f, 15.3f, 16.8f); c.drawPath(path, stroke)
            }
            Icon.GEAR -> {
                c.drawCircle(12f, 12f, 3f, stroke)
                for (k in 0 until 8) {
                    val a = Math.PI * 2 * k / 8
                    val x0 = 12f + (Math.cos(a) * 6.5).toFloat(); val y0 = 12f + (Math.sin(a) * 6.5).toFloat()
                    val x1 = 12f + (Math.cos(a) * 9).toFloat(); val y1 = 12f + (Math.sin(a) * 9).toFloat()
                    line(x0, y0, x1, y1)
                }
                c.drawPath(path, stroke); c.drawCircle(12f, 12f, 6.5f, stroke)
            }
            Icon.MERGE -> {
                line(7f, 4f, 7f, 9f); lineTo(12f, 14f); lineTo(12f, 20f); line(17f, 4f, 17f, 9f); lineTo(12f, 14f)
                line(9f, 17f, 12f, 20f); lineTo(15f, 17f); c.drawPath(path, stroke)
            }
            Icon.COPY -> {
                r.set(8f, 8f, 20f, 20f); c.drawRoundRect(r, 2f, 2f, stroke)
                path.moveTo(16f, 8f); path.lineTo(16f, 5f); path.lineTo(4f, 5f); path.lineTo(4f, 16f); path.lineTo(8f, 16f)
                c.drawPath(path, stroke)
            }
            Icon.UP -> { line(6f, 14f, 12f, 8f); lineTo(18f, 14f); c.drawPath(path, stroke) }
            Icon.DOWN -> { line(6f, 10f, 12f, 16f); lineTo(18f, 10f); c.drawPath(path, stroke) }
            Icon.PALETTE -> {
                path.moveTo(12f, 3.5f); path.cubicTo(6.5f, 3.5f, 3f, 7.5f, 3f, 12f); path.cubicTo(3f, 17f, 7f, 20.5f, 11.5f, 20.5f)
                path.cubicTo(13f, 20.5f, 13.5f, 19.2f, 12.8f, 18f); path.cubicTo(12f, 16.6f, 13f, 15f, 14.8f, 15f)
                path.lineTo(17f, 15f); path.cubicTo(19.5f, 15f, 21f, 13.3f, 21f, 11f); path.cubicTo(21f, 6.8f, 17f, 3.5f, 12f, 3.5f)
                c.drawPath(path, stroke)
                c.drawCircle(8f, 10f, 1.4f, fill); c.drawCircle(12f, 7.5f, 1.4f, fill); c.drawCircle(16f, 9.5f, 1.4f, fill)
            }
            Icon.VIDEO -> {
                r.set(3f, 6f, 16f, 18f); c.drawRoundRect(r, 2f, 2f, stroke)
                path.moveTo(16f, 10.5f); path.lineTo(21f, 7.5f); path.lineTo(21f, 16.5f); path.lineTo(16f, 13.5f); c.drawPath(path, stroke)
            }
            Icon.FIT -> {
                line(4f, 9f, 4f, 4f); lineTo(9f, 4f); line(15f, 4f, 20f, 4f); lineTo(20f, 9f)
                line(20f, 15f, 20f, 20f); lineTo(15f, 20f); line(9f, 20f, 4f, 20f); lineTo(4f, 15f)
                c.drawPath(path, stroke)
            }
            Icon.LEARN -> {
                path.moveTo(2.5f, 9.5f); path.lineTo(12f, 5f); path.lineTo(21.5f, 9.5f); path.lineTo(12f, 14f); path.close()
                c.drawPath(path, stroke)
                path.reset(); path.moveTo(6.5f, 11.5f); path.lineTo(6.5f, 16f); path.cubicTo(9f, 19f, 15f, 19f, 17.5f, 16f)
                path.lineTo(17.5f, 11.5f); c.drawPath(path, stroke)
                path.reset(); line(21.5f, 9.5f, 21.5f, 15f); c.drawPath(path, stroke)
            }
            Icon.IMPORT -> {
                line(12f, 3.5f, 12f, 14.5f); line(7.5f, 10f, 12f, 14.5f); lineTo(16.5f, 10f)
                line(4f, 15f, 4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f); c.drawPath(path, stroke)
            }
            Icon.SHAPES -> {
                c.drawCircle(8f, 8.5f, 4.5f, stroke)
                r.set(11.5f, 11.5f, 20.5f, 20.5f); c.drawRect(r, stroke)
                path.moveTo(4f, 20.5f); path.lineTo(7.5f, 14.5f); path.lineTo(11f, 20.5f); path.close(); c.drawPath(path, stroke)
            }
        }
        c.restore()
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) { path.moveTo(x0, y0); path.lineTo(x1, y1) }
    private fun lineTo(x: Float, y: Float) { path.lineTo(x, y) }
    private fun dot(c: Canvas, x: Float, y: Float) { c.drawCircle(x, y, 1.8f, fill) }
}
