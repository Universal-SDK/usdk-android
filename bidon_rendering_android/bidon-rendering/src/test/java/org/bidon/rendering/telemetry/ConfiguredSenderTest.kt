package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ConfiguredSender] against the acceptance criteria 5 (a refused endpoint leaves the stored one), 8 (an endpoint
 * that arrives after start sends what was stored), 14 (a later section without a block keeps the stored values, a
 * later full block replaces them) and 15 (a corrupt file is overwritten by the next good config).
 */
class ConfiguredSenderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val directory: File by lazy { temporary.newFolder() }
    private val settingsFile: SettingsFile get() = SettingsFile { File(directory, SettingsFile.FILE_NAME) }

    private val store = object : EventStore {
        override fun append(payload: ByteArray, eventClass: EventClass) = Unit
        override fun sync() = Unit
        override fun countDropped(reason: DropReason, count: Long) = Unit
        override fun peek(maxEvents: Int, maxPayloadBytes: Int): List<StoredEvent> = emptyList()
        override fun remove(events: List<StoredEvent>) = Unit
        override val size: Int get() = 0
    }

    /** Records what the sender under test passes on. */
    private class FakeSender(val store: EventStore, val endpoint: TelemetryEndpoint) : EventSender {
        var starts = 0
        var appends = 0
        var syncs = 0
        var runs = 0
        var due: Long? = 42L

        override fun afterStart() { starts++ }
        override fun afterAppend() { appends++ }
        override fun afterSync() { syncs++ }
        override fun runIfDue() { runs++ }
        override fun millisUntilDue(): Long? = due
    }

    private val made = mutableListOf<FakeSender>()

    private fun configured(file: SettingsFile = settingsFile) =
        ConfiguredSender(file) { store, endpoint -> FakeSender(store, endpoint).also { made += it } }

    private val firstEndpoint = testEndpoint(url = "https://first.example.com/v2/telemetry", appKey = "first-key")
    private val secondEndpoint = testEndpoint(url = "https://second.example.com/v2/telemetry", appKey = "second-key")
    private val firstConfig = TelemetryConfig(samplingRate = 0.5, eventRates = mapOf("a" to 0.1))
    private val secondConfig = TelemetryConfig(enabled = false, allowedEvents = setOf("b"))

    private fun stored(settings: TelemetrySettings) = settingsFile.write(settings)

    // Load

    @Test
    fun `load returns the stored switches`() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))

        assertEquals(firstConfig, configured().load())
    }

    @Test
    fun `load returns null when nothing is stored or only an endpoint is`() {
        assertNull(configured().load())

        stored(TelemetrySettings(firstEndpoint, null))
        assertNull(configured().load())
    }

    @Test
    fun `load of a corrupt file gives no switches and no sender`() {
        File(directory, SettingsFile.FILE_NAME).writeBytes(byteArrayOf(0, -1, 9, 9))
        val sender = configured()

        assertNull(sender.load())
        sender.attach(store)
        sender.afterStart()

        assertTrue(made.isEmpty())
    }

    // Stored endpoint

    @Test
    fun `after attach a stored endpoint gets a sender built with the store and the endpoint`() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))
        val sender = configured()
        sender.load()

        sender.attach(store)

        assertEquals(1, made.size)
        assertSame(store, made.single().store)
        assertEquals(firstEndpoint.view(), made.single().endpoint.view())
    }

    @Test
    fun `calls are delegated to the sender built from the stored endpoint`() {
        stored(TelemetrySettings(firstEndpoint, null))
        val sender = configured()
        sender.load()
        sender.attach(store)
        val built = made.single()

        sender.afterStart()
        sender.afterAppend()
        sender.afterAppend()
        sender.afterSync()
        sender.runIfDue()
        built.due = 7L

        assertEquals(1, built.starts)
        assertEquals(2, built.appends)
        assertEquals(1, built.syncs)
        assertEquals(1, built.runs)
        assertEquals(7L, sender.millisUntilDue())
        built.due = null
        assertNull(sender.millisUntilDue())
    }

    @Test
    fun `with no endpoint it sends nothing and wants no call`() {
        val sender = configured()
        sender.load()
        sender.attach(store)

        sender.afterStart()
        sender.afterAppend()
        sender.afterSync()
        sender.runIfDue()

        assertNull(sender.millisUntilDue())
        assertTrue(made.isEmpty())
    }

    @Test
    fun `stored switches alone do not build a sender`() {
        stored(TelemetrySettings(null, firstConfig))
        val sender = configured()
        sender.load()

        sender.attach(store)

        assertTrue(made.isEmpty())
    }

    // Apply

    @Test
    fun endpointAfterStart() {
        val sender = configured()
        sender.load()
        sender.attach(store)
        sender.afterStart()
        assertTrue(made.isEmpty())

        sender.apply(TelemetrySettings(firstEndpoint, null))

        val built = made.single()
        assertEquals(firstEndpoint.view(), built.endpoint.view())
        assertSame(store, built.store)
        assertEquals("the new sender is told the store has just opened, so stored events go out", 1, built.starts)
        sender.runIfDue()
        sender.afterAppend()
        assertEquals(1, built.runs)
        assertEquals(1, built.appends)
        assertEquals(42L, sender.millisUntilDue())
    }

    @Test
    fun `a new endpoint replaces the sender and the old one hears nothing more`() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))
        val sender = configured()
        sender.load()
        sender.attach(store)
        sender.afterStart()
        val old = made.single()

        sender.apply(TelemetrySettings(secondEndpoint, null))
        sender.afterAppend()
        sender.runIfDue()

        assertEquals(2, made.size)
        val replacement = made.last()
        assertEquals(secondEndpoint.view(), replacement.endpoint.view())
        assertEquals(1, replacement.starts)
        assertEquals(1, replacement.appends)
        assertEquals(1, replacement.runs)
        assertEquals(0, old.appends)
        assertEquals(0, old.runs)
    }

    @Test
    fun `the same endpoint again keeps the sender, so its retry wait holds`() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))
        val sender = configured()
        sender.load()
        sender.attach(store)
        sender.afterStart()

        sender.apply(TelemetrySettings(testEndpoint(url = "https://first.example.com/v2/telemetry", appKey = "first-key"), secondConfig))

        assertEquals(1, made.size)
        assertEquals(1, made.single().starts)
        assertEquals(secondConfig, settingsFile.read().config)
    }

    @Test
    fun `applied settings are written to the file`() {
        val sender = configured()
        sender.load()
        sender.attach(store)

        sender.apply(TelemetrySettings(firstEndpoint, firstConfig))

        assertEquals(TelemetrySettings(firstEndpoint, firstConfig).view(), settingsFile.read().view())
    }

    @Test
    fun storedValuesSurviveMissingBlock() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))
        val sender = configured()
        sender.load()
        sender.attach(store)
        val built = made.single()

        sender.apply(TelemetrySettings.NONE)
        sender.afterAppend()

        assertEquals(TelemetrySettings(firstEndpoint, firstConfig).view(), settingsFile.read().view())
        assertEquals(1, made.size)
        assertEquals(1, built.appends)
        assertEquals("a restart reads the same", firstConfig, configured().load())
    }

    @Test
    fun `switches alone keep the endpoint and the sender, and are merged into the file`() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))
        val sender = configured()
        sender.load()
        sender.attach(store)
        val built = made.single()

        sender.apply(TelemetrySettings(null, secondConfig))
        sender.afterAppend()

        assertEquals(1, made.size)
        assertEquals(1, built.appends)
        assertEquals("no restart of a sender whose endpoint did not change", 0, built.starts)
        assertEquals(TelemetrySettings(firstEndpoint, secondConfig).view(), settingsFile.read().view())
    }

    @Test
    fun `an endpoint alone keeps the stored switches and is merged into the file`() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))
        val sender = configured()
        sender.load()
        sender.attach(store)

        sender.apply(TelemetrySettings(secondEndpoint, null))

        assertEquals(TelemetrySettings(secondEndpoint, firstConfig).view(), settingsFile.read().view())
    }

    @Test
    fun newBlockReplacesStored() {
        stored(TelemetrySettings(firstEndpoint, firstConfig))
        val sender = configured()
        sender.load()
        sender.attach(store)

        sender.apply(TelemetrySettings(secondEndpoint, secondConfig))

        assertEquals(TelemetrySettings(secondEndpoint, secondConfig).view(), settingsFile.read().view())
        assertEquals(secondEndpoint.view(), made.last().endpoint.view())
    }

    @Test
    fun `settings with no endpoint first and an endpoint later are merged in the file`() {
        val sender = configured()
        sender.load()
        sender.attach(store)

        sender.apply(TelemetrySettings(null, firstConfig))
        sender.apply(TelemetrySettings(firstEndpoint, null))

        assertEquals(TelemetrySettings(firstEndpoint, firstConfig).view(), settingsFile.read().view())
    }

    @Test
    fun `nothing applied and nothing stored writes no file`() {
        val sender = configured()
        sender.load()
        sender.attach(store)

        sender.apply(TelemetrySettings.NONE)

        assertEquals(TelemetrySettings.NONE, settingsFile.read())
    }

    // Failures

    @Test
    fun `a failed write does not throw and the settings stay in force for this process`() {
        val unwritable = SettingsFile { File(directory, "missing/dir/${SettingsFile.FILE_NAME}") }
        val sender = configured(unwritable)
        sender.load()
        sender.attach(store)

        sender.apply(TelemetrySettings(firstEndpoint, firstConfig))
        sender.afterAppend()

        val built = made.single()
        assertEquals(firstEndpoint.view(), built.endpoint.view())
        assertEquals(1, built.starts)
        assertEquals(1, built.appends)
    }

    @Test
    fun corruptStoredConfigIsOverwrittenByTheNextGoodConfig() {
        File(directory, SettingsFile.FILE_NAME).writeText("{ truncated")
        val sender = configured()
        assertNull(sender.load())
        sender.attach(store)

        sender.apply(TelemetrySettings(firstEndpoint, firstConfig))

        assertEquals(TelemetrySettings(firstEndpoint, firstConfig).view(), settingsFile.read().view())
        assertEquals(1, made.size)
    }
}
