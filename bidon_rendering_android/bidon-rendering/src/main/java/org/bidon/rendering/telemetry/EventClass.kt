package org.bidon.rendering.telemetry

import java.util.concurrent.TimeUnit

/**
 * How much an event is worth keeping on the device: how long it may wait to be sent, and whether other events can push
 * it out when storage is full. Each class has a queue file of its own (D-024), so a diagnostic never evicts an error.
 *
 * @property ttlMillis how long an event of this class may wait before it is dropped as expired. Constants until remote
 * config can set them.
 */
internal enum class EventClass(val ttlMillis: Long) {

    /** Load and render failures and crashes. Rare, and each one matters, so they wait a day. */
    ERROR(TimeUnit.HOURS.toMillis(24)),

    /** Timings and everything else. */
    DIAGNOSTIC(TimeUnit.MINUTES.toMillis(15)),
}
