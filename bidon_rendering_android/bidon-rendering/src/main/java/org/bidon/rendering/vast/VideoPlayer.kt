package org.bidon.rendering.vast.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArraySet

class VideoPlayer(context: Context) {

    interface Listener {
        fun onReady()
        fun onStarted()
        fun onPaused()
        fun onCompleted()
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onError(error: Throwable)
    }

    data class State(
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    private val player: ExoPlayer =
        ExoPlayer.Builder(context.applicationContext).build()

    private val listeners = CopyOnWriteArraySet<Listener>()

    private var started = false

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunning = false

    init {

        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {

                    Player.STATE_READY -> {
                        dispatch { it.onReady() }
                        startProgress()
                    }

                    Player.STATE_ENDED -> {
                        stopProgress()
                        dispatch { it.onCompleted() }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {

                if (isPlaying && !started) {
                    started = true
                    dispatch { it.onStarted() }
                }

                if (!isPlaying && player.playbackState == Player.STATE_READY) {
                    dispatch { it.onPaused() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                stopProgress()
                dispatch { it.onError(error) }
            }
        })
    }

    // ---------------- LISTENERS ----------------

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private inline fun dispatch(block: (Listener) -> Unit) {
        listeners.forEach { block(it) }
    }

    // ---------------- API ----------------

    fun load(url: String) {
        started = false

        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = false
    }

    fun play() {
        player.playWhenReady = true
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    fun isMuted(): Boolean =
        player.volume == 0f

    fun getExoPlayer(): ExoPlayer =
        player

    fun getCurrentPosition(): Long =
        player.currentPosition

    fun getDuration(): Long =
        player.duration

    // ---------------- PROGRESS ----------------

    private val progressRunnable = object : Runnable {
        override fun run() {

            if (!progressRunning) return

            val duration = player.duration
            val position = player.currentPosition

            if (duration > 0) {
                dispatch {
                    it.onProgress(position, duration)
                }
            }

            handler.postDelayed(this, 250L)
        }
    }

    private fun startProgress() {
        if (progressRunning) return
        progressRunning = true
        handler.post(progressRunnable)
    }

    private fun stopProgress() {
        progressRunning = false
        handler.removeCallbacks(progressRunnable)
    }

    // ---------------- STATE ----------------

    fun saveState(): State {
        return State(
            positionMs = player.currentPosition,
            playWhenReady = player.playWhenReady
        )
    }

    fun restoreState(state: State?) {
        if (state == null) return

        player.seekTo(state.positionMs)
        player.playWhenReady = state.playWhenReady
    }

    // ---------------- RELEASE ----------------

    fun release() {
        stopProgress()
        player.release()
        listeners.clear()
    }
}