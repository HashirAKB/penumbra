package io.github.hashirakb.penumbra.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import io.github.hashirakb.penumbra.core.BrushPresets
import io.github.hashirakb.penumbra.core.ColoringPages
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.Lessons
import io.github.hashirakb.penumbra.core.StrokeKind
import io.github.hashirakb.penumbra.core.SymmetryConfig
import io.github.hashirakb.penumbra.engine.Document
import io.github.hashirakb.penumbra.engine.Engine
import kotlin.math.max
import kotlin.math.min

/** Builds new documents: blank canvases, coloring pages, photo drawings and lessons. */
object DocFactory {
    private val engine = Engine()

    /** Applies an event to a fresh document and records it in the journal (no undo needed). */
    fun record(doc: Document, e: JEvent) {
        engine.apply(doc, e)
        doc.journal.add(e)
    }

    fun blank(w: Int, h: Int, background: Int = 0xFFFFFFFF.toInt(), layerName: String = "Layer 1"): Document {
        val doc = Document(w, h, background)
        record(doc, JEvent.AddLayer(doc.allocateLayerId(), layerName, 0))
        return doc
    }

    fun renderPage(page: ColoringPages.Page, w: Int, h: Int, scale: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFF151515.toInt(); strokeWidth = page.lineWidth
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (prim in page.prims) when (prim) {
            is ColoringPages.Prim.Circle -> c.drawCircle(prim.cx, prim.cy, prim.r, p)
            is ColoringPages.Prim.Poly -> {
                path.reset()
                path.moveTo(prim.pts[0], prim.pts[1])
                var i = 2
                while (i < prim.pts.size) { path.lineTo(prim.pts[i], prim.pts[i + 1]); i += 2 }
                if (prim.closed) path.close()
                c.drawPath(path, p)
            }
        }
        return bmp
    }

    fun coloring(style: ColoringPages.Style, seed: Long, w: Int = 1536, h: Int = 2048): Document {
        val doc = blank(w, h, layerName = "Colors")
        val colorsId = doc.activeId
        val page = ColoringPages.generate(style, seed, w, h)
        val name = "page_${style.name.lowercase()}_$seed.png"
        doc.assets[name] = renderPage(page, w, h)
        val lineId = doc.allocateLayerId()
        record(doc, JEvent.AddLayer(lineId, "Line art", 1))
        record(doc, JEvent.PlaceImage(lineId, name, 0f, 0f, w.toFloat(), h.toFloat()))
        doc.layer(lineId)?.locked = true
        doc.activeId = colorsId
        return doc
    }

    /** Document sized to fit [img] (long side ≤ [maxSide]) with the image on its own layer. */
    fun fromImage(img: Bitmap, layerName: String, drawingLayer: Boolean, maxSide: Int = 2048): Document {
        val s = min(1f, maxSide.toFloat() / max(img.width, img.height))
        val w = max(16, (img.width * s).toInt()); val h = max(16, (img.height * s).toInt())
        val doc = blank(w, h, layerName = layerName)
        val base = doc.activeId
        val name = "image_${System.currentTimeMillis()}.png"
        doc.assets[name] = img
        record(doc, JEvent.PlaceImage(base, name, 0f, 0f, w.toFloat(), h.toFloat()))
        if (drawingLayer) {
            record(doc, JEvent.AddLayer(doc.allocateLayerId(), "Drawing", 1))
        }
        return doc
    }

    /** The lesson's "teacher" document: a journal of ink strokes to follow. */
    fun lesson(kind: Lessons.Kind, seed: Long, w: Int = 1536, h: Int = 2048): Document {
        val doc = Document(w, h, 0xFFFFFFFF.toInt())
        doc.journal.add(JEvent.AddLayer(1, "Lesson", 0))
        doc.nextLayerId = 2
        val brush = BrushPresets.INK
        val size = min(w, h) / 220f
        val sym = SymmetryConfig()
        for ((i, pts) in Lessons.strokes(kind, seed, w, h).withIndex()) {
            doc.journal.add(JEvent.Stroke(1, StrokeKind.PAINT, brush, 0xFF1B1B1F.toInt(), size, 1f,
                seed * 1000 + i, sym, false, pts))
        }
        return doc
    }

    /** Decodes an image from a content URI, downsampled so its long side is about [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val s = min(1f, maxSide.toFloat() / max(raw.width, raw.height))
        if (s >= 0.999f) return raw
        val out = Bitmap.createScaledBitmap(raw, max(1, (raw.width * s).toInt()), max(1, (raw.height * s).toInt()), true)
        if (out !== raw) raw.recycle()
        return out
    }

    /** Rectangle that fits an image of [iw]×[ih] centered in a [w]×[h] canvas. */
    fun fitRect(iw: Int, ih: Int, w: Int, h: Int): RectF {
        val s = min(w.toFloat() / iw, h.toFloat() / ih)
        val dw = iw * s; val dh = ih * s
        return RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    }
}
