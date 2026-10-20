package io.github.hashirakb.penumbra.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.hashirakb.penumbra.core.ColoringPages
import io.github.hashirakb.penumbra.core.Lessons
import io.github.hashirakb.penumbra.io.Exporter
import io.github.hashirakb.penumbra.io.Storage
import java.util.Random
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.math.max

/** The gallery: your drawings, plus ways to start something new. */
class MainActivity : Activity() {
    private lateinit var storage: Storage
    private val io = Executors.newFixedThreadPool(2)
    private lateinit var grid: GridLayout
    private lateinit var empty: TextView
    private lateinit var content: LinearLayout
    private val rnd = Random()

    companion object {
        private const val REQ_PHOTO = 1
        private const val REQ_IMPORT = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        window.statusBarColor = 0; window.navigationBarColor = 0
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        setContentView(scroll)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Theme.text(this, "Penumbra", 28f, bold = true))
        titles.addView(Theme.text(this, "Draw, color, learn — free and open source", 13f, Theme.MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(IconButton(this, Icon.GEAR, "Settings").apply { setOnClickListener { SettingsDialog.show(this@MainActivity, Settings(this@MainActivity)) {} } })
        content.addView(header)

        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cards.addView(card(Icon.PLUS, "New drawing", "Blank canvas") { newDrawing() })
        cards.addView(card(Icon.SPARKLE, "Coloring page", "Endless designs") { coloringPicker() })
        cards.addView(card(Icon.IMAGE, "Photo drawing", "Trace or turn into lines") { pickPhoto() })
        cards.addView(card(Icon.LEARN, "Lessons", "Draw along, step by step") { lessons() })
        cards.addView(card(Icon.IMPORT, "Open file", ".ora, PNG or JPEG") { importFile() })
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(cards) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.dpi(this@MainActivity, 18f) })

        content.addView(Theme.text(this, "Your art", 18f, bold = true).apply {
            setPadding(0, Theme.dpi(context, 24f), 0, Theme.dpi(context, 10f))
        })
        empty = Theme.text(this, "Nothing here yet. Start a drawing, pick a coloring page, or try a lesson.", 14f, Theme.MUTED)
        content.addView(empty)
        grid = GridLayout(this)
        content.addView(grid)

        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val pad = Theme.dpi(this, 18f)
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                content.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(pad, pad + insets.systemWindowInsetTop, pad, pad + insets.systemWindowInsetBottom)
            }
            insets
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** "Open with" / "Share to" Penumbra from other apps. */
    private fun handleIncoming(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) openEditor(EditorActivity.MODE_IMPORT, uri)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    private fun card(icon: Icon, title: String, sub: String, action: () -> Unit): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.panel(this@MainActivity, 18f, Theme.SURFACE_HI)
            val p = Theme.dpi(context, 14f)
            setPadding(p, p, p, p)
            isClickable = true
            contentDescription = title
            setOnClickListener { action() }
        }
        val ic = object : View(this) {
            override fun onDraw(c: Canvas) = Icons.draw(c, icon, width / 2f, height / 2f, Theme.dp(context, 26f), Theme.ACCENT)
        }
        v.addView(ic, LinearLayout.LayoutParams(Theme.dpi(this, 30f), Theme.dpi(this, 30f)))
        v.addView(Theme.text(this, title, 15f, bold = true).apply { setPadding(0, Theme.dpi(context, 10f), 0, 0) })
        v.addView(Theme.text(this, sub, 12f, Theme.MUTED))
        v.layoutParams = LinearLayout.LayoutParams(Theme.dpi(this, 150f), -2).apply { marginEnd = Theme.dpi(this@MainActivity, 10f) }
        return v
    }

    // =========================================================== gallery grid

    private fun refresh() {
        val items = storage.list()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        grid.removeAllViews()
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cols = max(2, (widthDp / 190).toInt())
        grid.columnCount = cols
        val gap = Theme.dpi(this, 10f)
        val cellW = (resources.displayMetrics.widthPixels - Theme.dpi(this, 36f) - gap * (cols - 1)) / cols
        for ((i, item) in items.withIndex()) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                contentDescription = item.title
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = Theme.panel(this@MainActivity, 14f, Theme.SURFACE_HI)
                clipToOutline = true
            }
            tile.addView(img, LinearLayout.LayoutParams(cellW, (cellW * 1.2f).toInt()))
            tile.addView(Theme.text(this, item.title, 14f, bold = true).apply { setPadding(Theme.dpi(context, 4f), Theme.dpi(context, 6f), 0, 0); maxLines = 1 })
            tile.addView(Theme.text(this, DateUtils.getRelativeTimeSpanString(item.modified).toString(), 12f, Theme.MUTED).apply { setPadding(Theme.dpi(context, 4f), 0, 0, 0) })
            tile.setOnClickListener { openEditor(EditorActivity.MODE_OPEN, id = item.id) }
            tile.setOnLongClickListener { itemMenu(item); true }
            val lp = GridLayout.LayoutParams().apply {
                width = cellW
                setMargins(0, 0, if ((i + 1) % cols == 0) 0 else gap, gap * 2)
            }
            grid.addView(tile, lp)
            io.execute {
                val b = storage.loadThumb(item.id)
                runOnUiThread { if (b != null) img.setImageBitmap(b) }
            }
        }
    }

    private fun itemMenu(item: Storage.Item) {
        val actions = linkedMapOf<String, () -> Unit>(
            "Open" to { openEditor(EditorActivity.MODE_OPEN, id = item.id) },
            "Replay how it was drawn" to { startActivity(Intent(this, ReplayActivity::class.java).putExtra(ReplayActivity.EXTRA_ID, item.id)) },
            "Trace it (practice)" to {
                startActivity(Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_MODE, EditorActivity.MODE_TRACE).putExtra(EditorActivity.EXTRA_SOURCE, item.id))
            },
            "Share image" to { shareImage(item) },
            "Share .ora file" to {
                Exporter.share(this, Exporter.shareableFile(this, item.file, item.title.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "drawing" } + ".ora"),
                    "image/openraster", "Share drawing file")
            },
            "Duplicate" to { storage.duplicate(item.id); refresh() },
            "Rename" to { rename(item) },
            "Delete" to {
                dialogBuilder(this).setTitle("Delete \"${item.title}\"?").setMessage("This can't be undone.")
                    .setPositiveButton("Delete") { _, _ -> storage.delete(item.id); refresh() }
                    .setNegativeButton("Cancel", null).show()
            },
        )
        dialogBuilder(this).setTitle(item.title).setItems(actions.keys.toTypedArray()) { _, w -> actions.values.elementAt(w).invoke() }.show()
    }

    private fun shareImage(item: Storage.Item) {
        io.execute {
            val bmp = try {
                ZipFile(item.file).use { z -> z.getEntry("mergedimage.png")?.let { e -> z.getInputStream(e).use { BitmapFactory.decodeStream(it) } } }
            } catch (t: Throwable) { null }
            val uri = bmp?.let { Exporter.saveImage(this, it, item.title.ifBlank { "Penumbra" }, jpeg = false) }
            runOnUiThread {
                if (uri != null) Exporter.share(this, uri, "image/png", "Share drawing")
                else Toast.makeText(this, "Couldn't export this drawing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rename(item: Storage.Item) {
        val input = EditText(this).apply { setText(item.title); setSingleLine(); selectAll() }
        dialogBuilder(this).setTitle("Rename").setView(padded(input))
            .setPositiveButton("OK") { _, _ -> storage.setTitle(item.id, input.text.toString().trim().ifEmpty { item.title }); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    // =========================================================== new things

    private fun openEditor(mode: String, uri: Uri? = null, id: String? = null, extras: Intent.() -> Unit = {}) {
        val i = Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_MODE, mode)
        if (uri != null) {
            i.putExtra(EditorActivity.EXTRA_URI, uri.toString())
            i.data = uri
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (id != null) i.putExtra(EditorActivity.EXTRA_ID, id)
        i.extras()
        startActivity(i)
    }

    private fun newDrawing() {
        val dm = resources.displayMetrics
        val sw = max(dm.widthPixels, dm.heightPixels); val sh = minOf(dm.widthPixels, dm.heightPixels)
        val presets = mutableListOf(
            Triple("Screen (${sh}×$sw)", sh, sw),
            Triple("Square 2048 × 2048", 2048, 2048),
            Triple("Portrait 1536 × 2048", 1536, 2048),
            Triple("Landscape 2048 × 1536", 2048, 1536),
        )
        val maxMem = Runtime.getRuntime().maxMemory()
        if (maxMem > 400L * 1024 * 1024) presets += Triple("Large 3072 × 3072", 3072, 3072)
        if (maxMem > 400L * 1024 * 1024) presets += Triple("A4 print 2480 × 3508", 2480, 3508)
        val labels = presets.map { it.first } + "Custom size…"
        dialogBuilder(this).setTitle("New drawing").setItems(labels.toTypedArray()) { _, w ->
            if (w < presets.size) openEditor(EditorActivity.MODE_NEW) {
                putExtra(EditorActivity.EXTRA_W, presets[w].second); putExtra(EditorActivity.EXTRA_H, presets[w].third)
            } else customSize()
        }.show()
    }

    private fun customSize() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun field(v: Int) = EditText(this).apply { setText(v.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine() }
        val w = field(2048); val h = field(2048)
        box.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(Theme.text(this, "  ×  ", 16f))
        box.addView(h, LinearLayout.LayoutParams(0, -2, 1f))
        dialogBuilder(this).setTitle("Canvas size (pixels)").setView(padded(box))
            .setPositiveButton("Create") { _, _ ->
                // 4096 keeps every layer within what Android's renderer can draw in one texture.
                val ww = w.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: 2048
                val hh = h.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: 2048
                val bytes = ww.toLong() * hh * 4
                if (bytes * 6 > Runtime.getRuntime().maxMemory()) {
                    Toast.makeText(this, "That's too large for this device's memory", Toast.LENGTH_LONG).show()
                } else openEditor(EditorActivity.MODE_NEW) { putExtra(EditorActivity.EXTRA_W, ww); putExtra(EditorActivity.EXTRA_H, hh) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun coloringPicker() {
        val styles = ColoringPages.Style.values()
        val seeds = LongArray(styles.size) { rnd.nextLong() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previews = ArrayList<ImageView>()
        var dialog: android.app.AlertDialog? = null
        for ((i, st) in styles.withIndex()) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true
                setPadding(Theme.dpi(context, 4f), 0, Theme.dpi(context, 4f), 0)
                setOnClickListener {
                    dialog?.dismiss()
                    openEditor(EditorActivity.MODE_COLORING) {
                        putExtra(EditorActivity.EXTRA_STYLE, i); putExtra(EditorActivity.EXTRA_SEED, seeds[i])
                    }
                }
            }
            val iv = ImageView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()); scaleType = ImageView.ScaleType.FIT_CENTER }
            previews += iv
            col.addView(iv, LinearLayout.LayoutParams(Theme.dpi(this, 120f), Theme.dpi(this, 160f)))
            col.addView(Theme.text(this, st.label, 13f).apply { setPadding(0, Theme.dpi(context, 6f), 0, 0) })
            row.addView(col)
        }
        fun render() {
            for ((i, st) in styles.withIndex()) {
                val seed = seeds[i]
                io.execute {
                    val page = ColoringPages.generate(st, seed, 1536, 2048)
                    val bmp = DocFactory.renderPage(page, 1536, 2048, 0.16f)
                    runOnUiThread { previews[i].setImageBitmap(bmp) }
                }
            }
        }
        render()
        box.addView(HorizontalScrollView(this).apply { addView(row) })
        box.addView(Theme.text(this, "Tap a page to start coloring. Every page is generated fresh — shuffle for new ones.", 12f, Theme.MUTED).apply {
            setPadding(0, Theme.dpi(context, 10f), 0, 0)
        })
        dialog = dialogBuilder(this).setTitle("Coloring pages").setView(padded(box))
            .setNeutralButton("Shuffle", null)
            .setNegativeButton("Close", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                for (i in seeds.indices) seeds[i] = rnd.nextLong()
                render()
            }
        }
        dialog.show()
    }

    private fun lessons() {
        val kinds = Lessons.Kind.values()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.AlertDialog? = null
        for ((i, k) in kinds.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                setPadding(0, Theme.dpi(context, 10f), 0, Theme.dpi(context, 10f))
                setOnClickListener {
                    dialog?.dismiss()
                    openEditor(EditorActivity.MODE_LESSON) {
                        putExtra(EditorActivity.EXTRA_LESSON, i); putExtra(EditorActivity.EXTRA_SEED, rnd.nextLong())
                    }
                }
            }
            row.addView(Theme.text(this, k.title, 16f, bold = true))
            row.addView(Theme.text(this, k.blurb, 13f, Theme.MUTED))
            list.addView(row)
        }
        list.addView(Theme.text(this, "Tip: long-press any of your drawings and choose \"Trace it\" to practice from your own art — or share a .ora file so friends can draw along with you.", 12f, Theme.MUTED).apply {
            setPadding(0, Theme.dpi(context, 8f), 0, 0)
        })
        dialog = dialogBuilder(this).setTitle("Lessons").setView(padded(ScrollView(this).apply { addView(list) }))
            .setNegativeButton("Close", null).show()
    }

    private fun pickPhoto() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_PHOTO) } catch (t: Throwable) { Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show() }
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/openraster", "image/png", "image/jpeg", "image/webp", "application/zip", "application/octet-stream"))
        }
        try { startActivityForResult(i, REQ_IMPORT) } catch (t: Throwable) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PHOTO -> openEditor(EditorActivity.MODE_PHOTO, uri)
            REQ_IMPORT -> openEditor(EditorActivity.MODE_IMPORT, uri)
        }
    }
}
