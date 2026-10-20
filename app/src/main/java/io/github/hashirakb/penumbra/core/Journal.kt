package io.github.hashirakb.penumbra.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Every change to a drawing is one event. The journal is the drawing's complete, replayable
 * history: it powers stroke-by-stroke replay, trace-along lessons and timelapse export, and it
 * travels inside the saved file so anyone opening the drawing can replay or learn from it.
 */
sealed class JEvent {
    class Stroke(
        val layerId: Int,
        val kind: StrokeKind,
        val brush: BrushSpec,
        val color: Int,
        val size: Float,
        val opacity: Float,
        val seed: Long,
        val symmetry: SymmetryConfig,
        val alphaLock: Boolean,
        val points: StrokePoints,
    ) : JEvent()

    class Fill(
        val layerId: Int, val x: Int, val y: Int, val color: Int,
        val tolerance: Int, val gap: Int, val expand: Int, val sampleAllLayers: Boolean,
        val alphaLock: Boolean,
    ) : JEvent()

    class AddLayer(val id: Int, val name: String, val index: Int) : JEvent()
    class DeleteLayer(val id: Int) : JEvent()
    class MoveLayer(val id: Int, val toIndex: Int) : JEvent()
    class DuplicateLayer(val srcId: Int, val newId: Int, val index: Int) : JEvent()
    class MergeDown(val upperId: Int, val lowerId: Int) : JEvent()
    class ClearLayer(val id: Int) : JEvent()
    class LayerProps(
        val id: Int, val name: String, val visible: Boolean, val opacity: Float,
        val blend: Int, val alphaLock: Boolean,
    ) : JEvent()

    /** Draws an image asset (stored alongside the journal) into a rectangle of a layer. */
    class PlaceImage(
        val layerId: Int, val asset: String,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
    ) : JEvent()

    /** Moves/scales/rotates a layer's pixels by a 3x3 matrix (row-major, 9 values). */
    class TransformLayer(val layerId: Int, val matrix: FloatArray) : JEvent()

    class Background(val color: Int) : JEvent()

    /** Whether replay should treat this as a visible "step" (something the artist drew). */
    val isDrawingStep: Boolean get() = this is Stroke || this is Fill || this is PlaceImage
}

class Journal(val width: Int, val height: Int, var background: Int) {
    val events = ArrayList<JEvent>()

    fun add(e: JEvent) { events += e }
    fun removeLast(): JEvent? = if (events.isEmpty()) null else events.removeAt(events.size - 1)

    /** Asset names referenced by PlaceImage events. */
    fun referencedAssets(): Set<String> =
        events.filterIsInstance<JEvent.PlaceImage>().map { it.asset }.toSet()

    fun write(os: OutputStream) {
        val out = DataOutputStream(java.io.BufferedOutputStream(os, 1 shl 16))
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(width); out.writeInt(height); out.writeInt(background)
        out.writeInt(events.size)
        for (e in events) writeEvent(out, e)
        out.flush()
    }

    private fun writeEvent(out: DataOutputStream, e: JEvent) {
        when (e) {
            is JEvent.Stroke -> {
                out.writeByte(T_STROKE)
                out.writeInt(e.layerId); out.writeByte(e.kind.ordinal)
                e.brush.write(out)
                out.writeInt(e.color); out.writeFloat(e.size); out.writeFloat(e.opacity)
                out.writeLong(e.seed)
                out.writeByte(e.symmetry.mode.ordinal); out.writeByte(e.symmetry.segments)
                out.writeFloat(e.symmetry.cx); out.writeFloat(e.symmetry.cy)
                out.writeBoolean(e.alphaLock)
                val p = e.points
                out.writeInt(p.size)
                val d = p.data
                for (i in 0 until p.size * StrokePoints.STRIDE) out.writeFloat(d[i])
            }
            is JEvent.Fill -> {
                out.writeByte(T_FILL)
                out.writeInt(e.layerId); out.writeInt(e.x); out.writeInt(e.y); out.writeInt(e.color)
                out.writeInt(e.tolerance); out.writeInt(e.gap); out.writeInt(e.expand)
                out.writeBoolean(e.sampleAllLayers); out.writeBoolean(e.alphaLock)
            }
            is JEvent.AddLayer -> { out.writeByte(T_ADD); out.writeInt(e.id); out.writeUTF(e.name); out.writeInt(e.index) }
            is JEvent.DeleteLayer -> { out.writeByte(T_DELETE); out.writeInt(e.id) }
            is JEvent.MoveLayer -> { out.writeByte(T_MOVE); out.writeInt(e.id); out.writeInt(e.toIndex) }
            is JEvent.DuplicateLayer -> { out.writeByte(T_DUP); out.writeInt(e.srcId); out.writeInt(e.newId); out.writeInt(e.index) }
            is JEvent.MergeDown -> { out.writeByte(T_MERGE); out.writeInt(e.upperId); out.writeInt(e.lowerId) }
            is JEvent.ClearLayer -> { out.writeByte(T_CLEAR); out.writeInt(e.id) }
            is JEvent.LayerProps -> {
                out.writeByte(T_PROPS); out.writeInt(e.id); out.writeUTF(e.name)
                out.writeBoolean(e.visible); out.writeFloat(e.opacity); out.writeInt(e.blend)
                out.writeBoolean(e.alphaLock)
            }
            is JEvent.PlaceImage -> {
                out.writeByte(T_PLACE); out.writeInt(e.layerId); out.writeUTF(e.asset)
                out.writeFloat(e.left); out.writeFloat(e.top); out.writeFloat(e.right); out.writeFloat(e.bottom)
            }
            is JEvent.TransformLayer -> {
                out.writeByte(T_TRANSFORM); out.writeInt(e.layerId)
                for (i in 0 until 9) out.writeFloat(e.matrix[i])
            }
            is JEvent.Background -> { out.writeByte(T_BG); out.writeInt(e.color) }
        }
    }

    companion object {
        private const val MAGIC = 0x504E424A // "PNBJ"
        private const val VERSION = 1
        private const val T_STROKE = 1
        private const val T_FILL = 2
        private const val T_ADD = 3
        private const val T_DELETE = 4
        private const val T_MOVE = 5
        private const val T_DUP = 6
        private const val T_MERGE = 7
        private const val T_CLEAR = 8
        private const val T_PROPS = 9
        private const val T_PLACE = 10
        private const val T_TRANSFORM = 11
        private const val T_BG = 12

        fun read(ins: InputStream): Journal {
            val inp = DataInputStream(java.io.BufferedInputStream(ins, 1 shl 16))
            if (inp.readInt() != MAGIC) throw IOException("Not a Penumbra journal")
            val version = inp.readInt()
            if (version > VERSION) throw IOException("Journal from a newer version ($version)")
            val j = Journal(inp.readInt(), inp.readInt(), inp.readInt())
            val n = inp.readInt()
            for (k in 0 until n) j.events += readEvent(inp)
            return j
        }

        private fun readEvent(inp: DataInputStream): JEvent = when (val t = inp.readByte().toInt()) {
            T_STROKE -> {
                val layer = inp.readInt()
                val kind = StrokeKind.values()[inp.readByte().toInt().coerceIn(0, 2)]
                val brush = BrushSpec.read(inp)
                val color = inp.readInt(); val size = inp.readFloat(); val opacity = inp.readFloat()
                val seed = inp.readLong()
                val mode = SymmetryMode.values()[inp.readByte().toInt().coerceIn(0, SymmetryMode.values().size - 1)]
                val seg = inp.readByte().toInt()
                val sym = SymmetryConfig(mode, seg, inp.readFloat(), inp.readFloat())
                val lock = inp.readBoolean()
                val count = inp.readInt()
                if (count < 0 || count > 5_000_000) throw IOException("Corrupt stroke")
                val raw = FloatArray(count * StrokePoints.STRIDE) { inp.readFloat() }
                JEvent.Stroke(layer, kind, brush, color, size, opacity, seed, sym, lock,
                    StrokePoints.fromRaw(raw, count))
            }
            T_FILL -> JEvent.Fill(inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt(),
                inp.readInt(), inp.readInt(), inp.readInt(), inp.readBoolean(), inp.readBoolean())
            T_ADD -> JEvent.AddLayer(inp.readInt(), inp.readUTF(), inp.readInt())
            T_DELETE -> JEvent.DeleteLayer(inp.readInt())
            T_MOVE -> JEvent.MoveLayer(inp.readInt(), inp.readInt())
            T_DUP -> JEvent.DuplicateLayer(inp.readInt(), inp.readInt(), inp.readInt())
            T_MERGE -> JEvent.MergeDown(inp.readInt(), inp.readInt())
            T_CLEAR -> JEvent.ClearLayer(inp.readInt())
            T_PROPS -> JEvent.LayerProps(inp.readInt(), inp.readUTF(), inp.readBoolean(),
                inp.readFloat(), inp.readInt(), inp.readBoolean())
            T_PLACE -> JEvent.PlaceImage(inp.readInt(), inp.readUTF(), inp.readFloat(),
                inp.readFloat(), inp.readFloat(), inp.readFloat())
            T_TRANSFORM -> JEvent.TransformLayer(inp.readInt(), FloatArray(9) { inp.readFloat() })
            T_BG -> JEvent.Background(inp.readInt())
            else -> throw IOException("Unknown event type $t")
        }
    }
}
