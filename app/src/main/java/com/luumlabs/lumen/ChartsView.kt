package com.luumlabs.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The "TOP 10" badge, white on red as Apple TV's own Top 10 rows carry it. */
fun Context.top10Badge(size: Float = 11f): TextView = text(size, weight = 800).apply {
    text = "TOP 10"
    letterSpacing = 0.06f
    gravity = Gravity.CENTER
    setPadding(dp(7), dp(2), dp(7), dp(2))
    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(255, 69, 58), Color.rgb(208, 22, 40))).apply {
        cornerRadius = dp(5).toFloat()
    }
}

/** A poster with its rank beside it in large figures, the way Apple TV lays out a Top 10 row. */
class RankedPoster(ctx: Context, val posterW: Int, val posterH: Int, private val numeralSp: Float) : FrameLayout(ctx) {
    val numeral = ctx.text(numeralSp, weight = 800).apply {
        includeFontPadding = false
        isSingleLine = true
        letterSpacing = -0.06f
        gravity = Gravity.BOTTOM or Gravity.END
        setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), Color.argb(150, 0, 0, 0))
    }
    val numeralW = (posterW * 0.5f).toInt()
    private val shadowPad = dp(18)
    private val shadow = ImageView(ctx).apply {
        setImageBitmap(shadowFor(posterW, posterH, dp(10).toFloat(), shadowPad))
        alpha = 0f
    }
    val card = FrameLayout(ctx)
    val image = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.argb(50, 255, 255, 255))
    }
    private val rim = GlassRim(dp(10).toFloat(), resources.displayMetrics.density * 1.1f).apply { strength = 0.45f }
    private val focus = Tween()
    var lit = false
        private set

    init {
        clipChildren = false
        clipToPadding = false
        addView(numeral, LayoutParams(numeralW + dp(14), posterH, Gravity.START or Gravity.BOTTOM))
        addView(shadow, LayoutParams(posterW + shadowPad * 2, posterH + shadowPad * 2).apply { leftMargin = numeralW - shadowPad })
        shadow.translationY = -shadowPad.toFloat()
        card.addView(image, LayoutParams(-1, -1))
        card.foreground = rim
        card.clipRounded(dp(10).toFloat())
        addView(card, LayoutParams(posterW, posterH).apply { leftMargin = numeralW })
        numeral.alpha = 0.8f
    }

    companion object {
        private val shadows = HashMap<Long, Bitmap>()

        /** One blurred shadow per poster size, shared: drawing one takes a software blur. */
        fun shadowFor(w: Int, h: Int, radius: Float, pad: Int): Bitmap =
            shadows.getOrPut(w.toLong() shl 32 or h.toLong()) { LiquidGlass.shadow(w, h, radius, pad) }
    }

    /** Two figures ("10") are set smaller, so they fit the same space as one. */
    fun setRank(n: Int) {
        numeral.text = n.toString()
        numeral.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (n >= 10) numeralSp * 0.68f else numeralSp)
    }

    /** Lifts the poster on a spring (and lights its rank), or lets it back down. */
    fun setLit(on: Boolean, scale: Float = 1.14f) {
        if (on == lit) return
        lit = on
        val s0 = card.scaleX
        val s1 = if (on) scale else 1f
        val a0 = shadow.alpha
        val n0 = numeral.alpha
        val r0 = rim.strength
        translationZ = if (on) 1f else 0f
        card.pivotX = posterW / 2f; card.pivotY = posterH.toFloat()
        shadow.pivotX = (posterW / 2f + shadowPad); shadow.pivotY = (posterH + shadowPad).toFloat()
        focus.start(if (on) 520 else 260, if (on) Motion.spring else Motion.soft) { p ->
            val e = p.coerceAtMost(1f)
            card.scaleX = lerp(s0, s1, p); card.scaleY = card.scaleX
            shadow.scaleX = card.scaleX; shadow.scaleY = card.scaleX
            shadow.alpha = lerp(a0, if (on) 0.9f else 0f, e)
            numeral.alpha = lerp(n0, if (on) 1f else 0.8f, e)
            rim.strength = lerp(r0, if (on) 1f else 0.45f, e)
        }
    }
}

/**
 * The Top 10 on the home screen: the chart's name under a TOP 10 badge and its first five posters
 * with their ranks, on glass. Every few seconds it turns to the next chart; OK opens them all.
 */
class ChartsWidget(ctx: Context, source: GlassSource, w: Int, h: Int) : WidgetCard(ctx, source, w, h) {
    private val name = ctx.text(15f, weight = 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val where = ctx.text(12f, Color.argb(160, 255, 255, 255), weight = 600)
    private val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
    private val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val empty = ctx.text(14f, Color.argb(200, 255, 255, 255), weight = 500).apply { gravity = Gravity.CENTER; text = "Loading this week’s Top 10…" }
    private val posters = ArrayList<RankedPoster>()
    private val main = Handler(Looper.getMainLooper())
    var charts: List<Charts.Chart> = emptyList()
        private set
    var current = 0
        private set
    var country = ""
    private var rotating = false
    private val turn = object : Runnable {
        override fun run() {
            if (!rotating) return
            // Hold still while the user is on it, or while there is nothing to turn to.
            if (!isFocused && charts.size > 1) turnTo((current + 1) % charts.size)
            main.postDelayed(this, ROTATE_MS)
        }
    }
    private var pending: Runnable? = null

    init {
        contentDescription = "Top 10"
        val p = dp(16)
        header.addView(ctx.top10Badge())
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
        header.addView(where)
        body.addView(header, FrameLayout.LayoutParams(-1, -2).apply { leftMargin = p; rightMargin = p; topMargin = dp(12) })
        val posterH = h - dp(12) - dp(22) - dp(14) - dp(12)
        val posterW = posterH * 2 / 3
        val slots = ((w - p * 2 + dp(6)) / (posterW * 1.5f + dp(6))).toInt().coerceIn(3, 6)
        for (k in 0 until slots) {
            val rp = RankedPoster(ctx, posterW, posterH, posterH * 0.55f / resources.displayMetrics.scaledDensity)
            posters += rp
            strip.addView(rp, LinearLayout.LayoutParams(rp.numeralW + posterW, posterH).apply { if (k > 0) marginStart = dp(6) })
        }
        body.addView(strip, FrameLayout.LayoutParams(-2, posterH, Gravity.BOTTOM).apply { leftMargin = p - dp(4); bottomMargin = dp(12) })
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        show(null)
    }

    /** Shows [list], or the loading line (or [failed]'s message) while there is none. */
    fun show(list: List<Charts.Chart>?, failed: Boolean = false) {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        charts = list.orEmpty()
        val has = charts.isNotEmpty()
        empty.visibility = if (has) View.GONE else View.VISIBLE
        empty.text = if (failed) "Top 10 unavailable right now.\nCheck the TV’s internet connection." else "Loading this week’s Top 10…"
        header.visibility = if (has) View.VISIBLE else View.INVISIBLE
        strip.visibility = header.visibility
        if (has) render(current.coerceIn(0, charts.size - 1))
    }

    private fun render(i: Int) {
        current = i
        val chart = charts[i]
        name.text = chart.label
        where.text = country
        posters.forEachIndexed { k, rp ->
            val t = chart.items.getOrNull(k)
            rp.visibility = if (t == null) View.INVISIBLE else View.VISIBLE
            if (t == null) return@forEachIndexed
            rp.setRank(k + 1)
            rp.image.setImageBitmap(ChartImages.peek(t.poster))
            if (ChartImages.peek(t.poster) == null) ChartImages.load(context, t.poster, 332, rgb565 = true) { b ->
                if (charts.getOrNull(current) === chart) rp.image.setImageBitmap(b)
            }
        }
    }

    /** Turns to chart [i]: the old one slides away and the new one follows, a few posters apart. */
    private fun turnTo(i: Int) {
        if (charts.isEmpty()) return
        header.animate().alpha(0f).setDuration(160).start()
        posters.forEachIndexed { k, rp -> rp.animate().alpha(0f).translationX(-dp(14).toFloat()).setStartDelay(k * 25L).setDuration(180).start() }
        pending?.let { main.removeCallbacks(it) }
        pending = Runnable {
            pending = null
            // The charts may have been replaced, with fewer of them, since the turn began.
            if (charts.isNotEmpty()) render(i.coerceIn(0, charts.size - 1))
            header.animate().alpha(1f).setDuration(320).start()
            posters.forEachIndexed { k, rp ->
                rp.translationX = dp(18).toFloat()
                rp.animate().alpha(1f).translationX(0f).setStartDelay(k * 40L).setDuration(420).setInterpolator(Motion.emphasized).start()
            }
        }.also { main.postDelayed(it, 260) }
    }

    fun start() {
        if (rotating) return
        rotating = true
        main.postDelayed(turn, ROTATE_MS)
    }

    fun stop() {
        rotating = false
        main.removeCallbacks(turn)
        // A turn caught half way finishes at once, so the widget is never left blank.
        pending?.let { main.removeCallbacks(it); it.run() }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object { private const val ROTATE_MS = 9_000L }
}

/**
 * The charts, full screen, the way Apple TV presents a title: its backdrop filling the screen and
 * slowly drifting, its rank, name, details, scores, cast and where it streams over a dark scrim,
 * and the ten posters with their ranks along the bottom. Left and right move along the chart, up
 * and down switch charts (the glass tab bar follows), OK opens the app it streams on, Back closes.
 */
class ChartsView(
    private val home: HomeActivity,
    private val host: FrameLayout,
    private val charts: List<Charts.Chart>,
    start: Int,
    private val country: String,
    private val repo: AppRepo,
    private val onClose: () -> Unit,
) {
    private val ctx: Context = home
    private val sw = ctx.resources.displayMetrics.widthPixels
    private val sh = ctx.resources.displayMetrics.heightPixels
    private val side = (sw * 0.0583f).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val kenBurns = !Device.isLowEnd(ctx)

    val root = FrameLayout(ctx).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isClickable = true
        alpha = 0f
        clipChildren = false
        translationZ = ctx.dp(150).toFloat()
        outlineProvider = null
    }
    private val backs = arrayOf(backImage(), backImage())
    /** Holds the two backdrops, so a fade only reorders them and never the scrim or the text. */
    private val backLayer = FrameLayout(ctx)
    private var front = 0
    /**
     * The apps on this TV, looked up once in the background (a package-manager query): matching a
     * streaming service to one runs on every move. Null until it arrives.
     */
    @Volatile private var apps: List<AppEntry>? = null
    private val content = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val tabs = TabBar(ctx, charts.map { it.short })
    private val caption = ctx.text(14f, Color.argb(220, 255, 255, 255), weight = 700).apply { letterSpacing = 0.04f }
    private val title = ctx.text(34f, weight = 800).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.02f)
    }
    private val meta = ctx.text(16f, Color.argb(215, 255, 255, 255), weight = 500).apply { maxLines = 1 }
    private val scores = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val about = ctx.text(15f, Color.argb(205, 255, 255, 255)).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.2f)
    }
    private val people = ctx.text(14f, Color.argb(175, 255, 255, 255), weight = 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val provider = ctx.text(15f, weight = 700).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(16), ctx.dp(7), ctx.dp(16), ctx.dp(7))
        background = glassCapsule(ctx, ctx.dp(18).toFloat(), Color.argb(46, 255, 255, 255))
    }
    private val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val strip = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val posterH = ctx.dp(112)
    private val posterW = posterH * 2 / 3
    private val items = ArrayList<RankedPoster>()
    private var itemW = 0
    private var chart = start.coerceIn(0, charts.size - 1)
    private var index = 0
    private val stripTween = Tween()
    private var closing = false
    private val loadBackdrop = Runnable { showBackdrop() }

    init {
        backs.forEach { backLayer.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(backLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(ctx).apply { background = Scrim() }, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ctx.top10Badge(13f))
        top.addView(tabs, LinearLayout.LayoutParams(-2, ctx.dp(40)).apply { marginStart = ctx.dp(16) })
        top.addView(ctx.text(13f, Color.argb(150, 255, 255, 255), weight = 600).apply { text = if (country.isEmpty()) "" else "  ·  $country  ·  Data: JustWatch" })
        content.addView(top, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = side; topMargin = ctx.dp(30) })
        info.addView(caption)
        info.addView(title, LinearLayout.LayoutParams((sw * 0.55f).toInt(), -2).apply { topMargin = ctx.dp(8) })
        info.addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        info.addView(scores, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(12) })
        info.addView(about, LinearLayout.LayoutParams((sw * 0.46f).toInt(), -2).apply { topMargin = ctx.dp(12) })
        info.addView(people, LinearLayout.LayoutParams((sw * 0.5f).toInt(), -2).apply { topMargin = ctx.dp(8) })
        info.addView(provider, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(12) })
        listOf(caption, title, meta, about, people).forEach { it.setShadowLayer(ctx.dp(8).toFloat(), 0f, ctx.dp(1).toFloat(), Color.argb(140, 0, 0, 0)) }
        content.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = side; bottomMargin = posterH + ctx.dp(34) + ctx.dp(40)
        })
        content.addView(strip, FrameLayout.LayoutParams(-1, (posterH * 1.25f).toInt(), Gravity.BOTTOM).apply {
            leftMargin = side; bottomMargin = ctx.dp(30)
        })
        root.setOnKeyListener { _, code, e -> onKey(code, e) }
        Thread {
            val list = repo.allApps()
            main.post {
                apps = list
                if (!closing) charts[chart].items.getOrNull(index)?.let { showProvider(it) }
            }
        }.start()
        buildStrip(0)
        tabs.select(chart, animate = false)
    }

    private fun backImage() = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }

    fun show(): ChartsView {
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.animate().alpha(1f).setDuration(260).setInterpolator(Motion.ease).start()
        content.pivotX = sw / 2f; content.pivotY = sh.toFloat()
        content.scaleX = 1.04f; content.scaleY = 1.04f
        content.tweenScale(1f, 640, Motion.soft)
        root.requestFocus()
        select(0, 0, first = true)
        return this
    }

    val isOpen get() = root.parent != null && !closing

    fun close() {
        if (closing) return
        closing = true
        main.removeCallbacks(loadBackdrop)
        content.tweenScale(1.03f, 220, Motion.exit)
        root.animate().alpha(0f).setDuration(240).withEndAction {
            host.removeView(root)
            onClose()
        }.start()
    }

    private fun onKey(code: Int, e: KeyEvent): Boolean {
        if (closing) return true
        if (e.action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { openCurrent(); return true }
            return code != KeyEvent.KEYCODE_BACK
        }
        val count = charts[chart].items.size
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) select(index - 1, -1) else items.getOrNull(index)?.let { Motion.bump(it.card, -1, 0) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < count - 1) select(index + 1, 1) else items.getOrNull(index)?.let { Motion.bump(it.card, 1, 0) }
            KeyEvent.KEYCODE_DPAD_UP -> if (chart > 0) switchChart(chart - 1, -1) else Motion.bump(tabs, 0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (chart < charts.size - 1) switchChart(chart + 1, 1) else Motion.bump(tabs, 0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {}
            else -> return false
        }
        return true
    }

    /** Lays out chart [chart]'s posters; [dir] slides them in from below (1) or above (-1). */
    private fun buildStrip(dir: Int) {
        strip.removeAllViews()
        items.clear()
        stripTween.cancel()
        strip.translationX = 0f
        val list = charts[chart].items
        val numSp = posterH * 0.6f / ctx.resources.displayMetrics.scaledDensity
        list.forEachIndexed { k, t ->
            val rp = RankedPoster(ctx, posterW, posterH, numSp)
            itemW = rp.numeralW + posterW + ctx.dp(26)
            rp.setRank(k + 1)
            rp.image.setImageBitmap(ChartImages.peek(t.poster))
            if (ChartImages.peek(t.poster) == null) ChartImages.load(ctx, t.poster, 332, rgb565 = true) { b -> if (items.getOrNull(k) === rp) rp.image.setImageBitmap(b) }
            items += rp
            strip.addView(rp, FrameLayout.LayoutParams(rp.numeralW + posterW, posterH, Gravity.BOTTOM).apply { leftMargin = k * itemW })
            if (dir != 0) {
                rp.alpha = 0f
                rp.translationY = dir * ctx.dp(40).toFloat()
                rp.animate().alpha(1f).translationY(0f).setStartDelay(k * 30L).setDuration(460).setInterpolator(Motion.emphasized).start()
            }
        }
    }

    private fun switchChart(to: Int, dir: Int) {
        chart = to
        tabs.select(to, animate = true)
        buildStrip(dir)
        index = -1
        select(0, 0, first = true)
    }

    /** Moves to title [i] of the chart: the poster lifts, the row glides, the details and backdrop follow. */
    private fun select(i: Int, dir: Int, first: Boolean = false) {
        val list = charts[chart].items
        if (list.isEmpty()) return
        items.getOrNull(index)?.setLit(false)
        index = i
        items.getOrNull(i)?.setLit(true)
        // Keep the lifted poster one slot in from the left, and never scroll past the end.
        val visible = sw - side * 2
        val total = list.size * itemW
        var tx = -((i - 1).coerceAtLeast(0) * itemW).toFloat()
        if (total > visible) tx = tx.coerceAtLeast((visible - total).toFloat()) else tx = 0f
        val tx0 = strip.translationX
        if (tx != tx0) stripTween.start(520, Motion.soft) { p -> strip.translationX = lerp(tx0, tx, p) }
        val t = list[i]
        info.animate().cancel()
        if (first) { fill(t); info.alpha = 0f; info.translationX = 0f; info.translationY = ctx.dp(16).toFloat()
            info.animate().alpha(1f).translationY(0f).setDuration(520).setStartDelay(120).setInterpolator(Motion.emphasized).start()
        } else info.animate().alpha(0f).translationX(-dir * ctx.dp(14).toFloat()).setDuration(90).setStartDelay(0).withEndAction {
            fill(t)
            info.translationX = dir * ctx.dp(22).toFloat()
            info.animate().alpha(1f).translationX(0f).setDuration(380).setInterpolator(Motion.emphasized).start()
        }.start()
        main.removeCallbacks(loadBackdrop)
        main.postDelayed(loadBackdrop, if (first) 0 else 240)
    }

    private fun fill(t: Charts.Title) {
        val c = charts[chart]
        caption.text = "No. ${index + 1} ${c.rank}"
        title.text = t.name
        meta.text = listOf(
            if (t.show) "Series" else "Movie",
            t.year.takeIf { it > 0 }?.toString(),
            t.genres.joinToString(", ").ifEmpty { null },
            Charts.runtime(t.runtime).ifEmpty { null },
            t.age.ifEmpty { null },
        ).filterNotNull().joinToString("  ·  ")
        scores.removeAllViews()
        t.imdb?.let { scores.addView(chip("IMDb", Color.rgb(245, 197, 24), Color.BLACK, "%.1f".format(it))) }
        t.tomato?.let { scores.addView(chip("RT", Color.rgb(250, 50, 10), Color.WHITE, "$it%" + if (t.fresh) "  Certified Fresh" else "")) }
        t.tmdb?.let { scores.addView(chip("TMDB", Color.rgb(1, 180, 228), Color.rgb(13, 37, 63), "${(it * 10).toInt()}%")) }
        t.jw?.let { scores.addView(chip("JW", Color.rgb(251, 197, 0), Color.BLACK, "${(it * 100).toInt()}%")) }
        scores.visibility = if (scores.childCount == 0) View.GONE else View.VISIBLE
        about.text = t.description
        about.visibility = if (t.description.isEmpty()) View.GONE else View.VISIBLE
        val credits = listOfNotNull(
            t.cast.takeIf { it.isNotEmpty() }?.let { "Starring " + it.take(3).joinToString(", ") },
            t.director?.let { "Directed by $it" },
        ).joinToString("    ")
        people.text = credits
        people.visibility = if (credits.isEmpty()) View.GONE else View.VISIBLE
        showProvider(t)
    }

    /** Where [t] streams, and whether that app is here to open. */
    private fun showProvider(t: Charts.Title) {
        val app = t.provider?.let { p -> apps?.let { Charts.providerApp(it, p) } }
        provider.text = when {
            t.provider == null -> ""
            app != null -> "▶  Open in ${app.label}"
            t.providerKind == "RENT" || t.providerKind == "BUY" -> "Rent or buy on ${t.provider}"
            else -> "Streaming on ${t.provider}"
        }
        provider.visibility = if (t.provider == null) View.GONE else View.VISIBLE
        provider.tag = app
    }

    /** A score: the site's name on a badge in its colours, then the score. */
    private fun chip(site: String, badge: Int, ink: Int, value: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(5), ctx.dp(4), ctx.dp(12), ctx.dp(4))
        background = glassCapsule(ctx, ctx.dp(16).toFloat(), Color.argb(40, 255, 255, 255))
        addView(ctx.text(11f, ink, weight = 800).apply {
            text = site
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
            background = GradientDrawable().apply { setColor(badge); cornerRadius = ctx.dp(11).toFloat() }
        })
        addView(ctx.text(15f, weight = 700).apply { text = value }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(7) })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(10) }
    }

    /** Cross-fades to the current title's backdrop (its poster, blurred, when it has none). */
    private fun showBackdrop() {
        val c = chart
        val i = index
        val t = charts[c].items.getOrNull(i) ?: return
        val url = t.backdrop ?: t.poster
        ChartImages.load(ctx, url, if (t.backdrop != null) 1280 else 332, rgb565 = true) { b ->
            if (closing || c != chart || i != index || b == null) return@load
            val pic: Bitmap = if (t.backdrop != null) b else Wallpapers.blurred(b, darken = 0.15f)
            val next = backs[1 - front]
            val old = backs[front]
            front = 1 - front
            next.animate().cancel()
            next.setImageBitmap(pic)
            next.bringToFront()
            next.alpha = 0f
            next.scaleX = 1.08f; next.scaleY = 1.08f
            next.animate().alpha(1f).scaleX(1.02f).scaleY(1.02f).setDuration(900).setStartDelay(0).setInterpolator(Motion.emphasized).withEndAction {
                old.alpha = 0f
                // A slow drift while it's up, as Apple TV's motion backdrops do.
                if (kenBurns && backs[front] === next) next.animate().scaleX(1.1f).scaleY(1.1f).setDuration(14_000)
                    .setInterpolator(android.view.animation.LinearInterpolator()).start()
            }.start()
        }
    }

    private fun openCurrent() {
        val t = charts[chart].items.getOrNull(index) ?: return
        val app = provider.tag as? AppEntry
        items.getOrNull(index)?.card?.let { card ->
            card.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90).withEndAction { card.animate().scaleX(1.14f).scaleY(1.14f).setDuration(260).setInterpolator(Motion.spring).start() }.start()
        }
        if (app == null) {
            Toast.makeText(ctx, if (t.provider == null) "Not streaming in your country right now" else "${t.provider} isn't installed on this TV", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = repo.launchIntent(app) ?: return
        try { ctx.startActivity(intent) } catch (_: Exception) { Toast.makeText(ctx, "Can't open ${app.label}", Toast.LENGTH_SHORT).show() }
    }

    /** Darkens the backdrop where the text and posters sit: left, bottom, and a little at the top. */
    private class Scrim : Drawable() {
        private val p = Paint()
        private var shaders: List<Shader> = emptyList()
        override fun onBoundsChange(b: android.graphics.Rect) {
            val w = b.width().toFloat(); val h = b.height().toFloat()
            shaders = listOf(
                LinearGradient(0f, 0f, w * 0.7f, 0f, Color.argb(225, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP),
                LinearGradient(0f, h * 0.35f, 0f, h, Color.TRANSPARENT, Color.argb(240, 0, 0, 0), Shader.TileMode.CLAMP),
                LinearGradient(0f, 0f, 0f, h * 0.25f, Color.argb(130, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP),
            )
        }
        override fun draw(c: Canvas) {
            for (s in shaders) { p.shader = s; c.drawRect(bounds, p) }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** tvOS 26's tab bar: labels on a glass capsule, the selected one on a white pill that glides between them. */
    private class TabBar(ctx: Context, labels: List<String>) : LinearLayout(ctx) {
        private val views = labels.map { l -> ctx.text(16f, weight = 600).apply { text = l; gravity = Gravity.CENTER; setPadding(ctx.dp(20), 0, ctx.dp(20), 0) } }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF()
        private var pl = 0f
        private var pr = 0f
        private var sel = 0
        private val tween = Tween()

        init {
            orientation = HORIZONTAL
            setWillNotDraw(false)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            background = glassCapsule(ctx, ctx.dp(20).toFloat(), Color.argb(46, 255, 255, 255))
            views.forEach { addView(it, LayoutParams(-2, -1)) }
        }

        fun select(i: Int, animate: Boolean) {
            sel = i
            views.forEachIndexed { k, v -> v.setTextColor(if (k == i) Color.BLACK else Color.argb(220, 255, 255, 255)) }
            val v = views.getOrNull(i) ?: return
            if (v.width == 0) { post { select(i, false) }; return }
            val l1 = v.left.toFloat(); val r1 = v.right.toFloat()
            if (!animate || pr == 0f) { pl = l1; pr = r1; invalidate(); return }
            val l0 = pl; val r0 = pr
            tween.start(460, Motion.spring) { p -> pl = lerp(l0, l1, p); pr = lerp(r0, r1, p); invalidate() }
        }

        override fun dispatchDraw(c: Canvas) {
            if (pr > pl) {
                r.set(pl, paddingTop.toFloat(), pr, (height - paddingBottom).toFloat())
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, pill)
            }
            super.dispatchDraw(c)
        }
    }
}
