package io.github.hashirakb.penumbra.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import io.github.hashirakb.penumbra.core.DabGenerator
import io.github.hashirakb.penumbra.core.DabList
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.StrokeKind
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Rebuilds a drawing from its journal, one event (or part of a stroke) at a time.
 * Powers the replay screen, trace-along lessons and timelapse export.
 */
class ReplayPlayer(source: Document) {
    val width = source.width
    val height = source.height
    /** Paper color at the start of the journal (Background events may change it later). */
    private val background0 = source.journal.background
    val events: List<JEvent> = ArrayList(source.journal.events)
    private val assets: Map<String, Bitmap> = HashMap(source.assets)
    private val engine = Engine()
    var doc = fresh()
        private set

    /** Index of the next event to apply. */
    var index = 0
        private set

    /** Indices of events that are visible drawing steps. */
    val steps: List<Int> = events.indices.filter { events[it].isDrawingStep }

    private fun fresh(): Document {
        val d = Document(width, height, background0)
        d.assets.putAll(assets)
        return d
    }

    fun reset() {
        recycle()
        doc = fresh()
        index = 0
    }

    val done get() = index >= events.size

    /** Applies the next event. */
    fun step(): JEvent? {
        if (done) return null
        val e = events[index++]
        try { engine.apply(doc, e) } catch (_: OutOfMemoryError) {}
        return e
    }

    /** Moves to just before event [target] (re-playing from the start when going back). */
    fun seek(target: Int) {
        val t = target.coerceIn(0, events.size)
        if (t < index) reset()
        while (index < t) step()
    }

    /** Applies events up to and including the next drawing step; returns that step's event. */
    fun nextStep(): JEvent? {
        while (!done) {
            val e = step() ?: return null
            if (e.isDrawingStep) return e
        }
        return null
    }

    /** The next drawing step without applying it (structural events before it are applied). */
    fun peekStep(): JEvent? {
        while (!done && !events[index].isDrawingStep) step()
        return if (done) null else events[index]
    }

    /** Number of drawing steps completed so far. */
    fun stepsDone(): Int = steps.count { it < index }

    // --------------------------------------------------------------- rendering

    private val groupPaint = Paint()
    private val dabPaint = Paint()
    private var partialDabs: DabList? = null
    private var partialKey = -1
    private var partialPoints = -1

    /**
     * Draws the current state into [canvas] (document coordinates), plus the first [points]
     * samples of the stroke at [partialIndex] if given.
     */
    fun draw(canvas: Canvas, partialIndex: Int = -1, points: Int = 0) {
        val e = events.getOrNull(partialIndex) as? JEvent.Stroke
        if (e == null || e.kind == StrokeKind.SMUDGE || points <= 0) {
            Compositor.draw(canvas, doc)
            return
        }
        val dabs = dabsForPrefix(partialIndex, e, points)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        Compositor.draw(canvas, doc, true) { layer, c ->
            if (layer.id != e.layerId) return@draw false
            c.saveLayer(rect, Compositor.layerPaint(layer, Paint()))
            c.drawBitmap(layer.bitmap, 0f, 0f, null)
            c.saveLayer(rect, engine.groupPaintFor(e.kind, e.opacity * e.brush.strokeOpacity, e.alphaLock, groupPaint))
            engine.renderer.preparePaint(dabPaint, e.brush, e.color)
            engine.renderer.draw(c, dabs, 0, dabs.size, e.brush, dabPaint)
            c.restore(); c.restore()
            true
        }
    }

    private fun dabsForPrefix(i: Int, e: JEvent.Stroke, n: Int): DabList {
        val cached = partialDabs
        if (cached != null && partialKey == i && partialPoints == n) return cached
        val out = DabList(max(64, n * 2))
        DabGenerator(e.brush, e.size, e.seed, e.symmetry.transforms(), out).addAll(e.points.prefix(n))
        partialDabs = out; partialKey = i; partialPoints = n
        return out
    }

    fun flatten(maxSide: Int, reuse: Bitmap? = null): Bitmap = Compositor.flatten(doc, maxSide, true, reuse)

    fun recycle() {
        for (l in doc.layers) l.bitmap.recycle()
        doc.layers.clear()
    }

    companion object {
        /** Path of a stroke's samples, for the trace-along "next stroke" hint. */
        fun pathOf(e: JEvent.Stroke): Path {
            val p = Path()
            val pts = e.points
            if (pts.size == 0) return p
            for (m in e.symmetry.transforms()) {
                p.moveTo(m.mapX(pts.x(0), pts.y(0)), m.mapY(pts.x(0), pts.y(0)))
                if (pts.size == 1) p.lineTo(m.mapX(pts.x(0), pts.y(0)) + 0.1f, m.mapY(pts.x(0), pts.y(0)))
                for (i in 1 until pts.size) p.lineTo(m.mapX(pts.x(i), pts.y(i)), m.mapY(pts.x(i), pts.y(i)))
            }
            return p
        }
    }
}

/** Encodes frames drawn on a Canvas into an H.264 MP4 file. */
class VideoEncoder(val width: Int, val height: Int, private val fps: Int, file: File) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val surface: Surface
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var written = 0L

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.12f).toInt().coerceIn(2_000_000, 16_000_000))
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    fun frame(draw: (Canvas) -> Unit) {
        val c = surface.lockHardwareCanvas()
        try { draw(c) } finally { surface.unlockCanvasAndPost(c) }
        drain(false)
    }

    fun finish() {
        codec.signalEndOfInputStream()
        drain(true)
        try { codec.stop() } catch (_: Throwable) {}
        codec.release()
        surface.release()
        if (muxing) try { muxer.stop() } catch (_: Throwable) {}
        muxer.release()
    }

    fun abort() {
        try { codec.stop() } catch (_: Throwable) {}
        codec.release(); surface.release()
        try { if (muxing) muxer.stop() } catch (_: Throwable) {}
        muxer.release()
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (end) 20_000L else 2_000L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (++idle > 250) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxing = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !config && muxing) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        // Evenly spaced timestamps, whatever speed we rendered at.
                        info.presentationTimeUs = written * 1_000_000L / fps
                        muxer.writeSampleData(track, buf, info)
                        written++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        /** Even dimensions fitting within [maxSide], keeping the aspect ratio. */
        fun sizeFor(w: Int, h: Int, maxSide: Int = 1280): Pair<Int, Int> {
            val s = min(1f, maxSide.toFloat() / max(w, h))
            val vw = ((w * s).toInt() / 2) * 2
            val vh = ((h * s).toInt() / 2) * 2
            return max(2, vw) to max(2, vh)
        }
    }
}

/**
 * Renders a timelapse of [source]'s journal: strokes animate point by point and the finished
 * picture holds for a moment at the end. Call on a worker thread.
 */
object Timelapse {
    fun render(source: Document, file: File, seconds: Int, progress: (Float) -> Boolean) {
        val player = ReplayPlayer(source)
        val fps = 30
        val (vw, vh) = VideoEncoder.sizeFor(source.width, source.height)
        // Work units: one per stroke sample, plus a fixed cost for other events.
        val units = player.events.map { if (it is JEvent.Stroke) max(1, it.points.size) else 12 }
        val total = max(1, units.sum())
        val frames = (seconds * fps).coerceAtLeast(30)
        val perFrame = max(1.0, total.toDouble() / frames)
        val enc = VideoEncoder(vw, vh, fps, file)
        val scale = vw.toFloat() / source.width
        try {
            var budget = 0.0
            var partialPts = 0
            var frame = 0
            while (!player.done) {
                budget += perFrame
                // Apply whole events while the budget allows; animate the stroke in progress.
                while (!player.done) {
                    val i = player.index
                    val e = player.events[i]
                    val need = units[i] - partialPts
                    if (budget >= need) {
                        budget -= need; partialPts = 0; player.step()
                    } else {
                        if (e is JEvent.Stroke) { partialPts += budget.toInt(); budget -= budget.toInt() }
                        break
                    }
                }
                val pi = player.index
                val pts = partialPts
                enc.frame { c ->
                    c.drawColor(0xFFFFFFFF.toInt())
                    c.save(); c.scale(scale, scale)
                    player.draw(c, pi, pts)
                    c.restore()
                }
                frame++
                if (!progress(min(0.98f, player.index.toFloat() / max(1, player.events.size)))) {
                    enc.abort(); player.recycle(); file.delete(); return
                }
            }
            // Hold on the finished artwork.
            repeat(fps * 2) {
                enc.frame { c ->
                    c.drawColor(0xFFFFFFFF.toInt())
                    c.save(); c.scale(scale, scale); player.draw(c); c.restore()
                }
            }
            enc.finish()
        } catch (t: Throwable) {
            try { enc.abort() } catch (_: Throwable) {}
            file.delete()
            throw t
        } finally {
            player.recycle()
        }
        progress(1f)
    }
}
