package org.bidon.rendering.telemetry

import android.webkit.WebViewClient
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.html.forWebViewError
import org.bidon.rendering.telemetry.catalogue.CatalogueTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error codes against the table checked in next to the golden samples (BID-136 AC 2 and AC 7): every code is
 * unique, sits in the band of its domain and stage, and matches its row, so a code renumbered or removed fails here.
 */
class ErrorCodeTest {

    private val table = CatalogueTestSupport.resourceText(TABLE_PATH) ?: error("missing $TABLE_PATH")

    // AC 7: the table

    @Test
    fun `the table has the header, in this order, and one row per code`() {
        val rows = ErrorCodeTable.parse(table)

        assertEquals(ErrorCode.entries.size, rows.size)
        assertEquals(rows.map { it.code }.distinct(), rows.map { it.code })
    }

    @Test
    fun `the table lists the codes that are raised today as active and every other code as reserved`() {
        val rows = ErrorCodeTable.parse(table)

        assertEquals(
            listOf(
                6001, 6002, 6003, 6004, 6005, 6006, 6014, 6015, 7001, 7002, 7003, 7004, 7005, 7006, 8001, 8002, 8003,
                11001, 11002, 11003,
            ),
            rows.filter { it.status == "active" }.map { it.code },
        )
        assertEquals(emptyList<Int>(), rows.filter { it.status == "retired" }.map { it.code })
        assertEquals(setOf("active", "reserved"), rows.map { it.status }.toSet())
    }

    @Test
    fun `every code in the enum has a row with its name, domain, stage, external source and status`() {
        ErrorCodeTable.assertMatches(ErrorCodeTable.parse(table))
    }

    @Test
    fun `the enum holds exactly the codes of the spec, in order`() {
        assertEquals(
            listOf(
                1001, 1002, 1101, 1102, 1901, 1902, 2001, 2002, 5001, 5002, 5003, 5004, 5005, 6001, 6002, 6003, 6004,
                6005, 6006, 6007, 6008, 6009, 6010, 6011, 6012, 6013, 6014, 6015, 7001, 7002, 7003, 7004, 7005, 7006,
                7101, 7102, 7103, 8001, 8002, 8003, 8004, 11001, 11002, 11003,
            ),
            ErrorCode.entries.map { it.number },
        )
    }

    @Test
    fun `every code is unique`() {
        assertEquals(ErrorCode.entries.size, ErrorCode.entries.map { it.number }.toSet().size)
        assertEquals(ErrorCode.entries.size, ErrorCode.entries.map { it.name }.toSet().size)
    }

    @Test
    fun `every code sits in the band of its domain and stage`() {
        ErrorCode.entries.forEach { code ->
            val band = BANDS.singleOrNull { code.number in it.range }
                ?: throw AssertionError("${code.name} (${code.number}) is outside every band")
            assertTrue("${code.name}: domain ${code.domain} in the ${band.range} band", code.domain in band.domains)
            if (code.stage != null) {
                assertTrue("${code.name}: stage ${code.stage} in the ${band.range} band", band.loadStages)
            }
        }
    }

    @Test
    fun `the load stage of a code is in the table when the code has one, and the show stage only for a display code`() {
        val rows = ErrorCodeTable.parse(table).associateBy { it.code }

        ErrorCode.entries.forEach { code ->
            val row = rows.getValue(code.number)
            assertEquals(code.name, code.stage?.wireName ?: ErrorCodeTable.NONE, row.loadStage)
            if (code.number in 1000..6999 || code.number in 8000..8999) {
                assertEquals("${code.name} has a show stage", ErrorCodeTable.NONE, row.showStage)
            }
            if (row.showStage != ErrorCodeTable.NONE) {
                assertEquals("${code.name} has a load stage", null, code.stage)
            }
            assertEquals(code.name, ErrorCodeTable.showStageCell(code), row.showStage)
        }
    }

    @Test
    fun `the show stage of every code is the one the spec gives its site`() {
        val expected = mapOf(
            ErrorCode.NO_PLAYABLE_MEDIA to ShowStage.MEDIA_SELECT,
            ErrorCode.PLAYER_ERROR to ShowStage.PLAYBACK_START,
            ErrorCode.WEBVIEW_GONE_AT_SHOW to ShowStage.READY_CHECK,
            ErrorCode.AD_SCREEN_NOT_STARTED to ShowStage.ACTIVITY_LAUNCH,
            ErrorCode.AD_SCREEN_NO_VIEW to ShowStage.VIEW_ATTACH,
            ErrorCode.PLAYER_NETWORK to ShowStage.PLAYBACK_START,
            ErrorCode.PLAYER_DECODE to ShowStage.PLAYBACK_START,
            ErrorCode.SHOW_NOT_READY to ShowStage.READY_CHECK,
            ErrorCode.SHOW_TWICE to ShowStage.READY_CHECK,
            ErrorCode.SHOWN_FROM_OTHER_PROCESS to ShowStage.READY_CHECK,
        )

        ErrorCode.entries.forEach { code -> assertEquals(code.name, expected[code], code.showStage) }
        assertEquals(null, ErrorCode.CLOSED_BEFORE_IMPRESSION.showStage)
    }

    @Test
    fun `the show stages are the five of the spec, spelled as they go on the wire`() {
        assertEquals(
            listOf("ready_check", "media_select", "activity_launch", "view_attach", "playback_start"),
            ShowStage.entries.map { it.wireName },
        )
    }

    @Test
    fun `the new show code is a publisher code with no external source, raised at the ready check`() {
        val code = ErrorCode.SHOWN_FROM_OTHER_PROCESS

        assertEquals(11003, code.number)
        assertEquals(ErrorDomain.PUBLISHER, code.domain)
        assertEquals(null, code.stage)
        assertEquals(ShowStage.READY_CHECK, code.showStage)
        assertEquals(null, code.externalSource)
        assertEquals(false, code.retired)
    }

    @Test
    fun `a show stage belongs to a display or publisher code only`() {
        ErrorCode.entries.filter { it.showStage != null }.forEach { code ->
            assertTrue(code.name, code.number in 7000..7999 || code.number in 11000..11999)
        }
    }

    @Test
    fun `the table keeps the show stage of the two player codes that only the video ticket raises`() {
        val rows = ErrorCodeTable.parse(table).associateBy { it.code }

        assertEquals("playback_start", rows.getValue(7101).showStage)
        assertEquals("playback_start", rows.getValue(7102).showStage)
        assertEquals("reserved", rows.getValue(7101).status)
    }

    @Test
    fun `a code with an external source in the table has one in the enum, and the reverse`() {
        val rows = ErrorCodeTable.parse(table).associateBy { it.code }

        ErrorCode.entries.forEach { code ->
            assertEquals(
                code.name,
                code.externalSource?.wireName ?: ErrorCodeTable.NONE,
                rows.getValue(code.number).externalSource,
            )
        }
    }

    @Test
    fun `no code is retired yet`() {
        assertEquals(emptyList<ErrorCode>(), ErrorCode.entries.filter { it.retired })
    }

    // AC 7: a changed table or enum fails

    @Test
    fun `a renumbered row fails the comparison`() {
        val renumbered = table.replace("| 6001 | WEBVIEW_MAIN_FRAME_ERROR |", "| 6101 | WEBVIEW_MAIN_FRAME_ERROR |")

        assertThrows(AssertionError::class.java) { ErrorCodeTable.assertMatches(ErrorCodeTable.parse(renumbered)) }
    }

    @Test
    fun `a removed row fails the comparison`() {
        val removed = table.lines().filterNot { it.startsWith("| 6003 |") }.joinToString("\n")

        assertThrows(AssertionError::class.java) { ErrorCodeTable.assertMatches(ErrorCodeTable.parse(removed)) }
    }

    @Test
    fun `a row for a code the enum does not have fails the comparison`() {
        val added = table.trimEnd() + "\n| 6099 | INVENTED | creative | parse | – | – | ad_load_failed | active | – |\n"

        assertThrows(AssertionError::class.java) { ErrorCodeTable.assertMatches(ErrorCodeTable.parse(added)) }
    }

    @Test
    fun `a renamed row fails the comparison`() {
        val renamed = table.replace("| 6003 | BLANK_CREATIVE |", "| 6003 | EMPTY_CREATIVE |")

        assertThrows(AssertionError::class.java) { ErrorCodeTable.assertMatches(ErrorCodeTable.parse(renamed)) }
    }

    @Test
    fun `a row with another domain, stage or external source fails the comparison`() {
        val domain = table.replace("| 8002 | VAST_WRAPPER_HTTP_STATUS | dsp |", "| 8002 | VAST_WRAPPER_HTTP_STATUS | network |")
        val stage = table.replace("| 6004 | VAST_INVALID | creative | parse |", "| 6004 | VAST_INVALID | creative | webview_load |")
        val source = table.replace("| 8002 | VAST_WRAPPER_HTTP_STATUS | dsp | asset_fetch | – | http |", "| 8002 | VAST_WRAPPER_HTTP_STATUS | dsp | asset_fetch | – | vast |")

        val showStage = table.replace("| 7001 | NO_PLAYABLE_MEDIA | creative | – | media_select |", "| 7001 | NO_PLAYABLE_MEDIA | creative | – | ready_check |")
        val noShowStage = table.replace("| 11003 | SHOWN_FROM_OTHER_PROCESS | publisher | – | ready_check |", "| 11003 | SHOWN_FROM_OTHER_PROCESS | publisher | – | – |")
        val byCreative = table.replace("| 7006 | CLOSED_BEFORE_IMPRESSION | device | – | by creative |", "| 7006 | CLOSED_BEFORE_IMPRESSION | device | – | view_attach |")

        listOf(domain, stage, source, showStage, noShowStage, byCreative).forEach { changed ->
            assertTrue("the replacement matched nothing", changed != table)
            assertThrows(AssertionError::class.java) { ErrorCodeTable.assertMatches(ErrorCodeTable.parse(changed)) }
        }
    }

    @Test
    fun `a row marked retired fails the comparison while the code is not retired`() {
        val retired = table.replace("| 6001 | WEBVIEW_MAIN_FRAME_ERROR | creative | webview_load | – | webview | ad_load_failed | active |", "| 6001 | WEBVIEW_MAIN_FRAME_ERROR | creative | webview_load | – | webview | ad_load_failed | retired |")

        assertTrue(retired != table)
        assertThrows(AssertionError::class.java) { ErrorCodeTable.assertMatches(ErrorCodeTable.parse(retired)) }
    }

    // AC 7: the table is read strictly

    @Test
    fun `a table with another header is refused`() {
        val wrongOrder = table.replace(
            "| Code | Name | Domain | Load stage | Show stage | External source | Event | Status | Raised by |",
            "| Code | Name | Load stage | Domain | Show stage | External source | Event | Status | Raised by |",
        )
        val missing = table.lines().filterNot { it.startsWith("| Code |") }.joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) { ErrorCodeTable.parse(wrongOrder) }
        assertThrows(IllegalArgumentException::class.java) { ErrorCodeTable.parse(missing) }
    }

    @Test
    fun `a row that is malformed is refused`() {
        val shortRow = table.replace("| 6003 | BLANK_CREATIVE | creative | webview_load | – | – | ad_load_failed | active | BID-136 |", "| 6003 | BLANK_CREATIVE | creative | webview_load | – | – | ad_load_failed | active |")
        val badCode = table.replace("| 6003 |", "| 60O3 |")
        val badStatus = table.replace("| active | BID-136 |\n| 6004", "| live | BID-136 |\n| 6004")
        val unknownShowStage = table.replace("| 7001 | NO_PLAYABLE_MEDIA | creative | – | media_select |", "| 7001 | NO_PLAYABLE_MEDIA | creative | – | media_pick |")
        val unknownDomain = table.replace("| 6003 | BLANK_CREATIVE | creative |", "| 6003 | BLANK_CREATIVE | creatve |")
        val duplicate = table.trimEnd() + "\n| 6003 | BLANK_CREATIVE | creative | webview_load | – | – | ad_load_failed | active | BID-136 |\n"

        listOf(shortRow, badCode, badStatus, unknownShowStage, unknownDomain, duplicate).forEach { broken ->
            assertTrue("the replacement matched nothing", broken != table)
            assertThrows(IllegalArgumentException::class.java) { ErrorCodeTable.parse(broken) }
        }
    }

    // AC 2: the WebView's code

    @Test
    fun `a WebView network error fails the main frame with the network code`() {
        listOf(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_TIMEOUT,
        ).forEach { webViewCode ->
            assertEquals("WebView code $webViewCode", ErrorCode.WEBVIEW_MAIN_FRAME_NETWORK, ErrorCode.forWebViewError(webViewCode))
        }
    }

    @Test
    fun `a WebView error that is not a network error fails the main frame with the creative code`() {
        listOf(
            WebViewClient.ERROR_UNSUPPORTED_SCHEME,
            WebViewClient.ERROR_BAD_URL,
            WebViewClient.ERROR_UNKNOWN,
            WebViewClient.ERROR_AUTHENTICATION,
            WebViewClient.ERROR_PROXY_AUTHENTICATION,
            WebViewClient.ERROR_REDIRECT_LOOP,
            WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_FILE,
            WebViewClient.ERROR_FILE_NOT_FOUND,
            WebViewClient.ERROR_TOO_MANY_REQUESTS,
            WebViewClient.ERROR_UNSAFE_RESOURCE,
        ).forEach { webViewCode ->
            assertEquals("WebView code $webViewCode", ErrorCode.WEBVIEW_MAIN_FRAME_ERROR, ErrorCode.forWebViewError(webViewCode))
        }
    }

    @Test
    fun `any other number is the creative code`() {
        val network = setOf(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_TIMEOUT,
        )
        runBlocking {
            checkAll(Arb.int()) { webViewCode ->
                val expected = if (webViewCode in network) ErrorCode.WEBVIEW_MAIN_FRAME_NETWORK else ErrorCode.WEBVIEW_MAIN_FRAME_ERROR
                assertEquals("WebView code $webViewCode", expected, ErrorCode.forWebViewError(webViewCode))
            }
        }
    }

    private class Band(val range: IntRange, val domains: Set<ErrorDomain>, val loadStages: Boolean)

    private companion object {
        const val TABLE_PATH = "telemetry/error-codes.md"

        /** 9000 to 9999 is not used on the device: a foreign code goes into `external_error_code`. */
        val BANDS = listOf(
            Band(1000..1999, setOf(ErrorDomain.BIDON_SDK, ErrorDomain.NETWORK_ADAPTER, ErrorDomain.BIDON_SERVER), loadStages = false),
            Band(2000..2999, setOf(ErrorDomain.BIDON_SDK), loadStages = false),
            Band(5000..5999, setOf(ErrorDomain.BIDON_SERVER, ErrorDomain.DSP, ErrorDomain.PUBLISHER), loadStages = true),
            Band(6000..6999, setOf(ErrorDomain.CREATIVE, ErrorDomain.DEVICE, ErrorDomain.BIDON_SDK, ErrorDomain.NETWORK), loadStages = true),
            Band(7000..7999, setOf(ErrorDomain.CREATIVE, ErrorDomain.DEVICE, ErrorDomain.NETWORK, ErrorDomain.BIDON_SDK), loadStages = false),
            Band(8000..8999, setOf(ErrorDomain.NETWORK, ErrorDomain.DSP), loadStages = true),
            Band(11000..11999, setOf(ErrorDomain.PUBLISHER), loadStages = false),
        )
    }
}

/** The checked-in table of error codes, read strictly: anything unexpected in it is an error, not a skipped line. */
internal object ErrorCodeTable {

    const val NONE = "–"

    /** The show stage cell of the code whose stage is the point the ad screen had reached. */
    const val BY_CREATIVE = "by creative"

    const val HEADER = "| Code | Name | Domain | Load stage | Show stage | External source | Event | Status | Raised by |"

    private val STATUSES = setOf("active", "reserved", "retired")

    class Row(
        val code: Int,
        val name: String,
        val domain: String,
        val loadStage: String,
        val showStage: String,
        val externalSource: String,
        val event: String,
        val status: String,
        val raisedBy: String,
    )

    /** The rows under the header, in file order. Fails with [IllegalArgumentException] on anything malformed. */
    fun parse(text: String): List<Row> {
        val lines = text.lines()
        val header = lines.indexOf(HEADER)
        require(header >= 0) { "the table has no header line exactly as: $HEADER" }
        require(lines.getOrNull(header + 1)?.matches(Regex("\\|(---\\|){9}")) == true) { "no separator under the header" }
        val rows = lines.drop(header + 2).filter { it.isNotBlank() }.map { line ->
            require(line.startsWith("| ") && line.endsWith(" |")) { "not a table row: $line" }
            val cells = line.removePrefix("| ").removeSuffix(" |").split(" | ")
            require(cells.size == 9) { "a row has ${cells.size} cells, not 9: $line" }
            require(cells.all { it.isNotBlank() }) { "a row has an empty cell: $line" }
            val code = requireNotNull(cells[0].takeIf { it.all(Char::isDigit) }?.toIntOrNull()) { "not a code: ${cells[0]}" }
            require(cells[2] in ErrorDomain.entries.map { it.wireName }) { "unknown domain ${cells[2]}" }
            require(cells[3] == NONE || cells[3] in FailedStage.entries.map { it.wireName }) { "unknown load stage ${cells[3]}" }
            require(cells[4] == NONE || cells[4] == BY_CREATIVE || cells[4] in ShowStage.entries.map { it.wireName }) { "unknown show stage ${cells[4]}" }
            require(cells[5] == NONE || cells[5] in ExternalErrorSource.entries.map { it.wireName }) { "unknown source ${cells[5]}" }
            require(cells[7] in STATUSES) { "unknown status ${cells[7]}" }
            Row(code, cells[1], cells[2], cells[3], cells[4], cells[5], cells[6], cells[7], cells[8])
        }
        require(rows.map { it.code }.toSet().size == rows.size) { "a code is listed twice" }
        return rows
    }

    /** What the show stage cell of [code] must say. */
    fun showStageCell(code: ErrorCode): String = when {
        code.showStage != null -> code.showStage.wireName
        code == ErrorCode.CLOSED_BEFORE_IMPRESSION -> BY_CREATIVE
        else -> NONE
    }

    /** Fails unless [rows] and the [ErrorCode] enum list the same codes with the same name, domain, stage and source. */
    fun assertMatches(rows: List<Row>) {
        val byCode = rows.associateBy { it.code }
        val missingInTable = ErrorCode.entries.filter { it.number !in byCode }.map { it.number }
        val missingInEnum = byCode.keys - ErrorCode.entries.map { it.number }.toSet()
        if (missingInTable.isNotEmpty() || missingInEnum.isNotEmpty()) {
            throw AssertionError("not in the table: $missingInTable, not in the enum: $missingInEnum")
        }
        ErrorCode.entries.forEach { code ->
            val row = byCode.getValue(code.number)
            assertEquals("name of ${code.number}", code.name, row.name)
            assertEquals("domain of ${code.name}", code.domain.wireName, row.domain)
            assertEquals("load stage of ${code.name}", code.stage?.wireName ?: NONE, row.loadStage)
            assertEquals("show stage of ${code.name}", showStageCell(code), row.showStage)
            assertEquals("external source of ${code.name}", code.externalSource?.wireName ?: NONE, row.externalSource)
            assertEquals("retired state of ${code.name}", code.retired, row.status == "retired")
        }
    }
}
