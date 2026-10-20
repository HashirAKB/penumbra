package io.github.hashirakb.penumbra.io

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** The user's drawings, kept as .ora files in app storage with small cached thumbnails. */
class Storage(private val context: Context) {
    private val dir: File = File(context.filesDir, "art").apply { mkdirs() }

    class Item(val id: String, val file: File, val thumb: File, val title: String, val modified: Long)

    fun list(): List<Item> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".ora") }
        .map { f ->
            val id = f.name.removeSuffix(".ora")
            Item(id, f, thumbFile(id), titleOf(id), f.lastModified())
        }
        .sortedByDescending { it.modified }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 13)
    fun fileFor(id: String) = File(dir, "$id.ora")
    fun thumbFile(id: String) = File(dir, "$id.thumb.png")
    private fun titleFile(id: String) = File(dir, "$id.title")

    fun titleOf(id: String): String =
        titleFile(id).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } ?: "Untitled"

    fun setTitle(id: String, title: String) { titleFile(id).writeText(title.take(80)) }

    fun saveThumb(id: String, bmp: Bitmap) {
        val t = OraIO.scaled(bmp, 480)
        OraIO.writeAtomically(thumbFile(id)) { t.compress(Bitmap.CompressFormat.PNG, 90, it) }
        if (t !== bmp) t.recycle()
    }

    fun loadThumb(id: String): Bitmap? = thumbFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun delete(id: String) {
        fileFor(id).delete(); thumbFile(id).delete(); titleFile(id).delete()
    }

    fun duplicate(id: String): String {
        val n = newId()
        fileFor(id).copyTo(fileFor(n))
        thumbFile(id).takeIf { it.exists() }?.copyTo(thumbFile(n))
        setTitle(n, titleOf(id) + " copy")
        return n
    }
}

/** Exports to the shared gallery (MediaStore) and hands files to other apps. */
object Exporter {
    fun saveImage(context: Context, bmp: Bitmap, name: String, jpeg: Boolean): Uri? {
        val mime = if (jpeg) "image/jpeg" else "image/png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name + if (jpeg) ".jpg" else ".png")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Penumbra")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (jpeg) {
                    // JPEG has no transparency: put the image on white first.
                    val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(flat)
                    c.drawColor(0xFFFFFFFF.toInt()); c.drawBitmap(bmp, 0f, 0f, null)
                    flat.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    flat.recycle()
                } else bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw FileNotFoundException()
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun saveVideo(context: Context, file: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Penumbra")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null); null
        }
    }

    fun share(context: Context, uri: Uri, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    /** Copies [file] into the share folder and returns a content:// URI for it. */
    fun shareableFile(context: Context, file: File, name: String): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val out = File(dir, name)
        file.copyTo(out, overwrite = true)
        return ShareProvider.uriFor(context, out)
    }
}

/** Minimal read-only provider for files in cache/share, so .ora files can be sent to other apps. */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = File(ctx.cacheDir, "share").canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir || !f.exists()) throw FileNotFoundException(name)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return c
    }

    override fun getType(uri: Uri): String = when {
        uri.toString().endsWith(".ora") -> OraIO.MIME
        uri.toString().endsWith(".mp4") -> "video/mp4"
        uri.toString().endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        fun authority(context: Context) = context.packageName + ".share"
        fun uriFor(context: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(f.name).build()
    }
}
