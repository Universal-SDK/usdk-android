package org.bidon.rendering.vast

internal class RewardedController {

    interface Listener {
        fun onRewardEarned()
        fun onRewardRejected(reason: String)
    }

    private var listener: Listener? = null

    private var rewardThreshold = 1.0f

    private var maxProgress = 0f
    private var rewarded = false
    private var skipped = false

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun setRewardThreshold(threshold: Float) {
        rewardThreshold = threshold.coerceIn(0f, 1f)
    }

    fun reset() {
        maxProgress = 0f
        rewarded = false
        skipped = false
    }

    fun onProgress(progress: Float) {
        if (skipped || rewarded) return

        if (progress > maxProgress) {
            maxProgress = progress
        }
    }


    fun onComplete() {
        if (skipped) {
            listener?.onRewardRejected("Video was skipped")
            return
        }

        if (rewarded) return

        if (maxProgress >= rewardThreshold) {
            rewarded = true
            listener?.onRewardEarned()
        } else {
            listener?.onRewardRejected("Completion threshold not reached")
        }
    }

    fun onSkip() {
        if (rewarded) return

        skipped = true
        listener?.onRewardRejected("User skipped video")
    }

    fun isRewardEarned(): Boolean = rewarded
}