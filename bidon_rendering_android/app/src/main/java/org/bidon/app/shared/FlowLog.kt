package org.bidon.app.shared

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One shared log for the whole demo app, so the chain stays readable when you switch tabs.
 *
 * Every line also goes to logcat prefixed with "hop:", so `adb logcat | grep hop:` shows the same
 * thing you see on screen.
 */
object FlowLog {

    private const val TAG = "BidonFlow"

    private val lines = mutableListOf<String>()
    private val listeners = mutableSetOf<(List<String>) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** A step of a flow that is going as expected. */
    fun step(stage: String, message: String) = add("[$stage] $message")

    /** A step that failed. Marked so it stands out in a long log. */
    fun fail(stage: String, message: String) = add("[$stage] FAILED - $message")

    fun clear() {
        onMain {
            lines.clear()
            publish()
        }
    }

    fun observe(listener: (List<String>) -> Unit) {
        onMain {
            listeners += listener
            listener(lines.toList())
        }
    }

    fun stopObserving(listener: (List<String>) -> Unit) {
        onMain { listeners -= listener }
    }

    private fun add(line: String) {
        Log.d(TAG, line)
        println("hop: $line")
        onMain {
            lines += line
            publish()
        }
    }

    private fun publish() {
        val snapshot = lines.toList()
        listeners.toList().forEach { it(snapshot) }
    }

    /** Callbacks can arrive off the main thread; the listeners touch views, so hop over. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
