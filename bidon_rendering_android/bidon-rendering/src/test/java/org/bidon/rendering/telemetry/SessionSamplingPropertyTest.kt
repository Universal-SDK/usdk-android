package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.egyptianHieroglyphs
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/**
 * Where a session falls in [0, 1), and how the gate turns that position and the configured rates into a keep decision
 * (D-031). The server repeats the decision from the session id alone, so the position is pinned to the formula, and
 * one value is written out in full for anyone reimplementing it.
 */
class SessionSamplingPropertyTest {

    private val anySession: Arb<SessionId> = Arb.choice(
        Arb.string(1..40, Codepoint.ascii()),
        Arb.string(1..40, Codepoint.cyrillic()),
        Arb.string(1..40, Codepoint.egyptianHieroglyphs()),
        Arb.string(1_000..4_000, Codepoint.ascii()),
    ).filter { it.isNotBlank() }.map { SessionId.of(it)!! }

    private val anyRate = Arb.double(0.0, 1.0, includeNonFiniteEdgeCases = false)

    /** Never moves, so the rate limit only matters where a test asks for more than 20 events of one name. */
    private val clock = MonotonicClock { 0L }

    @Test
    fun `a position is in 0 inclusive to 1 exclusive`() {
        runBlocking {
            checkAll(anySession) { session ->
                val position = session.samplingPosition()
                assertTrue("$position", position >= 0.0 && position < 1.0)
            }
        }
    }

    @Test
    fun `the same session id always has the same position`() {
        runBlocking {
            checkAll(anySession) { session ->
                val again = SessionId.of(session.value)!!
                assertEquals(session.samplingPosition(), session.samplingPosition(), 0.0)
                assertEquals(session.samplingPosition(), again.samplingPosition(), 0.0)
            }
        }
    }

    @Test
    fun `a position is the top 53 bits of the first 8 bytes of SHA-256 of the UTF-8 id, over 2 to the 53`() {
        runBlocking {
            checkAll(anySession) { session ->
                assertEquals(referencePosition(session.value), session.samplingPosition(), 0.0)
            }
        }
    }

    @Test
    fun `known session ids have the positions the formula gives`() {
        for (id in listOf("abc", "session-1", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "сесія")) {
            assertEquals(id, referencePosition(id), SessionId.of(id)!!.samplingPosition(), 0.0)
        }
    }

    @Test
    fun `the position of abc is written out in full, for a server to check against`() {
        // SHA-256("abc") starts ba7816bf8f01cfea. Shifted right by 11 that is 6560798095827001, and over 2^53 it is
        // exactly the double below.
        assertEquals(6560798095827001.0 / 9007199254740992.0, 0.7283949105904021, 0.0)
        assertEquals(0.7283949105904021, SessionId.of("abc")!!.samplingPosition(), 0.0)
    }

    @Test
    fun `a session is dropped at a rate equal to its position and kept at the next rate up`() {
        runBlocking {
            checkAll(anySession) { session ->
                val position = session.samplingPosition()
                assertNull(admit(session, TelemetryConfig(samplingRate = position)))
                val above = Math.nextUp(position)
                if (above <= 1.0) assertEquals(above, admit(session, TelemetryConfig(samplingRate = above))?.fraction)
            }
        }
    }

    @Test
    fun `a session kept at a rate is kept at every higher rate`() {
        runBlocking {
            checkAll(anySession, anyRate, anyRate) { session, x, y ->
                val low = minOf(x, y)
                val high = maxOf(x, y)
                if (admit(session, TelemetryConfig(samplingRate = low)) != null) {
                    assertEquals(high, admit(session, TelemetryConfig(samplingRate = high))?.fraction)
                }
            }
        }
    }

    @Test
    fun `per-name rates nest the same way, since they use the same position`() {
        runBlocking {
            checkAll(anySession, anyRate, anyRate) { session, x, y ->
                val config = TelemetryConfig(eventRates = mapOf("rare" to minOf(x, y), "common" to maxOf(x, y)))
                val gate = gate(session, config)
                if (gate.admit("rare", EventClass.DIAGNOSTIC) != null) {
                    assertEquals(maxOf(x, y), gate.admit("common", EventClass.DIAGNOSTIC)?.fraction)
                }
            }
        }
    }

    @Test
    fun `a per-name rate replaces the global rate, and the rate written on the event is the one that decided`() {
        runBlocking {
            checkAll(anySession, anyRate, anyRate) { session, global, perName ->
                val gate = gate(session, TelemetryConfig(samplingRate = global, eventRates = mapOf("named" to perName)))
                val expected = if (session.samplingPosition() < perName) perName else null

                assertEquals(expected, gate.admit("named", EventClass.DIAGNOSTIC)?.fraction)
            }
        }
    }

    @Test
    fun `a name without a rate of its own uses the global rate`() {
        runBlocking {
            checkAll(anySession, anyRate, anyRate) { session, global, perName ->
                val gate = gate(session, TelemetryConfig(samplingRate = global, eventRates = mapOf("named" to perName)))
                val expected = if (session.samplingPosition() < global) global else null

                assertEquals(expected, gate.admit("other", EventClass.DIAGNOSTIC)?.fraction)
            }
        }
    }

    @Test
    fun `a per-name rate of 1 keeps the name at 1 when the global rate is 0`() {
        val session = SessionId.of("abc")!!
        val gate = gate(session, TelemetryConfig(samplingRate = 0.0, eventRates = mapOf("named" to 1.0)))

        assertEquals(1.0, gate.admit("named", EventClass.DIAGNOSTIC)?.fraction)
    }

    @Test
    fun `the global and the per-name rate are not multiplied together`() {
        // Position of abc is about 0.728: below 0.8, but above 0.8 * 0.8 = 0.64.
        val session = SessionId.of("abc")!!
        val gate = gate(session, TelemetryConfig(samplingRate = 0.8, eventRates = mapOf("named" to 0.8)))

        assertEquals(0.8, gate.admit("named", EventClass.DIAGNOSTIC)?.fraction)
    }

    @Test
    fun `an error is always kept at 1, whatever the global or per-name rate`() {
        runBlocking {
            checkAll(anySession, anyRate, anyRate) { session, global, perName ->
                for (config in listOf(
                    TelemetryConfig(samplingRate = global, eventRates = mapOf("failed" to perName)),
                    TelemetryConfig(samplingRate = 0.0, eventRates = mapOf("failed" to 0.0)),
                )) {
                    val gate = gate(session, config)
                    assertEquals(SamplingRate.ALL, gate.admit("failed", EventClass.ERROR))
                    assertEquals(SamplingRate.ALL, gate.admit("unnamed", EventClass.ERROR))
                }
            }
        }
    }

    @Test
    fun `rate 0 keeps no diagnostic`() {
        runBlocking {
            checkAll(anySession) { session ->
                assertNull(admit(session, TelemetryConfig(samplingRate = 0.0)))
                assertNull(gate(session, TelemetryConfig(eventRates = mapOf("off" to 0.0))).admit("off", EventClass.DIAGNOSTIC))
            }
        }
    }

    @Test
    fun `rate 1 keeps every diagnostic, at 1`() {
        runBlocking {
            checkAll(anySession) { session ->
                assertEquals(SamplingRate.ALL, admit(session, TelemetryConfig(samplingRate = 1.0)))
                assertEquals(SamplingRate.ALL, admit(session, TelemetryConfig.DEFAULT))
            }
        }
    }

    @Test
    fun `about a tenth of random sessions fall below a tenth`() {
        val random = Random(73)
        val sessions = 10_000
        val below = (1..sessions).count {
            SessionId.of(UUID(random.nextLong(), random.nextLong()).toString())!!.samplingPosition() < 0.1
        }

        val share = below.toDouble() / sessions
        assertTrue("$share", share in 0.08..0.12)
    }

    /** One diagnostic named `event`, through a gate of its own so the rate limit never refuses it. */
    private fun admit(session: SessionId, config: TelemetryConfig): SamplingRate? =
        gate(session, config).admit("event", EventClass.DIAGNOSTIC)

    private fun gate(session: SessionId, config: TelemetryConfig) =
        TelemetryGate(session, RateLimiter(clock)).apply { this.config = config }

    /** The formula of D-031, computed independently of the code under test. */
    private fun referencePosition(id: String): Double {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val top53 = ByteBuffer.wrap(digest, 0, 8).long ushr 11
        return top53.toDouble() / (1L shl 53).toDouble()
    }
}
