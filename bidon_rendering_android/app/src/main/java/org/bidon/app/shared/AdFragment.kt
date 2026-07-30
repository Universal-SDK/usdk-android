package org.bidon.app.shared

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Shared shape for every tab: a title, a line saying which path this screen exercises, the
 * screen's own controls, and the shared step log pinned at the bottom.
 *
 * The log is pinned rather than part of the scrolling content so new lines never push the
 * buttons off screen.
 */
abstract class AdFragment : Fragment() {

    /** Tab title, shown at the top of the screen. */
    abstract val title: String

    /** One line on what path this screen runs, so it is obvious what is being tested. */
    abstract val subtitle: String

    /** Add this screen's buttons and views here. */
    abstract fun buildControls(container: LinearLayout)

    private lateinit var logText: TextView
    private lateinit var logScroller: ScrollView

    private val logListener: (List<String>) -> Unit = { lines ->
        if (::logText.isInitialized) {
            logText.text = if (lines.isEmpty()) "(no steps yet)" else lines.joinToString("\n")
            logScroller.post { logScroller.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        content.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.BLACK)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        content.addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(Color.DKGRAY)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        })

        buildControls(content)

        val contentScroller = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(content)
        }

        logText = TextView(ctx).apply {
            setTextColor(Color.BLACK)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        logScroller = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(180))
            setBackgroundColor(Color.parseColor("#EFEFEF"))
            addView(logText)
        }

        val logHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.DKGRAY)
            setPadding(dp(12), dp(6), dp(12), dp(6))

            addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                text = "STEP LOG"
                setTextColor(Color.WHITE)
                textSize = 12f
            })
            addView(Button(ctx).apply {
                text = "Clear"
                textSize = 10f
                setOnClickListener { FlowLog.clear() }
            })
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(contentScroller)
            addView(logHeader)
            addView(logScroller)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FlowLog.observe(logListener)
    }

    override fun onDestroyView() {
        FlowLog.stopObserving(logListener)
        super.onDestroyView()
    }

    // ---- small helpers so the screens stay readable ----

    protected fun button(text: String, onClick: () -> Unit) = Button(requireContext()).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    protected fun note(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextColor(Color.DKGRAY)
        textSize = 11f
        setPadding(0, dp(8), 0, dp(4))
    }

    protected fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    protected fun step(stage: String, message: String) = FlowLog.step(stage, message)

    protected fun fail(stage: String, message: String) = FlowLog.fail(stage, message)

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
