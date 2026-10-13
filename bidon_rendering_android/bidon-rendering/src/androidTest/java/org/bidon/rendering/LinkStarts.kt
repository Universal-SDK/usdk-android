package org.bidon.rendering

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections

/**
 * Keeps the address of each link the renderer starts, without letting anything open. The monitor sees every start
 * before the system resolves it, and a refused link is never started, so the addresses it keeps are the links the
 * renderer let through. A monitor that sees every start, whatever it matches, came with Android 8.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class LinkStarts private constructor() : Instrumentation.ActivityMonitor() {
    private val addresses: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        val address = intent.dataString ?: return null
        addresses += address
        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
    }

    companion object {
        /** The addresses of the links started while [block] runs, in the order they were started. */
        fun during(block: () -> Unit): List<String> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val starts = LinkStarts()
            instrumentation.addMonitor(starts)
            try {
                block()
            } finally {
                instrumentation.removeMonitor(starts)
            }
            return starts.addresses.toList()
        }
    }
}
