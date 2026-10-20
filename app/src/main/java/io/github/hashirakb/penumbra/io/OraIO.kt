package io.github.hashirakb.penumbra.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Xml
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.core.Journal
import io.github.hashirakb.penumbra.core.LayerBlend
import io.github.hashirakb.penumbra.core.OraStack
import io.github.hashirakb.penumbra.engine.Compositor
import io.github.hashirakb.penumbra.engine.Document
import io.github.hashirakb.penumbra.engine.Layer
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * OpenRaster (.ora) reader/writer. Penumbra saves standard ORA files — layers, names, opacity,
 * visibility and blend modes open in Krita, GIMP and MyPaint — plus a penumbra/ folder holding
 * the stroke journal and assets so replay and trace-along survive a round trip.
 */
object OraIO {
    const val MIME = "image/openraster"

    /** Keeps encoded PNGs between saves so only changed layers are re-encoded. */
    class Cache {
        val layers = HashMap<Int, Pair<Int, ByteArray>>()
        val assets = HashMap<String, ByteArray>()
    }

    /** Everything needed to write the file, captured on the main thread. */
    class Snapshot internal constructor(
        val width: Int, val height: Int,
        val layers: List<LayerSnap>,
        val merged: Bitmap,
        val journal: ByteArray,
        val assets: Map<String, Any>, // ByteArray (cached) or Bitmap (to encode)
        val meta: String,
        val cache: Cache,
    ) {
        class LayerSnap(
            val id: Int, val version: Int, val name: String, val opacity: Float, val visible: Boolean,
            val blend: LayerBlend, val locked: Boolean, val png: ByteArray?, val bitmap: Bitmap?,
        )

        /** Encodes and writes the archive. Safe to call on a worker thread. */
        fun writeTo(out: OutputStream) {
            val zip = ZipOutputStream(out)
            // The mimetype entry must come first and be stored uncompressed.
            val mime = MIME.toByteArray()
            val e = ZipEntry("mimetype")
            e.method = ZipEntry.STORED
            e.size = mime.size.toLong(); e.compressedSize = mime.size.toLong()
            e.crc = CRC32().apply { update(mime) }.value
            zip.putNextEntry(e); zip.write(mime); zip.closeEntry()

            val entries = ArrayList<OraStack.Entry>()
            for (l in layers.asReversed()) { // ORA lists top-most first
                val src = "data/layer${l.id}.png"
                val png = l.png ?: encodePng(l.bitmap!!).also {
                    synchronized(cache) { cache.layers[l.id] = l.version to it }
                    l.bitmap.recycle()
                }
                put(zip, src, png, compress = false)
                entries += OraStack.Entry(l.name, src, l.opacity, l.visible, l.blend, l.locked)
            }
            put(zip, "stack.xml", OraStack.write(width, height, entries).toByteArray())
            put(zip, "mergedimage.png", encodePng(merged), compress = false)
            val thumb = scaled(merged, 256)
            put(zip, "Thumbnails/thumbnail.png", encodePng(thumb), compress = false)
            if (thumb !== merged) thumb.recycle()
            merged.recycle()
            put(zip, "penumbra/document.json", meta.toByteArray())
            put(zip, "penumbra/journal.bin", journal)
            for ((name, v) in assets) {
                val bytes = when (v) {
                    is ByteArray -> v
                    is Bitmap -> encodePng(v).also { synchronized(cache) { cache.assets[name] = it } }
                    else -> continue
                }
                put(zip, "penumbra/assets/$name", bytes, compress = false)
            }
            zip.finish()
            zip.flush()
        }
    }

    fun snapshot(doc: Document, cache: Cache, title: String): Snapshot {
        val layers = doc.layers.map { l ->
            val cached = synchronized(cache) { cache.layers[l.id] }
            val png = if (cached != null && cached.first == l.version) cached.second else null
            Snapshot.LayerSnap(
                l.id, l.version, l.name, l.opacity, l.visible, l.blend, l.locked, png,
                if (png == null) l.bitmap.copy(Bitmap.Config.ARGB_8888, false) else null,
            )
        }
        val merged = Compositor.flatten(doc)
        val jb = ByteArrayOutputStream()
        doc.journal.write(jb)
        val referenced = doc.journal.referencedAssets()
        val assets = LinkedHashMap<String, Any>()
        for ((name, bmp) in doc.assets) {
            if (name !in referenced) continue
            assets[name] = synchronized(cache) { cache.assets[name] } ?: bmp
        }
        val meta = JSONObject().apply {
            put("app", "penumbra")
            put("format", 1)
            put("title", title)
            put("background", doc.background)
            put("activeId", doc.activeId)
            put("nextLayerId", doc.nextLayerId)
            put("layers", JSONArray().apply {
                for (l in doc.layers) put(JSONObject().apply {
                    put("id", l.id); put("alphaLock", l.alphaLock); put("locked", l.locked)
                })
            })
        }.toString()
        return Snapshot(doc.width, doc.height, layers, merged, jb.toByteArray(), assets, meta, cache)
    }

    class Loaded(val doc: Document, val title: String?)

    /** Reads an ORA file (ours or from another app). */
    fun read(input: InputStream, maxSide: Int = 8192): Loaded {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) files[entry.name] = zip.readBytes()
            }
        }
        val stack = files["stack.xml"] ?: throw IOException("Not an OpenRaster file (no stack.xml)")
        val parsed = parseStack(stack)
        if (parsed.width <= 0 || parsed.height <= 0 || parsed.width > maxSide || parsed.height > maxSide) {
            throw IOException("Unsupported canvas size ${parsed.width}×${parsed.height}")
        }
        val meta = files["penumbra/document.json"]?.let { JSONObject(String(it)) }
        val background = meta?.optInt("background", 0xFFFFFFFF.toInt()) ?: 0xFFFFFFFF.toInt()
        val doc = Document(parsed.width, parsed.height, background)
        val metaLayers = meta?.optJSONArray("layers")

        // stack.xml is top-first; our list is bottom-first.
        val ordered = parsed.layers.asReversed()
        for ((i, pl) in ordered.withIndex()) {
            val bytes = files[pl.src] ?: continue
            val img = decodeMutable(bytes) ?: continue
            val bmp = doc.newBitmap()
            Canvas(bmp).drawBitmap(img, pl.x.toFloat(), pl.y.toFloat(), null)
            img.recycle()
            val ml = metaLayers?.let { arr ->
                // Match by src name so a reordered stack.xml still lines up.
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { "data/layer${it.optInt("id")}.png" == pl.src }
            }
            val id = ml?.optInt("id") ?: (i + 1)
            val layer = Layer(id, pl.name.ifBlank { "Layer ${i + 1}" }, bmp)
            layer.opacity = pl.opacity; layer.visible = pl.visible; layer.blend = pl.blend
            layer.locked = pl.locked || (ml?.optBoolean("locked") ?: false)
            layer.alphaLock = ml?.optBoolean("alphaLock") ?: false
            doc.layers += layer
            doc.nextLayerId = maxOf(doc.nextLayerId, id + 1)
        }
        if (doc.layers.isEmpty()) {
            doc.layers += Layer(doc.allocateLayerId(), "Layer 1", doc.newBitmap())
        }
        doc.activeId = meta?.optInt("activeId", doc.layers.last().id) ?: doc.layers.last().id
        if (doc.layer(doc.activeId) == null) doc.activeId = doc.layers.last().id
        meta?.optInt("nextLayerId", doc.nextLayerId)?.let { doc.nextLayerId = maxOf(doc.nextLayerId, it) }

        // Journal: ours if present and consistent, otherwise synthesize one from the layers.
        val jb = files["penumbra/journal.bin"]
        var journalOk = false
        if (jb != null) {
            try {
                val j = Journal.read(ByteArrayInputStream(jb))
                if (j.width == doc.width && j.height == doc.height) {
                    doc.journal.background = j.background
                    doc.journal.events.addAll(j.events)
                    for (name in j.referencedAssets()) {
                        files["penumbra/assets/$name"]?.let { b -> decodeMutable(b)?.let { doc.assets[name] = it } }
                    }
                    journalOk = j.referencedAssets().all { it in doc.assets }
                }
            } catch (_: Exception) { journalOk = false }
        }
        if (!journalOk) synthesizeJournal(doc)
        for (l in doc.layers) l.savedVersion = -1
        return Loaded(doc, meta?.optString("title")?.takeIf { it.isNotBlank() })
    }

    /** Rebuilds a journal that recreates the current layers, so any file can be replayed. */
    fun synthesizeJournal(doc: Document) {
        doc.journal.events.clear()
        doc.journal.background = doc.background
        doc.assets.clear()
        for ((i, l) in doc.layers.withIndex()) {
            doc.journal.add(JEvent.AddLayer(l.id, l.name, i))
            if (!isEmpty(l.bitmap)) {
                val name = "base_layer${l.id}.png"
                doc.assets[name] = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                doc.journal.add(JEvent.PlaceImage(l.id, name, 0f, 0f, doc.width.toFloat(), doc.height.toFloat()))
            }
            doc.journal.add(JEvent.LayerProps(l.id, l.name, l.visible, l.opacity, l.blend.ordinal, l.alphaLock))
        }
    }

    fun isEmpty(bmp: Bitmap): Boolean {
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height step 2) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (v in row) if (v ushr 24 != 0) return false
        }
        return true
    }

    private class ParsedLayer(
        val name: String, val src: String, val x: Int, val y: Int, val opacity: Float,
        val visible: Boolean, val blend: LayerBlend, val locked: Boolean,
    )
    private class ParsedStack(val width: Int, val height: Int, val layers: List<ParsedLayer>)

    private fun parseStack(bytes: ByteArray): ParsedStack {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var w = 0; var h = 0
        val layers = ArrayList<ParsedLayer>()
        // Offsets of nested stacks (groups) accumulate; groups are flattened into plain layers.
        val offsets = ArrayDeque<IntArray>()
        offsets.addLast(intArrayOf(0, 0))
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "image" -> { w = p.getAttributeValue(null, "w")?.toIntOrNull() ?: 0; h = p.getAttributeValue(null, "h")?.toIntOrNull() ?: 0 }
                    "stack" -> {
                        val o = offsets.last()
                        offsets.addLast(intArrayOf(o[0] + (p.getAttributeValue(null, "x")?.toIntOrNull() ?: 0),
                            o[1] + (p.getAttributeValue(null, "y")?.toIntOrNull() ?: 0)))
                    }
                    "layer" -> {
                        val o = offsets.last()
                        val src = p.getAttributeValue(null, "src") ?: continue
                        layers += ParsedLayer(
                            name = p.getAttributeValue(null, "name") ?: "",
                            src = src,
                            x = o[0] + (p.getAttributeValue(null, "x")?.toIntOrNull() ?: 0),
                            y = o[1] + (p.getAttributeValue(null, "y")?.toIntOrNull() ?: 0),
                            opacity = p.getAttributeValue(null, "opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                            visible = p.getAttributeValue(null, "visibility") != "hidden",
                            blend = LayerBlend.fromOra(p.getAttributeValue(null, "composite-op")),
                            locked = p.getAttributeValue(null, "edit-locked") == "true",
                        )
                    }
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "stack" && offsets.size > 1) {
                offsets.removeLast()
            }
        }
        return ParsedStack(w, h, layers)
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray, compress: Boolean = true) {
        val e = ZipEntry(name)
        if (!compress) {
            // PNG data is already compressed; store it to save time.
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
            e.crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(e)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun encodePng(b: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(maxOf(1024, b.width * b.height / 4))
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun decodeMutable(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val s = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    /** Writes a file atomically (temp file + rename) so a crash never leaves a broken drawing. */
    fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().buffered(1 shl 16).use(write)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not save ${file.name}")
        }
    }
}
