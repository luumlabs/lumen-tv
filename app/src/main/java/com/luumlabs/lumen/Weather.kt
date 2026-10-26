package com.luumlabs.lumen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The weather, from Open-Meteo (free, no key) for the place [Geo] finds: now, the next hours and
 * the next six days. Refreshed every half hour at most; the last answer is kept on disk, so the
 * widget has something to show straight after a boot.
 */
object WeatherData {
    data class Hour(val label: String, val code: Int, val day: Boolean, val temp: Int, val pop: Int)
    data class Day(val label: String, val code: Int, val hi: Int, val lo: Int, val pop: Int)
    data class Now(
        val city: String, val temp: Int, val feels: Int, val code: Int, val day: Boolean, val hi: Int, val lo: Int,
        val humidity: Int, val wind: Int, val windUnit: String, val unit: String,
        val hours: List<Hour>, val days: List<Day>, val at: Long,
    )

    private const val TTL = 30 * 60_000L
    private const val BACKOFF = 10 * 60_000L
    @Volatile private var memo: Now? = null
    /** The city setting the last answer was fetched for ("" for automatic). */
    @Volatile private var memoQuery: String? = null
    @Volatile private var failedAt = 0L

    /** Whether temperatures are in Fahrenheit: by setting, or where the country uses them. */
    fun fahrenheit(ctx: Context): Boolean = when (Prefs(ctx).weatherUnit) {
        "f" -> true
        "c" -> false
        else -> Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
    }

    /** The last weather known, without waiting: memory, then disk. */
    fun cached(ctx: Context): Now? = memo ?: runCatching {
        val f = file(ctx)
        if (!f.isFile) null else JSONObject(f.readText()).let { o ->
            memoQuery = o.optString("lumen_query", "")
            parse(o, Prefs(ctx).clock24)
        }
    }.getOrNull()?.also { memo = it }

    /** Blocking. Fresh weather when the last is older than half an hour (or [force]); null offline with nothing kept. */
    fun load(ctx: Context, force: Boolean = false): Now? {
        val last = cached(ctx)
        val f = fahrenheit(ctx)
        val query = Prefs(ctx).weatherCity
        // Compared with the setting it was fetched for: the geocoder's name for a city may differ ("NYC").
        val samePlace = (memoQuery ?: query) == query
        if (!force && last != null && samePlace && System.currentTimeMillis() - last.at < TTL && (last.unit == "°F") == f) return last
        if (!force && samePlace && System.currentTimeMillis() - failedAt < BACKOFF) return last
        val place = Geo.place(ctx) ?: run { failedAt = System.currentTimeMillis(); return last }
        return try {
            val unit = if (f) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7$unit"
            val o = JSONObject(Net.get(url).decodeToString())
            o.put("lumen_city", place.city).put("lumen_at", System.currentTimeMillis()).put("lumen_f", f).put("lumen_query", query)
            file(ctx).writeText(o.toString())
            parse(o, Prefs(ctx).clock24).also { memo = it; memoQuery = query; failedAt = 0L }
        } catch (e: Exception) {
            android.util.Log.w("Weather", "load failed: ${e.message}")
            failedAt = System.currentTimeMillis()
            last
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "weather.json")

    /** Reads an Open-Meteo answer (with the fields [load] adds to it). */
    internal fun parse(o: JSONObject, clock24: Boolean): Now {
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val daily = o.getJSONObject("daily")
        val f = o.optBoolean("lumen_f", false)
        val times = hourly.getJSONArray("time")
        val nowHour = cur.getString("time").take(13)
        var start = 0
        for (k in 0 until times.length()) if (times.getString(k).take(13) == nowHour) { start = k; break }
        // Hours past the saved answer's own "now" are what is left of the forecast after a while offline.
        val savedAt = o.optLong("lumen_at", 0L)
        val elapsed = if (savedAt <= 0L) 0 else ((System.currentTimeMillis() - savedAt) / 3_600_000L).toInt().coerceAtLeast(0)
        start = (start + elapsed).coerceAtMost((times.length() - 6).coerceAtLeast(0))
        val hours = (start until minOf(start + 6, times.length())).map { k ->
            val t = times.getString(k)
            val h = t.substring(11, 13).toIntOrNull() ?: 0
            Hour(
                if (k == start) "Now" else if (clock24) "%02d".format(h) else "${if (h % 12 == 0) 12 else h % 12}${if (h < 12) "AM" else "PM"}",
                hourly.getJSONArray("weather_code").optInt(k),
                hourly.getJSONArray("is_day").optInt(k, 1) == 1,
                hourly.getJSONArray("temperature_2m").optDouble(k).roundToInt(),
                hourly.optJSONArray("precipitation_probability")?.optInt(k) ?: 0,
            )
        }
        val dTimes = daily.getJSONArray("time")
        val days = (0 until dTimes.length()).map { k ->
            val date = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dTimes.getString(k)) }.getOrNull()
            Day(
                if (k == 0) "Today" else date?.let { java.text.SimpleDateFormat("EEE", Locale.US).format(it) } ?: "",
                daily.getJSONArray("weather_code").optInt(k),
                daily.getJSONArray("temperature_2m_max").optDouble(k).roundToInt(),
                daily.getJSONArray("temperature_2m_min").optDouble(k).roundToInt(),
                daily.optJSONArray("precipitation_probability_max")?.optInt(k) ?: 0,
            )
        }.take(6)
        return Now(
            city = o.optString("lumen_city").ifEmpty { "Weather" },
            temp = cur.getDouble("temperature_2m").roundToInt(),
            feels = cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")).roundToInt(),
            code = cur.optInt("weather_code"),
            day = cur.optInt("is_day", 1) == 1,
            hi = days.firstOrNull()?.hi ?: 0,
            lo = days.firstOrNull()?.lo ?: 0,
            humidity = cur.optInt("relative_humidity_2m"),
            wind = cur.optDouble("wind_speed_10m").roundToInt(),
            windUnit = if (f) "mph" else "km/h",
            unit = if (f) "°F" else "°C",
            hours = hours, days = days, at = o.optLong("lumen_at", 0L),
        )
    }

    /** WMO weather interpretation codes, in words. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 77 -> "Snow"
        75 -> "Heavy Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorms"
        96, 99 -> "Hailstorms"
        else -> "—"
    }

    /** The sky behind the widget, as Apple's Weather paints it: by condition and time of day (top, bottom). */
    fun sky(code: Int, day: Boolean): IntArray = when {
        code in setOf(95, 96, 99) -> intArrayOf(Color.rgb(44, 48, 72), Color.rgb(18, 20, 34))
        code >= 51 -> if (day) intArrayOf(Color.rgb(70, 92, 124), Color.rgb(36, 50, 74)) else intArrayOf(Color.rgb(30, 38, 58), Color.rgb(12, 16, 28))
        code in setOf(3, 45, 48) -> if (day) intArrayOf(Color.rgb(104, 122, 146), Color.rgb(62, 76, 98)) else intArrayOf(Color.rgb(36, 42, 58), Color.rgb(16, 18, 28))
        else -> if (day) intArrayOf(Color.rgb(38, 120, 214), Color.rgb(16, 74, 160)) else intArrayOf(Color.rgb(16, 26, 62), Color.rgb(6, 10, 30))
    }.map { Color.argb(215, Color.red(it), Color.green(it), Color.blue(it)) }.toIntArray()
}

/**
 * A weather glyph drawn in the style of Apple's symbols: multicolour (a golden sun, a pale moon,
 * white clouds, blue rain, a yellow bolt) or, with [mono], all in white for clear glass, the parts
 * set apart by opacity. Scales to any size, so no image files.
 */
class WeatherIcon(private var code: Int = 0, private var day: Boolean = true, private val mono: Boolean = false) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = Path()

    fun set(code: Int, day: Boolean) { this.code = code; this.day = day; invalidateSelf() }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        // The knockout around a cloud clears pixels: only ever inside a layer of the icon's own.
        val knock = mono && (code == 1 || code == 2)
        if (knock) c.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null) else c.save()
        c.translate(b.left + (b.width() - s) / 2, b.top + (b.height() - s) / 2)
        c.scale(s, s)
        when (code) {
            0 -> if (day) sun(c, 0.5f, 0.5f, 0.22f) else moon(c, 0.5f, 0.5f, 0.3f)
            1, 2 -> {
                if (day) sun(c, 0.62f, 0.36f, 0.16f) else moon(c, 0.64f, 0.34f, 0.2f)
                if (mono) knockout(c, 0.08f, 0.4f, 0.74f)
                cloud(c, 0.08f, 0.4f, 0.74f, if (code == 1) 0.85f else 1f)
            }
            3 -> { cloud(c, 0.22f, 0.24f, 0.62f, 0.55f); cloud(c, 0.06f, 0.34f, 0.84f, 1f) }
            45, 48 -> { cloud(c, 0.1f, 0.16f, 0.8f, 1f); lines(c) }
            51, 53, 55, 56, 57 -> { cloud(c, 0.1f, 0.12f, 0.8f, 1f); drops(c, short = true) }
            61, 63, 65, 66, 67, 80, 81, 82 -> { cloud(c, 0.1f, 0.12f, 0.8f, 1f); drops(c, short = false) }
            71, 73, 75, 77, 85, 86 -> { cloud(c, 0.1f, 0.12f, 0.8f, 1f); flakes(c) }
            95, 96, 99 -> { cloud(c, 0.1f, 0.1f, 0.8f, 1f); bolt(c) }
            else -> cloud(c, 0.1f, 0.3f, 0.8f, 1f)
        }
        c.restore()
    }

    private fun sun(c: Canvas, x: Float, y: Float, r: Float) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * 0.28f
        p.shader = null
        p.color = if (mono) Color.WHITE else Color.rgb(255, 204, 0)
        for (k in 0 until 8) {
            val a = Math.PI * k / 4
            val c1 = cos(a).toFloat(); val s1 = sin(a).toFloat()
            c.drawLine(x + c1 * r * 1.42f, y + s1 * r * 1.42f, x + c1 * r * 1.85f, y + s1 * r * 1.85f, p)
        }
        p.style = Paint.Style.FILL
        p.shader = if (mono) null else RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f, Color.rgb(255, 232, 92), Color.rgb(255, 176, 0), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }

    private fun moon(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset(); tmp.reset()
        path.addCircle(x, y, r, Path.Direction.CW)
        tmp.addCircle(x + r * 0.55f, y - r * 0.35f, r * 0.85f, Path.Direction.CW)
        path.op(tmp, Path.Op.DIFFERENCE)
        p.style = Paint.Style.FILL
        if (mono) { p.shader = null; p.color = Color.WHITE }
        else p.shader = LinearGradient(x - r, y - r, x + r, y + r, Color.rgb(255, 250, 225), Color.rgb(214, 210, 190), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
    }

    /** A cloud filling [w] from ([x], [y]); [shade] dims a cloud set behind another. */
    private fun cloud(c: Canvas, x: Float, y: Float, w: Float, shade: Float) {
        path.reset(); tmp.reset()
        val h = w * 0.56f
        path.addRoundRect(RectF(x, y + h * 0.48f, x + w, y + h), h * 0.26f, h * 0.26f, Path.Direction.CW)
        tmp.addCircle(x + w * 0.32f, y + h * 0.52f, h * 0.34f, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        tmp.addCircle(x + w * 0.58f, y + h * 0.38f, h * 0.42f, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        tmp.addCircle(x + w * 0.8f, y + h * 0.66f, h * 0.28f, Path.Direction.CW); path.op(tmp, Path.Op.UNION)
        p.style = Paint.Style.FILL
        if (mono) {
            // A cloud in front of a sun or moon covers it; one behind another is set back by opacity.
            p.shader = null
            p.color = Color.argb((255 * shade * shade).toInt().coerceIn(90, 255), 255, 255, 255)
        } else {
            val top = Color.rgb((255 * shade).toInt(), (255 * shade).toInt(), (255 * shade).toInt())
            val bottom = Color.rgb((214 * shade).toInt(), (224 * shade).toInt(), (236 * shade).toInt())
            p.shader = LinearGradient(0f, y, 0f, y + h, top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawPath(path, p)
        p.shader = null
    }

    /** Clears a thin outline around the cloud so a white cloud stands apart from a white sun. */
    private fun knockout(c: Canvas, x: Float, y: Float, w: Float) {
        val h = w * 0.56f
        path.reset(); tmp.reset()
        path.addRoundRect(RectF(x, y + h * 0.48f, x + w, y + h), h * 0.26f, h * 0.26f, Path.Direction.CW)
        tmp.addCircle(x + w * 0.32f, y + h * 0.52f, h * 0.34f, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        tmp.addCircle(x + w * 0.58f, y + h * 0.38f, h * 0.42f, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        tmp.addCircle(x + w * 0.8f, y + h * 0.66f, h * 0.28f, Path.Direction.CW); path.op(tmp, Path.Op.UNION)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.07f
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        c.drawPath(path, p)
        p.xfermode = null
        p.style = Paint.Style.FILL
    }

    private fun drops(c: Canvas, short: Boolean) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 0.07f
        p.color = if (mono) Color.argb(210, 255, 255, 255) else Color.rgb(90, 200, 250)
        for (k in 0 until 3) {
            val x = 0.3f + k * 0.2f
            if (short) c.drawPoint(x - 0.02f, 0.78f, p).also { c.drawPoint(x - 0.06f, 0.9f, p) }
            else c.drawLine(x, 0.68f, x - 0.07f, 0.9f, p)
        }
    }

    private fun flakes(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        for (k in 0 until 3) c.drawCircle(0.3f + k * 0.2f, 0.78f + (k % 2) * 0.08f, 0.045f, p)
    }

    private fun bolt(c: Canvas) {
        path.reset()
        path.moveTo(0.52f, 0.6f); path.lineTo(0.38f, 0.8f); path.lineTo(0.5f, 0.8f)
        path.lineTo(0.44f, 0.97f); path.lineTo(0.64f, 0.74f); path.lineTo(0.52f, 0.74f); path.lineTo(0.6f, 0.6f); path.close()
        p.style = Paint.Style.FILL
        p.color = if (mono) Color.WHITE else Color.rgb(255, 204, 0)
        c.drawPath(path, p)
    }

    private fun lines(c: Canvas) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 0.06f
        p.color = if (mono) Color.argb(180, 255, 255, 255) else Color.argb(220, 210, 216, 226)
        c.drawLine(0.16f, 0.74f, 0.84f, 0.74f, p)
        c.drawLine(0.26f, 0.88f, 0.74f, 0.88f, p)
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The weather on the home screen, in the layout of Apple's medium Weather widget on clear Liquid
 * Glass: the place, the temperature in light figures, the condition and the day's high and low,
 * and the next hours along the bottom, all in white with monochrome symbols, nothing tinted. OK
 * opens the six-day forecast.
 */
class WeatherWidget(ctx: Context, source: GlassSource, w: Int, h: Int) : WidgetCard(ctx, source, w, h) {
    private val city = ctx.text(14f, Color.argb(235, 255, 255, 255), weight = 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val arrow = ImageView(ctx).apply { setImageDrawable(LocationArrow()) }
    private val temp = ctx.text(38f, weight = 200).apply { includeFontPadding = false; letterSpacing = -0.03f }
    private val icon = WeatherIcon(mono = true)
    private val iconView = ImageView(ctx).apply { setImageDrawable(icon) }
    private val condition = ctx.text(13f, weight = 600).apply { gravity = Gravity.END; maxLines = 1 }
    private val range = ctx.text(13f, Color.argb(190, 255, 255, 255), weight = 500).apply { gravity = Gravity.END }
    private val hours = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val empty = ctx.text(14f, Color.argb(200, 255, 255, 255), weight = 500).apply { text = "Finding the weather…"; gravity = Gravity.CENTER }
    var now: WeatherData.Now? = null
        private set

    init {
        contentDescription = "Weather"
        val p = dp(18)
        val place = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(city, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5) })
            addView(arrow, LinearLayout.LayoutParams(dp(9), dp(9)))
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(place)
            addView(temp, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        }
        body.addView(left, FrameLayout.LayoutParams(w / 2, -2).apply { leftMargin = p; topMargin = dp(13) })
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(iconView, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(condition, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(range, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        }
        body.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = p; topMargin = dp(14) })
        body.addView(hours, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = p - dp(4); rightMargin = p - dp(4); bottomMargin = dp(12) })
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        // Clear glass can sit on a bright wallpaper: a soft shadow keeps white type legible.
        listOf(city, temp, condition, range).forEach { it.setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), Color.argb(90, 0, 0, 0)) }
        show(null)
    }

    fun show(n: WeatherData.Now?) {
        now = n
        val has = n != null
        empty.visibility = if (has) View.GONE else View.VISIBLE
        for (v in listOf(city, arrow, temp, iconView, condition, range, hours)) v.visibility = if (has) View.VISIBLE else View.INVISIBLE
        glass.tint = null
        if (n == null) return
        city.text = n.city
        temp.text = "${n.temp}°"
        icon.set(n.code, n.day)
        condition.text = WeatherData.describe(n.code)
        range.text = "H:${n.hi}°  L:${n.lo}°"
        hours.removeAllViews()
        for (hr in n.hours) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(context.text(11f, Color.argb(175, 255, 255, 255), weight = 600).apply { text = hr.label; gravity = Gravity.CENTER })
                addView(ImageView(context).apply { setImageDrawable(WeatherIcon(hr.code, hr.day, mono = true)) }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { topMargin = dp(3) })
                addView(context.text(13f, weight = 600).apply {
                    text = "${hr.temp}°"; gravity = Gravity.CENTER
                    setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.argb(80, 0, 0, 0))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }
            hours.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** The small navigation arrow Apple puts beside the current location. */
    private class LocationArrow : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        private val path = Path()
        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            path.reset()
            path.moveTo(bounds.left + w, bounds.top.toFloat())
            path.lineTo(bounds.left.toFloat(), bounds.top + h * 0.45f)
            path.lineTo(bounds.left + w * 0.52f, bounds.top + h * 0.5f)
            path.lineTo(bounds.left + w * 0.56f, bounds.top + h)
            path.close()
            c.drawPath(path, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

/**
 * The forecast on OK: a frosted sheet in the manner of Apple's Weather app, with today in large
 * type, the hours ahead, and six days whose temperature bars sit on one scale for the week, coloured
 * from cool to warm.
 */
class WeatherSheet(private val host: FrameLayout, private val n: WeatherData.Now) {
    private val ctx = host.context
    val root = FrameLayout(ctx).apply {
        isClickable = true
        isFocusable = true
        alpha = 0f
        translationZ = ctx.dp(200).toFloat()
        outlineProvider = null
    }
    private val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private var onDismiss: (() -> Unit)? = null
    private var onChangePlace: (() -> Unit)? = null
    private var leaving = false

    init {
        root.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(LiquidGlass.frostedSnapshot(host)) },
            FrameLayout.LayoutParams(-1, -1))
        // Neutral, like the widget: the frosted screen dimmed, no sky colour.
        root.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(110, 0, 0, 0), Color.argb(170, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, -1))
        card.addView(ctx.text(30f, weight = 500).apply { text = n.city; gravity = Gravity.CENTER })
        card.addView(ctx.text(84f, weight = 150).apply { text = "${n.temp}°"; gravity = Gravity.CENTER; includeFontPadding = false })
        card.addView(ctx.text(19f, weight = 600).apply { text = WeatherData.describe(n.code); gravity = Gravity.CENTER })
        card.addView(ctx.text(17f, Color.argb(220, 255, 255, 255), weight = 500).apply {
            text = "H:${n.hi}°  L:${n.lo}°   ·   Feels like ${n.feels}°   ·   Humidity ${n.humidity}%   ·   Wind ${n.wind} ${n.windUnit}"
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4) })
        val panelW = ctx.dp(620)
        val hoursRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            background = glassCapsule(ctx, ctx.dp(22).toFloat(), Color.argb(34, 255, 255, 255))
        }
        for (hr in n.hours) hoursRow.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ctx.text(13f, Color.argb(220, 255, 255, 255), weight = 600).apply { text = hr.label })
            addView(ImageView(ctx).apply { setImageDrawable(WeatherIcon(hr.code, hr.day, mono = true)) }, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)).apply { topMargin = ctx.dp(6) })
            if (hr.pop >= 20) addView(ctx.text(11f, Color.argb(200, 255, 255, 255), weight = 700).apply { text = "${hr.pop}%" })
            addView(ctx.text(16f, weight = 600).apply { text = "${hr.temp}°" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(hoursRow, LinearLayout.LayoutParams(panelW, -2).apply { topMargin = ctx.dp(22) })
        val daysBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(10))
            background = glassCapsule(ctx, ctx.dp(22).toFloat(), Color.argb(34, 255, 255, 255))
        }
        daysBox.addView(ctx.text(12f, Color.argb(170, 255, 255, 255), weight = 700).apply { text = "6-DAY FORECAST"; letterSpacing = 0.08f },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ctx.dp(4) })
        val weekLo = n.days.minOfOrNull { it.lo } ?: 0
        val weekHi = n.days.maxOfOrNull { it.hi } ?: 1
        for (d in n.days) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ctx.text(17f, weight = 600).apply { text = d.label }, LinearLayout.LayoutParams(ctx.dp(80), -2))
            row.addView(ImageView(ctx).apply { setImageDrawable(WeatherIcon(d.code, true, mono = true)) }, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
            row.addView(ctx.text(12f, Color.argb(200, 255, 255, 255), weight = 700).apply { text = if (d.pop >= 20) "${d.pop}%" else ""; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ctx.dp(44), -2))
            row.addView(ctx.text(17f, Color.argb(170, 255, 255, 255), weight = 600).apply { text = "${d.lo}°"; gravity = Gravity.END },
                LinearLayout.LayoutParams(ctx.dp(48), -2))
            row.addView(RangeBar(ctx, weekLo, weekHi, d.lo, d.hi, if (d.label == "Today") n.temp else null),
                LinearLayout.LayoutParams(0, ctx.dp(6), 1f).apply { marginStart = ctx.dp(14); marginEnd = ctx.dp(14) })
            row.addView(ctx.text(17f, weight = 600).apply { text = "${d.hi}°" }, LinearLayout.LayoutParams(ctx.dp(48), -2))
            daysBox.addView(row, LinearLayout.LayoutParams(-1, ctx.dp(42)))
        }
        card.addView(daysBox, LinearLayout.LayoutParams(panelW, -2).apply { topMargin = ctx.dp(14) })
        card.addView(ctx.text(14f, Color.argb(150, 255, 255, 255), weight = 500).apply {
            text = "Not your city? Press OK to change the location."
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(16) })
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun onDismiss(f: () -> Unit) = apply { onDismiss = f }

    /** OK on the sheet: the home screen opens the location settings. */
    fun onChangePlace(f: () -> Unit) = apply { onChangePlace = f }

    fun show(): WeatherSheet {
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.animate().alpha(1f).setDuration(220).start()
        card.scaleX = 0.92f; card.scaleY = 0.92f
        card.tweenScale(1f, 560, Motion.spring)
        for (k in 0 until card.childCount) card.getChildAt(k).apply {
            alpha = 0f; translationY = ctx.dp(16).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(60L + k * 45L).setDuration(460).setInterpolator(Motion.emphasized).start()
        }
        root.requestFocus()
        return this
    }

    val isShowing get() = root.parent != null

    fun dismiss() {
        if (leaving || !isShowing) return
        leaving = true
        card.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).setDuration(160).setInterpolator(Motion.exit).start()
        root.animate().alpha(0f).setDuration(200).withEndAction { host.removeView(root); onDismiss?.invoke() }.start()
    }

    /** A day's range on the week's scale, cool blue to warm orange, with today's temperature as a dot. */
    private class RangeBar(ctx: Context, val weekLo: Int, val weekHi: Int, val lo: Int, val hi: Int, val now: Int?) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = h / 2
            p.shader = null
            p.color = Color.argb(60, 0, 0, 0)
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
            val span = (weekHi - weekLo).coerceAtLeast(1).toFloat()
            val x0 = (lo - weekLo) / span * w
            val x1 = ((hi - weekLo) / span * w).coerceAtLeast(x0 + h)
            p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.rgb(94, 200, 250), Color.rgb(160, 220, 120), Color.rgb(255, 204, 0), Color.rgb(255, 140, 60)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(x0, 0f, x1, h, r, r, p)
            p.shader = null
            now?.let {
                val x = ((it - weekLo) / span * w).coerceIn(x0 + r, x1 - r)
                p.color = Color.argb(90, 0, 0, 0); c.drawCircle(x, r, r + 2f, p)
                p.color = Color.WHITE; c.drawCircle(x, r, r, p)
            }
        }
    }

    init {
        // One listener: OK changes the location when the home screen offers it (else closes), Back
        // closes, and every other key but Home stays with the sheet.
        root.setOnKeyListener { _, code, e ->
            val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (e.action == KeyEvent.ACTION_UP && !leaving && (ok || code == KeyEvent.KEYCODE_BACK)) {
                val change = onChangePlace
                if (ok && change != null) change() else dismiss()
                true
            } else code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_HOME
        }
    }
}
