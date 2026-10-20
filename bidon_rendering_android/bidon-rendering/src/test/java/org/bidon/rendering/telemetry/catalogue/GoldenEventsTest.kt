package org.bidon.rendering.telemetry.catalogue

import java.io.File
import java.math.BigInteger
import org.bidon.rendering.telemetry.EventName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each event is encoded again by its production encoder and compared with its checked-in sample as a JSON tree
 * (BID-115 AC 1 to 3). Key order is not part of the contract, JSON types are: `5`, `5.0`, `"5"` and `true` differ.
 */
class GoldenEventsTest {

    private val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    private fun sampleFiles(): List<String> {
        val dir = javaClass.classLoader!!.getResource("telemetry/golden")?.let { File(it.toURI()) }
        return dir?.listFiles()?.map { it.name }?.sorted().orEmpty()
    }

    /** JSON text as a tree of maps, lists and typed leaves: integers as [BigInteger], other numbers as [Double]. */
    private fun tree(value: Any?): Any? = when (value) {
        is JSONObject -> value.keySet().associateWith { tree(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { tree(value.get(it)) }
        JSONObject.NULL -> null
        is Int, is Long, is BigInteger -> BigInteger(value.toString())
        is Number -> value.toDouble()
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> = tree(JSONObject(text)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun withPlaceholderId(label: String, json: Map<String, Any?>): Map<String, Any?> {
        val envelope = json["envelope"] as? Map<String, Any?> ?: throw AssertionError("$label has no envelope object")
        val id = envelope["event_id"]
        assertTrue("$label: envelope.event_id is not a UUID v4: $id", id is String && uuidV4.matches(id))
        return json + ("envelope" to (envelope + ("event_id" to "<uuid-v4>")))
    }

    private fun pretty(value: Any?, indent: String = ""): String = when (value) {
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.sortedBy { it.key as String }
            .joinToString(",\n", "{\n", "\n$indent}") { "$indent  \"${it.key}\": ${pretty(it.value, "$indent  ")}" }
        is List<*> -> if (value.isEmpty()) "[]" else value
            .joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${pretty(it, "$indent  ")}" }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    @Test
    fun `every event name has a sample file`() {
        val missing = EventName.entries.filter { CatalogueTestSupport.resourceText(GoldenEvents.samplePath(it)) == null }
        assertTrue("Events without a sample: ${missing.map { GoldenEvents.samplePath(it) }}", missing.isEmpty())
    }

    @Test
    fun `every sample file belongs to an event name`() {
        val expected = EventName.entries.map { "${it.wireName}.json" }.toSet()
        val stray = sampleFiles().filter { it !in expected }
        assertTrue("Samples without an event name in the list: $stray", stray.isEmpty())
    }

    @Test
    fun `each event encodes to its sample as a JSON tree`() {
        EventName.entries.forEach { name ->
            val encoded = withPlaceholderId("${name.wireName} encoded", parse(GoldenEvents.encode(name)))
            val sampleText = CatalogueTestSupport.resourceText(GoldenEvents.samplePath(name))
                ?: throw AssertionError(
                    "No sample at ${GoldenEvents.samplePath(name)}. Create it with this content:\n" + pretty(encoded),
                )
            val sample = withPlaceholderId("${name.wireName} sample", parse(sampleText))
            assertEquals(
                "${name.wireName} no longer encodes to ${GoldenEvents.samplePath(name)}. New JSON (event_id shown " +
                    "as a placeholder, keep the sample's own UUID):\n${pretty(encoded)}\n",
                sample,
                encoded,
            )
        }
    }

    @Test
    fun `each sample is strict JSON, pretty-printed with sorted keys`() {
        // org.json reads single quotes and trailing commas; the backend's decoder does not.
        EventName.entries.forEach { name ->
            val text = CatalogueTestSupport.resourceText(GoldenEvents.samplePath(name)) ?: return@forEach
            assertEquals("${GoldenEvents.samplePath(name)} is not in canonical form", pretty(parse(text)) + "\n", text)
        }
    }

    @Test
    fun `encoding twice gives the same tree apart from the event id`() {
        EventName.entries.forEach { name ->
            val first = withPlaceholderId("first ${name.wireName}", parse(GoldenEvents.encode(name)))
            val second = withPlaceholderId("second ${name.wireName}", parse(GoldenEvents.encode(name)))
            assertEquals("${name.wireName} is not stable between runs", first, second)
        }
    }
}
