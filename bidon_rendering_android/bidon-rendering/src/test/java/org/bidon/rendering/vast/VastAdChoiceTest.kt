package org.bidon.rendering.vast

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A response with several `<Ad>` elements plays exactly one of them. The choice is pure, so it is
 * pinned here example by example and then across random responses against a second formulation of
 * the same rules.
 */
class VastAdChoiceTest {

    @Test
    fun `no ads gives no choice`() {
        assertNull(VastAdChoice.choose(emptyList(), podsAllowed = true))
        assertNull(VastAdChoice.choose(emptyList(), podsAllowed = false))
    }

    @Test
    fun `a single stand-alone ad is chosen`() {
        assertEquals(0, VastAdChoice.choose(listOf(null), podsAllowed = true))
        assertEquals(0, VastAdChoice.choose(listOf(null), podsAllowed = false))
    }

    @Test
    fun `the first stand-alone ad is chosen from a buffet`() {
        assertEquals(0, VastAdChoice.choose(listOf(null, null, null), podsAllowed = true))
        assertEquals(0, VastAdChoice.choose(listOf(null, null, null), podsAllowed = false))
    }

    @Test
    fun `the smallest sequence is chosen when pods are allowed`() {
        assertEquals(1, VastAdChoice.choose(listOf(2, 1), podsAllowed = true))
        assertEquals(2, VastAdChoice.choose(listOf(3, 2, 1), podsAllowed = true))
    }

    @Test
    fun `equal sequences are broken by document order`() {
        assertEquals(1, VastAdChoice.choose(listOf(3, 1, 1), podsAllowed = true))
    }

    @Test
    fun `a pod beats a stand-alone ad even when the stand-alone ad comes first`() {
        assertEquals(1, VastAdChoice.choose(listOf(null, 1), podsAllowed = true))
        assertEquals(2, VastAdChoice.choose(listOf(null, null, 4), podsAllowed = true))
    }

    @Test
    fun `a stand-alone ad is chosen over a pod when pods are not allowed`() {
        assertEquals(1, VastAdChoice.choose(listOf(1, null), podsAllowed = false))
        assertEquals(2, VastAdChoice.choose(listOf(2, 1, null, null), podsAllowed = false))
    }

    @Test
    fun `a lone ad plays whatever its sequence`() {
        assertEquals(0, VastAdChoice.choose(listOf(1), podsAllowed = false))
        assertEquals(0, VastAdChoice.choose(listOf(3), podsAllowed = true))
    }

    @Test
    fun `nothing is chosen from a pod when pods are not allowed`() {
        assertNull(VastAdChoice.choose(listOf(1, 2), podsAllowed = false))
    }

    private val sequences: Arb<List<Int?>> = Arb.list(Arb.int(-2..6).orNull(0.3), 0..8)

    /** The same rules, written as a sort over (sequence, position) pairs instead of a scan. */
    private fun expected(sequences: List<Int?>, podsAllowed: Boolean): Int? {
        val sequenced = sequences.withIndex().mapNotNull { (index, sequence) -> sequence?.let { it to index } }
        val standAlone = sequences.withIndex().filter { it.value == null }.map { it.index }
        return if (sequences.size == 1) {
            0
        } else if (podsAllowed && sequenced.isNotEmpty()) {
            sequenced.sortedWith(compareBy({ it.first }, { it.second })).first().second
        } else {
            standAlone.firstOrNull()
        }
    }

    @Test
    fun `any response gives the choice the rules describe`() {
        runBlocking {
            checkAll(5_000, sequences, Arb.boolean()) { list, podsAllowed ->
                assertEquals(
                    "$list, pods allowed: $podsAllowed",
                    expected(list, podsAllowed),
                    VastAdChoice.choose(list, podsAllowed)
                )
            }
        }
    }

    @Test
    fun `the choice is always an ad of the response`() {
        runBlocking {
            checkAll(5_000, sequences, Arb.boolean()) { list, podsAllowed ->
                val chosen = VastAdChoice.choose(list, podsAllowed)
                assertTrue("$list gave $chosen", chosen == null || chosen in list.indices)
            }
        }
    }

    @Test
    fun `a response with any ad that may play always gets a choice`() {
        runBlocking {
            checkAll(5_000, sequences) { list ->
                if (list.any { it == null }) {
                    assertNotNull("$list, pods not allowed", VastAdChoice.choose(list, podsAllowed = false))
                }
                if (list.isNotEmpty()) {
                    assertNotNull("$list, pods allowed", VastAdChoice.choose(list, podsAllowed = true))
                }
            }
        }
    }

    @Test
    fun `without pods the choice from several ads is never a sequenced ad`() {
        runBlocking {
            checkAll(5_000, sequences) { list ->
                if (list.size < 2) return@checkAll
                val chosen = VastAdChoice.choose(list, podsAllowed = false) ?: return@checkAll
                assertNull("$list gave $chosen", list[chosen])
            }
        }
    }
}
