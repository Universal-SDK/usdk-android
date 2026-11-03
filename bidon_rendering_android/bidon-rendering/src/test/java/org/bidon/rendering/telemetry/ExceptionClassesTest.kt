package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The only form a class name leaves the device in (BID-136 AC 5): a Java class name of at most 256 characters. */
class ExceptionClassesTest {

    @Test
    fun `a Java class name is sent as it is`() {
        listOf(
            "java.lang.IllegalStateException",
            "Root",
            "org.bidon.rendering.telemetry.AdEventsTest\$SelfCause",
            "a.b.C\$1",
            "_x.\$y.z9",
        ).forEach { name -> assertEquals(name, sendableClassName(name)) }
    }

    @Test
    fun `a name that is not a Java class name is left out`() {
        listOf(
            "",
            "a b.C",
            "a.b:c",
            "a:b",
            "java.lang.IllegalStateException: message",
            "a.b\nc",
            "a.b\tc",
            "1a.B",
            "a.1b",
            ".a",
            "a.",
            "a..b",
            "a/b",
            "a.b-c",
            "https://creative.invalid/x",
        ).forEach { name -> assertNull("<$name>", sendableClassName(name)) }
    }

    @Test
    fun `a name of 256 characters is sent and one of 257 is not`() {
        val longest = "a." + "b".repeat(254)
        assertEquals(256, longest.length)

        assertEquals(longest, sendableClassName(longest))
        assertNull(sendableClassName(longest + "b"))
    }
}
