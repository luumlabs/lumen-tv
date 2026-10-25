package com.luumlabs.lumen

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** One app on the grid: a rounded 16:9 tile of layered glass, with the name under it when focused. */
class TileCell(ctx: Context, val tileW: Int, val tileH: Int) : FrameLayout(ctx) {
    val card = FrameLayout(ctx)
    /** The tile's background layer: the app's banner, or the gradient behind its icon. */
    val image = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        // A hair of overscan so the parallax drift never exposes an edge.
        scaleX = 1.06f; scaleY = 1.06f
    }
    /** The icon of an icon-only app, floating above [image]; the parallax moves it twice as far. */
    private val front = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = GONE
    }
    val label: TextView = ctx.text(15f, weight = 600).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        // Long names glide along once or twice while focused, as on tvOS, instead of being cut.
        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = 2
        alpha = 0f
        setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), Color.argb(150, 0, 0, 0))
    }
    private val labelY = tileH + dp(17).toFloat()
    private var jiggle: ObjectAnimator? = null
    /** Soft shadow under the card, only visible while it is lifted (focused). */
    private val shadow = ImageView(ctx).apply {
        setImageBitmap(shadowFor(ctx, tileW, tileH))
        alpha = 0f
    }
    private val shadowPad = dp(SHADOW_PAD)
    private val focusTween = Tween()
    private val pressTween = Tween()
    /** The light glancing off a focused tile; only drawn while focused. */
    private val sheen = View(ctx).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.argb(84, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.TRANSPARENT)
            gradientRadius = tileW * 0.62f
            setGradientCenter(0.5f, 0.5f)
        }
        alpha = 0f
        visibility = INVISIBLE
    }
    /** A narrow diagonal band of light that passes across the tile once it has settled. */
    private val glintBand = View(ctx).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, Color.argb(34, 255, 255, 255), Color.argb(74, 255, 255, 255), Color.argb(34, 255, 255, 255), Color.TRANSPARENT))
        rotation = 18f
        alpha = 0f
        visibility = INVISIBLE
    }
    /** The glass edge: a hairline catching the light, brighter when focused. */
    private val rim = GlassRim(dp(CORNER).toFloat(), resources.displayMetrics.density * 1.1f).apply { strength = RIM_IDLE }
    private var focusedNow = false
    private var tvosNow = true

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        isLongClickable = true
        addView(shadow, LayoutParams(tileW + shadowPad * 2, tileH + shadowPad * 2))
        shadow.translationX = -shadowPad.toFloat()
        shadow.translationY = -shadowPad.toFloat()
        card.addView(image, LayoutParams(tileW, tileH))
        val iconBox = tileH * 0.58f
        val frontSize = (iconBox * (TileArt.FRONT_ICON + TileArt.FRONT_PAD * 2) / TileArt.FRONT_ICON).toInt()
        card.addView(front, LayoutParams(frontSize, frontSize, Gravity.CENTER))
        card.addView(sheen, LayoutParams(tileW, tileH))
        card.addView(glintBand, LayoutParams((tileW * 0.42f).toInt(), (tileH * 1.7f).toInt(), Gravity.CENTER))
        card.foreground = rim
        card.clipRounded(dp(CORNER).toFloat())
        addView(card, LayoutParams(tileW, tileH))
        // The name hangs below the tile without taking layout space, so rows keep an exact pitch.
        addView(label, LayoutParams(tileW + dp(30), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        label.translationY = labelY
    }

    /** Shows [art]; null clears the tile while its artwork loads. */
    fun setArt(art: TileArt?) {
        image.setImageBitmap(art?.back)
        val f = art?.front
        front.setImageBitmap(f)
        front.visibility = if (f != null) VISIBLE else GONE
    }

    /**
     * tvOS focus: the tile lifts towards the viewer on a firm spring over a deepening shadow,
     * leaning in slightly from the direction of travel while its layers slide back into register
     * (the icon twice as far as its background), all of it settling without a wobble. With [tvos],
     * a soft highlight comes to rest on the glass and, once the tile has settled, a single glint of
     * light passes across it. Nothing moves after that: a resting screen costs no frames. Driven by
     * [Tween], so it plays even with animations off.
     */
    fun setFocusedLook(f: Boolean, fromDx: Int, fromDy: Int, showLabel: Boolean = true, tvos: Boolean = true) {
        focusedNow = f
        tvosNow = tvos
        cancelGlint()
        // A press still springing back would otherwise outlast this and leave the tile enlarged.
        pressTween.cancel()
        val s0 = card.scaleX
        val s1 = if (f) FOCUS_SCALE else 1f
        // Draw the lifted tile (and its shadow) above its neighbours.
        translationZ = if (f) 1f else 0f
        card.cameraDistance = tileW * 16f
        val tilt = f && tvos && (fromDx != 0 || fromDy != 0)
        val rx0 = if (tilt) -fromDy * TILT else card.rotationX
        val ry0 = if (tilt) fromDx * TILT else card.rotationY
        val sh0 = shadow.alpha
        val sh1 = if (f) 0.85f else 0f
        val shy0 = shadow.translationY
        val shy1 = -shadowPad + if (f) dp(18).toFloat() else 0f
        val l0 = label.alpha
        val l1 = if (f && showLabel) 1f else 0f
        val ly0 = if (l0 == 0f && l1 > 0f) labelY + dp(8) else label.translationY
        label.isSelected = f && showLabel
        val g0 = sheen.alpha
        val g1 = if (f && tvos) 0.8f else 0f
        val rim0 = rim.strength
        val rim1 = if (f) 1f else RIM_IDLE
        val lt0 = if (tilt) fromDx * 0.6f else rim.light
        if (f && tvos) {
            // The highlight enters from the side the focus came from and comes to rest near the top.
            sheen.translationX = -fromDx * tileW * 0.4f
            sheen.translationY = -tileH * 0.25f - fromDy * tileH * 0.25f
            image.translationX = -fromDx * dp(4f).toFloat()
            image.translationY = -fromDy * dp(3f).toFloat()
            front.translationX = -fromDx * dp(9f).toFloat()
            front.translationY = -fromDy * dp(7f).toFloat()
        }
        val gx0 = sheen.translationX
        val gy0 = sheen.translationY
        val ix0 = image.translationX
        val iy0 = image.translationY
        val fx0 = front.translationX
        val fy0 = front.translationY
        sheen.visibility = if (f && tvos || g0 > 0f) VISIBLE else INVISIBLE
        focusTween.start(if (f) 460 else 260, if (f) Motion.lift else Motion.soft, end = {
            if (!f) sheen.visibility = INVISIBLE
            if (f && tvos) scheduleGlint()
        }) { p ->
            // Size rides the spring; everything else glides in on one curve, so nothing wobbles.
            val e = Motion.emphasized.getInterpolation(p.coerceIn(0f, 1f))
            card.scaleX = lerp(s0, s1, p); card.scaleY = card.scaleX
            card.rotationX = lerp(rx0, 0f, e)
            card.rotationY = lerp(ry0, 0f, e)
            shadow.alpha = lerp(sh0, sh1, e)
            shadow.scaleX = card.scaleX * 1.02f; shadow.scaleY = shadow.scaleX
            shadow.translationY = lerp(shy0, shy1, e)
            label.alpha = lerp(l0, l1, e)
            label.translationY = lerp(ly0, labelY, e)
            sheen.alpha = lerp(g0, g1, e)
            rim.strength = lerp(rim0, rim1, e)
            rim.light = lerp(lt0, 0f, e)
            if (f) { sheen.translationX = lerp(gx0, 0f, e); sheen.translationY = lerp(gy0, -tileH * 0.25f, e) }
            image.translationX = lerp(ix0, 0f, e)
            image.translationY = lerp(iy0, 0f, e)
            front.translationX = lerp(fx0, 0f, e)
            front.translationY = lerp(fy0, 0f, e)
        }
    }

    /** OK held down presses the focused tile in a little; letting go springs it back up. */
    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        // Losing focus releases the press before the focus listener runs: by then isFocused is false.
        if (pressed == was || !isFocused) return
        val s0 = card.scaleX
        val s1 = if (pressed) FOCUS_SCALE * 0.95f else FOCUS_SCALE
        pressTween.start(if (pressed) 110 else 380, if (pressed) Motion.ease else Motion.lift) { p ->
            card.scaleX = lerp(s0, s1, p); card.scaleY = card.scaleX
            shadow.scaleX = card.scaleX * 1.02f; shadow.scaleY = shadow.scaleX
        }
    }

    // ---- the glint across a settled, focused tile ----------------------------------------------

    private val glintTween = Tween()
    private val glintLater = Runnable { glint() }

    private fun scheduleGlint() {
        removeCallbacks(glintLater)
        if (idleAllowed && focusedNow && tvosNow && jiggle == null) postDelayed(glintLater, 420)
    }

    private fun cancelGlint() {
        removeCallbacks(glintLater)
        glintTween.cancel()
        glintBand.visibility = INVISIBLE
    }

    /** One band of light sweeping across the glass, left to right, once. */
    private fun glint() {
        if (!focusedNow || !idleAllowed) return
        glintBand.visibility = VISIBLE
        val from = -tileW * 0.85f
        val to = tileW * 0.85f
        glintTween.start(1100, android.view.animation.LinearInterpolator(), end = { glintBand.visibility = INVISIBLE }) { p ->
            glintBand.translationX = lerp(from, to, Motion.emphasized.getInterpolation(p))
            // In and out softly, brightest as it crosses the middle.
            glintBand.alpha = kotlin.math.sin(p * Math.PI).toFloat()
        }
    }

    /** The screen is hidden, or left alone: no glint now. */
    fun settle() = cancelGlint()

    /** The focused tile catches the light again (a key was pressed, or the screen came back). */
    fun wake() { if (focusedNow && !focusTween.running && !glintTween.running) scheduleGlint() }

    override fun onDetachedFromWindow() {
        cancelGlint()
        super.onDetachedFromWindow()
    }

    companion object {
        const val SHADOW_PAD = 30
        const val FOCUS_SCALE = 1.14f
        const val CORNER = 12
        private const val RIM_IDLE = 0.42f
        /** How far a tile leans in from the direction of travel, in degrees. */
        private const val TILT = 4f
        private var shared: android.graphics.Bitmap? = null

        /** Whether a settled tile may catch a glint; off while the home screen is hidden or left alone. */
        @JvmStatic var idleAllowed = true

        /** One shadow bitmap serves every tile. */
        fun shadowFor(ctx: Context, w: Int, h: Int) = shared ?: LiquidGlass.shadow(w, h, ctx.dp(CORNER).toFloat(), ctx.dp(SHADOW_PAD))
            .also { shared = it }
    }

    fun setJiggle(on: Boolean) {
        jiggle?.cancel()
        jiggle = null
        card.rotation = 0f
        if (on) {
            cancelGlint()
            jiggle = ObjectAnimator.ofFloat(card, "rotation", -1.4f, 1.4f).apply {
                duration = 130
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }
}

class TileAdapter(
    private val repo: AppRepo,
    private val tileW: Int,
    private val tileH: Int,
    private val listener: Listener,
) : RecyclerView.Adapter<TileAdapter.VH>() {

    interface Listener {
        fun onTileFocused(pos: Int, cell: TileCell)
        fun onTileClicked(pos: Int, cell: TileCell)
        fun onTileLongPressed(pos: Int, cell: TileCell)
        /** Return true if the key was consumed (edge bumps, move mode). */
        fun onTileKey(pos: Int, cell: TileCell, keyCode: Int): Boolean
    }

    class VH(val cell: TileCell) : RecyclerView.ViewHolder(cell)

    var apps: MutableList<AppEntry> = mutableListOf()
        private set
    var movingPkg: String? = null
    private val loader = Executors.newFixedThreadPool(2)
    /** Stable ids by key: a hash of the package name can collide, and RecyclerView needs them unique. */
    private val ids = HashMap<String, Long>()

    /** Tiles in the dock (the first row) hide their name, which would sit on the glass edge. */
    var dockCount = 0

    /** Tilt-in and gliding highlight on focus ([Prefs.focusTvos]); otherwise only the zoom and shadow. */
    var tvosFocus = true

    /** Direction of the last D-pad move, used for the tilt-in. */
    var lastDx = 0
    var lastDy = 0

    /**
     * Updates only the tiles that changed, so a package event doesn't rebind
     * (and blank) the whole grid. [changed] is redrawn even if its entry is equal.
     */
    fun submit(list: List<AppEntry>, changed: String? = null) {
        val old = apps
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].pkg == list[n].pkg
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n] && list[n].pkg != changed
        })
        apps = list.toMutableList()
        diff.dispatchUpdatesTo(this)
    }

    fun move(from: Int, to: Int) {
        val a = apps.removeAt(from)
        apps.add(to, a)
        notifyItemMoved(from, to)
    }

    /** Stops the tile loaders; the adapter is done with. */
    fun release() = loader.shutdownNow()

    override fun getItemCount() = apps.size
    override fun getItemId(position: Int) = ids.getOrPut(apps[position].pkg) { ids.size + 1L }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val cell = TileCell(parent.context, tileW, tileH)
        cell.layoutParams = RecyclerView.LayoutParams(tileW, tileH)
        val vh = VH(cell)
        cell.setOnFocusChangeListener { _, f ->
            val p = vh.bindingAdapterPosition
            cell.setFocusedLook(f, lastDx, lastDy, showLabel = p >= dockCount, tvos = tvosFocus)
            if (f && p != RecyclerView.NO_POSITION) listener.onTileFocused(p, cell)
        }
        cell.setOnClickListener {
            vh.bindingAdapterPosition.takeIf { it >= 0 }?.let { listener.onTileClicked(it, cell) }
        }
        cell.setOnLongClickListener {
            vh.bindingAdapterPosition.takeIf { it >= 0 }?.let { listener.onTileLongPressed(it, cell) }
            true
        }
        cell.setOnKeyListener { _, code, e ->
            e.action == KeyEvent.ACTION_DOWN && vh.bindingAdapterPosition >= 0 &&
                listener.onTileKey(vh.bindingAdapterPosition, cell, code)
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val app = apps[position]
        val cell = h.cell
        cell.label.text = app.label
        cell.contentDescription = app.label
        val same = cell.tag == app.pkg
        cell.tag = app.pkg
        cell.setJiggle(app.pkg == movingPkg)
        val cached = repo.cachedTile(app.pkg)
        if (cached != null) {
            cell.setArt(cached)
        } else {
            // Rebinding the same app keeps its old picture until the new one is ready.
            if (!same) cell.setArt(null)
            if (loader.isShutdown) return
            loader.execute {
                val art = repo.tile(app)
                cell.post {
                    if (cell.tag == app.pkg) {
                        cell.setArt(art)
                        // The tile is what computes the app's colour, so tell the host again: the
                        // focus event that made it visible arrived before the colour existed.
                        val p = h.bindingAdapterPosition
                        if (cell.isFocused && p >= 0) listener.onTileFocused(p, cell)
                    }
                }
            }
        }
    }
}
