package com.luumlabs.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The colour behind the grid, taken from the focused app: a soft mesh of its three colours, as
 * Apple's own backgrounds glow in the colours of what is on screen. Each colour change is a small
 * picture, made in a fraction of a millisecond and stretched over the screen, faded in over the
 * last; a fade interrupted half way is first folded into the picture underneath, so quick moves
 * along a row never jump. Costs one full-screen bitmap pass at rest, two while fading.
 */
class Ambient(ctx: Context) : FrameLayout(ctx) {
    private val under = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val over = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
    private var underBmp: Bitmap? = null
    private var overBmp: Bitmap? = null
    private val fade = Tween()
    private var shown: IntArray? = null

    /** What is on screen now (or about to be, at the end of the fade): the dock's glass refracts it. */
    val current: Bitmap? get() = overBmp ?: underBmp

    /** Called with the picture once a fade has settled (or been cleared): the widgets' glass follows. */
    var onSettled: ((Bitmap?) -> Unit)? = null

    init {
        addView(under, LayoutParams(-1, -1))
        addView(over, LayoutParams(-1, -1))
    }

    /** Fades to [colours] (vivid, deep, muted); false if they are already showing. */
    fun show(colours: IntArray, ms: Long = 650): Boolean {
        if (shown?.contentEquals(colours) == true) return false
        shown = colours
        val next = mesh(colours)
        if (fade.running) {
            fade.cancel()
            underBmp = blend(underBmp, overBmp, over.alpha)
            under.setImageBitmap(underBmp)
        }
        if (underBmp == null) {
            underBmp = next
            under.setImageBitmap(next)
            onSettled?.invoke(next)
            return true
        }
        overBmp = next
        over.setImageBitmap(next)
        over.alpha = 0f
        fade.start(ms, Motion.ease, end = {
            underBmp = overBmp
            under.setImageBitmap(underBmp)
            overBmp = null
            over.setImageDrawable(null)
            over.alpha = 0f
            onSettled?.invoke(underBmp)
        }) { p -> over.alpha = p.coerceIn(0f, 1f) }
        return true
    }

    /** Clears the colour (the setting was turned off). */
    fun clear() {
        fade.cancel()
        shown = null
        underBmp = null; overBmp = null
        under.setImageDrawable(null); over.setImageDrawable(null)
        onSettled?.invoke(null)
    }

    private fun blend(a: Bitmap?, b: Bitmap?, t: Float): Bitmap? {
        if (a == null || b == null) return b ?: a
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(a, 0f, 0f, p)
        p.alpha = (255 * t.coerceIn(0f, 1f)).toInt()
        c.drawBitmap(b, 0f, 0f, p)
        return out
    }

    companion object {
        private const val W = 160
        private const val H = 90

        /**
         * The mesh: a near-black ground tinted by the deep colour, the vivid one pooled at the top
         * left, the muted one top right, the deep one rising from the bottom, which then darkens
         * so the rows and the dock stay readable. Mostly opaque: the wallpaper only shows through
         * where the colour is thinnest.
         */
        fun mesh(colours: IntArray): Bitmap {
            val main = tame(colours[0])
            val deep = tame(colours.getOrElse(1) { colours[0] })
            val muted = tame(colours.getOrElse(2) { colours[0] })
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val w = W.toFloat()
            val h = H.toFloat()
            c.drawColor(Color.argb(218, Color.red(deep) / 5, Color.green(deep) / 5, Color.blue(deep) / 5))
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            fun blob(x: Float, y: Float, r: Float, col: Int, a: Int) {
                p.shader = RadialGradient(x, y, r,
                    intArrayOf(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)),
                        Color.argb(a * 2 / 5, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
            }
            blob(w * 0.12f, h * 0.05f, w * 0.78f, main, 220)
            blob(w * 0.96f, h * 0.22f, w * 0.62f, muted, 170)
            blob(w * 0.52f, h * 1.05f, w * 0.7f, deep, 200)
            p.shader = LinearGradient(0f, h * 0.5f, 0f, h, Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            return b
        }

        /** Pulls glaring colours down a little: near-white swatches would wash the screen out. */
        private fun tame(c: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv[2] = hsv[2].coerceAtMost(0.82f)
            // A white or grey icon has no hue to give; keep it a cool graphite instead of beige.
            if (hsv[1] < 0.12f) { hsv[0] = 220f; hsv[1] = 0.12f; hsv[2] = hsv[2].coerceAtMost(0.5f) }
            return Color.HSVToColor(hsv)
        }
    }
}
