package com.example.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SyncMapMerger]. Pure Kotlin, no Android dependencies, so this
 * runs on the JVM: ./gradlew testStandardDebugUnitTest
 */
class SyncMapMergerTest {

    private fun sentence(index: Int, text: String) =
        SyncMapSentenceEntry(sentenceIndex = index, text = text, offsetMs = index * 1000L, endOffsetMs = index * 1000L + 900L)

    private fun chapter(index: Int, sentenceCount: Int, fileName: String = "ch$index.mp3") =
        SyncMapChapterEntry(
            chapterIndex = index,
            title = "Chapter $index",
            fileName = fileName,
            fileUri = "content://test/$fileName",
            durationMs = sentenceCount * 1000L,
            isDirectPath = false,
            sentences = (0 until sentenceCount).map { sentence(it, "sentence $it") }
        )

    private fun book(title: String, narrator: String, chapters: List<SyncMapChapterEntry>) =
        BookSyncMap(
            bookTitle = title,
            author = "Author",
            narrator = narrator,
            year = "2026",
            genre = "Audiobook",
            chapters = chapters
        )

    @Test
    fun `disjoint ranges are unioned and sorted`() {
        val first = book("B", "LexiVoice", listOf(chapter(2, 3), chapter(3, 3)))
        val second = book("B", "LexiVoice TTS", listOf(chapter(4, 3), chapter(1, 3)))

        val merged = SyncMapMerger.merge(listOf(first, second))!!

        assertEquals(listOf(1, 2, 3, 4), merged.chapters.map { it.chapterIndex })
        assertEquals(4, merged.chapters.size)
    }

    @Test
    fun `duplicate chapter keeps the entry with more sentences`() {
        val sparse = book("B", "LexiVoice", listOf(chapter(1, 2)))
        val complete = book("B", "LexiVoice", listOf(chapter(1, 5)))

        val merged = SyncMapMerger.merge(listOf(sparse, complete))!!

        assertEquals(1, merged.chapters.size)
        assertEquals(5, merged.chapters[0].sentences.size)
    }

    @Test
    fun `duplicate chapter keeps the first when equally complete`() {
        val first = book("B", "LexiVoice", listOf(chapter(1, 3, fileName = "from-first.mp3")))
        val second = book("B", "LexiVoice", listOf(chapter(1, 3, fileName = "from-second.mp3")))

        val merged = SyncMapMerger.merge(listOf(first, second))!!

        assertEquals("from-first.mp3", merged.chapters[0].fileName)
    }

    @Test
    fun `book metadata comes from the last map that supplies it`() {
        val first = book("Old Title", "LexiVoice", listOf(chapter(1, 1)))
        val second = book("New Title", "LexiVoice TTS", listOf(chapter(2, 1)))

        val merged = SyncMapMerger.merge(listOf(first, second))!!

        assertEquals("New Title", merged.bookTitle)
        assertEquals("LexiVoice TTS", merged.narrator)
        assertEquals("Author", merged.author)
    }

    @Test
    fun `blank metadata in the newest map falls back to an earlier one`() {
        val first = book("Real Title", "LexiVoice", listOf(chapter(1, 1)))
        val second = book("", "", listOf(chapter(2, 1)))

        val merged = SyncMapMerger.merge(listOf(first, second))!!

        assertEquals("Real Title", merged.bookTitle)
        assertEquals("LexiVoice", merged.narrator)
    }

    @Test
    fun `empty input yields null`() {
        assertNull(SyncMapMerger.merge(emptyList()))
        assertNull(SyncMapMerger.merge(listOf(book("B", "N", emptyList()))))
    }

    @Test
    fun `isUpToDate compares the chapter set`() {
        val merged = SyncMapMerger.merge(
            listOf(book("B", "N", listOf(chapter(1, 2))), book("B", "N", listOf(chapter(2, 2))))
        )!!

        assertFalse(SyncMapMerger.isUpToDate(null, merged))
        assertFalse(SyncMapMerger.isUpToDate(book("B", "N", listOf(chapter(1, 2))), merged))
        assertTrue(SyncMapMerger.isUpToDate(book("B", "N", merged.chapters), merged))
    }
}
