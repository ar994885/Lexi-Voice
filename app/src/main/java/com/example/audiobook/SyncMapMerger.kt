package com.example.audiobook

/**
 * Unions several sync maps that describe different chapter ranges of the same book.
 *
 * Why this exists: the scan only ever read a file named exactly `sync_map.json`, so
 * when a folder ended up with `sync_map (1).json`, `sync_map_2.json` and so on —
 * which is what Android's file manager produces when the same name is copied twice,
 * and what happens whenever recordings made on one device are moved to another —
 * every chapter outside the first file was invisible and sync silently stopped.
 *
 * Chapters are keyed by `chapterIndex`, so maps covering different ranges simply
 * concatenate. Where two maps cover the *same* chapter, the entry with more
 * sentences wins, because that is the more complete recording; a tie keeps the
 * first. Book-level metadata is taken from the last map that supplies a non-blank
 * value, so the most recent export wins.
 *
 * Pure Kotlin with no Android dependencies, so it is directly unit-testable.
 */
object SyncMapMerger {

    fun merge(maps: List<BookSyncMap>): BookSyncMap? {
        if (maps.isEmpty()) return null

        val byIndex = LinkedHashMap<Int, SyncMapChapterEntry>()
        for (map in maps) {
            for (chapter in map.chapters) {
                val existing = byIndex[chapter.chapterIndex]
                if (existing == null || chapter.sentences.size > existing.sentences.size) {
                    byIndex[chapter.chapterIndex] = chapter
                }
            }
        }
        if (byIndex.isEmpty()) return null

        fun lastNonBlank(select: (BookSyncMap) -> String): String =
            maps.asReversed().firstNotNullOfOrNull { select(it).takeIf(String::isNotBlank) } ?: ""

        return BookSyncMap(
            bookTitle = lastNonBlank { it.bookTitle },
            author = lastNonBlank { it.author },
            narrator = lastNonBlank { it.narrator },
            year = lastNonBlank { it.year },
            genre = lastNonBlank { it.genre },
            coverPath = maps.asReversed().firstNotNullOfOrNull { it.coverPath },
            sourceEpubKey = maps.asReversed().firstNotNullOfOrNull { it.sourceEpubKey },
            sourceEpubIsDirect = maps.any { it.sourceEpubIsDirect },
            chapters = byIndex.values.sortedBy { it.chapterIndex }
        )
    }

    /**
     * True when [consolidated] already covers exactly the chapters in [merged], in
     * which case there is nothing to rewrite and the folder can be left alone.
     */
    fun isUpToDate(consolidated: BookSyncMap?, merged: BookSyncMap): Boolean {
        if (consolidated == null) return false
        if (consolidated.chapters.size != merged.chapters.size) return false
        if (consolidated.chapters.map { it.chapterIndex } != merged.chapters.map { it.chapterIndex }) {
            return false
        }
        // Sentence counts count too. A richer sibling map can add sentences to a
        // chapter that is already present, and comparing only the chapter set left
        // the consolidated file on disk permanently behind what playback used.
        val onDisk = consolidated.chapters.associate { it.chapterIndex to it.sentences.size }
        return merged.chapters.all { onDisk[it.chapterIndex] == it.sentences.size }
    }
}
