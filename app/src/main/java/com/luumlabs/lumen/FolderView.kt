package com.luumlabs.lumen

import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * An open folder, the way tvOS shows one: the home screen frosted behind it, the folder's name
 * large at the top left and its apps as a grid of the same glass tiles, zooming up out of the
 * screen. OK opens an app, holding OK offers to move it back out, Back closes the folder.
 */
class FolderView(
    private val home: HomeActivity,
    private val host: FrameLayout,
    repo: AppRepo,
    val folder: AppFolder,
    private val apps: List<AppEntry>,
    private val geometry: Geometry,
    tvosFocus: Boolean,
    private val callbacks: Callbacks,
) : TileAdapter.Listener {
    /** Tile and grid measurements, shared with the home grid so a folder's tiles match it. */
    class Geometry(val tileW: Int, val tileH: Int, val cols: Int, val side: Int, val gap: Int, val rowPitch: Int)

    interface Callbacks {
        fun openFromFolder(app: AppEntry, cell: TileCell)
        fun folderAppLongPressed(view: FolderView, app: AppEntry)
        fun folderClosed(view: FolderView)
    }

    private val ctx = home
    private val sh = ctx.resources.displayMetrics.heightPixels
    val root = FrameLayout(ctx).apply {
        clipChildren = false
        isClickable = true
        alpha = 0f
        // Above the grid's lifted tiles and the Control Center; sheets (200 dp) stay above it.
        translationZ = ctx.dp(150).toFloat()
        outlineProvider = null
    }
    private val frost = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val content = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val title = ctx.text(40f, weight = 700).apply {
        text = folder.name
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setShadowLayer(ctx.dp(10).toFloat(), 0f, ctx.dp(1).toFloat(), Color.argb(120, 0, 0, 0))
    }
    private val count = ctx.text(17f, Color.argb(170, 255, 255, 255), weight = 500).apply {
        text = if (apps.size == 1) "1 app" else "${apps.size} apps"
    }
    private val adapter = TileAdapter(repo, geometry.tileW, geometry.tileH, this).apply {
        setHasStableIds(true)
        dockCount = 0
        this.tvosFocus = tvosFocus
    }
    private val grid = RecyclerView(ctx).apply {
        clipChildren = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        itemAnimator = null
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setPadding(geometry.side, (sh * 0.3f).toInt(), geometry.side, (sh * 0.18f).toInt())
    }
    private var closing = false
    /** The tile last focused, focused again when a sheet over the folder goes away. */
    private var lastFocused = 0

    init {
        root.addView(frost, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(ctx).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        content.addView(title, FrameLayout.LayoutParams((ctx.resources.displayMetrics.widthPixels * 0.7f).toInt(), -2).apply {
            leftMargin = geometry.side; topMargin = (sh * 0.13f).toInt()
        })
        content.addView(count, FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = geometry.side + ctx.dp(2); topMargin = (sh * 0.13f).toInt() + ctx.dp(58)
        })
        grid.layoutManager = GridLayoutManager(ctx, geometry.cols)
        grid.adapter = adapter
        val cols = geometry.cols
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val col = parent.getChildAdapterPosition(view) % cols
                out.left = col * geometry.gap / cols
                out.right = geometry.gap - (col + 1) * geometry.gap / cols
                out.bottom = geometry.rowPitch - geometry.tileH
            }
        })
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        if (apps.isEmpty()) content.addView(ctx.text(18f, Color.argb(190, 255, 255, 255)).apply {
            text = "This folder is empty. Hold OK on an app on the Home Screen and choose “Move into folder”."
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ctx.dp(560), -2, Gravity.CENTER))
        root.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { close(); true } else false
        }
        adapter.submit(apps)
    }

    fun show(): FolderView {
        frost.setImageBitmap(LiquidGlass.frostedSnapshot(host))
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.animate().alpha(1f).setDuration(220).setInterpolator(Motion.ease).start()
        // The folder zooms up out of the screen.
        content.pivotX = host.width / 2f
        content.pivotY = host.height * 0.55f
        content.scaleX = 0.9f; content.scaleY = 0.9f
        content.tweenScale(1f, 560, Motion.spring)
        if (apps.isEmpty()) root.isFocusable = true
        grid.post { refocus() }
        return this
    }

    val isOpen get() = root.parent != null && !closing

    fun refocus() {
        if (apps.isEmpty()) { root.requestFocus(); return }
        val p = lastFocused.coerceIn(0, apps.size - 1)
        grid.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus()
            ?: grid.post { grid.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
    }

    /** Stops the tile loaders at once (the home screen is going away). */
    fun release() = adapter.release()

    /** Blocks D-pad focus inside the folder while a sheet is over it. */
    fun setBlocked(blocked: Boolean) {
        grid.descendantFocusability = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    fun close() {
        if (closing) return
        closing = true
        content.tweenScale(0.94f, 200, Motion.exit)
        root.animate().alpha(0f).setDuration(200).withEndAction {
            host.removeView(root)
            adapter.release()
            callbacks.folderClosed(this)
        }.start()
    }

    // ---- tiles ----

    override fun onTileFocused(pos: Int, cell: TileCell) { lastFocused = pos }

    override fun onTileClicked(pos: Int, cell: TileCell) {
        apps.getOrNull(pos)?.let { callbacks.openFromFolder(it, cell) }
    }

    override fun onTileLongPressed(pos: Int, cell: TileCell) {
        apps.getOrNull(pos)?.let { callbacks.folderAppLongPressed(this, it) }
    }

    override fun onTileKey(pos: Int, cell: TileCell, keyCode: Int): Boolean {
        val (dx, dy) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            else -> return false
        }
        adapter.lastDx = dx
        adapter.lastDy = dy
        val n = apps.size
        val cols = geometry.cols
        val col = pos % cols
        val lastRow = (n - 1) / cols
        val atEdge = (dx < 0 && col == 0) || (dx > 0 && (col == cols - 1 || pos == n - 1)) ||
            (dy < 0 && pos < cols) || (dy > 0 && pos / cols == lastRow)
        if (atEdge) { Motion.bump(cell.card, dx, dy); return true }
        if (dy > 0 && pos + cols >= n) {
            grid.findViewHolderForAdapterPosition(n - 1)?.itemView?.requestFocus()
            return true
        }
        return false
    }
}
