package com.luumlabs.lumen

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Rect
import android.view.View
import com.luumlabs.lumen.tv.TvControls

/**
 * tvOS-style settings: the title centred on top, a large icon on the left with a description of
 * the focused row under it, and on the right a list that starts, like the Apple TV's, with a few
 * categories, each opening a page of its own. Rows are plain text at rest; the focused one turns
 * into a lifted white pane, and the list glides to keep it near the middle of the screen.
 */
class SettingsActivity : Activity() {
    companion object {
        const val SONY_DREAM = "com.sony.dtv.braviashow/com.sony.dtv.braviashow.BraviaDayDreamService"
        const val AVATAR_FILE = "profile_avatar.png"
        fun avatarFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, AVATAR_FILE).takeIf { it.isFile }
        const val ICON_FILE = "settings_icon.png"
        const val BACKDROP_FILE = "settings_bg.jpg"
        private const val NEED_ADB = "Authorize local debugging first"

        /**
         * A picture of the user's own behind the whole settings page (Apple TV's, a TV on a blue haze,
         * say), with the TV where the big icon would be; uploaded from the phone page or pushed over adb.
         */
        fun backdropFile(ctx: android.content.Context): java.io.File? =
            listOfNotNull(java.io.File(ctx.filesDir, BACKDROP_FILE), ctx.getExternalFilesDir(null)?.let { java.io.File(it, BACKDROP_FILE) })
                .firstOrNull { it.isFile && it.length() > 0 }

        /**
         * A picture of the user's own for the settings icon (Apple TV's, say), uploaded from the phone
         * page or pushed over adb to Android/data/com.luumlabs.lumen/files; null for the built-in gear.
         * Like the boot video, it is not part of the app.
         */
        fun customIcon(ctx: android.content.Context): java.io.File? = if (Prefs(ctx).ownIcon) iconFile(ctx) else null

        /** The uploaded picture whether or not it is in use ([Prefs.ownIcon]). */
        fun iconFile(ctx: android.content.Context): java.io.File? =
            listOfNotNull(java.io.File(ctx.filesDir, ICON_FILE), ctx.getExternalFilesDir(null)?.let { java.io.File(it, ICON_FILE) })
                .firstOrNull { it.isFile && it.length() > 0 }

        /** The icon decoded at no more than about [width] px wide: a 1280 px picture shown at 600 needn't take 6 MB. */
        fun decodeIcon(f: java.io.File, width: Int): android.graphics.Bitmap? {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= width) sample *= 2
            return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private val IDLE_TEXT = Color.argb(235, 255, 255, 255)
        private val IDLE_VALUE = Color.argb(140, 255, 255, 255)
        private val FOCUS_VALUE = Color.argb(150, 0, 0, 0)
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepo
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView
    private lateinit var about: TextView
    private lateinit var icon: ImageView
    private lateinit var backdrop: ImageView
    /** The home wallpaper, blurred, behind the page when no picture is set for it. */
    private lateinit var wash: ImageView
    private lateinit var scroll: ScrollView
    private var bundledBackdrop = false
    private var page = Page.MAIN
    /** Which row opened each page, focused again on the way back to its parent. */
    private val openers = HashMap<Page, Int>()
    /** Every row's value line and how to read it, so the page can catch up after another screen. */
    private val values = ArrayList<Triple<TextView, () -> String, Array<String>>>()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private enum class Page(val title: String, val parent: Page?) {
        MAIN("Settings", null),
        APPEARANCE("Appearance", MAIN),
        WIDGETS("Widgets", MAIN),
        BACKGROUND("Wallpaper & Screen Saver", MAIN),
        INTRO("Boot Animation", MAIN),
        APPS("Apps", MAIN),
        MEMORY("Memory & Power", MAIN),
        SYSTEM("System", MAIN),
        KEEP("Keep When Freeing Memory", MEMORY),
        HIDDEN("Hidden Apps", APPS),
        REMOVED("Removed Preinstalled Apps", APPS),
    }

    /** A ScrollView that brings the focused row to just above the middle, as tvOS lists do. */
    private class CenteringScrollView(ctx: android.content.Context) : ScrollView(ctx) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
            if (childCount == 0) return 0
            val view = height - paddingTop - paddingBottom
            val max = (getChildAt(0).height + paddingTop + paddingBottom - height).coerceAtLeast(0)
            val target = rect.centerY() - view * 0.42f - paddingTop
            return (target.toInt() - scrollY).coerceIn(-scrollY, max - scrollY)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepo(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(22, 22, 26)) }
        wash = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wash, FrameLayout.LayoutParams(-1, -1))
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeBackdrop(v as ImageView) }
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        heading = text(30f, weight = 600).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.argb(90, 0, 0, 0))
        }
        root.addView(heading, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(38) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(84), dp(32), 0)
        }
        icon = ImageView(this).apply {
            showIcon(this)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipRounded(dp(20).toFloat())
            clipToOutline = true
            foreground = GlassRim(dp(20).toFloat(), resources.displayMetrics.density * 1.2f).apply { strength = 0.6f }
            elevation = dp(14).toFloat()
        }
        left.addView(icon, LinearLayout.LayoutParams(-1, dp(207)))
        about = text(15f, Color.argb(170, 255, 255, 255)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.3f)
            setShadowLayer(dp(6).toFloat(), 0f, 0f, Color.argb(110, 0, 0, 0))
        }
        left.addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
        showBackdrop()
        body.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        // Not clipped, so a focused row can grow past the list's edges.
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        scroll = CenteringScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isSmoothScrollingEnabled = true
            clipToPadding = false
            clipChildren = false
            setPadding(dp(14), dp(18), dp(14), dp(90))
            addView(list)
        }
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1.05f).apply { marginEnd = dp(52) })
        root.addView(body, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(84) })
        setContentView(root)
        show(Page.MAIN, animate = false)
    }

    override fun onResume() {
        super.onResume()
        // Values may have changed elsewhere (the wallpaper picker, the phone page): catch up, but
        // leave alone a row still showing the progress of something it started.
        for ((v, value, last) in values) {
            if (v.text.toString() != last[0]) continue
            val now = value()
            last[0] = now
            v.text = now
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Builds [p]; [dir] 1 slides it in from the right (deeper), -1 from the left (back). */
    private fun show(p: Page, focusIndex: Int = 0, dir: Int = 1, animate: Boolean = true) {
        page = p
        list.removeAllViews()
        values.clear()
        heading.text = p.title
        about.text = ""
        when (p) {
            Page.MAIN -> main()
            Page.APPEARANCE -> appearance()
            Page.WIDGETS -> widgets()
            Page.BACKGROUND -> background()
            Page.INTRO -> intro()
            Page.APPS -> apps()
            Page.MEMORY -> memory()
            Page.SYSTEM -> system()
            Page.KEEP -> appPicker(prefs.keep) { pkg, on ->
                prefs.keep = if (on) prefs.keep + pkg else prefs.keep - pkg
            }
            Page.HIDDEN -> appPicker(prefs.hidden, includeHidden = true) { pkg, on ->
                prefs.hidden = if (on) prefs.hidden + pkg else prefs.hidden - pkg
            }
            Page.REMOVED -> removedApps()
        }
        scroll.scrollTo(0, 0)
        if (animate) {
            list.alpha = 0f
            list.translationX = dir * dp(48).toFloat()
            list.animate().alpha(1f).translationX(0f).setDuration(420).setInterpolator(Motion.emphasized).start()
            heading.alpha = 0f
            heading.animate().alpha(1f).setDuration(320).setInterpolator(Motion.ease).start()
        }
        list.post {
            val target = list.getChildAt(focusIndex.coerceIn(0, maxOf(list.childCount - 1, 0)))
            (target?.takeIf { it.isFocusable } ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable })
                ?.requestFocus()
        }
    }

    /** The row last pressed on the current page. */
    private var clicked = 0

    /** Opens [p] from the row just pressed, which is focused again on the way back. */
    private fun open(p: Page) {
        openers[p] = clicked
        show(p, 0, 1)
    }

    /** The uploaded background, whose TV stands in for the big icon; or the home wallpaper, blurred. RGB_565: half the memory. */
    private fun showBackdrop() {
        val file = if (prefs.settingsBg) backdropFile(this) else null
        bundledBackdrop = prefs.settingsBg && file == null
        val bmp = file?.let {
            android.graphics.BitmapFactory.decodeFile(it.path, android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            })
        } ?: if (bundledBackdrop) android.graphics.BitmapFactory.decodeResource(resources, R.drawable.default_settings_bg,
            android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }) else null
        backdrop.setImageBitmap(bmp)
        placeBackdrop(backdrop)
        icon.visibility = if (bmp != null) View.INVISIBLE else View.VISIBLE
        // Below the TV in the picture, which reaches a little lower than the icon would.
        (about.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (bmp != null) 62 else 26)
        about.requestLayout()
        if (bmp != null) { wash.setImageDrawable(null); return }
        io.execute {
            val image = if (Wallpapers.isPhoto(prefs.background)) {
                val walls = Wallpapers(this, Wallpapers.sourceFor(prefs.background))
                walls.current()?.let { walls.forHome(it) }
            } else null
            val blurred = Wallpapers.blurred(image ?: Backgrounds.render(if (Wallpapers.isPhoto(prefs.background)) "green" else prefs.background), darken = 0.5f)
            runOnUiThread {
                if (isDestroyed || backdrop.drawable != null) return@runOnUiThread
                wash.setImageBitmap(blurred)
                wash.alpha = 0f
                wash.animate().alpha(1f).setDuration(400).start()
            }
        }
    }

    /**
     * Fills the screen, 8% larger than needed and against the right edge: a subject in the left
     * third of a 16:9 picture (where the icon would be) then sits over the description column.
     */
    private fun placeBackdrop(v: ImageView) {
        val d = v.drawable ?: return
        if (v.width == 0 || d.intrinsicWidth <= 0) return
        // The bundled artwork sits further left, clear of the focused right-hand buttons.
        val s = maxOf(v.width / d.intrinsicWidth.toFloat(), v.height / d.intrinsicHeight.toFloat()) * if (bundledBackdrop) 1.12f else 1.08f
        v.imageMatrix = android.graphics.Matrix().apply {
            setScale(s, s)
            postTranslate(v.width - d.intrinsicWidth * s, (v.height - d.intrinsicHeight * s) / 2)
        }
    }

    private fun showIcon(v: ImageView) {
        val own = customIcon(this)?.let { decodeIcon(it, dp(380)) }
        if (own != null) v.setImageBitmap(own) else v.setImageResource(R.drawable.banner_settings)
    }

    // ------------------------------------------------------------------- pages

    private fun main() {
        row("Appearance", "Clock, focus animation, Control Center style and the colour behind your apps.", { "" }, chevron = true) { open(Page.APPEARANCE) }
        row("Widgets", "The weather and this week's Top 10, on glass above the dock.", {
            listOfNotNull("Weather".takeIf { prefs.widgetWeather }, "Top 10".takeIf { prefs.widgetCharts }).joinToString(", ").ifEmpty { "Off" }
        }, chevron = true) { open(Page.WIDGETS) }
        row("Wallpaper & Screen Saver", "The home screen background, how often it changes, and the photo frame that shows when the TV is idle.", {
            Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background))
        }, chevron = true) { open(Page.BACKGROUND) }
        row("Boot Animation", "What plays when the home screen first appears after the TV starts or wakes.", {
            when (prefs.intro) { Prefs.INTRO_BOOT -> "On boot"; Prefs.INTRO_WAKE -> "Boot and wake"; else -> "Off" }
        }, chevron = true) { open(Page.INTRO) }
        row("Apps", "App order, hidden apps and preinstalled apps you removed.", { "" }, chevron = true) { open(Page.APPS) }
        row("Memory & Power", "Freeing memory, apps that are always kept, and what the power button does.", {
            "${Cleaner.availMb(this)} MB free"
        }, chevron = true) { open(Page.MEMORY) }
        row("System", "Local debugging, the home screen guardian and the TV's own settings.", { "" }, chevron = true) { open(Page.SYSTEM) }
        spacer()
        row("Software Updates", "Check for new Lumen and MiracastReceiver versions, install the casting component, or turn on automatic updates.", { "" }, chevron = true) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        row("About", "Lumen TV, a calm tvOS-inspired home screen for Android TV. Based on Tigris by lesnolie (MIT licence). Not affiliated with Apple.",
            { "Version " + packageManager.getPackageInfo(packageName, 0).versionName }) {}
    }

    private fun appearance() {
        section("Clock")
        row("Time format", "Whether the clock on the home screen and in Control Center uses 12-hour (11:49 AM) or 24-hour (23:49) time.",
            { if (prefs.clock24) "24-hour" else "12-hour" }) { prefs.clock24 = !prefs.clock24 }
        row("Clock position", "A small clock in the top-right corner, a large clock with the date in the top-left (the earlier style), or no clock at all.", {
            when (prefs.clockStyle) { Prefs.CLOCK_LARGE -> "Large, top left"; Prefs.CLOCK_OFF -> "Off"; else -> "Top right" }
        }) {
            val opts = listOf(Prefs.CLOCK_CORNER, Prefs.CLOCK_LARGE, Prefs.CLOCK_OFF)
            prefs.clockStyle = opts[(opts.indexOf(prefs.clockStyle) + 1) % opts.size]
        }
        section("Home Screen")
        row("Colour follows the focused app", "The background glows in the colours of the selected app, and the dock's glass refracts them. Off: the wallpaper shows as it is.",
            { onOff(prefs.appTint) }) { prefs.appTint = !prefs.appTint }
        row("Focus animation", "tvOS: the selected app lifts on a spring, tilts with the direction, its layers shift in depth, and it keeps drifting gently while you rest. Simple: it only grows." +
            "\n“Auto” uses Simple on lower-end TVs.", {
            auto(prefs.focusStyle, prefs.focusTvos(this), "tvOS", "Simple")
        }) {
            prefs.focusStyle = next(prefs.focusStyle, Prefs.FOCUS_TVOS, Prefs.FOCUS_SIMPLE)
        }
        row("Control Center style", "Glass: frosted glass that shows the image behind, and the selected button lights up its neighbours. Panel: the earlier dark panel, easier on performance." +
            "\n“Auto” uses Panel on lower-end TVs.", {
            auto(prefs.ccStyle, prefs.ccGlass(this), "Glass", "Panel")
        }) {
            prefs.ccStyle = next(prefs.ccStyle, Prefs.CC_GLASS, Prefs.CC_PANEL)
        }
        section("Profile & Settings")
        row("Upload avatar", "Scan the QR code with your phone, upload a photo under “Avatar” on the web page, then return to the home screen.", { "From phone" }, chevron = true) {
            startActivity(Intent(this, WallpaperActivity::class.java).putExtra("upload_avatar", true))
        }
        row("Show avatar in the top-right corner", "Press the avatar in the top-right corner to open Settings.", { onOff(prefs.showAvatar) }) { prefs.showAvatar = !prefs.showAvatar }
        row("Settings background", "A picture behind this screen (your uploaded one, or the built-in artwork), or your home wallpaper, blurred.", {
            if (prefs.settingsBg) (if (backdropFile(this) != null) "Uploaded picture" else "Built-in picture") else "Blurred wallpaper"
        }) {
            prefs.settingsBg = !prefs.settingsBg
            showBackdrop()
        }
        if (iconFile(this) != null) row("Settings icon", "The icon for this entry: your own uploaded image, or the built-in gear.", {
            if (prefs.ownIcon) "Uploaded image" else "Gear"
        }) {
            prefs.ownIcon = !prefs.ownIcon
            showIcon(icon)
        }
    }

    private fun widgets() {
        section("Weather")
        row("Weather widget", "The temperature, the sky and the next hours above the dock. Press OK on it for the six-day forecast. Weather data by Open-Meteo.",
            { onOff(prefs.widgetWeather) }) { prefs.widgetWeather = !prefs.widgetWeather }
        row("Temperature", "Celsius or Fahrenheit. “Auto” follows the TV's region.", {
            when (prefs.weatherUnit) { "c" -> "Celsius"; "f" -> "Fahrenheit"; else -> "Auto (${if (WeatherData.fahrenheit(this)) "°F" else "°C"})" }
        }) { prefs.weatherUnit = when (prefs.weatherUnit) { Prefs.AUTO -> "c"; "c" -> "f"; else -> Prefs.AUTO } }
        row("Location", "Found from the TV's internet address; press OK to look again. To name a city instead, run: adb shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver --es weather_city \"Madrid\"", {
            prefs.weatherCity.ifEmpty { Geo.cachedCity(this)?.let { "Automatic ($it)" } ?: "Automatic" }
        }) { v ->
            v.text = "Locating…"
            io.execute {
                Geo.forget(applicationContext)
                val place = Geo.place(applicationContext)
                if (place != null) WeatherData.load(applicationContext, force = true)
                runOnUiThread { v.text = place?.let { prefs.weatherCity.ifEmpty { "Automatic (${it.city})" } } ?: "Can't tell where the TV is" }
            }
        }
        section("Top 10")
        row("Top 10 widget", "This week's most popular movies and shows, and the best reviewed of them, with scores from IMDb, Rotten Tomatoes, TMDB and JustWatch, the cast and where to watch. Press OK on it to browse. Data by JustWatch.",
            { onOff(prefs.widgetCharts) }) { prefs.widgetCharts = !prefs.widgetCharts }
        row("Region", "Which country's charts to show. “Auto” uses where the TV is.", { prefs.chartsCountry.ifEmpty { "Auto" } }) {
            val opts = listOf("", "US", "GB", "CA", "AU", "IE", "ES", "MX", "AR", "CO", "CL", "PE", "BR", "PT", "FR", "DE", "IT", "NL", "SE", "IN", "JP", "KR")
            prefs.chartsCountry = opts[(opts.indexOf(prefs.chartsCountry).coerceAtLeast(0) + 1) % opts.size]
        }
    }

    private fun background() {
        section("Wallpaper")
        row("Background", "Home screen background: a theme, a wallpaper source, or your own photos.", {
            Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background)).let {
                if (prefs.background == Wallpapers.MINE) it else "$it (rotates)"
            }
        }, chevron = true) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        row("Photo rotation interval", "How often the rotating background changes.", { minutesLabel(prefs.wallMinutes) }) {
            val opts = listOf(15, 30, 60, 180, 1440)
            prefs.wallMinutes = opts[(opts.indexOf(prefs.wallMinutes) + 1) % opts.size]
        }
        row("Change now", "Fetch a new photo from the wallpaper source now. Photos are downloaded when shown, and old ones are cleaned up after a few days.",
            { "${Wallpapers(this, Wallpapers.sourceFor(prefs.background)).files().size} saved" }) { v ->
            v.text = "Downloading…"
            Thread {
                val w = Wallpapers(applicationContext, Wallpapers.sourceFor(prefs.background))
                w.refresh()
                w.advance(prefs.wallMinutes, force = true)
                runOnUiThread { v.text = "${w.files().size} saved" }
            }.start()
        }
        section("Screen Saver")
        row("Show frame when the TV is idle", "After the TV has been idle for a while, loop photos or aerial videos full screen instead of the built-in screensaver.",
            { onOff(prefs.frameScreensaver) }) { v ->
            val on = !prefs.frameScreensaver
            val current = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()
            val mine = "$packageName/${PhotoDream::class.java.name}"
            // Whatever the TV had before goes back when the frame is turned off (Sony's own if unknown).
            if (on && !current.startsWith(packageName)) prefs.prevScreensaver = current
            val comp = if (on) mine else prefs.prevScreensaver.ifEmpty { SONY_DREAM }
            v.text = "Setting up…"
            Thread {
                val ok = putSecure("screensaver_components", comp) && putSecure("screensaver_enabled", "1")
                if (ok) prefs.frameScreensaver = on
                runOnUiThread { v.text = if (ok) onOff(prefs.frameScreensaver) else NEED_ADB }
            }.start()
        }
        row("Screensaver content", "Whether the frame shows background photos or Apple TV aerial videos.",
            { if (prefs.saverMode == Prefs.SAVER_AERIAL) "Aerial videos" else "Photos" }) {
            prefs.saverMode = if (prefs.saverMode == Prefs.SAVER_AERIAL) Prefs.SAVER_PHOTOS else Prefs.SAVER_AERIAL
        }
        row("Start after", "How long without input before the frame appears.", { minutesLabel(idleMinutes()) }) { v ->
            val opts = listOf(1, 3, 5, 10, 15, 30)
            val next = opts[(opts.indexOf(idleMinutes()) + 1) % opts.size]
            v.text = "Setting up…"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "settings put system screen_off_timeout ${next * 60_000}") != null
                runOnUiThread { v.text = if (ok) minutesLabel(next) else NEED_ADB }
            }.start()
        }
        row("Time per photo", "How long each photo is shown in the frame.", { "${prefs.frameSeconds} s" }) {
            val opts = listOf(15, 30, 60, 120)
            prefs.frameSeconds = opts[(opts.indexOf(prefs.frameSeconds) + 1) % opts.size]
        }
    }

    private fun intro() {
        val video = Intro.videoFile(this) != null
        row("Boot animation", "Plays the first time the home screen appears after boot; the logo zooms into the desktop at the end. Press any key to skip." +
            if (video) "" else "\nPlays the built-in boot video by default; you can also upload your own.", {
            when (prefs.intro) { Prefs.INTRO_BOOT -> "On boot"; Prefs.INTRO_WAKE -> "On boot and wake"; else -> "Off" }
        }) {
            val opts = listOf(Prefs.INTRO_OFF, Prefs.INTRO_BOOT, Prefs.INTRO_WAKE)
            prefs.intro = opts[(opts.indexOf(prefs.intro) + 1) % opts.size]
            // This boot counts as played: turning it on doesn't start it on the next return home.
            prefs.introBoot = Intro.bootStamp(this)
        }
        row("Animation content", "Uploaded video: for example a boot animation of your own, with the logo zooming in at the end. Lumen wordmark: the wordmark fades in and then zooms." +
            if (video) "" else "\nWithout an uploaded video, video mode uses the built-in animation.", {
            if (prefs.introSource == Prefs.INTRO_WORDMARK) "Lumen wordmark" else if (video) "Uploaded video" else "Built-in video"
        }) {
            prefs.introSource = if (prefs.introSource == Prefs.INTRO_WORDMARK) Prefs.INTRO_VIDEO else Prefs.INTRO_WORDMARK
        }
        row("Wait after wake", "When waking from standby, stay black for this long before playing the animation. Many TVs (especially OLED) take a few seconds to actually light up; increase it if you miss the start of the animation, decrease it if the black screen lasts too long.", {
            if (prefs.wakeDelay == 0) "No wait" else "${prefs.wakeDelay / 1000.0} s".replace(".0 ", " ")
        }) {
            val opts = listOf(0, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 10_000)
            prefs.wakeDelay = opts.firstOrNull { it > prefs.wakeDelay } ?: 0
        }
        row("Sound", "Play the audio from the animation video.", { onOff(prefs.introSound) }) { prefs.introSound = !prefs.introSound }
        row("Preview", "Play it once now, back on the home screen.", { "" }, chevron = true) {
            startActivity(Intent(this, HomeActivity::class.java)
                .putExtra(Intro.EXTRA_PREVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun apps() {
        row("Move recently opened apps to the front", "When on, an app or folder that opens successfully moves to the front. When off, the current order is kept and only “Edit Home Screen” (hold OK on an app) changes it.",
            { onOff(prefs.recentFirst) }) { prefs.recentFirst = !prefs.recentFirst }
        row("Hidden apps", "Apps that don't appear on the home screen.", { "${prefs.hidden.size}" }, chevron = true) { open(Page.HIDDEN) }
        row("Removed preinstalled apps", "Preinstalled apps removed from the long-press menu can be restored here.", { "${prefs.removed.size}" }, chevron = true) {
            open(Page.REMOVED)
        }
    }

    private fun memory() {
        section("Memory")
        row("Free memory now", "Close background apps now.", { "${Cleaner.availMb(this)} MB available" }) { v ->
            v.text = "Freeing…"
            Thread {
                val r = Cleaner.clean(applicationContext)
                runOnUiThread { v.text = r.description() }
            }.start()
        }
        row("Free memory automatically in standby", "In standby, only cached processes are cleaned; apps are not force-stopped. Skipped when the foreground app can't be confirmed.", { onOff(prefs.cleanOnStandby) }) {
            prefs.cleanOnStandby = !prefs.cleanOnStandby
        }
        row("Scheduled memory freeing", "Periodically clean cached processes without force-stopping apps. Off by default on new installs.", {
            if (prefs.cleanIntervalHours == 0) "Off" else "Every ${prefs.cleanIntervalHours} h"
        }) {
            val opts = listOf(0, 2, 4, 8)
            prefs.cleanIntervalHours = opts[(opts.indexOf(prefs.cleanIntervalHours) + 1) % opts.size]
            KeeperService.start(this, KeeperService.ACTION_RESCHEDULE)
        }
        row("Apps kept when freeing memory", "Apps that won't be closed when freeing memory, such as casting and music.", { "${prefs.keep.size}" }, chevron = true) {
            open(Page.KEEP)
        }
        section("Power")
        row("When you press power on the remote", "Standby: as usual. Full power off: saves power, but boot takes longer.", {
            if (prefs.shutdownOnPowerKey) "Full power off" else "Standby"
        }) {
            prefs.shutdownOnPowerKey = !prefs.shutdownOnPowerKey
            if (prefs.shutdownOnPowerKey && !prefs.adbAuthorized)
                Toast.makeText(this, "Requires local debugging authorization to take effect", Toast.LENGTH_LONG).show()
        }
    }

    private fun system() {
        row("Local debugging authorization", "Let the home screen use local debugging for actions like power off and freeing memory. Choose “Always allow” in the dialog.",
            { if (prefs.adbAuthorized) "Authorized" else "Not authorized" }) { v ->
            v.text = "Choose “Always allow” in the dialog"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "echo ok", 60_000) != null
                runOnUiThread { v.text = if (ok) "Authorized" else "Not authorized — press OK to retry" }
            }.start()
        }
        // Only where the TV keeps Home for its own launcher; on Sony or stock Android TV Lumen is the real home.
        if (Guard.needed(this)) row("Home screen guardian", "When the Home key takes you to the stock launcher, come back here automatically.",
            { if (!prefs.guard) "Off" else if (HomeGuardService.isRunning) "On" else "On (not running)" }) { v ->
            val on = !prefs.guard
            v.text = "Setting up…"
            Thread {
                val ok = (Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)) && Guard.setEnabled(applicationContext, on)
                runOnUiThread {
                    v.text = if (!ok) NEED_ADB else if (on) "On" else "Off"
                    if (ok && on) Toast.makeText(this, "The Home key will bring you back here", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        if (Guard.isXiaomi() || prefs.keepAdb) row("Keep ADB debugging on", "Prevent the system from turning ADB debugging off.", { onOff(prefs.keepAdb) }) { v ->
            val on = !prefs.keepAdb
            v.text = "Setting up…"
            Thread {
                val ok = !on || Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)
                if (ok) { prefs.keepAdb = on; Guard.ensureAdb(applicationContext) }
                runOnUiThread { v.text = if (ok) onOff(prefs.keepAdb) else NEED_ADB }
            }.start()
        }
        row("TV settings", "Open the TV's built-in settings.", { "" }, chevron = true) { runCatching { startActivity(TvControls(this).settingsIntent()) } }
    }

    private fun appPicker(selected: Set<String>, includeHidden: Boolean = false, toggle: (String, Boolean) -> Unit) {
        val explain = if (page == Page.KEEP) "The selected apps won't be closed when memory is freed." else "The selected apps won't appear on the home screen."
        about.text = explain
        val apps = if (includeHidden) repo.allApps().sortedBy { it.label } else repo.allApps().filter { !repo.isSystem(it.pkg) }.sortedBy { it.label }
        val sel = selected.toMutableSet()
        for (a in apps) {
            row(a.label, explain, { if (a.pkg in sel) "✓" else "" }) { v ->
                val on = a.pkg !in sel
                if (on) sel += a.pkg else sel -= a.pkg
                toggle(a.pkg, on)
                v.text = if (on) "✓" else ""
            }
        }
    }

    /** Preinstalled apps deleted from the long-press menu; selecting one reinstalls it from the system partition. */
    private fun removedApps() {
        val explain = "Pick an app to reinstall it from the system partition."
        about.text = explain
        if (prefs.removed.isEmpty()) row("No removed apps", explain, { "" }) {}
        for (pkg in prefs.removed.sorted()) {
            val label = try {
                packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(packageManager).toString()
            } catch (e: Exception) { pkg }
            row(label, explain, { "Restore" }) { v ->
                v.text = "Restoring…"
                Thread {
                    val ok = LocalAdb.shell(applicationContext, "cmd package install-existing $pkg")?.contains("installed") == true
                    if (ok) prefs.removed = prefs.removed - pkg
                    runOnUiThread { v.text = if (ok) "Restored" else "Restore failed" }
                }.start()
            }
        }
    }

    /** A small grey group title above a run of rows. */
    private fun section(title: String) {
        val first = list.childCount == 0
        list.addView(text(15f, Color.argb(130, 255, 255, 255), weight = 600).apply {
            text = title
            setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.argb(90, 0, 0, 0))
        }, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(20); topMargin = if (first) 0 else dp(24); bottomMargin = dp(8)
        })
    }

    /** A gap between groups of rows on the main page. */
    private fun spacer() = list.addView(View(this), LinearLayout.LayoutParams(-1, dp(22)))

    /** The description under the icon, cross-faded when it changes. */
    private fun describe(desc: String) {
        if (about.text == desc) return
        about.animate().cancel()
        if (about.text.isEmpty()) {
            about.text = desc
            about.alpha = 0f
            about.animate().alpha(1f).setDuration(220).start()
            return
        }
        about.animate().alpha(0f).setDuration(110).withEndAction {
            about.text = desc
            about.animate().alpha(1f).setDuration(220).start()
        }.start()
    }

    /**
     * One settings row; [value] is re-read after each click, [desc] shows under the icon while
     * the row is focused, and [chevron] marks a row that opens another screen. [onClick] gets the
     * value line, which it may set to show progress; it is then left alone until the work ends.
     */
    private fun row(label: String, desc: String?, value: () -> String, chevron: Boolean = false, onClick: (TextView) -> Unit) {
        val r = object : LinearLayout(this) {
            override fun setPressed(pressed: Boolean) {
                val was = isPressed
                super.setPressed(pressed)
                if (pressed != was && isFocused) tweenScale(if (pressed) 1.0f else 1.04f, if (pressed) 110 else 380, if (pressed) Motion.ease else Motion.spring)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(22), 0, dp(if (chevron) 16 else 22), 0)
        }
        val l = text(18f, IDLE_TEXT, weight = 500).apply {
            text = label
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val first = value()
        val last = arrayOf(first)
        val v = text(17f, IDLE_VALUE).apply { text = first; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        values += Triple(v, value, last)
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        val arrow = if (chevron) text(26f, IDLE_VALUE, weight = 300).apply { text = "›" } else null
        arrow?.let { r.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10); bottomMargin = dp(2) }) }
        // Plain text at rest, as on tvOS; the focused row becomes a white pane lifted on a shadow.
        val idle = rounded(Color.TRANSPARENT, dp(16).toFloat())
        val focus = rounded(Color.WHITE, dp(16).toFloat())
        r.background = idle
        r.setOnFocusChangeListener { _, f ->
            r.background = if (f) focus else idle
            l.setTextColor(if (f) Color.BLACK else IDLE_TEXT)
            v.setTextColor(if (f) FOCUS_VALUE else IDLE_VALUE)
            arrow?.setTextColor(if (f) FOCUS_VALUE else IDLE_VALUE)
            if (f && desc != null) describe(desc)
            r.tweenScale(if (f) 1.04f else 1f, if (f) 440 else 240, if (f) Motion.spring else Motion.soft, z = if (f) dp(12).toFloat() else 0f)
        }
        r.setOnClickListener {
            val before = v.text.toString()
            clicked = list.indexOfChild(r)
            onClick(v)
            // Unless the click put up a progress line of its own, show the new value.
            if (v.text.toString() == before && r.isAttachedToWindow) {
                val now = value()
                last[0] = now
                if (now != before) {
                    v.text = now
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(200).start()
                }
            }
        }
        list.addView(r, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) })
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    /** "Auto (…)" with what auto picks on this TV, or the forced choice. */
    private fun auto(pref: String, on: Boolean, yes: String, no: String) =
        if (pref == Prefs.AUTO) "Auto (${if (on) yes else no})" else if (on) yes else no

    /** The next of Auto → [a] → [b]. */
    private fun next(pref: String, a: String, b: String) = when (pref) { Prefs.AUTO -> a; a -> b; else -> Prefs.AUTO }

    /** A secure setting, written directly once WRITE_SECURE_SETTINGS is granted, otherwise through local adb. Blocking. */
    private fun putSecure(key: String, value: String): Boolean =
        if (Guard.hasSecureSettings(this)) runCatching { Settings.Secure.putString(contentResolver, key, value) }.getOrDefault(false)
        else LocalAdb.shell(applicationContext, "settings put secure $key $value") != null

    private fun minutesLabel(m: Int) = when {
        m >= 1440 -> "Daily"
        m >= 60 -> "${m / 60} h"
        else -> "$m min"
    }

    /** On Android TV the screensaver starts after the "screen off" timeout. */
    private fun idleMinutes() = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val parent = page.parent
        if (keyCode == KeyEvent.KEYCODE_BACK && parent != null) {
            show(parent, openers[page] ?: 0, dir = -1)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && page.parent != null) true else super.onKeyDown(keyCode, event)
}
