package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchBodyTest {

    private val endpoint = TelemetryEndpoint.of("https://example.com/v2/telemetry", "app_\"key\"", "com.example", "1.0")!!
    private val session = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!
    private val body = BatchBody(endpoint, session)

    @Test
    fun `the body carries the batch header the contract requires`() {
        val json = JSONObject(body.encode(listOf("""{"event_id":"a"}""".toByteArray())).decodeToString())

        assertEquals("app_\"key\"", json.getJSONObject("app").getString("key"))
        assertEquals("com.example", json.getJSONObject("app").getString("bundle"))
        assertEquals(session.value, json.getJSONObject("session").getString("id"))
        assertEquals(setOf("app", "session", "events"), json.keyNames())
    }

    @Test
    fun `stored payloads are written byte for byte, in order`() {
        // Spacing, key order and escapes that a decode and re-encode would all change.
        val payloads = listOf("""{ "b":1,"a":"é" }""", """{"event_id":"x","n":1.50}""").map { it.toByteArray() }

        val encoded = body.encode(payloads)

        val expected = body.encode(emptyList()).dropLast(2).toByteArray() +
            payloads[0] + ",".toByteArray() + payloads[1] + "]}".toByteArray()
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun `an empty batch is still a valid body`() {
        assertEquals(0, JSONObject(body.encode(emptyList()).decodeToString()).getJSONArray("events").length())
    }

    @Test
    fun `every event comes back out of the body as it went in`() {
        runBlocking {
            checkAll(Arb.list(Arb.string(0..40), 0..20)) { values ->
                val payloads = values.map { JSONObject().put("v", it).toString().toByteArray() }

                val events = JSONObject(body.encode(payloads).decodeToString()).getJSONArray("events")

                assertEquals(values, (0 until events.length()).map { events.getJSONObject(it).getString("v") })
            }
        }
    }

    @Test
    fun `a full batch of the largest payload the budget allows fits the contract's body cap exactly`() {
        runBlocking {
            checkAll(Arb.int(1..BatchBody.MAX_EVENTS)) { count ->
                val each = body.maxPayloadBytes / count
                val payloads = List(count) { index ->
                    val size = if (index == 0) body.maxPayloadBytes - each * (count - 1) else each
                    ByteArray(size) { 'x'.code.toByte() }
                }

                val size = body.encode(payloads).size

                assertTrue("$count events: $size bytes", size <= BatchBody.MAX_BODY_BYTES)
                if (count == BatchBody.MAX_EVENTS) assertEquals(BatchBody.MAX_BODY_BYTES, size)
            }
        }
    }

    @Test
    fun `the payload budget leaves room for the largest header the endpoint allows`() {
        val longest = "\"".repeat(TelemetryEndpoint.MAX_VALUE_LENGTH)
        val worst = BatchBody(TelemetryEndpoint.of("https://example.com/t", longest, longest, "1")!!, session)

        assertTrue(worst.maxPayloadBytes >= 15 * 16 * 1024)
    }
}
