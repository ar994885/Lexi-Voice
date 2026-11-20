package com.example.audiobook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SyncReconciler]. Pure Kotlin, no Android dependencies, so this
 * runs on the JVM: ./gradlew testStandardDebugUnitTest
 */
class SyncReconcilerTest {

    @Test
    fun `unchanged segmentation maps one to one`() {
        val segments = listOf("First sentence.", "Second sentence.", "Third sentence.")
        val sentences = listOf("First sentence.", "Second sentence.", "Third sentence.")

        assertArrayEquals(intArrayOf(0, 1, 2), SyncReconciler.align(segments, sentences))
    }

    @Test
    fun `italic run folded into its sentence maps every fragment to that sentence`() {
        // The real case: the old rule emitted the <em> content as its own sentence.
        val segments = listOf(
            "On a mountain peak next to Ten Thousand Swords Peak,",
            "Li Yuanba",
            "was setting up a Formation."
        )
        val sentences = listOf(
            "On a mountain peak next to Ten Thousand Swords Peak, Li Yuanba was setting up a Formation.",
            "Next sentence."
        )

        assertArrayEquals(intArrayOf(0, 0, 0), SyncReconciler.align(segments, sentences))
    }

    @Test
    fun `merges do not shift the sentences that follow them`() {
        val segments = listOf(
            "Alpha.",
            "Beta was",
            "spoken as two.",
            "Gamma."
        )
        val sentences = listOf(
            "Alpha.",
            "Beta was spoken as two.",
            "Gamma."
        )

        // The whole point: Gamma must land on index 2, not index 3.
        assertArrayEquals(intArrayOf(0, 1, 1, 2), SyncReconciler.align(segments, sentences))
    }

    @Test
    fun `spacing artefact from the old rule still matches`() {
        // Old data contains `" Shi Ming` where the source has `"Shi Ming`.
        val segments = listOf("\" Shi Ming is here.")
        val sentences = listOf("\"Shi Ming is here.")

        assertArrayEquals(intArrayOf(0), SyncReconciler.align(segments, sentences))
    }

    @Test
    fun `one segment spanning several sentences maps to the first of them`() {
        val segments = listOf("One long spoken line.", "After.")
        val sentences = listOf("One long", "spoken line.", "After.")

        val mapping = SyncReconciler.align(segments, sentences)
        assertEquals(0, mapping[0])
        assertEquals(2, mapping[1])
    }

    @Test
    fun `repeated identical sentences resolve in order`() {
        val segments = listOf("He nodded.", "She left.", "He nodded.")
        val sentences = listOf("He nodded.", "She left.", "He nodded.")

        assertArrayEquals(intArrayOf(0, 1, 2), SyncReconciler.align(segments, sentences))
    }

    @Test
    fun `a sentence added to the book does not derail the rest of the chapter`() {
        val segments = listOf("Alpha.", "Gamma.")
        val sentences = listOf("Alpha.", "Beta, newly inserted.", "Gamma.")

        // Gamma must resync onto index 2 rather than staying at 1.
        assertEquals(0, SyncReconciler.align(segments, sentences)[0])
        assertEquals(2, SyncReconciler.align(segments, sentences)[1])
    }

    @Test
    fun `a sentence removed from the book does not derail the rest of the chapter`() {
        val segments = listOf("Alpha.", "Beta.", "Gamma.")
        val sentences = listOf("Alpha.", "Gamma.")

        val mapping = SyncReconciler.align(segments, sentences)
        assertEquals(0, mapping[0])
        assertEquals(1, mapping[2])
    }

    @Test
    fun `mapping is never negative when both lists have content`() {
        val segments = listOf("Alpha.", "Beta.", "Gamma.")
        val sentences = listOf("Totally different.", "Also different.")

        val mapping = SyncReconciler.align(segments, sentences)
        // Unmatched segments report -1 rather than a wrong position.
        assertTrue(mapping.all { it == -1 })
    }

    @Test
    fun `recording that stopped mid-chapter still maps its prefix`() {
        // Common in practice: the audio covers only part of the chapter, so the
        // segments are a prefix of the sentences and the character streams differ.
        val segments = listOf("Alpha.", "Beta was", "spoken as two.")
        val sentences = listOf("Alpha.", "Beta was spoken as two.", "Gamma, never recorded.")

        assertArrayEquals(intArrayOf(0, 1, 1), SyncReconciler.align(segments, sentences))
    }

    @Test
    fun `mapping stays monotonic across a long mixed chapter`() {
        // Alternating merges and plain sentences, as the italic rule produced.
        val segments = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        repeat(40) { n ->
            segments.add("Sentence $n starts,")
            segments.add("emphasis $n")
            segments.add("and finishes.")
            sentences.add("Sentence $n starts, emphasis $n and finishes.")
        }

        val mapping = SyncReconciler.align(segments, sentences)
        assertEquals(segments.size, mapping.size)
        for (k in 1 until mapping.size) {
            assertTrue("mapping must not go backwards at $k", mapping[k] >= mapping[k - 1])
        }
        assertEquals(0, mapping.first())
        assertEquals(sentences.size - 1, mapping.last())
    }

    @Test
    fun `empty inputs yield no mappings`() {
        assertEquals(0, SyncReconciler.align(emptyList(), listOf("A.")).size)
        assertArrayEquals(intArrayOf(-1), SyncReconciler.align(listOf("A."), emptyList()))
    }

    @Test
    fun `normalise ignores case and all whitespace`() {
        assertEquals("shimingishere.", SyncReconciler.normalise("  Shi  Ming   is here. "))
        assertEquals(SyncReconciler.normalise("A b"), SyncReconciler.normalise("ab"))
    }
}
