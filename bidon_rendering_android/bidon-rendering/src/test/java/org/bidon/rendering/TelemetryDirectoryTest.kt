package org.bidon.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryDirectoryTest {

    @Test
    fun `the main process keeps its name`() {
        assertEquals("com.demo.tetris", TelemetryDirectory.directoryName("com.demo.tetris"))
    }

    @Test
    fun `a secondary process gets a directory of its own`() {
        assertEquals("com.demo.tetris_remote", TelemetryDirectory.directoryName("com.demo.tetris:remote"))
    }

    @Test
    fun `nothing that could escape the directory survives`() {
        assertEquals("_.._x_y", TelemetryDirectory.directoryName("/../x y"))
    }
}
