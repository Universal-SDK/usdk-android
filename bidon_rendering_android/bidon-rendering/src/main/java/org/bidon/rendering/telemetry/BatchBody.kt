package org.bidon.rendering.telemetry

import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Builds the body of a telemetry request: the batch header the contract requires, then the events.
 *
 * The events are the payloads exactly as they were stored, written between the brackets of the `events` array. They
 * are never decoded or rebuilt, so a batch sent again is byte for byte the batch sent before, and the server can
 * deduplicate on each event's `event_id`. [sessionId] is this process's session; an event stored by an earlier
 * process carries its own `session_id`, which the contract lets override the batch's.
 */
internal class BatchBody(endpoint: TelemetryEndpoint, sessionId: SessionId) {

    private val prefix: ByteArray = (
        "{\"app\":{\"key\":" + JSONObject.quote(endpoint.appKey) +
            ",\"bundle\":" + JSONObject.quote(endpoint.bundle) +
            "},\"session\":{\"id\":" + JSONObject.quote(sessionId.value) +
            "},\"events\":["
        ).toByteArray(Charsets.UTF_8)

    /**
     * How many bytes of payload a batch of [MAX_EVENTS] can carry and still fit in [MAX_BODY_BYTES], after the header
     * and the commas between events.
     */
    val maxPayloadBytes: Int = MAX_BODY_BYTES - prefix.size - SUFFIX.size - (MAX_EVENTS - 1)

    fun encode(payloads: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream(prefix.size + SUFFIX.size + payloads.sumOf { it.size + 1 })
        body.write(prefix)
        payloads.forEachIndexed { index, payload ->
            if (index > 0) body.write(','.code)
            body.write(payload)
        }
        body.write(SUFFIX)
        return body.toByteArray()
    }

    companion object {

        /** The most events one request may carry under the ingest contract. */
        const val MAX_EVENTS = 100

        /** The largest body the ingest contract accepts. */
        const val MAX_BODY_BYTES = 256 * 1024

        private val SUFFIX = "]}".toByteArray(Charsets.UTF_8)
    }
}
