package org.bidon.rendering.mraid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placement decides what a creative is allowed to do, and it is now mutable so the same loaded
 * creative can be shown inline and then fullscreen. These pin that down.
 */
class MraidStateMachineTest {

    private fun machine(
        placement: MraidPlacementType
    ): Pair<MraidStateMachine, MutableList<MraidState>> {
        val seen = mutableListOf<MraidState>()
        return MraidStateMachine(placement) { seen += it } to seen
    }

    @Test
    fun `starts in loading`() {
        val (sm, _) = machine(MraidPlacementType.INLINE)
        assertEquals(MraidState.LOADING, sm.current)
    }

    @Test
    fun `loading only goes to default`() {
        val (sm, _) = machine(MraidPlacementType.INLINE)
        assertFalse(sm.transitionTo(MraidState.EXPANDED))
        assertEquals(MraidState.LOADING, sm.current)
        assertTrue(sm.transitionTo(MraidState.DEFAULT))
    }

    @Test
    fun `inline creative may expand and resize`() {
        val (sm, _) = machine(MraidPlacementType.INLINE)
        sm.transitionTo(MraidState.DEFAULT)
        assertTrue(sm.transitionTo(MraidState.EXPANDED))

        val (sm2, _) = machine(MraidPlacementType.INLINE)
        sm2.transitionTo(MraidState.DEFAULT)
        assertTrue(sm2.transitionTo(MraidState.RESIZED))
    }

    @Test
    fun `interstitial creative may not expand or resize - it already fills the screen`() {
        val (sm, _) = machine(MraidPlacementType.INTERSTITIAL)
        sm.transitionTo(MraidState.DEFAULT)
        assertFalse(sm.transitionTo(MraidState.EXPANDED))
        assertFalse(sm.transitionTo(MraidState.RESIZED))
        assertEquals(MraidState.DEFAULT, sm.current)
    }

    @Test
    fun `any placement may close`() {
        for (placement in MraidPlacementType.entries) {
            val (sm, _) = machine(placement)
            sm.transitionTo(MraidState.DEFAULT)
            assertTrue("$placement should allow close", sm.transitionTo(MraidState.HIDDEN))
        }
    }

    @Test
    fun `switching placement to interstitial takes expand away`() {
        // This is what showFullscreen does to a creative that was loaded for an inline slot.
        val (sm, _) = machine(MraidPlacementType.INLINE)
        sm.transitionTo(MraidState.DEFAULT)

        sm.placement = MraidPlacementType.INTERSTITIAL
        assertFalse(sm.transitionTo(MraidState.EXPANDED))

        sm.placement = MraidPlacementType.INLINE
        assertTrue(sm.transitionTo(MraidState.EXPANDED))
    }

    @Test
    fun `only real transitions are announced to the creative`() {
        val (sm, seen) = machine(MraidPlacementType.INTERSTITIAL)
        sm.transitionTo(MraidState.DEFAULT)
        sm.transitionTo(MraidState.EXPANDED) // refused
        assertEquals(listOf(MraidState.DEFAULT), seen)
    }
}
