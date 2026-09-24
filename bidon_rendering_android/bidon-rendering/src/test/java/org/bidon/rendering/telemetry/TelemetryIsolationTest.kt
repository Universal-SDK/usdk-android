package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The telemetry package depends on nothing in the renderer and nothing in Android (D-018), so that it can move to
 * wherever telemetry ends up living by relocating one directory. This test is that rule, checked on every build.
 */
class TelemetryIsolationTest {

    private val sources = File("src/main/java/org/bidon/rendering/telemetry")

    @Test
    fun `the telemetry package imports nothing from Android or from the rest of the renderer`() {
        val files = sources.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("no sources found under ${sources.absolutePath}", files.isNotEmpty())

        val violations = files.flatMap { file ->
            file.readLines().filter { it.isForbiddenImport() }.map { "${file.name}: ${it.trim()}" }
        }

        assertEquals(emptyList<String>(), violations)
    }

    private fun String.isForbiddenImport(): Boolean {
        val line = trim()
        if (!line.startsWith("import ")) return false
        val imported = line.removePrefix("import ").trim()
        return imported.startsWith("android.") ||
            imported.startsWith("androidx.") ||
            (imported.startsWith("org.bidon.") && !imported.startsWith("org.bidon.rendering.telemetry."))
    }
}
