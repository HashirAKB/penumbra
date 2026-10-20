package io.github.hashirakb.penumbra.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import io.github.hashirakb.penumbra.core.JEvent

/** One reversible change to a document. */
sealed class UndoEntry {
    abstract fun undo(doc: Document)
    abstract fun redo(doc: Document)
    abstract val bytes: Long

    /** Before/after pixels of a rectangle of one layer. */
    class Pixels(val layerId: Int, val x: Int, val y: Int, val before: Bitmap, val after: Bitmap) : UndoEntry() {
        override fun undo(doc: Document) = put(doc, before)
        override fun redo(doc: Document) = put(doc, after)
        private fun put(doc: Document, bmp: Bitmap) {
            val layer = doc.layer(layerId) ?: return
            val c = Canvas(layer.bitmap)
            c.drawBitmap(bmp, x.toFloat(), y.toFloat(), REPLACE)
            layer.touch()
        }
        override val bytes: Long get() = before.allocationByteCount.toLong() + after.allocationByteCount
    }

    /** Layer order, membership and properties before/after a structural change. */
    class Stack(val before: StackState, val after: StackState) : UndoEntry() {
        override fun undo(doc: Document) = before.restore(doc)
        override fun redo(doc: Document) = after.restore(doc)
        override val bytes: Long get() = 256
    }

    class Background(val before: Int, val after: Int) : UndoEntry() {
        override fun undo(doc: Document) { doc.background = before }
        override fun redo(doc: Document) { doc.background = after }
        override val bytes: Long get() = 16
    }

    /** Several entries applied together (undo runs them backwards). */
    class Group(val parts: List<UndoEntry>) : UndoEntry() {
        override fun undo(doc: Document) { for (p in parts.asReversed()) p.undo(doc) }
        override fun redo(doc: Document) { for (p in parts) p.redo(doc) }
        override val bytes: Long get() = parts.sumOf { it.bytes }
    }

    companion object {
        val REPLACE = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        /** Captures [rect] of [layer] (clipped to the bitmap); null if empty. */
        fun capture(layer: Layer, rect: Rect): Pair<Rect, Bitmap>? {
            val r = Rect(rect)
            if (!r.intersect(0, 0, layer.bitmap.width, layer.bitmap.height)) return null
            if (r.width() <= 0 || r.height() <= 0) return null
            return r to Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        }
    }
}

class StackState(
    val layers: List<Layer>,
    val props: List<LayerProps>,
    val activeId: Int,
) {
    fun restore(doc: Document) {
        doc.layers.clear()
        doc.layers.addAll(layers)
        for (i in layers.indices) layers[i].setProps(props[i])
        doc.activeId = activeId
        for (l in layers) l.touch()
    }

    companion object {
        fun of(doc: Document) = StackState(doc.layers.toList(), doc.layers.map { it.props() }, doc.activeId)
    }
}

/**
 * Undo/redo stacks kept in step with the journal: every undoable change is one journal event,
 * so undoing removes the event and the journal always describes exactly what is on the canvas.
 */
class History(private val doc: Document) {
    private class Item(val entry: UndoEntry, val event: JEvent)

    private val undo = ArrayDeque<Item>()
    private val redo = ArrayDeque<Item>()
    private var bytes = 0L
    private val budget = Runtime.getRuntime().maxMemory() / 4

    var onChange: (() -> Unit)? = null

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(entry: UndoEntry, event: JEvent) {
        doc.journal.add(event)
        undo.addLast(Item(entry, event))
        bytes += entry.bytes
        for (r in redo) bytes -= r.entry.bytes
        redo.clear()
        // Forget the oldest steps when over budget (the journal keeps them for replay).
        while (bytes > budget && undo.size > 1) bytes -= undo.removeFirst().entry.bytes
        doc.markChanged()
        onChange?.invoke()
    }

    fun undo(): Boolean {
        val item = undo.removeLastOrNull() ?: return false
        item.entry.undo(doc)
        doc.journal.removeLast()
        redo.addLast(item)
        doc.markChanged()
        onChange?.invoke()
        return true
    }

    fun redo(): Boolean {
        val item = redo.removeLastOrNull() ?: return false
        item.entry.redo(doc)
        doc.journal.add(item.event)
        undo.addLast(item)
        doc.markChanged()
        onChange?.invoke()
        return true
    }

    fun clear() { undo.clear(); redo.clear(); bytes = 0 }
}
