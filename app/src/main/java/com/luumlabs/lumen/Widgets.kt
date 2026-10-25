package com.luumlabs.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP helpers for the widgets: short timeouts, a browser user agent, no redirects across hosts. */
object Net {
    private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray = request(url, null, headers)

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): ByteArray =
        request(url, body.toByteArray(), headers + ("Content-Type" to "application/json"))

    private fun request(url: String, body: ByteArray?, headers: Map<String, String>): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads (or reads from [dir] in the cache) a picture, decoded to at most about [maxWidth]
     * px wide; [rgb565] halves the memory of pictures with no transparency. Blocking; null on failure.
     */
    fun image(ctx: Context, url: String, dir: String, maxWidth: Int, rgb565: Boolean = false): Bitmap? {
        val folder = File(ctx.cacheDir, dir).apply { mkdirs() }
        val f = File(folder, Integer.toHexString(url.hashCode()) + ".img")
        if (!f.exists()) try {
            val tmp = File(folder, f.name + ".part")
            tmp.writeBytes(get(url))
            tmp.renameTo(f)
            folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(120)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("Net", "image $url failed: ${e.message}")
            return null
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            if (rgb565) inPreferredConfig = Bitmap.Config.RGB_565
        })?.also { it.prepareToDraw() }
    }
}

/**
 * Where the TV is, for the weather and for the country whose charts to show: a city set in
 * settings (looked up with Open-Meteo's geocoder), or else worked out from the public IP address.
 * Kept for half a day.
 */
object Geo {
    data class Place(val lat: Double, val lon: Double, val city: String, val country: String)

    private const val TTL = 12 * 60 * 60_000L
    /** The last place found, when, and for which city setting ("" for automatic). */
    @Volatile private var memo: Triple<Long, Place, String>? = null
    @Volatile private var failedAt = 0L

    /** Blocking. Null when neither the named city nor the IP address can be placed. */
    fun place(ctx: Context): Place? {
        val prefs = Prefs(ctx)
        val named = prefs.weatherCity.trim()
        memo?.let { (at, p, forName) -> if (System.currentTimeMillis() - at < TTL && forName == named) return p }
        val sp = ctx.getSharedPreferences("geo", Context.MODE_PRIVATE)
        val cached = sp.getString("place", null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
        val fresh = System.currentTimeMillis() - sp.getLong("at", 0) < TTL
        if (cached != null && fresh && sp.getString("named", "") == named) return cached.also { memo = Triple(sp.getLong("at", 0), it, named) }
        // Offline, the IP services are not asked again every minute.
        if (System.currentTimeMillis() - failedAt < 10 * 60_000L) return cached
        val found = (if (named.isNotEmpty()) runCatching { geocode(named) }.getOrNull() else null) ?: fromIp()
        if (found == null) failedAt = System.currentTimeMillis()
        if (found != null) {
            memo = Triple(System.currentTimeMillis(), found, named)
            sp.edit().putString("place", encode(found).toString()).putLong("at", System.currentTimeMillis()).putString("named", named).apply()
            return found
        }
        return cached
    }

    private fun geocode(name: String): Place? {
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val r = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json").decodeToString())
        val o = r.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Place(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("name", name), o.optString("country_code", ""))
    }

    /** A few free, keyless services, tried in turn. */
    private fun fromIp(): Place? {
        val tries = listOf<() -> Place?>(
            {
                val o = JSONObject(Net.get("https://get.geojs.io/v1/ip/geo.json").decodeToString())
                Place(o.getString("latitude").toDouble(), o.getString("longitude").toDouble(), o.optString("city"), o.optString("country_code"))
            },
            {
                val o = JSONObject(Net.get("https://ipwho.is/").decodeToString())
                if (!o.optBoolean("success", true)) null
                else Place(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city"), o.optString("country_code"))
            },
            {
                val o = JSONObject(Net.get("https://ipapi.co/json/").decodeToString())
                Place(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city"), o.optString("country_code"))
            },
        )
        for (t in tries) runCatching { t() }.getOrNull()?.let { return it }
        return null
    }

    /** The city found last time, without looking again; null before the first lookup. */
    fun cachedCity(ctx: Context): String? = ctx.getSharedPreferences("geo", Context.MODE_PRIVATE).getString("place", null)
        ?.let { runCatching { JSONObject(it).optString("city") }.getOrNull() }?.ifEmpty { null }

    /** Forgets where the TV is, so the next [place] looks again. */
    fun forget(ctx: Context) {
        memo = null
        failedAt = 0L
        ctx.getSharedPreferences("geo", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun encode(p: Place) = JSONObject().put("lat", p.lat).put("lon", p.lon).put("city", p.city).put("country", p.country)
    private fun decode(o: JSONObject) = Place(o.getDouble("lat"), o.getDouble("lon"), o.optString("city"), o.optString("country"))
}

/**
 * What lies behind the home screen's glass widgets, kept by the home screen: the wallpaper blurred
 * (shown centre-cropped) and the colour wash over it (stretched over the screen). Each panel paints
 * its share of both, so the glass shows what is actually behind it without capturing the screen.
 */
class GlassSource {
    var wall: Bitmap? = null
        set(v) { field = v; wallShader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; changed() }
    var wash: Bitmap? = null
        set(v) { field = v; washShader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; changed() }
    internal var wallShader: BitmapShader? = null
    internal var washShader: BitmapShader? = null
    internal val panels = ArrayList<GlassPanel>()

    fun changed() { for (p in panels) p.invalidateSelf() }
}

/**
 * A pane of Liquid Glass for a widget: its share of the blurred wallpaper and the colour wash,
 * lifted and made more vivid, a milky veil (or [tint], a gradient of its own, such as the weather's
 * sky), a sheen across the top and a lit rim. [lit] (0 to 1) brightens it on focus.
 */
class GlassPanel(private val owner: View, private val radius: Float, private val source: GlassSource) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val m = Matrix()
    private val loc = IntArray(2)
    private val tone = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(1.4f)
        postConcat(ColorMatrix(floatArrayOf(0.84f, 0f, 0f, 0f, 26f, 0f, 0.84f, 0f, 0f, 26f, 0f, 0f, 0.84f, 0f, 26f, 0f, 0f, 0f, 1f, 0f)))
    })
    private var sheen: Shader? = null
    private var rim: Shader? = null
    private var tintShader: Shader? = null

    /** A gradient of the panel's own over the glass (top colour, bottom colour), or null for the plain veil. */
    var tint: IntArray? = null
        set(v) { field = v; rebuildTint(); invalidateSelf() }

    var lit = 0f
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    /**
     * Clear Liquid Glass baked over what is behind this pane ([LiquidGlass.bake]), refracting at the
     * rim; drawn instead of the live approximation once the home screen has made it.
     */
    var baked: Bitmap? = null
        set(v) { field = v; dim = v?.let(::dimFor) ?: 0; invalidateSelf() }

    /** A dark veil under the content where the glass sits on something bright, so white text reads. */
    private var dim = 0

    private fun dimFor(b: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(b, 12, 6, true)
        var sum = 0f
        for (y in 0 until 6) for (x in 0 until 12) {
            val c = small.getPixel(x, y)
            sum += (Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f) / 255f
        }
        if (small !== b) small.recycle()
        val luma = sum / 72f
        return (((luma - 0.42f) / 0.4f).coerceIn(0f, 1f) * 120).toInt()
    }

    /** Follows the source's pictures while attached. */
    fun attach() { if (this !in source.panels) source.panels += this }
    fun detach() { source.panels -= this }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        sheen = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.argb(64, 255, 255, 255), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(40, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 0.7f, 1f), Shader.TileMode.CLAMP)
        rim = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.WHITE, Color.argb(50, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(150, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        rebuildTint()
    }

    private fun rebuildTint() {
        val t = tint ?: run { tintShader = null; return }
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        tintShader = LinearGradient(0f, 0f, bounds.width() * 0.3f, h, t[0], t.getOrElse(1) { t[0] }, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        rect.set(bounds)
        val r = radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2)
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        baked?.let { drawBaked(c, it, r); return }
        val save = c.save()
        c.clipPath(path)
        val screen = owner.rootView
        owner.getLocationInWindow(loc)
        val sw = screen.width.toFloat()
        val sh = screen.height.toFloat()
        // The owner may be scaled (focus lift): map through its scale so the picture stays put.
        val sx = owner.scaleX
        val sy = owner.scaleY
        source.wall?.let { b ->
            val s = maxOf(sw / b.width, sh / b.height)
            m.setScale(s, s)
            m.postTranslate((sw - b.width * s) / 2 - loc[0], (sh - b.height * s) / 2 - loc[1])
            m.postScale(1 / sx, 1 / sy)
            source.wallShader?.setLocalMatrix(m)
            paint.shader = source.wallShader
            paint.colorFilter = tone
            paint.alpha = 255
            c.drawRect(rect, paint)
            paint.colorFilter = null
        } ?: run { paint.shader = null; paint.color = Color.argb(150, 30, 32, 40); c.drawRect(rect, paint) }
        source.wash?.let { b ->
            m.setScale(sw / b.width, sh / b.height)
            m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            m.postScale(1 / sx, 1 / sy)
            source.washShader?.setLocalMatrix(m)
            paint.shader = source.washShader
            paint.alpha = 255
            c.drawRect(rect, paint)
        }
        val ts = tintShader
        if (ts != null) {
            paint.shader = ts
            paint.alpha = (205 + 40 * lit).toInt()
            c.drawRect(rect, paint)
        } else {
            paint.shader = null
            paint.color = Color.argb((26 + 46 * lit).toInt(), 255, 255, 255)
            c.drawRect(rect, paint)
        }
        paint.shader = sheen
        paint.alpha = (200 + 55 * lit).toInt()
        c.drawRect(rect, paint)
        c.restoreToCount(save)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = owner.resources.displayMetrics.density * (1f + 0.7f * lit)
        paint.shader = rim
        paint.alpha = (140 + 115 * lit).toInt()
        rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        c.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    /** The baked glass, a veil where it is bright, and on focus a brighter body and rim. */
    private fun drawBaked(c: Canvas, b: Bitmap, r: Float) {
        val save = c.save()
        c.clipPath(path)
        paint.shader = null
        paint.alpha = 255
        c.drawBitmap(b, null, rect, paint)
        if (dim > 0) { paint.color = Color.argb(dim, 0, 0, 0); c.drawRect(rect, paint) }
        if (lit > 0f) { paint.color = Color.argb((34 * lit).toInt(), 255, 255, 255); c.drawRect(rect, paint) }
        c.restoreToCount(save)
        if (lit > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = owner.resources.displayMetrics.density * 1.4f
            paint.shader = rim
            paint.alpha = (230 * lit).toInt()
            rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
            c.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }
    }

    override fun getOutline(outline: Outline) = outline.setRoundRect(bounds, radius.coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f))
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A home-screen widget: a glass pane of [w]×[h] px holding its content in [body]. Focused, it
 * lifts on a spring over a soft shadow and its glass lights up, like a focused tile.
 */
open class WidgetCard(ctx: Context, source: GlassSource, val w: Int, val h: Int) : FrameLayout(ctx) {
    private val radius = dp(26).toFloat()
    val body = FrameLayout(ctx).apply { clipChildren = false }
    val glass = GlassPanel(body, radius, source)
    private val pad = dp(28)
    private val shadow = ImageView(ctx).apply {
        setImageBitmap(LiquidGlass.shadow(w, h, radius, pad))
        alpha = 0f
    }
    private val lift = Tween()

    init {
        isFocusable = true
        isClickable = true
        clipChildren = false
        clipToPadding = false
        addView(shadow, LayoutParams(w + pad * 2, h + pad * 2))
        shadow.translationX = -pad.toFloat()
        shadow.translationY = -pad.toFloat()
        body.background = glass
        body.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        body.clipToOutline = true
        addView(body, LayoutParams(w, h))
        setOnFocusChangeListener { _, f -> onFocus(f) }
    }

    protected open fun onFocus(f: Boolean) {
        val s0 = body.scaleX
        val s1 = if (f) 1.06f else 1f
        val a0 = shadow.alpha
        val y0 = shadow.translationY
        val l0 = glass.lit
        translationZ = if (f) 1f else 0f
        lift.start(if (f) 520 else 280, if (f) Motion.spring else Motion.soft) { p ->
            val e = p.coerceAtMost(1f)
            body.scaleX = lerp(s0, s1, p); body.scaleY = body.scaleX
            shadow.scaleX = body.scaleX; shadow.scaleY = body.scaleX
            shadow.alpha = lerp(a0, if (f) 0.85f else 0f, e)
            shadow.translationY = lerp(y0, -pad + if (f) dp(16).toFloat() else 0f, e)
            glass.lit = lerp(l0, if (f) 1f else 0f, e)
        }
    }

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (pressed == was || !isFocused) return
        body.tweenScale(if (pressed) 1.0f else 1.06f, if (pressed) 110 else 380, if (pressed) Motion.ease else Motion.spring)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.attach()
    }

    override fun onDetachedFromWindow() {
        glass.detach()
        super.onDetachedFromWindow()
    }
}
