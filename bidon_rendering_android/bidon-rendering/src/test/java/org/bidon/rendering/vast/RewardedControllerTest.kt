package org.bidon.rendering.vast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decides whether a rewarded video earned its reward.
 *
 * The trap worth knowing: the threshold is full completion, and a player's progress ticks do not
 * land exactly on the duration. Whoever drives this has to say the video reached the end, which
 * is why VideoAdActivity calls onProgress(1f) before onComplete(). Without that the reward was
 * always refused even after the video played all the way through.
 */
class RewardedControllerTest {

    private class Recorder : RewardedController.Listener {
        var earned = 0
        var rejected = mutableListOf<String>()
        override fun onRewardEarned() { earned++ }
        override fun onRewardRejected(reason: String) { rejected += reason }
    }

    private fun controller(): Pair<RewardedController, Recorder> {
        val recorder = Recorder()
        return RewardedController().apply { setListener(recorder); reset() } to recorder
    }

    @Test
    fun `a video watched to the end earns the reward`() {
        val (controller, recorder) = controller()
        controller.onProgress(0.5f)
        controller.onProgress(1f)
        controller.onComplete()
        assertEquals(1, recorder.earned)
        assertTrue(controller.isRewardEarned())
    }

    @Test
    fun `stopping just short of the end does not earn it`() {
        // Exactly what happened in practice: the last progress tick was under 1.0, so completing
        // was refused. The fix is upstream - tell the controller the video reached the end.
        val (controller, recorder) = controller()
        controller.onProgress(0.97f)
        controller.onComplete()
        assertEquals(0, recorder.earned)
        assertEquals(listOf("Completion threshold not reached"), recorder.rejected)
    }

    @Test
    fun `skipping refuses the reward`() {
        val (controller, recorder) = controller()
        controller.onProgress(0.4f)
        controller.onSkip()
        assertEquals(0, recorder.earned)
        assertFalse(controller.isRewardEarned())
    }

    @Test
    fun `completing after a skip still refuses it`() {
        val (controller, recorder) = controller()
        controller.onSkip()
        controller.onProgress(1f)
        controller.onComplete()
        assertEquals(0, recorder.earned)
        assertTrue(recorder.rejected.contains("Video was skipped"))
    }

    @Test
    fun `the reward is earned at most once`() {
        val (controller, recorder) = controller()
        controller.onProgress(1f)
        controller.onComplete()
        controller.onComplete()
        assertEquals(1, recorder.earned)
    }

    @Test
    fun `progress only ever moves forward`() {
        val (controller, recorder) = controller()
        controller.onProgress(1f)
        controller.onProgress(0.1f)
        controller.onComplete()
        assertEquals(1, recorder.earned)
    }

    @Test
    fun `reset clears a previous outcome so the next ad starts clean`() {
        val (controller, recorder) = controller()
        controller.onSkip()
        controller.reset()
        controller.onProgress(1f)
        controller.onComplete()
        assertEquals(1, recorder.earned)
    }
}
