package org.bidon.rendering

import android.app.Activity
import android.os.Bundle
import android.os.Process
import org.bidon.rendering.telemetry.Clock
import org.bidon.rendering.telemetry.EventEnvelope
import org.bidon.rendering.telemetry.TelemetryEndpoint
import org.bidon.rendering.telemetry.TelemetryEvent
import java.io.File

/**
 * A host app screen in a process of its own. The test process runs under instrumentation, which Android keeps in the
 * foreground, so it never hears that its UI was hidden; this process does, like a publisher's app.
 *
 * It starts telemetry the way the renderer does, with an endpoint at [EXTRA_URL], stores three events and a fourth,
 * `ready`, which writes the process's pid and telemetry session to [READY_FILE] in [EXTRA_DIRECTORY] as it is stored.
 */
class TelemetryHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = File(intent.getStringExtra(EXTRA_DIRECTORY)!!)
        val endpoint = TelemetryEndpoint.of(intent.getStringExtra(EXTRA_URL)!!, "app_key", packageName, "0.14.0")!!
        // Whatever an earlier run of this process left would otherwise travel in the first batch.
        TelemetryDirectory.forCurrentProcess(this).deleteRecursively()
        val telemetry = RendererTelemetry.start(applicationContext, endpoint)
        val session = RendererTelemetry.sessionId
        repeat(3) { index -> telemetry.emit(EventEnvelope.capture("event_$index", session, Clock.System)) }
        // Encoded on the telemetry thread once the three before it are stored, and stored as the fourth event.
        val ready = EventEnvelope.capture("ready", session, Clock.System)
        telemetry.emit(
            TelemetryEvent {
                File(directory, READY_FILE).writeText("${Process.myPid()}\n${session.value}")
                ready.encode()
            },
        )
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_DIRECTORY = "directory"
        const val READY_FILE = "ready"
    }
}
