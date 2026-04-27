/*package org.bidon.rendering.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

class CloseButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000") // напівпрозорий чорний
    }

    private val path = Path()

    var onClick: (() -> Unit)? = null

    private var isPressed = false

    init {
        // Обробка натискань
        setOnClickListener {
            onClick?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val size = minOf(width, height) * 0.6f

        // Фон (коло)
        canvas.drawCircle(centerX, centerY, size / 2 + 8, backgroundPaint)

        // Хрестик (дві лінії)
        path.reset()

        // Лінія 1: зліва-зверху направо-вниз
        path.moveTo(centerX - size / 2, centerY - size / 2)
        path.lineTo(centerX + size / 2, centerY + size / 2)

        // Лінія 2: справа-зверху наліво-вниз
        path.moveTo(centerX + size / 2, centerY - size / 2)
        path.lineTo(centerX - size / 2, centerY + size / 2)

        canvas.drawPath(path, paint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                backgroundPaint.color = Color.parseColor("#CC000000") // темніший фон
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                backgroundPaint.color = Color.parseColor("#80000000") // повертаємо початковий колір
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Встановити колір хрестика
     */
    fun setCrossColor(color: Int) {
        paint.color = color
        invalidate()
    }

    /**
     * Встановити колір фону
     */
    fun setBtnBackgroundColor(color: Int) {
        backgroundPaint.color = color
        invalidate()
    }

    /**
     * Встановити розмір кнопки (в dp)
     */
    fun setSize(dp: Int) {
        val pixels = (dp * resources.displayMetrics.density).toInt()
        layoutParams = layoutParams.apply {
            width = pixels
            height = pixels
        }
        requestLayout()
    }
}*/



package org.bidon.rendering.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CloseButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 🎨 Colors
    private val defaultBgColor = Color.parseColor("#80000000")
    private val pressedBgColor = Color.parseColor("#CC000000")
    private val crossColor = Color.WHITE

    // 🎨 Paints
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = crossColor
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = defaultBgColor
    }

    private val path = Path()
    var onClick: (() -> Unit)? = null

    private var isPressedState = false

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Close ad"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minSize = (48 * resources.displayMetrics.density).toInt()

        val width = resolveSize(minSize, widthMeasureSpec)
        val height = resolveSize(minSize, heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val size = minOf(w, h) * 0.5f

        canvas.drawCircle(cx, cy, size, backgroundPaint)

        path.reset()

        path.moveTo(cx - size / 2, cy - size / 2)
        path.lineTo(cx + size / 2, cy + size / 2)

        path.moveTo(cx + size / 2, cy - size / 2)
        path.lineTo(cx - size / 2, cy + size / 2)

        canvas.drawPath(path, crossPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                isPressedState = true
                backgroundPaint.color = pressedBgColor
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isPressedState) {
                    performClick()
                }

                isPressedState = false
                backgroundPaint.color = defaultBgColor
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressedState = false
                backgroundPaint.color = defaultBgColor
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClick?.invoke()
        return true
    }

    fun setCrossColor(color: Int) {
        crossPaint.color = color
        invalidate()
    }

    fun setBackgroundColorCustom(color: Int) {
        backgroundPaint.color = color
        invalidate()
    }

    fun setSizeDp(dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        layoutParams = layoutParams.apply {
            width = px
            height = px
        }
        requestLayout()
    }
}