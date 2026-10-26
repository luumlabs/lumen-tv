package com.luumlabs.lumen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.palette.graphics.Palette
import com.luumlabs.lumen.tv.Input
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
    /** Set for a TV input pinned to the grid; then [pkg] is its [InputTile.key]. */
    val input: Input? = null,
    val folder: AppFolder? = null,
)

/** A TV input (HDMI) pinned to the home grid from the Control Center's input list. */
object InputTile {
    private const val PREFIX = "input:"
    private const val SEP = "\u001f"

    fun key(i: Input) = PREFIX + i.uri
    fun isInput(pkg: String) = pkg.startsWith(PREFIX)

    /** How it is kept in [Prefs.pinnedInputs]. */
    fun encode(i: Input) = listOf(i.uri, i.title, i.label).joinToString(SEP)

    fun decode(s: String): Input? {
        val p = s.split(SEP)
        return if (p.size == 3) Input(p[0], p[1], p[2], false) else null
    }
}

/**
 * A tile's artwork in layers, as tvOS builds its icons: [back] fills the tile; [front], when the app
 * has no banner of its own, is its icon floating above it, which the focus parallax moves further
 * than the background so the tile reads as having depth. [front] carries [FRONT_PAD] of transparent
 * margin on each side for its soft shadow.
 */
class TileArt(val back: Bitmap, val front: Bitmap? = null) {
    val bytes get() = back.byteCount + (front?.byteCount ?: 0)

    companion object {
        const val FRONT_ICON = 192
        const val FRONT_PAD = 18
    }
}

/** Enumerates launchable apps and renders their 16:9 tvOS-style tiles. */
class AppRepo(private val ctx: Context) {
    private val pm: PackageManager = ctx.packageManager
    private val prefs = Prefs(ctx)
    private val tiles = object : LruCache<String, TileArt>((if (Device.isLowEnd(ctx)) 14 else 24) * 1024 * 1024) {
        override fun sizeOf(key: String, value: TileArt) = value.bytes
    }

    /**
     * Three colours per package (vivid, deep, muted), taken from its icon; the home background
     * washes into them behind the focused tile. Written by the tile loaders, read on the main thread.
     */
    private val palettes = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    /** The main colour of [pkg]'s artwork, once its tile has been drawn. */
    fun tintFor(pkg: String): Int? = palettes[pkg]?.get(0)

    /** [pkg]'s three colours (vivid, deep, muted), once its tile has been drawn. */
    fun paletteFor(pkg: String): IntArray? = palettes[pkg]

    fun allApps(): List<AppEntry> {
        val byPkg = LinkedHashMap<String, AppEntry>()
        fun add(list: List<ResolveInfo>, leanback: Boolean) {
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName && (!leanback || ai.name == HomeActivity::class.java.name)) continue
                // Lumen lists two screens of its own (settings, Wallpaper); each gets a key of its own.
                val key = if (ai.packageName == ctx.packageName && ai.name != SettingsActivity::class.java.name) ownKey(ai.name) else ai.packageName
                if (byPkg[key]?.leanback == true) continue
                byPkg[key] = AppEntry(
                    key,
                    ri.loadLabel(pm).toString().trim(),
                    ComponentName(ai.packageName, ai.name),
                    leanback,
                )
            }
        }
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), true)
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0), false)
        // Firmware apps the maker's launcher shows although they declare no launcher category
        // (Xiaomi's 应用中心 by category, 高清播放器 and 米家 by name; from Aerial's device notes).
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_APPSTORE_LAUNCHER), 0), false)
        for (c in UNLISTED_ENTRIES) {
            if (c.packageName in byPkg) continue
            val ai = runCatching { pm.getActivityInfo(c, 0) }.getOrNull() ?: continue
            if (ai.exported && ai.isEnabled && ai.applicationInfo.enabled)
                byPkg[c.packageName] = AppEntry(c.packageName, ai.loadLabel(pm).toString().trim(), c, false)
        }
        // Other launchers only confuse things on a launcher grid; so does the home screen itself
        // (the guard build lists it as an app).
        HIDE_ALWAYS.forEach { byPkg.remove(it) }
        if (byPkg[ctx.packageName]?.component?.className == HomeActivity::class.java.name) byPkg.remove(ctx.packageName)
        for (s in prefs.pinnedInputs) {
            val i = InputTile.decode(s) ?: continue
            byPkg[InputTile.key(i)] = AppEntry(InputTile.key(i), i.name, ComponentName(ctx, HomeActivity::class.java), false, i)
        }
        return byPkg.values.toList()
    }

    /** Visible apps in user order; new apps go to the end. */
    fun orderedVisible(): List<AppEntry> {
        val folders = Folders(ctx).read()
        val grouped = folders.flatMap { it.members }.toSet()
        val apps = allApps().filter { it.pkg !in grouped } + folders.map {
            AppEntry(it.id, it.name, ComponentName(ctx, HomeActivity::class.java), false, folder = it)
        }
        val hidden = prefs.hidden
        var order = prefs.order
        if (order.isEmpty()) order = Prefs.DEFAULT_ORDER
        val rank = order.withIndex().associate { it.value to it.index }
        val collator = Collator.getInstance(Locale.getDefault())
        val visible = apps.filter { it.pkg !in hidden }
        // Asked once per app: inside the comparator it was a package-manager call per comparison.
        val system = visible.associate { it.pkg to isSystem(it.pkg) }
        return visible.sortedWith(compareBy<AppEntry> { rank[it.pkg] ?: Int.MAX_VALUE }
            .thenBy { if (system[it.pkg] == true) 1 else 0 }
            .thenComparator { a, b -> collator.compare(a.label, b.label) })
    }

    /** Grid key of one of Lumen's own screens besides settings. */
    private fun ownKey(cls: String) = ctx.packageName + ":" + cls.substringAfterLast('.')

    /** A screen of Lumen itself or a pinned input: it can be moved and hidden, not deleted. */
    fun isOwn(pkg: String) = pkg == ctx.packageName || pkg.startsWith(ctx.packageName + ":") || InputTile.isInput(pkg)

    fun isSystem(pkg: String): Boolean = if (InputTile.isInput(pkg)) false else try {
        (pm.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: PackageManager.NameNotFoundException) { false }

    /**
     * Apps the TV needs to work (settings, the TV player, inputs, the built-in
     * launcher); never offered for deletion. Launchers the user installed can go.
     */
    fun isEssential(pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg in ESSENTIAL) return true
        if (!isSystem(pkg)) return false
        if (listOf(".settings", "tvinput", "launcher", "systemui", "inputmethod", ".ime").any { it in pkg.lowercase() }) return true
        return isLauncher(pkg)
    }

    fun isLauncher(pkg: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS).any { it.activityInfo.packageName == pkg }
    }

    fun launchIntent(app: AppEntry): Intent? {
        if (app.component.packageName == ctx.packageName)
            return Intent(Intent.ACTION_MAIN).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val i = if (app.leanback) pm.getLeanbackLaunchIntentForPackage(app.pkg) else null
        return (i ?: pm.getLaunchIntentForPackage(app.pkg) ?: Intent(Intent.ACTION_MAIN).setComponent(app.component))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun cachedTile(pkg: String): TileArt? = tiles.get(tileKey(pkg))

    /** The settings tile is keyed by its custom icon's date too, so a newly uploaded one shows up. */
    private fun tileKey(pkg: String): String =
        if (pkg == ctx.packageName) pkg + "@" + (SettingsActivity.customIcon(ctx)?.lastModified() ?: 0L) else pkg

    /** Apps by package for drawing folder tiles; one package-manager query serves every folder. */
    @Volatile private var members: Map<String, AppEntry>? = null

    /** Builds the tile artwork; call off the main thread. */
    fun tile(app: AppEntry): TileArt {
        val key = tileKey(app.pkg)
        tiles.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        app.folder?.let { f ->
            drawFolderTile(c, f)
            return TileArt(bmp).also { tiles.put(key, it) }
        }
        app.input?.let { drawInputTile(c, it); return TileArt(bmp).also { a -> tiles.put(key, a) } }
        val own = if (app.component.className == SettingsActivity::class.java.name)
            SettingsActivity.customIcon(ctx)?.let { SettingsActivity.decodeIcon(it, TILE_W) }?.let { BitmapDrawable(ctx.resources, it) }
        else null
        val banner = own ?: try {
            pm.getActivityInfo(app.component, 0).loadBanner(pm)
                ?: pm.getApplicationInfo(app.pkg, 0).loadBanner(pm)
        } catch (e: Exception) { null }
        // Every app contributes background colours, banner or not, so the home wash follows focus.
        val icon = try { pm.getActivityIcon(app.component) } catch (e: Exception) { pm.defaultActivityIcon }
        val colours = palette(icon)
        palettes[app.pkg] = colours
        // Some apps ship android:logo instead of android:banner (fit for a 16:9 tile).
        val logo = if (banner == null) try { pm.getApplicationInfo(app.pkg, 0).loadLogo(pm) } catch (e: Exception) { null } else null
        val art = when {
            banner != null -> { drawCenterCrop(c, banner); TileArt(bmp) }
            logo != null && logo.intrinsicWidth > 0 -> { drawCenterCrop(c, logo); TileArt(bmp) }
            else -> { drawIconBack(c, colours[0]); TileArt(bmp, iconLayer(icon)) }
        }
        tiles.put(key, art)
        return art
    }

    /**
     * The colours that best represent [icon]: its most vivid swatch, a deep one and a muted one,
     * each falling back on the next best so there are always three.
     */
    private fun palette(icon: Drawable): IntArray = runCatching {
        val src = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 96, 96)
        icon.draw(Canvas(src))
        val p = Palette.from(src).maximumColorCount(16).generate()
        src.recycle()
        val main = (p.lightVibrantSwatch ?: p.vibrantSwatch ?: p.dominantSwatch)?.rgb ?: FALLBACK
        val deep = (p.darkVibrantSwatch ?: p.darkMutedSwatch)?.rgb ?: blend(main, Color.BLACK, 0.55f)
        val muted = (p.mutedSwatch ?: p.vibrantSwatch ?: p.lightMutedSwatch)?.rgb ?: blend(main, deep, 0.5f)
        intArrayOf(main, deep, muted)
    }.getOrDefault(intArrayOf(FALLBACK, blend(FALLBACK, Color.BLACK, 0.55f), FALLBACK))

    /**
     * Keeps only the most recently drawn tiles, about a screenful: the home screen is behind
     * another app. Tiles still on screen stay in memory with their views either way.
     */
    fun trimTiles() = tiles.trimToSize(tiles.maxSize() / 4)

    /** Drops the cached tile of [pkg] (it was updated or removed), or of every app. */
    fun invalidate(pkg: String? = null) {
        members = null
        if (pkg == null) tiles.evictAll() else tiles.remove(tileKey(pkg))
    }

    private fun drawCenterCrop(c: Canvas, d: Drawable) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: TILE_W
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: TILE_H
        val scale = maxOf(TILE_W.toFloat() / iw, TILE_H.toFloat() / ih)
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        val l = (TILE_W - w) / 2
        val t = (TILE_H - h) / 2
        c.drawColor(Color.BLACK)
        d.setBounds(l, t, l + w, t + h)
        d.draw(c)
    }

    /**
     * The background of an icon-only app's tile, how tvOS 26 frames non-TV apps in Liquid Glass:
     * a diagonal gradient of the app's own colour, light at the top left, the raw colour through
     * the middle, deep at the bottom right, with light pooled in the corner where glass catches it.
     */
    private fun drawIconBack(c: Canvas, base: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val light = blend(base, Color.WHITE, 0.42f)
        val deep = blend(base, Color.BLACK, 0.62f)
        p.shader = android.graphics.LinearGradient(
            0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(),
            intArrayOf(light, base, deep), floatArrayOf(0f, 0.48f, 1f),
            android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), p)
        p.shader = android.graphics.RadialGradient(
            TILE_W * 0.24f, TILE_H * 0.12f, TILE_W * 0.62f,
            intArrayOf(Color.argb(96, 255, 255, 255), Color.argb(24, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), p)
        // A light veil like frosted glass, and a soft pool of shade where the icon will float.
        p.shader = android.graphics.RadialGradient(TILE_W / 2f, TILE_H * 0.56f, TILE_H * 0.5f,
            intArrayOf(Color.argb(46, 0, 0, 0), Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), p)
        c.drawColor(Color.argb(14, 255, 255, 255))
    }

    /** The icon as a layer of its own, with a soft shadow under it, to float above [drawIconBack]. */
    private fun iconLayer(icon: Drawable): Bitmap {
        val s = TileArt.FRONT_ICON
        val pad = TileArt.FRONT_PAD
        val out = Bitmap.createBitmap(s + pad * 2, s + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val glyph = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, s, s)
        icon.draw(Canvas(glyph))
        // Software canvas: BlurMaskFilter works here.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = android.graphics.BlurMaskFilter(pad * 0.7f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            color = Color.argb(110, 0, 0, 0)
        }
        val alpha = glyph.extractAlpha()
        c.drawBitmap(alpha, pad.toFloat(), pad + pad * 0.35f, shadow)
        alpha.recycle()
        c.drawBitmap(glyph, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        glyph.recycle()
        return out
    }

    /**
     * A folder: a pane of tinted glass holding the first six of its apps as small tiles, three by
     * two, with its name beneath them; translucent, so the wallpaper shows through.
     */
    private fun drawFolderTile(c: Canvas, f: AppFolder) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(),
            Color.argb(120, 92, 100, 128), Color.argb(150, 28, 30, 44), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), p)
        p.shader = null
        val all = members ?: allApps().associateBy { it.pkg }.also { members = it }
        val gap = 10f
        val w = 104f
        val h = w * 9f / 16f
        val left0 = (TILE_W - (w * 3 + gap * 2)) / 2f
        val top0 = 26f
        val r = 9f
        f.members.filter { it !in prefs.hidden }.mapNotNull { all[it] }.take(6).forEachIndexed { index, member ->
            val left = left0 + (index % 3) * (w + gap)
            val top = top0 + (index / 3) * (h + gap)
            val dst = RectF(left, top, left + w, top + h)
            val art = tile(member)
            val save = c.save()
            val clip = android.graphics.Path().apply { addRoundRect(dst, r, r, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(art.back, null, dst, p)
            art.front?.let { fr ->
                val inset = h * 0.18f
                val sz = h - inset * 2
                val scale = sz / TileArt.FRONT_ICON
                val pad = TileArt.FRONT_PAD * scale
                val cx = dst.centerX(); val cy = dst.centerY()
                c.drawBitmap(fr, null, RectF(cx - sz / 2 - pad, cy - sz / 2 - pad, cx + sz / 2 + pad, cy + sz / 2 + pad), p)
            }
            c.restoreToCount(save)
        }
        p.color = Color.WHITE
        p.textSize = 24f
        p.textAlign = Paint.Align.CENTER
        p.typeface = Fonts.inter(ctx, 600)
        c.drawText(android.text.TextUtils.ellipsize(f.name, android.text.TextPaint(p), 360f,
            android.text.TextUtils.TruncateAt.END).toString(), TILE_W / 2f, 197f, p)
    }

    /** Mixes [color] towards [towards] by [amount] (0 = colour, 1 = target). */
    private fun blend(color: Int, towards: Int, amount: Float): Int {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        val r = (ch(color, 16) + (ch(towards, 16) - ch(color, 16)) * amount).toInt()
        val g = (ch(color, 8) + (ch(towards, 8) - ch(color, 8)) * amount).toInt()
        val b = (ch(color, 0) + (ch(towards, 0) - ch(color, 0)) * amount).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    /** An input tile: a dark glass card with the input glyph and its name, like tvOS's own inputs. */
    private fun drawInputTile(c: Canvas, i: Input) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(),
            Color.rgb(58, 64, 82), Color.rgb(22, 24, 32), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), p)
        p.shader = null
        val glyph = ctx.getDrawable(R.drawable.ic_input)!!.mutate()
        glyph.setTint(Color.WHITE)
        val g = 64
        glyph.setBounds((TILE_W - g) / 2, 46, (TILE_W + g) / 2, 46 + g)
        glyph.draw(c)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.typeface = Fonts.inter(ctx, 600)
        p.textSize = 30f
        c.drawText(i.title, TILE_W / 2f, 160f, p)
        if (i.label.isNotEmpty()) {
            p.color = Color.argb(170, 255, 255, 255)
            p.typeface = Fonts.inter(ctx, 500)
            p.textSize = 20f
            c.drawText(i.label, TILE_W / 2f, 192f, p)
        }
    }

    companion object {
        private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"
        private val UNLISTED_ENTRIES = listOf(
            ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"), // 高清播放器
            ComponentName("com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"), // 米家
        )
        const val TILE_W = 400
        const val TILE_H = 225
        private val FALLBACK = Color.rgb(120, 130, 150)
        val HIDE_ALWAYS = setOf(
            "com.oversea.aslauncher",
            "com.dangbei.TVHomeLauncher",
            "com.android.tv.settings.system.FallbackHome",
        )
        private val ESSENTIAL = setOf(
            "com.sony.dtv.tvx",                // Sony's TV / HDMI player
            "com.android.tv",                  // Live Channels
            "com.google.android.tv",
            "com.android.vending",
            "com.google.android.gms",
            "com.sony.dtv.systemupdate",
            "com.sony.dtv.update",
        )
    }
}
