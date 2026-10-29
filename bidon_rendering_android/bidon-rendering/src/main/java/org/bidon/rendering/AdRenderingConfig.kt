package org.bidon.rendering

import androidx.annotation.ColorInt
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How the DSP wants one ad rendered.
 *
 * It comes with each bid, so two ads in the same session can differ. That is why it is handed to
 * the renderer created for the ad rather than set once at init like [RendererConfig].
 *
 * Only the settings the renderer acts on are modelled. The server's object carries more (end
 * cards, store kit, background and so on), and those are ignored until something renders them.
 *
 * A host gets one only from [fromJson]. The constructors stay inside the library, so a field can
 * be added without breaking a host built against an older version, and nothing outside can get
 * past the limits.
 */
@ConsistentCopyVisibility
data class AdRenderingConfig internal constructor(
    val closeButton: CloseButtonConfig = CloseButtonConfig(),

    /**
     * When a VAST video becomes skippable if the VAST document does not say. A skip offset in the
     * document wins: it is set for this creative, while this is the DSP's general setting.
     */
    val skipOffsetMs: Long = DEFAULT_SKIP_OFFSET_MS,

    /**
     * The creative type the DSP declared, spelled as the bid carries it: one of `html`, `mraid`,
     * `vast`, `static_image`, `native` and `playable`, or `null` when it declared none of them.
     */
    internal val declaredType: String? = null,

    internal val impressionTracking: ImpressionTracking = ImpressionTracking()
) {
    init {
        require(skipOffsetMs >= 0) { "skipOffsetMs must not be negative, was $skipOffsetMs" }
        require(declaredType == null || declaredType in DECLARABLE_TYPES) {
            "declaredType must be one of $DECLARABLE_TYPES, was $declaredType"
        }
    }

    /**
     * The renderer the declared type settles when the markup cannot: HTML or MRAID. VAST markup is
     * recognised on its own, and any other type counts as none.
     */
    internal val declaredCreativeType: CreativeType?
        get() = when (declaredType) {
            "mraid" -> CreativeType.MRAID
            "html" -> CreativeType.HTML
            else -> null
        }

    companion object {
        /** The server's default, used when neither the VAST document nor the DSP sets one. */
        const val DEFAULT_SKIP_OFFSET_MS: Long = 15_000

        /**
         * Reads the `rendering` object of a bid.
         *
         * Never throws. The server validates the config, but this is the last stop before the
         * user's screen: anything absent, of the wrong type or unknown falls back to its default,
         * and a number out of range is clamped to the nearest allowed value.
         */
        fun fromJson(json: JSONObject?): AdRenderingConfig {
            val container = json?.optJSONObject("container")
            val creative = json?.optJSONObject("creative")
            return AdRenderingConfig(
                closeButton = closeButtonFrom(json?.optJSONObject("close_button")),
                skipOffsetMs = container.millis(
                    "skip_offset_seconds",
                    default = DEFAULT_SKIP_OFFSET_MS,
                    max = Long.MAX_VALUE
                ),
                declaredType = (creative?.opt("type") as? String)?.takeIf { it in DECLARABLE_TYPES },
                impressionTracking = impressionTrackingFrom(
                    container?.optJSONObject("impression_tracking")
                )
            )
        }
    }
}

/**
 * The close button of a fullscreen ad.
 *
 * There is deliberately no way to hide it. A fullscreen ad the user cannot leave breaks store
 * policy and the MRAID spec, so the limits below keep the button reachable and visible whatever
 * the DSP asks for.
 */
@ConsistentCopyVisibility
data class CloseButtonConfig internal constructor(
    /** How long after the ad appears before the button can be used. */
    val delayMs: Long = 5_000,

    /** Whether the seconds left are shown where the button will appear. */
    val showCountdown: Boolean = true,

    val style: Style = Style.ICON_X,

    /** Size of the visible button. The touch target never shrinks below Android's 48 dp. */
    val sizeDp: Int = 30,

    val position: Position = Position.TOP_RIGHT,

    /** Colour of the cross, ring or text, as an opaque ARGB int. */
    @get:ColorInt val color: Int = 0xFFFFFFFF.toInt(),

    /** Opacity of the whole button. */
    val opacity: Float = 0.9f,

    /** Gap between the visible button and the edge of the safe area. */
    val paddingDp: Int = 12
) {
    init {
        require(delayMs in 0..MAX_DELAY_MS) { "delayMs must be within 0..$MAX_DELAY_MS, was $delayMs" }
        require(sizeDp in MIN_SIZE_DP..MAX_SIZE_DP) {
            "sizeDp must be within $MIN_SIZE_DP..$MAX_SIZE_DP, was $sizeDp"
        }
        require(opacity in MIN_OPACITY..1f) { "opacity must be within $MIN_OPACITY..1, was $opacity" }
        require(paddingDp in 0..MAX_PADDING_DP) {
            "paddingDp must be within 0..$MAX_PADDING_DP, was $paddingDp"
        }
        require(color ushr 24 == 0xFF) { "color must be opaque, was 0x%08X".format(color) }
    }

    /**
     * The disc or pill behind is black or white, whichever contrasts more with [color]. More
     * styles may be added.
     */
    enum class Style {
        /** A cross on a disc. */
        ICON_X,

        /** A cross on a disc, ringed in the button colour. */
        ICON_CIRCLE,

        /** The word "Close" on a pill. */
        TEXT
    }

    /** Absolute corners: a DSP that asks for top right gets top right in right-to-left apps too. */
    enum class Position { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    companion object {
        const val MAX_DELAY_MS: Long = 30_000
        const val MIN_SIZE_DP: Int = 16
        const val MAX_SIZE_DP: Int = 48
        const val MIN_OPACITY: Float = 0.5f
        const val MAX_PADDING_DP: Int = 32
    }
}

/**
 * When an impression counts as viewable. Carried for viewability-gated impressions (BID-52);
 * nothing acts on it yet.
 */
internal data class ImpressionTracking(
    val minViewablePercent: Int = 50,
    val minViewableMs: Long = 1_000
) {
    init {
        require(minViewablePercent in 0..100) {
            "minViewablePercent must be within 0..100, was $minViewablePercent"
        }
        require(minViewableMs >= 0) { "minViewableMs must not be negative, was $minViewableMs" }
    }
}

private fun closeButtonFrom(json: JSONObject?): CloseButtonConfig {
    val default = CloseButtonConfig()
    json ?: return default

    // `visible` is not read: the button is always shown.
    return CloseButtonConfig(
        delayMs = json.millis(
            "delay_seconds",
            default = default.delayMs,
            max = CloseButtonConfig.MAX_DELAY_MS
        ),
        showCountdown = json.optBoolean("countdown_visible", default.showCountdown),
        style = when (json.optString("style")) {
            "icon_circle" -> CloseButtonConfig.Style.ICON_CIRCLE
            "text_close" -> CloseButtonConfig.Style.TEXT
            // "custom" asks for an image from the DSP, which could leave the button invisible.
            else -> default.style
        },
        sizeDp = json.dp(
            "size_dp",
            CloseButtonConfig.MIN_SIZE_DP..CloseButtonConfig.MAX_SIZE_DP,
            default.sizeDp
        ),
        position = when (json.optString("position")) {
            "top_left" -> CloseButtonConfig.Position.TOP_LEFT
            "bottom_left" -> CloseButtonConfig.Position.BOTTOM_LEFT
            "bottom_right" -> CloseButtonConfig.Position.BOTTOM_RIGHT
            else -> default.position
        },
        color = parseHexColor(json.optString("color")) ?: default.color,
        opacity = json.number("opacity")
            ?.coerceIn(CloseButtonConfig.MIN_OPACITY.toDouble(), 1.0)
            ?.toFloat()
            ?: default.opacity,
        paddingDp = json.dp("padding_dp", 0..CloseButtonConfig.MAX_PADDING_DP, default.paddingDp)
    )
}

private fun impressionTrackingFrom(json: JSONObject?): ImpressionTracking {
    val default = ImpressionTracking()
    return ImpressionTracking(
        minViewablePercent = json.number("min_viewable_pct")?.let { roundedWithin(it, 0..100) }
            ?: default.minViewablePercent,
        minViewableMs = json.millis(
            "min_viewable_seconds",
            default = default.minViewableMs,
            max = Long.MAX_VALUE
        )
    )
}

/** The creative types a DSP can declare in a bid, spelled as the bid carries them. */
private val DECLARABLE_TYPES = setOf("html", "mraid", "vast", "static_image", "native", "playable")

/** `#RGB` or `#RRGGBB`, the two forms the server accepts, as an opaque ARGB int. */
internal fun parseHexColor(value: String): Int? {
    if (!value.startsWith('#')) return null
    val digits = value.substring(1)
    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null

    val rgb = when (digits.length) {
        3 -> digits.map { "$it$it" }.joinToString("")
        6 -> digits
        else -> return null
    }
    return rgb.toInt(16) or OPAQUE
}

private const val OPAQUE = 0xFF000000.toInt()

/** The number at [key], or null when it is absent or not a number. */
private fun JSONObject?.number(key: String): Double? =
    this?.optDouble(key, Double.NaN)?.takeUnless { it.isNaN() }

/** A number of seconds at [key] as milliseconds within 0..[max], or [default] when there is none. */
private fun JSONObject?.millis(key: String, default: Long, max: Long): Long =
    number(key)?.let { millisWithin(it, max) } ?: default

private fun JSONObject?.dp(key: String, range: IntRange, default: Int): Int =
    number(key)?.let { roundedWithin(it, range) } ?: default

// The clamping, apart from the JSON so it can be tested with infinities: Android's org.json reads
// the string "Infinity" as a number, while the JVM one the tests run on does not.

internal fun millisWithin(seconds: Double, max: Long): Long =
    (seconds * 1000).coerceIn(0.0, max.toDouble()).roundToLong()

internal fun roundedWithin(value: Double, range: IntRange): Int =
    value.coerceIn(range.first.toDouble(), range.last.toDouble()).roundToInt()
