package org.bidon.rendering.mraid

/**
 * MRAID 2.0/3.0 state machine.
 *
 * Allowed transitions:
 *   loading  → default
 *   default  → expanded | resized | hidden
 *   expanded → default
 *   resized  → default | hidden
 *   hidden   → default
 */
internal enum class MraidState {
    LOADING, DEFAULT, EXPANDED, RESIZED, HIDDEN;

    /** Returns the string value sent to mraid.js via fireStateChangeEvent */
    fun toJs(): String = name.lowercase()
}

internal enum class MraidPlacementType {
    INLINE, INTERSTITIAL;

    fun toJs(): String = when (this) {
        INLINE -> "inline"
        INTERSTITIAL -> "interstitial"
    }
}

internal class MraidStateMachine(
    placement: MraidPlacementType,
    private val onStateChanged: (MraidState) -> Unit
) {

    /**
     * Mutable because the same loaded creative can be shown inline and then fullscreen. Placement
     * only decides whether expand and resize are permitted, so switching it is enough; the
     * creative itself is never rebuilt.
     */
    var placement: MraidPlacementType = placement

    var current: MraidState = MraidState.LOADING
        private set

    fun transitionTo(next: MraidState): Boolean {
        val allowed = allowedTransitions(current, placement)

        if (next !in allowed) {
            return false
        }

        current = next
        onStateChanged(next)
        return true
    }

    private fun allowedTransitions(
        from: MraidState,
        placement: MraidPlacementType
    ): Set<MraidState> = when (from) {

        MraidState.LOADING -> setOf(MraidState.DEFAULT)

        MraidState.DEFAULT -> buildSet {
            add(MraidState.HIDDEN)
            // Only inline banners can expand or resize
            if (placement == MraidPlacementType.INLINE) {
                add(MraidState.EXPANDED)
                add(MraidState.RESIZED)
            }
        }

        MraidState.EXPANDED -> setOf(MraidState.DEFAULT)

        MraidState.RESIZED -> setOf(MraidState.DEFAULT, MraidState.HIDDEN)

        MraidState.HIDDEN -> setOf(MraidState.DEFAULT)
    }
}