package org.bidon.rendering

import android.app.Activity
import android.os.Bundle
import android.os.Process
import org.bidon.rendering.telemetry.BackgroundTelemetry
import org.bidon.rendering.telemetry.BatchBody
import org.bidon.rendering.telemetry.BatchSender
import org.bidon.rendering.telemetry.Clock
import org.bidon.rendering.telemetry.DiskEventStore
import org.bidon.rendering.telemetry.EventEnvelope
import org.bidon.rendering.telemetry.HttpTelemetryClient
import org.bidon.rendering.telemetry.SessionId
import org.bidon.rendering.telemetry.TelemetryEndpoint
import org.bidon.rendering.telemetry.TelemetryEvent
import java.io.File

/**
 * A host app screen in a process of its own. The test process runs under instrumentation, which Android keeps in the
 * foreground, so it never hears that its UI was hidden; this process does, like a publisher's app.
 *
 * It starts telemetry that sends to [EXTRA_URL], stores three events and a fourth, `ready`, which writes the process's
 * pid to [READY_FILE] in [EXTRA_DIRECTORY] as it is stored.
 */
class TelemetryHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = File(intent.getStringExtra(EXTRA_DIRECTORY)!!)
        val session = SessionId.of(intent.getStringExtra(EXTRA_SESSION)!!)!!
        val endpoint = TelemetryEndpoint.of(intent.getStringExtra(EXTRA_URL)!!, "app_key", packageName, "0.14.0")!!
        val telemetry = BackgroundTelemetry.start(
            openStore = { DiskEventStore.open(File(directory, "store")) },
            onFailure = { File(directory, FAILED_FILE).writeText(it.toString()) },
            openSender = { store -> BatchSender(store, HttpTelemetryClient(endpoint), BatchBody(endpoint, session)) },
        )
        applicationContext.registerComponentCallbacks(RendererTelemetry.SyncInBackground(telemetry))
        repeat(3) { index -> telemetry.emit(EventEnvelope.capture("event_$index", session, Clock.System)) }
        // Encoded on the telemetry thread once the three before it are stored, and stored as the fourth event.
        val ready = EventEnvelope.capture("ready", session, Clock.System)
        telemetry.emit(
            TelemetryEvent {
                File(directory, READY_FILE).writeText(Process.myPid().toString())
                ready.encode()
            },
        )
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_DIRECTORY = "directory"
        const val EXTRA_SESSION = "session"
        const val READY_FILE = "ready"
        const val FAILED_FILE = "failed"
    }
}
