package org.bidon.rendering.telemetry

/** A fake store for tests about what gets written: it never has anything to send. */
internal abstract class WriteOnlyStore : EventStore {
    override fun peek(maxEvents: Int, maxPayloadBytes: Int): List<StoredEvent> = emptyList()
    override fun remove(events: List<StoredEvent>) = Unit
    override val size: Int get() = 0
}
