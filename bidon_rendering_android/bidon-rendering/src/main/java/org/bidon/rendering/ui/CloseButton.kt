package org.bidon.rendering.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import org.bidon.rendering.CloseButtonConfig
import kotlin.math.ceil
import kotlin.math.max

/**
 * The close button of a fullscreen ad, drawn the way the DSP's [CloseButtonConfig] asks.
 *
 * Until it becomes available it shows the seconds left, or nothing if the DSP turned the
 * countdown off, and taps on it do nothing. The visible button sits [CloseButtonConfig.paddingDp]
 * from the safe area in its corner, and that gap belongs to the button: a tap that lands between
 * the button and the edge closes the ad rather than reaching the creative. The touch target is
 * never smaller than [AdWindow.TOUCH_TARGET_DP] and grows toward the middle of the screen.
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
    private val paddingPx = dp(config.paddingDp).toFloat()

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
            resolveSize(ceil(max(touchTarget, shapeWidth + paddingPx)).toInt(), widthMeasureSpec),
            resolveSize(ceil(max(touchTarget, shapeHeight + paddingPx)).toInt(), heightMeasureSpec)
        )
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // The backdrop darkens while pressed; the colour is picked in onDraw.
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Drawn in from the outer corner of the touch target by the padding, so the gap to the
        // edge of the screen is still part of what can be tapped.
        val left = if (config.position.isLeft) paddingPx else width - paddingPx - shapeWidth
        val top = if (config.position.isTop) paddingPx else height - paddingPx - shapeHeight
        shape.set(left, top, left + shapeWidth, top + shapeHeight)

        val radius = shapeHeight / 2
        backdropPaint.color = backdropFor(config.color, isPressed)
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

        /** The cross spans half the button, the stroke an eighth of it: the look it had before. */
        const val CROSS_RATIO = 0.5f
        const val STROKE_RATIO = 0.125f
        const val TEXT_SIZE_RATIO = 0.45f
    }
}

/**
 * The disc behind the cross or text: dark behind a light colour and light behind a dark one, so
 * whatever colour the DSP picks stands out from it. Deeper while pressed.
 */
internal fun backdropFor(@ColorInt color: Int, pressed: Boolean): Int {
    val red = (color shr 16) and 0xFF
    val green = (color shr 8) and 0xFF
    val blue = color and 0xFF
    val isLight = 0.299 * red + 0.587 * green + 0.114 * blue >= 128
    val alpha = if (pressed) 0xCC else 0x80
    val shade = if (isLight) 0x000000 else 0xFFFFFF
    return (alpha shl 24) or shade
}

private const val STATE_CLOSE_AVAILABLE_AT = "org.bidon.rendering.close_available_at"

/** When the close button of the activity this state was saved from becomes usable, if saved. */
internal fun Bundle.closeAvailableAtMs(): Long? =
    if (containsKey(STATE_CLOSE_AVAILABLE_AT)) getLong(STATE_CLOSE_AVAILABLE_AT) else null

internal fun Bundle.putCloseAvailableAtMs(availableAtMs: Long) =
    putLong(STATE_CLOSE_AVAILABLE_AT, availableAtMs)

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
