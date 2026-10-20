package io.github.hashirakb.penumbra.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import io.github.hashirakb.penumbra.core.JEvent
import io.github.hashirakb.penumbra.engine.ReplayPlayer
import io.github.hashirakb.penumbra.io.OraIO
import io.github.hashirakb.penumbra.io.Storage
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Watch a drawing being made, stroke by stroke, then trace it yourself. */
class ReplayActivity : Activity() {
    companion object { const val EXTRA_ID = "id" }

    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private var view: ReplayView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        setContentView(root)
        window.statusBarColor = 0; window.navigationBarColor = 0
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        val progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val storage = Storage(this)
        loader.execute {
            val r = runCatching { storage.fileFor(id).inputStream().buffered().use { OraIO.read(it) }.doc }
            runOnUiThread {
                root.removeView(progress)
                r.onSuccess { build(ReplayPlayer(it), id, storage.titleOf(id)) }
                    .onFailure { dialogBuilder(this).setMessage("Couldn't open this drawing").setPositiveButton("OK") { _, _ -> finish() }.show() }
            }
        }
    }

    private fun build(player: ReplayPlayer, id: String, title: String) {
        val v = ReplayView(this, player)
        view = v
        root.addView(v, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Theme.panel(this@ReplayActivity, 24f) }
        left.addView(IconButton(this, Icon.BACK, "Back").apply { setOnClickListener { finish() } })
        left.addView(Theme.text(this, title, 15f, bold = true).apply { setPadding(0, 0, Theme.dpi(context, 14f), 0) })
        top.addView(left)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = Theme.panel(this@ReplayActivity, 24f) }
        right.addView(IconButton(this, Icon.LEARN, "Trace this drawing").apply {
            setOnClickListener {
                startActivity(Intent(this@ReplayActivity, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_MODE, EditorActivity.MODE_TRACE)
                    .putExtra(EditorActivity.EXTRA_SOURCE, id))
            }
        })
        top.addView(right)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Theme.panel(this@ReplayActivity, 28f)
            setPadding(Theme.dpi(context, 8f), 0, Theme.dpi(context, 12f), 0)
        }
        val play = IconButton(this, Icon.PAUSE, "Play / pause")
        play.setOnClickListener { v.playing = !v.playing; if (v.playing && player.done) v.restart() }
        bar.addView(play)
        val speed = TextView(this).apply {
            setTextColor(Theme.ON_SURFACE); textSize = 14f; text = "1×"
            setPadding(Theme.dpi(context, 10f), Theme.dpi(context, 6f), Theme.dpi(context, 10f), Theme.dpi(context, 6f))
            background = Theme.pill(context, Theme.SURFACE_HI)
            contentDescription = "Speed"
        }
        val speeds = intArrayOf(1, 2, 4, 8, 16)
        speed.setOnClickListener {
            val i = (speeds.indexOf(v.speed) + 1) % speeds.size
            v.speed = speeds[i]; speed.text = "${speeds[i]}×"
        }
        bar.addView(speed)
        val seek = SeekBar(this).apply { max = max(1, player.steps.size) }
        bar.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        val count = Theme.text(this, "", 13f, Theme.MUTED)
        bar.addView(count)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) v.seekToStep(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { v.playing = false }
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        v.onProgress = { done, total, playing ->
            if (!seek.isPressed) seek.progress = done
            count.text = "$done / $total"
            play.icon = if (playing) Icon.PAUSE else Icon.PLAY
            play.invalidate()
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, Theme.dpi(this, 56f), Gravity.BOTTOM).apply {
            leftMargin = Theme.dpi(this@ReplayActivity, 12f); rightMargin = Theme.dpi(this@ReplayActivity, 12f)
        })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val t: Int; val b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); t = s.top; b = s.bottom
            } else {
                @Suppress("DEPRECATION") val tt = insets.systemWindowInsetTop; t = tt
                @Suppress("DEPRECATION") val bb = insets.systemWindowInsetBottom; b = bb
            }
            top.setPadding(Theme.dpi(this, 6f), t + Theme.dpi(this, 4f), Theme.dpi(this, 6f), 0)
            (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + Theme.dpi(this, 16f)
            v.insetTop = t; v.insetBottom = b
            root.requestLayout()
            insets
        }
        root.requestApplyInsets()
        v.playing = true
    }

    override fun onPause() { super.onPause(); view?.playing = false }
    override fun onDestroy() { super.onDestroy(); view?.release(); loader.shutdown() }
}

/** Animates a [ReplayPlayer]: strokes are drawn point by point. */
class ReplayView(ctx: Context, private val player: ReplayPlayer) : View(ctx) {
    var speed = 1
    var insetTop = 0
    var insetBottom = 0
    var onProgress: ((Int, Int, Boolean) -> Unit)? = null
    var playing = false
        set(v) { field = v; if (v) postOnAnimation(tick); report() }

    private var partialPoints = 0
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; setShadowLayer(20f, 0f, 6f, 0x88000000.toInt()) }
    private val rect = RectF()

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            advance()
            invalidate()
            if (player.done) { playing = false } else postOnAnimation(this)
        }
    }

    private fun advance() {
        var budget = 4f * speed
        var guard = 0
        while (budget > 0 && !player.done && guard++ < 400) {
            val e = player.events[player.index]
            if (e is JEvent.Stroke) {
                val remaining = e.points.size - partialPoints
                if (budget >= remaining) {
                    budget -= remaining; partialPoints = 0; player.step()
                } else {
                    partialPoints += budget.toInt().coerceAtLeast(1); budget = 0f
                }
            } else {
                player.step()
                if (e.isDrawingStep) budget -= 12f
            }
        }
        report()
    }

    private fun report() = onProgress?.invoke(player.stepsDone(), player.steps.size, playing)

    fun restart() { player.reset(); partialPoints = 0; invalidate(); report() }

    fun seekToStep(k: Int) {
        partialPoints = 0
        val target = if (k >= player.steps.size) player.events.size else player.steps[k]
        player.seek(target)
        invalidate()
        report()
    }

    override fun onDraw(c: Canvas) {
        val pad = Theme.dp(context, 16f)
        val top = insetTop + Theme.dp(context, 64f)
        val bottom = insetBottom + Theme.dp(context, 88f)
        val availW = width - 2 * pad; val availH = height - top - bottom
        val s = min(availW / player.width, availH / player.height)
        val dw = player.width * s; val dh = player.height * s
        val left = (width - dw) / 2f; val t = top + (availH - dh) / 2f
        rect.set(left, t, left + dw, t + dh)
        c.drawRect(rect, shadow)
        c.save()
        c.clipRect(rect)
        c.translate(left, t)
        c.scale(s, s)
        player.draw(c, player.index, partialPoints)
        c.restore()
    }

    fun release() { playing = false; player.recycle() }
}
