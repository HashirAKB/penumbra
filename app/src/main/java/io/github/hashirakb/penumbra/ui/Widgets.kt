package io.github.hashirakb.penumbra.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.hashirakb.penumbra.core.ColorMath
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Colors and helpers shared by the whole UI. */
object Theme {
    const val BG = 0xFF16161A.toInt()
    const val SURFACE = 0xF2222228.toInt()
    const val SURFACE_HI = 0xFF2E2E36.toInt()
    const val ON_SURFACE = 0xFFEDEDF2.toInt()
    const val MUTED = 0xFF9A9AA8.toInt()
    const val ACCENT = 0xFFFFB454.toInt()
    const val ACCENT_DIM = 0x33FFB454
    const val DANGER = 0xFFFF6B6B.toInt()

    fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    fun dpi(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun panel(ctx: Context, radius: Float = 18f, color: Int = SURFACE): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, radius)
            setColor(color)
            setStroke(dpi(ctx, 1f), 0x22FFFFFF)
        }

    fun pill(ctx: Context, color: Int): GradientDrawable =
        GradientDrawable().apply { cornerRadius = dp(ctx, 100f); setColor(color) }

    fun text(ctx: Context, s: String, size: Float = 14f, color: Int = ON_SURFACE, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private var checker: Bitmap? = null

    /** Transparency checkerboard shader. */
    fun checkerShader(ctx: Context): BitmapShader {
        val bmp = checker ?: run {
            val n = dpi(ctx, 6f)
            val b = Bitmap.createBitmap(n * 2, n * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(0xFFFFFFFF.toInt())
            val p = Paint().apply { color = 0xFFD5D5DC.toInt() }
            c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
            c.drawRect(n.toFloat(), n.toFloat(), 2f * n, 2f * n, p)
            checker = b
            b
        }
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}

/** Round icon button with optional selected (accent) state. */
@SuppressLint("ViewConstructor")
class IconButton(ctx: Context, var icon: Icon, desc: String) : View(ctx) {
    var selectedState = false
        set(v) { field = v; invalidate() }
    var enabledLook = true
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnLongClickListener { android.widget.Toast.makeText(ctx, desc, android.widget.Toast.LENGTH_SHORT).show(); true }
        tooltipText = desc
        val s = Theme.dpi(ctx, 44f)
        layoutParams = ViewGroup.MarginLayoutParams(s, s)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, Theme.pill(ctx, Color.WHITE))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (selectedState) {
            bg.color = Theme.ACCENT_DIM
            c.drawCircle(cx, cy, min(width, height) / 2f - Theme.dp(context, 2f), bg)
        }
        val color = when {
            !enabledLook -> 0x55FFFFFF
            selectedState -> Theme.ACCENT
            else -> Theme.ON_SURFACE
        }
        Icons.draw(c, icon, cx, cy, Theme.dp(context, 24f), color)
    }
}

/** Circle showing the current color; tap opens the color panel. */
class ColorSwatch(ctx: Context) : View(ctx) {
    var color = Color.BLACK
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val s = Theme.dpi(ctx, 44f)
        layoutParams = ViewGroup.MarginLayoutParams(s, s)
        contentDescription = "Color"
        isClickable = true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - Theme.dp(context, 6f)
        p.style = Paint.Style.FILL; p.color = color
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = Theme.dp(context, 2.5f); p.color = Color.WHITE
        c.drawCircle(cx, cy, r + Theme.dp(context, 1.5f), p)
    }
}

/**
 * Vertical slider for brush size / opacity, Procreate-style: drag anywhere on it.
 * [logarithmic] maps the track exponentially (good for brush size).
 */
@SuppressLint("ViewConstructor")
class VerticalSlider(
    ctx: Context, private val min: Float, private val max: Float,
    private val logarithmic: Boolean, desc: String,
) : View(ctx) {
    var value = min
        set(v) { field = v.coerceIn(min, max); invalidate() }
    var onChange: ((Float, Boolean) -> Unit)? = null
    var previewColor: Int? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        contentDescription = desc
        layoutParams = ViewGroup.MarginLayoutParams(Theme.dpi(ctx, 36f), Theme.dpi(ctx, 150f))
    }

    private fun toT(v: Float): Float = if (logarithmic) (ln(v / min) / ln(max / min)) else (v - min) / (max - min)
    private fun fromT(t: Float): Float = if (logarithmic) (min * Math.pow((max / min).toDouble(), t.toDouble())).toFloat()
        else min + (max - min) * t

    override fun onDraw(c: Canvas) {
        val pad = Theme.dp(context, 10f)
        val w = Theme.dp(context, 8f)
        val cx = width / 2f
        rect.set(cx - w / 2, pad, cx + w / 2, height - pad)
        track.color = 0x33FFFFFF
        c.drawRoundRect(rect, w, w, track)
        val t = toT(value).coerceIn(0f, 1f)
        val y = height - pad - t * (height - 2 * pad)
        rect.set(cx - w / 2, y, cx + w / 2, height - pad)
        track.color = 0x99FFFFFF.toInt()
        c.drawRoundRect(rect, w, w, track)
        knob.color = previewColor ?: Theme.ON_SURFACE
        knob.style = Paint.Style.FILL
        c.drawCircle(cx, y, Theme.dp(context, 9f), knob)
        knob.style = Paint.Style.STROKE; knob.strokeWidth = Theme.dp(context, 2f); knob.color = Theme.BG
        c.drawCircle(cx, y, Theme.dp(context, 9f), knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = Theme.dp(context, 10f)
        val t = (1f - (e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); value = fromT(t); onChange?.invoke(value, false) }
            MotionEvent.ACTION_MOVE -> { value = fromT(t); onChange?.invoke(value, false) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onChange?.invoke(value, true)
        }
        return true
    }
}

/** Hue ring with a saturation/value square inside. */
class ColorWheel(ctx: Context) : View(ctx) {
    private val hsv = floatArrayOf(0f, 0f, 0f)
    var onChange: ((Int, Boolean) -> Unit)? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sq = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var dragging = 0 // 1 ring, 2 square
    private val rect = RectF()

    var color: Int
        get() = ColorMath.hsvToRgb(hsv[0], hsv[1], hsv[2])
        set(v) { ColorMath.rgbToHsv(v, hsv); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = min(w, Theme.dpi(context, 260f))
        setMeasuredDimension(w, s)
    }

    private val ringW get() = Theme.dp(context, 22f)
    private val outer get() = min(width, height) / 2f - Theme.dp(context, 4f)
    private val half get() = (outer - ringW - Theme.dp(context, 10f)) / 1.4142f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val rr = outer - ringW / 2f
        ring.strokeWidth = ringW
        ring.shader = SweepGradient(cx, cy, IntArray(7) { ColorMath.hsvToRgb(it * 60f, 1f, 1f) }, null)
        c.drawCircle(cx, cy, rr, ring)
        val h = half
        rect.set(cx - h, cy - h, cx + h, cy + h)
        val sat = LinearGradient(rect.left, 0f, rect.right, 0f, Color.WHITE, ColorMath.hsvToRgb(hsv[0], 1f, 1f), Shader.TileMode.CLAMP)
        val value = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        sq.shader = ComposeShader(value, sat, PorterDuff.Mode.MULTIPLY)
        c.drawRoundRect(rect, Theme.dp(context, 6f), Theme.dp(context, 6f), sq)
        // markers
        val a = Math.toRadians(hsv[0].toDouble())
        mark.strokeWidth = Theme.dp(context, 3f); mark.color = Color.WHITE
        c.drawCircle(cx + (cos(a) * rr).toFloat(), cy + (sin(a) * rr).toFloat(), ringW / 2f, mark)
        val mx = rect.left + hsv[1] * rect.width()
        val my = rect.top + (1f - hsv[2]) * rect.height()
        mark.color = if (hsv[2] > 0.6f && hsv[1] < 0.4f) Color.BLACK else Color.WHITE
        c.drawCircle(mx, my, Theme.dp(context, 8f), mark)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f; val cy = height / 2f
        val dx = e.x - cx; val dy = e.y - cy
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            parent.requestDisallowInterceptTouchEvent(true)
            dragging = if (hypot(dx, dy) > outer - ringW - Theme.dp(context, 6f)) 1 else 2
        }
        if (dragging == 1) {
            var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (deg < 0) deg += 360f
            hsv[0] = deg
        } else if (dragging == 2) {
            val h = half
            hsv[1] = ((e.x - (cx - h)) / (2 * h)).coerceIn(0f, 1f)
            hsv[2] = (1f - (e.y - (cy - h)) / (2 * h)).coerceIn(0f, 1f)
        }
        val done = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        onChange?.invoke(color, done)
        if (done) dragging = 0
        invalidate()
        return true
    }
}

/** A row of tappable color dots. */
class SwatchRow(ctx: Context, colors: IntArray, size: Float = 30f, onPick: (Int) -> Unit) : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL
        for (c in colors) {
            val v = object : View(ctx) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(cv: Canvas) {
                    p.style = Paint.Style.FILL; p.color = c
                    cv.drawCircle(width / 2f, height / 2f, min(width, height) / 2f - Theme.dp(ctx, 2f), p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = Theme.dp(ctx, 1f); p.color = 0x33FFFFFF
                    cv.drawCircle(width / 2f, height / 2f, min(width, height) / 2f - Theme.dp(ctx, 2f), p)
                }
            }
            val s = Theme.dpi(ctx, size)
            v.layoutParams = LayoutParams(s, s).apply { marginEnd = Theme.dpi(ctx, 4f) }
            v.contentDescription = ColorMath.toHex(c)
            v.setOnClickListener { onPick(c) }
            addView(v)
        }
    }
}

/** Labeled horizontal slider used in settings panels. */
class LabeledSlider(
    ctx: Context, label: String, private val min: Float, private val max: Float, initial: Float,
    private val format: (Float) -> String, onChange: (Float, Boolean) -> Unit,
) : LinearLayout(ctx) {
    private val valueText = Theme.text(ctx, format(initial), 13f, Theme.MUTED)

    init {
        orientation = VERTICAL
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Theme.text(ctx, label, 14f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueText)
        addView(row)
        val bar = SeekBar(ctx)
        bar.max = 1000
        bar.progress = (((initial - min) / (max - min)) * 1000).toInt()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + (max - min) * p / 1000f
                valueText.text = format(v)
                onChange(v, false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { onChange(min + (max - min) * sb.progress / 1000f, true) }
        })
        addView(bar)
    }
}
