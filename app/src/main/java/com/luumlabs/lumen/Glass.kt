package com.luumlabs.lumen

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Liquid Glass baked on the CPU. Android 12 has no runtime shaders, but the
 * home background is a still picture, so the glass can be computed once per
 * picture and then shown as a plain bitmap.
 */
object LiquidGlass {

    /**
     * Bakes a glass slab covering [rect] (screen pixels) over [source], which the
     * screen shows centre-cropped, with [overlay] drawn on top of it in screen
     * coordinates. Inside, the picture is softly blurred, brightened and made a
     * little more vivid; towards the rim it is pulled in from beyond the edge, the
     * way a thick rounded edge refracts, splitting slightly into its colours as
     * real glass disperses light. The rim is a crisp line lit from the top left
     * with a soft bevel glow inside it, and the lower edge darkens a touch, which
     * is what gives the slab its thickness.
     *
     * [clear] is the transparent variant of Liquid Glass: hardly blurred or tinted, so what is
     * behind reads through it, with a deeper lens at the rim and stronger highlights.
     *
     * What is behind is blurred at half resolution and sampled from there: once blurred it looks
     * the same, and it is a quarter of the work and memory, which matters on a TV's CPU.
     */
    fun bake(source: Bitmap, screenW: Int, screenH: Int, rect: Rect, radius: Float, overlay: Drawable? = null, clear: Boolean = false): Bitmap {
        val w = rect.width()
        val h = rect.height()
        // Match Canvas round-rect geometry for capsules, including when the requested radius exceeds half the short side.
        val cornerRadius = radius.coerceIn(0f, min(w, h) / 2f)
        val zone = if (clear) min(h * 0.36f, cornerRadius * 1.8f) else min(h * 0.3f, cornerRadius * 1.5f)
        val pull = zone * (if (clear) 1.1f else 0.95f)
        val blurR = if (clear) 2 else 3                 // at half resolution
        val margin = (pull * 1.2f + blurR * 6 + 4).toInt()

        // The part of the screen around the slab, as the user sees it, at half size.
        val x0 = rect.left - margin
        val y0 = rect.top - margin
        val cw = (w + margin * 2) / 2
        val ch = (h + margin * 2) / 2
        val region = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(region)
        canvas.drawColor(Color.BLACK)
        canvas.scale(0.5f, 0.5f)
        canvas.translate(-x0.toFloat(), -y0.toFloat())
        val s = max(screenW / source.width.toFloat(), screenH / source.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((screenW - source.width * s) / 2, (screenH - source.height * s) / 2)
        }
        canvas.drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG))
        overlay?.let { it.setBounds(0, 0, screenW, screenH); it.draw(canvas) }
        val src = IntArray(cw * ch)
        region.getPixels(src, 0, cw, 0, 0, cw, ch)
        region.recycle()
        // Three box passes come close to a Gaussian blur: frosted, without the boxy halo of two.
        repeat(if (clear) 2 else 3) { boxBlur(src, cw, ch, blurR) }

        val out = IntArray(w * h)
        val hw = w / 2f
        val hh = h / 2f
        val rimW = if (clear) 1.8f else 1.6f
        val bevel = min(cornerRadius * 0.5f, h * 0.14f).coerceAtLeast(4f)
        val shade = bevel * 1.4f
        val sat = if (clear) 1.12f else 1.28f
        val keep = if (clear) 0.95f else 0.84f
        val lift = if (clear) 10f else 32f
        for (j in 0 until h) {
            val py = j + 0.5f
            // A faint sheen over the upper part, as light falling on a curved top surface.
            val top = (1f - py / (h * 0.5f)).coerceAtLeast(0f)
            val sheen = top * top * (if (clear) 0.09f else 0.07f)
            for (i in 0 until w) {
                val px = i + 0.5f
                // Signed distance to the rounded rectangle (negative inside).
                val qx = abs(px - hw) - (hw - cornerRadius)
                val qy = abs(py - hh) - (hh - cornerRadius)
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val sdf = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - cornerRadius
                val a = (0.5f - sdf).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val depth = -sdf
                var nx: Float
                var ny: Float
                if (qx > 0f && qy > 0f) {
                    val l = sqrt(ox * ox + oy * oy).coerceAtLeast(1e-3f)
                    nx = ox / l; ny = oy / l
                } else if (qx > qy) { nx = 1f; ny = 0f } else { nx = 0f; ny = 1f }
                if (px < hw) nx = -nx
                if (py < hh) ny = -ny

                val t = (1f - depth / zone).coerceIn(0f, 1f)
                val off = t * t * pull
                // Coordinates in the half-size blurred region.
                val sx = (px + margin) * 0.5f
                val sy = (py + margin) * 0.5f
                var r: Float
                var g: Float
                var b: Float
                if (off > 0.75f) {
                    // Dispersion: red bends a little less than green, blue a little more.
                    val o = off * 0.5f
                    val cg = sample(src, cw, ch, sx + nx * o, sy + ny * o)
                    val cr = sample(src, cw, ch, sx + nx * o * 0.9f, sy + ny * o * 0.9f)
                    val cb = sample(src, cw, ch, sx + nx * o * 1.12f, sy + ny * o * 1.12f)
                    r = Color.red(cr).toFloat(); g = Color.green(cg).toFloat(); b = Color.blue(cb).toFloat()
                } else {
                    val c = sample(src, cw, ch, sx, sy)
                    r = Color.red(c).toFloat(); g = Color.green(c).toFloat(); b = Color.blue(c).toFloat()
                }
                // A little milky and a little more saturated than what is behind.
                val luma = r * 0.3f + g * 0.59f + b * 0.11f
                r = luma + (r - luma) * sat; g = luma + (g - luma) * sat; b = luma + (b - luma) * sat
                r = r * keep + lift; g = g * keep + lift; b = b * keep + lift
                if (sheen > 0f) { r += (255f - r) * sheen; g += (255f - g) * sheen; b += (255f - b) * sheen }

                // Light from the top left: strongest on the edge facing it, a softer reflection on
                // the opposite edge, where it bounces back through the glass.
                val facing = -(nx * 0.45f + ny * 0.9f)
                val lit = max(facing, 0f)
                val back = max(-facing, 0f)
                // Thickness: the lower rim sits in its own shadow.
                if (depth < shade && back > 0f) {
                    val k = (1f - depth / shade).let { it * it } * 0.2f * back
                    r *= 1f - k; g *= 1f - k; b *= 1f - k
                }
                // Bevel: a soft glow just inside the lit edge.
                if (depth < bevel) {
                    val k = (1f - depth / bevel).let { it * it } * (if (clear) 0.34f * lit + 0.12f * back else 0.26f * lit + 0.08f * back)
                    r += (255f - r) * k; g += (255f - g) * k; b += (255f - b) * k
                }
                // Specular rim, crisp and thin.
                if (depth < rimW) {
                    val k = (1f - depth / rimW).coerceIn(0f, 1f) * (0.3f + 0.62f * lit + 0.36f * back)
                    r += (255f - r) * k; g += (255f - g) * k; b += (255f - b) * k
                }
                out[j * w + i] = Color.argb((a * 255).toInt(), clamp(r), clamp(g), clamp(b))
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * What [v] shows right now, at [scale] of its size, blurred and made a little more vivid: the
     * frosted backdrop behind an alert or an overlay. Captured once, so it costs nothing per frame.
     * Null if the view isn't laid out or can't be drawn in software.
     */
    fun frostedSnapshot(v: View, scale: Float = 0.125f, blur: Int = 3): Bitmap? {
        if (v.width == 0 || v.height == 0) return null
        val w = (v.width * scale).toInt().coerceAtLeast(1)
        val h = (v.height * scale).toInt().coerceAtLeast(1)
        return try {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.BLACK)
            c.scale(scale, scale)
            v.draw(c)
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, blur) }
            for (k in px.indices) {
                val p = px[k]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
                val l = r * 0.3f + g * 0.59f + bl * 0.11f
                px[k] = Color.rgb(clamp(l + (r - l) * 1.35f), clamp(l + (g - l) * 1.35f), clamp(l + (bl - l) * 1.35f))
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
            b
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)

    /** Bilinear: the blurred picture is at half size, and nearest-pixel reads would show its grain. */
    private fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = (x - 0.5f).coerceIn(0f, (w - 1).toFloat())
        val fy = (y - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val tx = fx - x0; val ty = fy - y0
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]; val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        fun ch(shift: Int): Int {
            val a = ((c00 shr shift) and 255) * (1 - tx) + ((c10 shr shift) and 255) * tx
            val b = ((c01 shr shift) and 255) * (1 - tx) + ((c11 shr shift) and 255) * tx
            return (a * (1 - ty) + b * ty).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Separable box blur in place; three channels, opaque output. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = r * 2 + 1
        for (line in 0 until lines) {
            fun at(k: Int): Int {
                val p = k.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + p] else src[p * w + line]
            }
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val c = at(k); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (k in 0 until len) {
                val idx = if (horizontal) line * w + k else k * w + line
                dst[idx] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(k + r + 1)
                val sub = at(k - r)
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }

    /**
     * A soft drop shadow for a rounded card of [w]×[h], padded by [pad] on every
     * side so the blur has room. Drawn in software, where BlurMaskFilter works.
     */
    fun shadow(w: Int, h: Int, radius: Float, pad: Int): Bitmap {
        val b = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 0, 0)
            maskFilter = BlurMaskFilter(pad * 0.55f, BlurMaskFilter.Blur.NORMAL)
        }
        // A touch narrower than the card, so the shadow pools underneath rather than haloing.
        val inset = pad * 0.18f
        Canvas(b).drawRoundRect(RectF(pad + inset, pad.toFloat(), pad + w - inset, (pad + h).toFloat()), radius, radius, p)
        return b
    }
}

/**
 * The edge of a glass tile: a hairline that catches the light, bright on the top-left corner and
 * fainter where it reflects at the bottom right, drawn over the artwork. [strength] brightens it
 * on focus; [light] (-1 to 1) slides the bright corner sideways as the tile tilts.
 */
class GlassRim(private val radius: Float, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val rect = RectF()
    private val shift = Matrix()
    private var shader: android.graphics.Shader? = null

    var strength = 0.5f
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    var light = 0f
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        shader = android.graphics.LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(46, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.argb(120, 255, 255, 255)),
            floatArrayOf(0f, 0.32f, 0.68f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val s = shader ?: return
        rect.set(bounds)
        rect.inset(stroke / 2, stroke / 2)
        shift.setTranslate(light * bounds.width() * 0.35f, 0f)
        s.setLocalMatrix(shift)
        paint.shader = s
        paint.alpha = (255 * strength.coerceIn(0f, 1f)).toInt()
        val r = (radius - stroke / 2).coerceAtLeast(0f)
        c.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
