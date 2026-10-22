package org.bidon.rendering.telemetry

import java.io.IOException

/**
 * The telemetry thread's settings: the ones in force, the file that keeps them, and the sender they drive.
 *
 * Until it has an endpoint it sends nothing, like [EventSender.None]. When an endpoint arrives, from the file at start
 * or through [apply] later, it builds a sender for it with [makeSender] and tells that sender the store has just
 * opened, so events stored before the endpoint was known are sent too. An endpoint equal to the one in force keeps
 * the sender it has.
 *
 * Used only on the telemetry thread.
 */
internal class ConfiguredSender(
    private val file: SettingsFile,
    private val makeSender: (EventStore, TelemetryEndpoint) -> EventSender,
) : EventSender, SettingsTarget {

    /** The settings in force: what was stored, with every delivered part on top. */
    private var current = TelemetrySettings.NONE

    private var store: EventStore? = null

    private var sender: EventSender = EventSender.None

    /** Reads the stored settings, keeps their endpoint, and returns their switches. Call before [attach]. */
    override fun load(): TelemetryConfig? {
        current = file.read()
        return current.config
    }

    /** The store is open: from now on an endpoint gets a sender. The thread starts that sender itself. */
    fun attach(store: EventStore) {
        this.store = store
        current.endpoint?.let { sender = makeSender(store, it) }
    }

    /**
     * New settings delivered: each present part replaces the one in force, the sender follows a new endpoint, and the
     * result is written to the file. A failed write keeps the settings in force for this process and is not an error.
     */
    override fun apply(settings: TelemetrySettings) {
        if (settings == TelemetrySettings.NONE) return
        val previous = current.endpoint
        current = settings.over(current)
        val endpoint = settings.endpoint
        val store = store
        // The same endpoint again keeps its sender, so a wait the server asked for is not cut short.
        if (endpoint != null && endpoint != previous && store != null) {
            sender = makeSender(store, endpoint).also { it.afterStart() }
        }
        try {
            file.write(current)
        } catch (failure: Exception) {
            // The settings still hold for this process; the next one starts from the last file that was written.
            if (failure !is IOException && failure !is SecurityException) throw failure
        }
    }

    override fun afterStart() = sender.afterStart()
    override fun afterAppend() = sender.afterAppend()
    override fun afterSync() = sender.afterSync()
    override fun runIfDue() = sender.runIfDue()
    override fun millisUntilDue(): Long? = sender.millisUntilDue()
}
