package org.bidon.rendering.telemetry.catalogue

import org.bidon.rendering.telemetry.EventName
import org.bidon.rendering.telemetry.catalogue.Conformance.Rule
import org.bidon.rendering.telemetry.catalogue.Conformance.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every checked-in sample is held against the vendored catalogue, and what it breaks is exactly the pending list
 * (BID-115 AC 5 and AC 6): a new violation fails, and so does a pending entry the catalogue now accepts or no sample
 * produces any more.
 */
class CatalogueConformanceTest {

    private val catalogue by lazy { CatalogueTestSupport.vendoredCatalogue() }

    private fun violationsBySample(): Map<EventName, List<Violation>> = EventName.entries.associateWith { name ->
        val sample = CatalogueTestSupport.resourceText(GoldenEvents.samplePath(name))
            ?: throw AssertionError("No sample at ${GoldenEvents.samplePath(name)} for $name")
        Conformance.check(sample, catalogue)
    }

    private fun describe(v: Violation) = "${v.event}.${v.key} (${v.rule})"

    @Test
    fun `the violations across all samples are exactly the pending entries`() {
        val found = violationsBySample().values.flatten().toSet()
        val pending = PendingCatalogue.entries.map { Violation(it.event, it.key, it.rule) }.toSet()
        val problems = mismatches(found, pending)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a pending entry no sample breaks any more is reported as stale`() {
        val fixed = Violation("e", "a", Rule.UNKNOWN_FIELD)
        assertEquals(
            listOf("Stale pending entry e.a (UNKNOWN_FIELD): no sample breaks it any more; remove it."),
            mismatches(found = emptySet(), pending = setOf(fixed)),
        )
    }

    @Test
    fun `a violation no pending entry covers is reported as new`() {
        val broken = Violation("e", "b", Rule.WRONG_TYPE)
        assertEquals(
            listOf("New violation e.b (WRONG_TYPE): a sample breaks the catalogue and no pending entry covers it."),
            mismatches(found = setOf(broken), pending = emptySet()),
        )
    }

    /** One line per pending entry nothing breaks any more, then one per violation the pending list does not cover. */
    private fun mismatches(found: Set<Violation>, pending: Set<Violation>): List<String> =
        (pending - found).map { "Stale pending entry ${describe(it)}: no sample breaks it any more; remove it." } +
            (found - pending).map {
                "New violation ${describe(it)}: a sample breaks the catalogue and no pending entry covers it."
            }

    @Test
    fun `the pending list has no duplicate entries`() {
        val keys = PendingCatalogue.entries.map { Violation(it.event, it.key, it.rule) }
        assertEquals(keys.distinct(), keys)
    }

    @Test
    fun `every pending entry names the ticket that removes it`() {
        PendingCatalogue.entries.forEach {
            assertTrue(
                "Pending entry ${it.event}.${it.key} must name the ticket that removes it: ${it.removedBy}",
                it.removedBy.startsWith("Telemetry: ") && it.removedBy.length > "Telemetry: ".length,
            )
        }
    }

    @Test
    fun `creative_type is not reported today`() {
        val reported = violationsBySample().values.flatten().filter { it.key == "creative_type" }
        assertEquals(emptyList<Violation>(), reported)
    }

    @Test
    fun `no sample breaks a rule the pending list does not name even for a known event`() {
        // render_crashed is a catalogue name, so its two extra fields must be the only thing it breaks.
        val crashed = violationsBySample().getValue(EventName.RENDER_CRASHED)
        assertEquals(
            setOf("crashed_on_main_thread", "exceptions"),
            crashed.map { it.key }.toSet(),
        )
    }
}
