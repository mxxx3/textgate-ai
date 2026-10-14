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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.textgate.ai.network.GeminiClient.DetectedTextBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive View that displays an image and renders translated text
 * directly overlaid on top of the original text bounding boxes.
 * Supports pinch-to-zoom, pan, double-tap zoom reset, and block selection.
 */
class ImageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            scaleFactor = 1.0f
            panX = 0f
            panY = 0f
            selectedBlock = null
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

    private var scaleFactor = 1.0f
    private var panX = 0f
    private var panY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1.0f, 4.0f)
            clampPan()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleFactor > 1.05f) {
                panX -= distanceX
                panY -= distanceY
                clampPan()
                invalidate()
                return true
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val pivotX = width / 2f
            val pivotY = height / 2f
            val mappedX = (e.x - panX - pivotX) / scaleFactor + pivotX
            val mappedY = (e.y - panY - pivotY) / scaleFactor + pivotY
            handleTap(mappedX, mappedY)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaleFactor > 1.2f) {
                scaleFactor = 1.0f
                panX = 0f
                panY = 0f
            } else {
                scaleFactor = 2.5f
                panX = (width / 2f - e.x) * (scaleFactor - 1f)
                panY = (height / 2f - e.y) * (scaleFactor - 1f)
                clampPan()
            }
            invalidate()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        computeImageBounds(bmp)

        val pivotX = width / 2f
        val pivotY = height / 2f

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scaleFactor, scaleFactor, pivotX, pivotY)

        canvas.drawBitmap(bmp, null, imageDstRect, null)

        if (showOverlay && blocks.isNotEmpty()) {
            drawOverlayBlocks(canvas)
        }

        canvas.restore()
    }

    private fun drawOverlayBlocks(canvas: Canvas) {
        val scaleX = imageDstRect.width() / 1000f
        val scaleY = imageDstRect.height() / 1000f

        val paddingX = dpToPx(4f)
        val paddingY = dpToPx(2.5f)
        val minPillHeight = dpToPx(16f)

        for (block in blocks) {
            val text = block.translatedText.ifBlank { block.originalText }.trim()
            if (text.isEmpty()) continue

            val rawLeft = imageDstRect.left + block.xmin * scaleX
            val rawTop = imageDstRect.top + block.ymin * scaleY
            val rawRight = imageDstRect.left + block.xmax * scaleX
            val rawBottom = imageDstRect.top + block.ymax * scaleY

            val rawWidth = (rawRight - rawLeft).coerceAtLeast(1f)
            val rawHeight = (rawBottom - rawTop).coerceAtLeast(1f)

            // Determine background & text colors
            val bgColor = parseColorOrFallback(block.bgColor, block.textColor == "light")
            bgPaint.color = bgColor

            val isDark = isColorDark(bgColor)
            val textColor = if (isDark) Color.WHITE else Color.parseColor("#0F172A")
            textPaint.color = textColor

            val basePillHeight = max(rawHeight + paddingY * 2, minPillHeight)
            val centerY = (rawTop + rawBottom) / 2f

            val maxAvailWidth = (imageDstRect.right - rawLeft + paddingX).coerceAtLeast(dpToPx(24f))

            // Initial candidate font size based on box height
            var candidateSize = (basePillHeight * 0.70f).coerceIn(dpToPx(8.5f), dpToPx(22f))
            textPaint.textSize = candidateSize
            textPaint.isFakeBoldText = (candidateSize <= dpToPx(12f))

            val singleLineWidth = textPaint.measureText(text)

            val layout: StaticLayout
            val pillWidth: Float
            val pillHeight: Float
            val pillLeft: Float
            val pillTop: Float
            val pillRight: Float
            val pillBottom: Float

            if (singleLineWidth + paddingX * 2 <= maxAvailWidth) {
                // Fits cleanly on a single line
                pillWidth = max(rawWidth + paddingX * 2, singleLineWidth + paddingX * 2).coerceAtMost(maxAvailWidth)
                pillHeight = basePillHeight
                pillLeft = (rawLeft - paddingX).coerceIn(imageDstRect.left, (imageDstRect.right - pillWidth).coerceAtLeast(imageDstRect.left))
                pillRight = pillLeft + pillWidth
                pillTop = centerY - pillHeight / 2f
                pillBottom = centerY + pillHeight / 2f

                layout = StaticLayout.Builder.obtain(
                    text,
                    0,
                    text.length,
                    textPaint,
                    (pillWidth - paddingX).toInt().coerceAtLeast(1)
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
            } else {
                // Paragraph or long sentence requiring line wrapping
                val wrapWidth = max(rawWidth + paddingX * 2, min(singleLineWidth + paddingX * 2, maxAvailWidth)).coerceAtMost(maxAvailWidth)
                var bestSize = candidateSize
                val minFontSize = dpToPx(7.5f)

                var bestLayout = StaticLayout.Builder.obtain(
                    text,
                    0,
                    text.length,
                    textPaint,
                    (wrapWidth - paddingX * 2).toInt().coerceAtLeast(1)
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()

                while (bestSize > minFontSize && bestLayout.lineCount > 3 && bestLayout.height > basePillHeight * 2f) {
                    bestSize -= 1.5f
                    textPaint.textSize = bestSize
                    textPaint.isFakeBoldText = (bestSize <= dpToPx(12f))
                    bestLayout = StaticLayout.Builder.obtain(
                        text,
                        0,
                        text.length,
                        textPaint,
                        (wrapWidth - paddingX * 2).toInt().coerceAtLeast(1)
                    )
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build()
                }

                layout = bestLayout
                pillWidth = wrapWidth
                pillHeight = max(basePillHeight, layout.height.toFloat() + paddingY * 2)
                pillLeft = (rawLeft - paddingX).coerceIn(imageDstRect.left, (imageDstRect.right - pillWidth).coerceAtLeast(imageDstRect.left))
                pillRight = pillLeft + pillWidth
                pillTop = centerY - pillHeight / 2f
                pillBottom = centerY + pillHeight / 2f
            }

            blockDrawRect.set(pillLeft, pillTop, pillRight, pillBottom)

            // Draw pill background
            val cornerRadius = dpToPx(4f)
            canvas.drawRoundRect(blockDrawRect, cornerRadius, cornerRadius, bgPaint)

            // Draw translated text centered inside the pill
            canvas.save()
            val textOffsetY = pillTop + (pillHeight - layout.height) / 2f
            val textOffsetX = pillLeft + paddingX
            canvas.translate(textOffsetX, textOffsetY)
            layout.draw(canvas)
            canvas.restore()

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        if (!showOverlay || blocks.isEmpty()) return

        val scaleX = imageDstRect.width() / 1000f
        val scaleY = imageDstRect.height() / 1000f

        var clicked: DetectedTextBlock? = null
        for (block in blocks) {
            val left = imageDstRect.left + block.xmin * scaleX - dpToPx(4f)
            val top = imageDstRect.top + block.ymin * scaleY - dpToPx(4f)
            val right = imageDstRect.left + block.xmax * scaleX + dpToPx(8f)
            val bottom = imageDstRect.top + block.ymax * scaleY + dpToPx(4f)

            if (x in left..right && y in top..bottom) {
                clicked = block
                break
            }
        }

        selectedBlock = clicked
        onBlockClickListener?.invoke(clicked)
        invalidate()
    }

    private fun clampPan() {
        if (scaleFactor <= 1.0f) {
            scaleFactor = 1.0f
            panX = 0f
            panY = 0f
            return
        }
        val maxPanX = width * (scaleFactor - 1f) / 2f
        val maxPanY = height * (scaleFactor - 1f) / 2f
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun parseColorOrFallback(hex: String?, isDarkBg: Boolean): Int {
        if (!hex.isNullOrBlank()) {
            try {
                val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
                return Color.parseColor(cleanHex)
            } catch (_: Exception) {
            }
        }
        return if (isDarkBg) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
    }

    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
