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

    /** Set while [dueAt] was set by a failure, which new events do not bring forward; null otherwise. */
    private var retrying: Retrying? = null

    /** Before this, on [clock], nothing is sent: a `Retry-After` longer than the sender waits for within a series. */
    private var notBefore = Long.MIN_VALUE

    /** Events stored since the last pass started: the ones a pass has not yet seen. */
    private var storedSincePass = 0

    override fun afterStart() {
        if (store.size > 0) schedule(now())
    }

    override fun afterAppend() {
        storedSincePass++
        if (retrying != null) return
        val now = now()
        when {
            storedSincePass >= BatchBody.MAX_EVENTS -> schedule(now)
            dueAt == null -> schedule(now + TIMER_MILLIS)
        }
    }

    override fun afterSync() {
        val now = now()
        val current = retrying
        if (current == null) {
            schedule(now)
            return
        }
        // Counted only when it brings a send forward: a wait nothing may cut short does not use it up.
        val due = dueAt ?: return
        if (current.backgroundAttemptMade || maxOf(now, notBefore) >= due) return
        retrying = current.copy(backgroundAttemptMade = true)
        schedule(now)
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
            // One payload that is not a JSON object would make the whole body invalid, so it goes on its own.
            val (batch, malformed) = peeked.partition { StrictJson.isOneObject(it.payload) }
            if (malformed.isNotEmpty()) drop(malformed, DropReason.MALFORMED)
            if (batch.isEmpty()) continue
            val retry = deliver(batch)
            if (retry != null) {
                scheduleRetry(retry.retryAfterMillis)
                return
            }
            // A request that got through ends the series, so a later batch that fails starts a new one.
            retrying = null
        }
        retrying = null
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
            is PostResult.Rejected -> {
                drop(events, DropReason.REJECTED)
                null
            }
            PostResult.TooLarge ->
                if (events.size == 1) {
                    drop(events, DropReason.REJECTED)
                    null
                } else {
                    val half = events.size / 2
                    deliver(events.subList(0, half)) ?: deliver(events.subList(half, events.size))
                }
            is PostResult.Retry -> result
        }

    private fun drop(events: List<StoredEvent>, reason: DropReason) {
        store.remove(events)
        store.countDropped(reason, events.size.toLong())
    }

    private fun scheduleRetry(retryAfterMillis: Long?) {
        val now = now()
        val failures = (retrying?.failures ?: 0) + 1
        when {
            retryAfterMillis != null && retryAfterMillis > MAX_RETRY_AFTER_MILLIS -> {
                // Anything longer than a day outlives every event it could hold back.
                notBefore = now + minOf(retryAfterMillis, EventClass.ERROR.ttlMillis)
                retrying = Retrying.GAVE_UP
                schedule(now + TIMER_MILLIS)
            }
            failures >= MAX_ATTEMPTS -> {
                retrying = Retrying.GAVE_UP
                schedule(now + TIMER_MILLIS)
            }
            else -> {
                retrying = Retrying(failures, backgroundAttemptMade = retrying?.backgroundAttemptMade ?: false)
                // A Retry-After is the least the server asks for; a shorter one, even zero, does not cut the back-off.
                schedule(now + maxOf(retryAfterMillis ?: 0, backOffMillis(failures)))
            }
        }
    }

    /**
     * A failure has scheduled the next send.
     *
     * @property failures failed attempts in this series. Zero once the sender has given up on the series and waits for
     * the timer, where the next failure starts a new series.
     * @property backgroundAttemptMade whether the app going to the background has already brought this series forward.
     * An attempt made while given up counts for the series the next failure starts.
     */
    private data class Retrying(val failures: Int, val backgroundAttemptMade: Boolean) {
        companion object {
            val GAVE_UP = Retrying(failures = 0, backgroundAttemptMade = false)
        }
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
