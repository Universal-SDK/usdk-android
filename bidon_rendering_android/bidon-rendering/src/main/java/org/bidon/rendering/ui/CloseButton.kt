package org.bidon.rendering.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.bidon.rendering.CloseButtonConfig
import kotlin.math.ceil
import kotlin.math.max

/**
 * The close button of a fullscreen ad, drawn the way the DSP's [CloseButtonConfig] asks.
 *
 * Until it becomes available it shows the seconds left, or nothing if the DSP turned the
 * countdown off, and taps on it do nothing. The visible button sits [CloseButtonConfig.paddingDp]
 * from the safe area in its corner. The touch target around it is never smaller than
 * [AdWindow.TOUCH_TARGET_DP] and grows toward the middle of the screen, so a small button is
 * still easy to hit.
 */
@SuppressLint("ViewConstructor")
internal class CloseButton(
    context: Context,
    private val config: CloseButtonConfig
) : View(context) {

    var onClick: (() -> Unit)? = null

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.color
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val shapeHeight = dp(config.sizeDp).toFloat()
    private val shapeWidth: Float
    private val shape = RectF()

    private var availableAtMs = 0L
    private var onAvailable: (() -> Unit)? = null
    private var secondsLeft = 0

    init {
        alpha = config.opacity
        contentDescription = "Close ad"
        strokePaint.strokeWidth = shapeHeight * STROKE_RATIO
        textPaint.textSize = shapeHeight * TEXT_SIZE_RATIO
        shapeWidth = when (config.style) {
            CloseButtonConfig.Style.TEXT -> textPaint.measureText(LABEL) + shapeHeight
            else -> shapeHeight
        }

        setOnClickListener { onClick?.invoke() }
        isEnabled = false
        visibility = INVISIBLE
    }

    /** Layout parameters that put the button in its corner of a [FrameLayout]. */
    fun cornerLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        config.position.gravity
    )

    /**
     * Makes the button usable at [availableAtMs], a [SystemClock.elapsedRealtime] time, counting
     * down until then. An activity recreated mid-countdown passes the same time again, so the
     * countdown carries on instead of starting over.
     */
    fun becomeAvailableAt(availableAtMs: Long, onAvailable: () -> Unit) {
        this.availableAtMs = availableAtMs
        this.onAvailable = onAvailable
        removeCallbacks(tick)
        tick.run()
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            // Never longer than the delay itself, whatever time was handed in.
            val remainingMs = (availableAtMs - SystemClock.elapsedRealtime())
                .coerceAtMost(config.delayMs)

            if (remainingMs <= 0) {
                secondsLeft = 0
                isEnabled = true
                visibility = VISIBLE
                invalidate()
                onAvailable?.invoke()
                onAvailable = null
                return
            }

            secondsLeft = Countdown.secondsLeft(remainingMs)
            visibility = if (config.showCountdown) VISIBLE else INVISIBLE
            invalidate()
            postDelayed(this, Countdown.untilNextTick(remainingMs))
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val touchTarget = dp(AdWindow.TOUCH_TARGET_DP).toFloat()
        setMeasuredDimension(
            resolveSize(ceil(max(touchTarget, shapeWidth)).toInt(), widthMeasureSpec),
            resolveSize(ceil(max(touchTarget, shapeHeight)).toInt(), heightMeasureSpec)
        )
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // The backdrop darkens while pressed; the colour is picked in onDraw.
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Drawn in the corner of the touch target rather than its middle, so the padding is
        // measured to what the user actually sees.
        val left = if (config.position.isLeft) 0f else width - shapeWidth
        val top = if (config.position.isTop) 0f else height - shapeHeight
        shape.set(left, top, left + shapeWidth, top + shapeHeight)

        val radius = shapeHeight / 2
        backdropPaint.color = if (isPressed) BACKDROP_PRESSED else BACKDROP
        canvas.drawRoundRect(shape, radius, radius, backdropPaint)

        when {
            secondsLeft > 0 -> drawCentred(canvas, secondsLeft.toString())
            config.style == CloseButtonConfig.Style.TEXT -> drawCentred(canvas, LABEL)
            else -> {
                drawCross(canvas)
                if (config.style == CloseButtonConfig.Style.ICON_CIRCLE) {
                    val ring = radius - strokePaint.strokeWidth / 2
                    canvas.drawCircle(shape.centerX(), shape.centerY(), ring, strokePaint)
                }
            }
        }
    }

    private fun drawCross(canvas: Canvas) {
        val arm = shapeHeight * CROSS_RATIO / 2
        val cx = shape.centerX()
        val cy = shape.centerY()
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, strokePaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, strokePaint)
    }

    private fun drawCentred(canvas: Canvas, text: String) {
        val baseline = shape.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, shape.centerX(), baseline, textPaint)
    }

    private companion object {
        const val LABEL = "Close"
        const val BACKDROP = 0x80000000.toInt()
        const val BACKDROP_PRESSED = 0xCC000000.toInt()

        /** The cross spans half the button, the stroke an eighth of it: the look it had before. */
        const val CROSS_RATIO = 0.5f
        const val STROKE_RATIO = 0.125f
        const val TEXT_SIZE_RATIO = 0.45f
    }
}

/** The countdown arithmetic, kept apart from the view so it can be tested on the JVM. */
internal object Countdown {

    /** Whole seconds to show for [remainingMs]: with 4.3 s left the button shows 5. */
    fun secondsLeft(remainingMs: Long): Int = ((remainingMs + 999) / 1000).toInt()

    /** How long until the number shown next changes. */
    fun untilNextTick(remainingMs: Long): Long = remainingMs - (secondsLeft(remainingMs) - 1) * 1000L
}

// Absolute sides on purpose: the DSP asked for a corner of the screen, not a reading direction.
@get:SuppressLint("RtlHardcoded")
private val CloseButtonConfig.Position.gravity: Int
    get() = when (this) {
        CloseButtonConfig.Position.TOP_LEFT -> Gravity.TOP or Gravity.LEFT
        CloseButtonConfig.Position.TOP_RIGHT -> Gravity.TOP or Gravity.RIGHT
        CloseButtonConfig.Position.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.LEFT
        CloseButtonConfig.Position.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.RIGHT
    }

private val CloseButtonConfig.Position.isLeft: Boolean
    get() = this == CloseButtonConfig.Position.TOP_LEFT ||
        this == CloseButtonConfig.Position.BOTTOM_LEFT

private val CloseButtonConfig.Position.isTop: Boolean
    get() = this == CloseButtonConfig.Position.TOP_LEFT ||
        this == CloseButtonConfig.Position.TOP_RIGHT
