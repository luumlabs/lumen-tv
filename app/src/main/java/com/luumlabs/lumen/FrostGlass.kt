package com.luumlabs.lumen

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View

/** Samples the same blurred wallpaper in screen coordinates, even while the owner scrolls. */
class FrostGlass(private val owner: View, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private val location = IntArray(2)
    private val path = Path()
    private var bitmap: Bitmap? = null
    private var texture: BitmapShader? = null
    /** Lifted and saturated, as the glass gathers the colour of what is behind it. */
    private val tone = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(1.35f)
        postConcat(ColorMatrix(floatArrayOf(0.86f, 0f, 0f, 0f, 24f, 0f, 0.86f, 0f, 0f, 24f, 0f, 0f, 0.86f, 0f, 24f, 0f, 0f, 0f, 1f, 0f)))
    })
    private var body: Shader? = null
    private var sheen: Shader? = null
    private var rim: Shader? = null
    var focused = false
        set(value) { field = value; invalidateSelf() }
    fun texture(image: Bitmap) {
        bitmap = image; texture = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); invalidateSelf()
    }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        body = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.argb(40, 255, 255, 255), Color.argb(14, 210, 224, 255), Color.argb(46, 12, 18, 34)), null, Shader.TileMode.CLAMP)
        sheen = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(60, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.5f), Shader.TileMode.CLAMP)
        rim = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.WHITE, Color.argb(50, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.argb(150, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2)
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        val save = canvas.save(); canvas.clipPath(path)
        bitmap?.let { b ->
            owner.getLocationInWindow(location)
            val screen = owner.rootView
            val scale = maxOf(screen.width.toFloat() / b.width, screen.height.toFloat() / b.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((screen.width - b.width * scale) / 2 - location[0], (screen.height - b.height * scale) / 2 - location[1])
            texture?.setLocalMatrix(matrix); paint.shader = texture; paint.alpha = 255
            paint.colorFilter = tone
            canvas.drawRect(rect, paint)
            paint.colorFilter = null
        }
        paint.shader = body
        paint.alpha = if (focused) 255 else 200
        canvas.drawRect(rect, paint)
        if (focused) { paint.color = Color.argb(70, 255, 255, 255); paint.shader = null; canvas.drawRect(rect, paint) }
        paint.shader = sheen; paint.alpha = 255
        canvas.drawRect(rect, paint); paint.shader = null
        canvas.restoreToCount(save)
        // The rim: lit at the top left, a softer reflection at the bottom right.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = if (focused) 3f else 1.5f
        paint.shader = rim
        paint.alpha = if (focused) 255 else 150
        rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null; paint.alpha = 255; paint.style = Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
