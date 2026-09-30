package org.bidon.rendering.telemetry

import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Sends stored events to the server, driven by the telemetry thread that owns the [EventStore].
 *
 * The thread tells the sender what happened and asks it when it next wants to run; the sender never blocks the
 * thread except for the request itself.
 */
internal interface EventSender {

    /** The store has just been opened, with whatever an earlier process left in it. */
    fun afterStart()

    /** An event has just been stored. */
    fun afterAppend()

    /** The store has just been synced because the app went to the background. */
    fun afterSync()

    /** Sends, if a send is due. */
    fun runIfDue()

    /** How long until the sender wants [runIfDue] called, or null when nothing is scheduled. */
    fun millisUntilDue(): Long?

    /** Sends nothing: telemetry has no endpoint. */
    object None : EventSender {
        override fun afterStart() = Unit
        override fun afterAppend() = Unit
        override fun afterSync() = Unit
        override fun runIfDue() = Unit
        override fun millisUntilDue(): Long? = null
    }
}

/**
 * The [EventSender] of D-028: batches from the store, through [client], with retries.
 *
 * A pass sends batches until the store is empty or a request fails. A failure ends the pass and schedules the next
 * attempt: after 5, 10, 20 and 40 seconds, or after the server's `Retry-After` when that is longer and at most 30
 * seconds. After the fifth failed attempt the next pass waits for the timer. A longer `Retry-After` ends the pass, and
 * nothing is sent before it has passed.
 *
 * Passes start when the store opens with events in it, [TIMER_MILLIS] after an event is stored, as soon as a full
 * batch has been stored since the last pass, and when the app goes to the background. While a retry is scheduled,
 * stored events do not bring it forward; the app going to the background does, once per series of retries, since the
 * process may be frozen before the retry is due.
 *
 * A stored payload that is not one JSON object would make its whole batch invalid, so it is dropped before sending.
 *
 * Nothing is removed from the store until the server has answered for it. An event the server did not refuse stays
 * until it is sent or its TTL runs out; there is no limit on attempts across passes.
 */
internal class BatchSender(
    private val store: EventStore,
    private val client: TelemetryClient,
    private val body: BatchBody,
    private val clock: MonotonicClock = MonotonicClock.System,
    private val random: Random = Random.Default,
) : EventSender {

    /** When [runIfDue] should next send, on [clock]. Null when nothing is scheduled. */
    private var dueAt: Long? = null

    /** Whether [dueAt] was set by a failure, which new events do not bring forward. */
    private var waitingAfterFailure = false

    /** Failed attempts since the last one that got through, within the current series of retries. */
    private var failures = 0

    /** Before this, on [clock], nothing is sent: a `Retry-After` longer than the sender waits for within a series. */
    private var notBefore = Long.MIN_VALUE

    /** Whether the app going to the background has already brought the current series of retries forward. */
    private var backgroundAttemptMade = false

    /** Events stored since the last pass started: the ones a pass has not yet seen. */
    private var storedSincePass = 0

    override fun afterStart() {
        if (store.size > 0) schedule(now())
    }

    override fun afterAppend() {
        storedSincePass++
        if (waitingAfterFailure) return
        val now = now()
        when {
            storedSincePass >= BatchBody.MAX_EVENTS -> schedule(now)
            dueAt == null -> schedule(now + TIMER_MILLIS)
        }
    }

    override fun afterSync() {
        if (waitingAfterFailure) {
            if (backgroundAttemptMade) return
            backgroundAttemptMade = true
        }
        schedule(now())
    }

    override fun runIfDue() {
        val at = dueAt ?: return
        if (now() >= at) runPass()
    }

    override fun millisUntilDue(): Long? = dueAt?.let { (it - now()).coerceAtLeast(0) }

    private fun runPass() {
        dueAt = null
        storedSincePass = 0
        while (true) {
            val peeked = store.peek(BatchBody.MAX_EVENTS, body.maxPayloadBytes)
            if (peeked.isEmpty()) break
            val (batch, malformed) = peeked.partition { BatchBody.isOneObject(it.payload) }
            if (malformed.isNotEmpty()) drop(malformed, DropReason.MALFORMED)
            if (batch.isEmpty()) continue
            val retry = deliver(batch)
            if (retry != null) {
                scheduleRetry(retry.retryAfterMillis)
                return
            }
            failures = 0
        }
        endSeries()
    }

    /** The current series of retries is over, whether because a request got through or because it gave up. */
    private fun endSeries() {
        waitingAfterFailure = false
        failures = 0
        backgroundAttemptMade = false
    }

    /**
     * Sends [events] and removes them once the server has answered for them. A batch that is too large is sent as
     * two halves, down to single events; a single event that is still too large is dropped.
     *
     * @return null when the server answered for every event, or the failure that stopped it.
     */
    private fun deliver(events: List<StoredEvent>): PostResult.Retry? =
        when (val result = client.post(body.encode(events.map { it.payload }))) {
            PostResult.Accepted -> {
                store.remove(events)
                null
            }
            is PostResult.Rejected -> drop(events, DropReason.REJECTED)
            PostResult.TooLarge ->
                if (events.size == 1) {
                    drop(events, DropReason.REJECTED)
                } else {
                    val half = events.size / 2
                    deliver(events.subList(0, half)) ?: deliver(events.subList(half, events.size))
                }
            is PostResult.Retry -> result
        }

    private fun drop(events: List<StoredEvent>, reason: DropReason): PostResult.Retry? {
        store.remove(events)
        store.countDropped(reason, events.size.toLong())
        return null
    }

    private fun scheduleRetry(retryAfterMillis: Long?) {
        val now = now()
        failures++
        when {
            retryAfterMillis != null && retryAfterMillis > MAX_RETRY_AFTER_MILLIS -> {
                // Anything longer than a day outlives every event it could hold back.
                notBefore = now + minOf(retryAfterMillis, EventClass.ERROR.ttlMillis)
                endSeries()
                schedule(now + TIMER_MILLIS)
            }
            failures >= MAX_ATTEMPTS -> {
                endSeries()
                schedule(now + TIMER_MILLIS)
            }
            // A Retry-After is the least the server asks for; a shorter one, even zero, does not cut the back-off.
            else -> schedule(now + maxOf(retryAfterMillis ?: 0, backOffMillis(failures)))
        }
        waitingAfterFailure = true
    }

    /** Doubles from [FIRST_BACK_OFF_MILLIS] with each failure; half of each wait is fixed and half is random. */
    private fun backOffMillis(failures: Int): Long {
        val full = FIRST_BACK_OFF_MILLIS shl (failures - 1)
        return full / 2 + random.nextLong(full / 2 + 1)
    }

    private fun schedule(at: Long) {
        dueAt = maxOf(at, notBefore)
    }

    private fun now(): Long = clock.elapsedMillis()

    companion object {

        /** How long a stored event waits for others to join its batch before a pass starts anyway. */
        val TIMER_MILLIS = TimeUnit.SECONDS.toMillis(60)

        /** Attempts in a series of retries, the first included, before the next pass waits for the timer. */
        const val MAX_ATTEMPTS = 5

        val FIRST_BACK_OFF_MILLIS = TimeUnit.SECONDS.toMillis(5)

        /** The longest `Retry-After` waited for within a series of retries. */
        val MAX_RETRY_AFTER_MILLIS = TimeUnit.SECONDS.toMillis(30)
    }
}
