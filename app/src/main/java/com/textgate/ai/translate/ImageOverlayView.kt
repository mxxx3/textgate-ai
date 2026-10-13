package com.textgate.ai.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.textgate.ai.network.GeminiClient.DetectedTextBlock
import kotlin.math.min

/**
 * Interactive View that displays an image and renders translated text
 * directly overlaid on top of the original text bounding boxes.
 */
class ImageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var blocks: List<DetectedTextBlock> = emptyList()
        set(value) {
            field = value
            selectedBlock = null
            invalidate()
        }

    var showOverlay: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var selectedBlock: DetectedTextBlock? = null
        set(value) {
            field = value
            invalidate()
        }

    var onBlockClickListener: ((DetectedTextBlock?) -> Unit)? = null

    private val imageDstRect = RectF()
    private val blockDrawRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        color = Color.parseColor("#2563EB")
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
    }

    private var downX = 0f
    private var downY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        computeImageBounds(bmp)
        canvas.drawBitmap(bmp, null, imageDstRect, null)

        if (!showOverlay || blocks.isEmpty()) return

        val scaleX = imageDstRect.width() / 1000f
        val scaleY = imageDstRect.height() / 1000f

        for (block in blocks) {
            val left = imageDstRect.left + block.xmin * scaleX
            val top = imageDstRect.top + block.ymin * scaleY
            val right = imageDstRect.left + block.xmax * scaleX
            val bottom = imageDstRect.top + block.ymax * scaleY

            // Add 1.5dp slight expansion to ensure complete coverage of underlying text
            val padding = dpToPx(1.5f)
            blockDrawRect.set(left - padding, top - padding, right + padding, bottom + padding)

            // Determine background color
            val bgColor = parseColorOrFallback(block.bgColor, block.textColor == "light")
            bgPaint.color = bgColor

            val cornerRadius = dpToPx(4f)
            canvas.drawRoundRect(blockDrawRect, cornerRadius, cornerRadius, bgPaint)

            // Draw translated text
            val textColor = if (isColorDark(bgColor)) Color.WHITE else Color.parseColor("#111827")
            textPaint.color = textColor

            drawFittedText(
                canvas = canvas,
                text = block.translatedText,
                rect = blockDrawRect
            )

            // Highlight selected block
            if (block == selectedBlock) {
                canvas.drawRoundRect(blockDrawRect, cornerRadius, cornerRadius, strokePaint)
            }
        }
    }

    private fun computeImageBounds(bmp: Bitmap) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        val bmpWidth = bmp.width.toFloat()
        val bmpHeight = bmp.height.toFloat()

        val scale = min(viewWidth / bmpWidth, viewHeight / bmpHeight)
        val destWidth = bmpWidth * scale
        val destHeight = bmpHeight * scale

        val left = (viewWidth - destWidth) / 2f
        val top = (viewHeight - destHeight) / 2f

        imageDstRect.set(left, top, left + destWidth, top + destHeight)
    }

    private fun drawFittedText(canvas: Canvas, text: String, rect: RectF) {
        val width = rect.width() - dpToPx(4f)
        val height = rect.height() - dpToPx(2f)
        if (width <= 4 || height <= 4) return

        // Auto-scale font size
        var fontSize = min(height * 0.85f, dpToPx(22f))
        val minFontSize = dpToPx(8f)

        var staticLayout: StaticLayout? = null
        while (fontSize >= minFontSize) {
            textPaint.textSize = fontSize
            staticLayout = StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                textPaint,
                width.toInt().coerceAtLeast(1)
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()

            if (staticLayout.height <= height) {
                break
            }
            fontSize -= 1.5f
        }

        if (staticLayout == null) return

        canvas.save()
        // Clip to the bounding box to avoid any overflow
        canvas.clipRect(rect)
        // Center text vertically inside the box
        val offsetY = rect.top + (rect.height() - staticLayout.height) / 2f
        val offsetX = rect.left + dpToPx(2f)
        canvas.translate(offsetX, offsetY)
        staticLayout.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                if (dx < dpToPx(10f) && dy < dpToPx(10f)) {
                    handleTap(event.x, event.y)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        if (!showOverlay || blocks.isEmpty()) return

        val scaleX = imageDstRect.width() / 1000f
        val scaleY = imageDstRect.height() / 1000f

        var clicked: DetectedTextBlock? = null
        for (block in blocks) {
            val left = imageDstRect.left + block.xmin * scaleX
            val top = imageDstRect.top + block.ymin * scaleY
            val right = imageDstRect.left + block.xmax * scaleX
            val bottom = imageDstRect.top + block.ymax * scaleY

            if (x in left..right && y in top..bottom) {
                clicked = block
                break
            }
        }

        selectedBlock = clicked
        onBlockClickListener?.invoke(clicked)
        invalidate()
    }

    private fun parseColorOrFallback(hex: String?, isDarkBg: Boolean): Int {
        if (!hex.isNullOrBlank()) {
            try {
                val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
                return Color.parseColor(cleanHex)
            } catch (_: Exception) {
            }
        }
        return if (isDarkBg) Color.parseColor("#1E1E1E") else Color.parseColor("#F3F4F6")
    }

    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
