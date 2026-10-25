package com.luumlabs.lumen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun View.clipRounded(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) =
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
    }
    clipToOutline = true
}

object Motion {
    val ease = DecelerateInterpolator(2f)
    /** Focus and lift: a quick spring that overshoots by a hair (about 3 %) and settles, as tvOS does. */
    val spring = Spring(0.76f)
    /** A spring with no visible overshoot, for things that should glide rather than bounce. */
    val soft = Spring(0.95f)
    /** Scrolling and page changes: fast out of the gate, a long gentle landing. */
    val emphasized: android.view.animation.Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Leaving the screen: picks up speed and is gone. */
    val exit: android.animation.TimeInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /**
     * tvOS "bump" when focus cannot move any further in that direction: a shove against the edge
     * that springs back with one small rebound.
     */
    fun bump(v: View, dx: Int, dy: Int) {
        v.cameraDistance = v.width * 12f
        v.animate().cancel()
        val t = v.getTag(R.id.motion_bump) as? Tween ?: Tween().also { v.setTag(R.id.motion_bump, it) }
        val px = v.dp(6)
        t.start(380, android.view.animation.LinearInterpolator()) { p ->
            val f = (kotlin.math.sin(p * Math.PI * 2.2) * (1 - p) * (1 - p)).toFloat()
            v.rotationY = dx * 6f * f
            v.rotationX = -dy * 6f * f
            v.translationX = dx * px * f
            v.translationY = dy * px * f
        }
    }
}

/**
 * A damped spring from 0 to 1, as a time curve: [damping] 1 is critically damped (no overshoot),
 * lower values overshoot and settle. The stiffness is chosen so the motion has died down to a
 * thousandth of its travel by the end of the animation's duration, which therefore sets how fast
 * it feels: most of the travel happens in the first third, the rest is the settle.
 */
class Spring(private val damping: Float = 0.8f) : android.animation.TimeInterpolator {
    private val zeta = damping.coerceIn(0.3f, 1f).toDouble()
    // e^(-ζω) = 0.001 at t = 1.
    private val omega = 6.9 / zeta
    private val omegaD = omega * kotlin.math.sqrt((1 - zeta * zeta).coerceAtLeast(0.0))

    override fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val x = t.toDouble()
        val decay = kotlin.math.exp(-zeta * omega * x)
        val v = if (omegaD < 1e-3) 1 - decay * (1 + omega * x)
        else 1 - decay * (kotlin.math.cos(omegaD * x) + zeta * omega / omegaD * kotlin.math.sin(omegaD * x))
        return v.toFloat()
    }
}

/**
 * A frame-by-frame animation that still plays where the TV's animation scale is 0 ("animations
 * off", common on Chinese firmwares and in speed-up guides), which makes every ValueAnimator and
 * ViewPropertyAnimator jump straight to its end: focus would change with no zoom at all. Slowed
 * or sped-up scales still apply, down to half speed, so a TV set to 0.5 looks as it always did.
 */
class Tween : android.view.Choreographer.FrameCallback {
    private var startNanos = -1L
    private var durationNanos = 1L
    private var interp: android.animation.TimeInterpolator = Motion.ease
    private var update: ((Float) -> Unit)? = null
    private var end: (() -> Unit)? = null
    var running = false
        private set

    fun start(ms: Long, interpolator: android.animation.TimeInterpolator = Motion.ease, end: (() -> Unit)? = null, update: (Float) -> Unit) {
        cancel()
        this.interp = interpolator
        this.update = update
        this.end = end
        durationNanos = (ms * scale() * 1_000_000).toLong().coerceAtLeast(1)
        startNanos = -1L
        running = true
        update(interpolator.getInterpolation(0f))
        android.view.Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (startNanos < 0) startNanos = frameTimeNanos
        val t = ((frameTimeNanos - startNanos).toFloat() / durationNanos).coerceIn(0f, 1f)
        update?.invoke(interp.getInterpolation(t))
        if (t >= 1f) {
            running = false
            end?.invoke()
        } else android.view.Choreographer.getInstance().postFrameCallback(this)
    }

    fun cancel() {
        if (!running) return
        running = false
        android.view.Choreographer.getInstance().removeFrameCallback(this)
    }

    companion object {
        @Volatile var systemScale = 1f

        /** Read once per start of the home screen; the setting can only change in system settings. */
        fun readScale(ctx: Context) {
            systemScale = runCatching {
                android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
        }

        fun scale() = if (systemScale <= 0f) 0.5f else systemScale.coerceIn(0.5f, 1.5f)
    }
}

/**
 * Brings [views] in one after another, [stagger] ms apart: each fades up to [alphaOf] and slides in
 * from ([dx], [dy]). One [Tween] drives them all rather than each view's animate(), which a focus
 * zoom ([tweenScale]) cancels, and a button focused straight away would have stayed invisible.
 */
fun staggerIn(
    views: List<View>, delay: Long, stagger: Long, each: Long, dx: Float = 0f, dy: Float = 0f,
    alphaOf: (View) -> Float = { 1f }, onFrame: (() -> Unit)? = null,
): Tween {
    views.forEach { it.alpha = 0f; it.translationX = dx; it.translationY = dy }
    val total = delay + stagger * (views.size - 1).coerceAtLeast(0) + each
    return Tween().also { t ->
        t.start(total, android.view.animation.LinearInterpolator()) { p ->
            val ms = p * total
            views.forEachIndexed { k, v ->
                val e = Motion.emphasized.getInterpolation(((ms - delay - k * stagger) / each).coerceIn(0f, 1f))
                v.alpha = alphaOf(v) * e
                v.translationX = dx * (1 - e)
                v.translationY = dy * (1 - e)
            }
            onFrame?.invoke()
        }
    }
}

/** Linear interpolation, for animating between a start and an end value. */
fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Focus zoom: scales the view to [s] (and lifts it to [z]) with a [Tween] kept on the view. */
fun View.tweenScale(s: Float, ms: Long, interp: android.animation.TimeInterpolator = Motion.ease, z: Float? = null, onFrame: (() -> Unit)? = null) {
    animate().cancel()
    val t = getTag(R.id.motion_focus) as? Tween ?: Tween().also { setTag(R.id.motion_focus, it) }
    val s0 = scaleX
    val z0 = translationZ
    t.start(ms, interp) { f ->
        scaleX = lerp(s0, s, f)
        scaleY = scaleX
        if (z != null) translationZ = lerp(z0, z, f)
        onFrame?.invoke()
    }
}

const val FONT_BOLD = "sans-serif-medium"

/**
 * Inter (variable, OFL) stands in for Apple's SF: Roboto's digits are what
 * make a screen read as "Android" at a glance. Chinese falls back to the system font.
 */
object Fonts {
    private val cache = HashMap<Int, Typeface>()

    fun inter(ctx: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        try {
            Typeface.Builder(ctx.assets, "inter.ttf")
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .setFallback("sans-serif")
                .build() ?: Typeface.DEFAULT
        } catch (e: Exception) {
            Typeface.create(if (weight >= 500) FONT_BOLD else "sans-serif", Typeface.NORMAL)
        }
    }
}

fun Context.text(sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false, weight: Int = if (bold) 600 else 400): TextView =
    TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = Fonts.inter(this@text, weight)
        includeFontPadding = false
    }

/** The capsule of an idle button: a sliver of clear glass with a hairline rim lit from above. */
fun glassCapsule(ctx: Context, radius: Float, fill: Int = Color.argb(40, 255, 255, 255)): android.graphics.drawable.Drawable {
    val body = GradientDrawable().apply {
        cornerRadius = radius
        colors = intArrayOf(Color.argb((Color.alpha(fill) * 1.35f).toInt().coerceAtMost(255), 255, 255, 255), fill)
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(ctx.dp(1), Color.argb(52, 255, 255, 255))
    }
    return body
}

/**
 * A pill button in tvOS style: clear glass when idle, solid white with
 * dark text when focused, lifted on a soft shadow.
 */
class PillButton(ctx: Context, label: String, iconRes: Int? = null, val onClick: () -> Unit) : LinearLayout(ctx) {
    private val tv = ctx.text(17f, weight = 600).apply { text = label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val icon: ImageView? = iconRes?.let { ImageView(ctx).apply { setImageResource(it) } }
    private val idle = glassCapsule(ctx, dp(26).toFloat())
    private val focus = rounded(Color.WHITE, dp(26).toFloat())

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = idle
        setPadding(dp(28), 0, dp(28), 0)
        icon?.let { addView(it, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }) }
        addView(tv)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, f ->
            background = if (f) focus else idle
            tv.setTextColor(if (f) Color.BLACK else Color.WHITE)
            icon?.setColorFilter(if (f) Color.BLACK else Color.WHITE)
            tweenScale(if (f) 1.07f else 1f, if (f) 420 else 260, if (f) Motion.spring else Motion.soft, z = if (f) dp(12).toFloat() else 0f)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (isFocused) tweenScale(if (pressed) 1.0f else 1.07f, if (pressed) 110 else 360, if (pressed) Motion.ease else Motion.spring)
    }
}

/** A ScrollView that wraps its content up to [maxHeight] and scrolls beyond that. */
class MaxHeightScrollView(ctx: Context, private val maxHeight: Int) : android.widget.ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
}

/**
 * Full-screen alert in the style of tvOS 26: the screen behind frosted and dimmed, a centred
 * title and message and a column of glass capsules that springs in. Long lists scroll. Back
 * dismisses it.
 */
class Sheet(private val host: FrameLayout) {
    private val ctx = host.context
    val root = FrameLayout(ctx).apply {
        // Focused controls can scale past the bounds of their parent.
        clipChildren = false
        isClickable = true
        alpha = 0f
        // Above focused tiles, which lift themselves with translationZ; no shadow (see ControlCenter.scrim).
        translationZ = ctx.dp(200).toFloat()
        outlineProvider = null
    }
    private val frost = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // Pills grow on focus, beyond this wrap-content column.
        clipChildren = false
        clipToPadding = false
    }
    private val title = ctx.text(30f, weight = 700).apply { gravity = Gravity.CENTER }
    private val message = ctx.text(17f, Color.argb(185, 255, 255, 255)).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.28f)
    }
    private val buttons = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        // Room for the focused pill's growth and shadow inside the scroller.
        setPadding(ctx.dp(24), ctx.dp(4), ctx.dp(24), ctx.dp(18))
    }
    private val scroller = MaxHeightScrollView(ctx, (ctx.resources.displayMetrics.heightPixels * 0.56f).toInt()).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        isSmoothScrollingEnabled = true
        addView(buttons)
    }
    private var onDismiss: (() -> Unit)? = null
    private var dismissing = false
    private var entrance: Tween? = null

    init {
        root.addView(frost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Dims the frosted picture so white text reads on any wallpaper.
        root.addView(View(ctx).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(title, LinearLayout.LayoutParams(ctx.dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(message, LinearLayout.LayoutParams(ctx.dp(560), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(12); bottomMargin = ctx.dp(22)
        })
        col.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { dismiss(); true } else false
        }
    }

    fun title(t: String) = apply { title.text = t }
    fun message(m: String?) = apply {
        message.text = m ?: ""
        message.visibility = if (m.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun button(label: String, icon: Int? = null, keepOpen: Boolean = false, action: () -> Unit) = apply {
        val b = PillButton(ctx, label, icon) {
            // A second press while the sheet is already leaving must not run another action.
            if (!dismissing) {
                if (!keepOpen) dismiss()
                action()
            }
        }
        buttons.addView(b, LinearLayout.LayoutParams(ctx.dp(440), ctx.dp(52)).apply { topMargin = ctx.dp(12) })
    }

    fun onDismiss(f: () -> Unit) = apply { onDismiss = f }

    fun show(): Sheet {
        frost.setImageBitmap(LiquidGlass.frostedSnapshot(host))
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.animate().alpha(1f).setDuration(200).setInterpolator(Motion.ease).start()
        col.scaleX = 0.92f; col.scaleY = 0.92f; col.alpha = 0f
        col.tweenScale(1f, 520, Motion.spring) { col.alpha = ((col.scaleX - 0.92f) / 0.08f).coerceIn(0f, 1f) }
        // The capsules follow one another in, a few frames apart.
        entrance = staggerIn((0 until buttons.childCount).map { buttons.getChildAt(it) }, 40, 28, 360, dy = ctx.dp(14).toFloat())
        (0 until buttons.childCount).map { buttons.getChildAt(it) }.firstOrNull { it is PillButton }?.requestFocus()
        return this
    }

    val isShowing get() = root.parent != null

    fun dismiss() {
        if (!isShowing || dismissing) return
        dismissing = true
        // The entrance may still be running; it would fight the exit for the same properties.
        entrance?.cancel()
        (col.getTag(R.id.motion_focus) as? Tween)?.cancel()
        col.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).setDuration(150).setInterpolator(Motion.exit).start()
        root.animate().alpha(0f).setDuration(180).setStartDelay(30).withEndAction {
            host.removeView(root)
            onDismiss?.invoke()
        }.start()
    }

    /** Back is delivered to the focused child, so buttons forward it here. */
    fun handlesBack(): Boolean { dismiss(); return true }
}
