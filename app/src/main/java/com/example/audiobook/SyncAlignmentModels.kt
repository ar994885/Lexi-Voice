package com.example.audiobook

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AudiobookChapterMeta(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long
)

data class Mp3ChapterFileMeta(
    val chapterIndex: Int,
    val title: String,
    val fileName: String,
    val fileUri: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val isDirectPath: Boolean = false
)

data class SyncAlignmentSegment(
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class SyncMapSentenceEntry(
    val sentenceIndex: Int,
    val text: String,
    val offsetMs: Long,
    val endOffsetMs: Long
)

data class SyncMapChapterEntry(
    val chapterIndex: Int,
    val title: String,
    val fileName: String,
    val fileUri: String = "",
    val durationMs: Long,
    val isDirectPath: Boolean = false,
    val sentences: List<SyncMapSentenceEntry> = emptyList()
)

data class BookSyncMap(
    val bookTitle: String,
    val author: String,
    val narrator: String = "LexiVoice TTS",
    val year: String = "2026",
    val genre: String = "Audiobook",
    val coverPath: String? = null,
    val sourceEpubKey: String? = null,
    val sourceEpubIsDirect: Boolean = false,
    val chapters: List<SyncMapChapterEntry> = emptyList()
)

data class FlattenedSyncTimeline(
    val chapters: List<AudiobookChapterMeta>,
    val segments: List<SyncAlignmentSegment>,
    val chapterFiles: List<Mp3ChapterFileMeta>,
    val totalDurationMs: Long
)

object SyncAlignmentSerializer {

    fun encodeChapters(chapters: List<AudiobookChapterMeta>): String {
        val arr = JSONArray()
        chapters.forEach { ch ->
            arr.put(
                JSONObject().apply {
                    put("index", ch.index)
                    put("title", ch.title)
                    put("startMs", ch.startMs)
                    put("endMs", ch.endMs)
                }
            )
        }
        return arr.toString()
    }

    fun decodeChapters(raw: String): List<AudiobookChapterMeta> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                AudiobookChapterMeta(
                    index = obj.optInt("index", idx),
                    title = obj.optString("title", "Chapter ${idx + 1}"),
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun normalizeChapterOrdinals(chapters: List<AudiobookChapterMeta>): List<AudiobookChapterMeta> {
        return chapters.mapIndexed { ordinalIdx, ch ->
            if (ch.index == ordinalIdx) ch else ch.copy(index = ordinalIdx)
        }
    }

    fun encodeChapterFiles(chapters: List<Mp3ChapterFileMeta>): String {
        val arr = JSONArray()
        chapters.forEach { ch ->
            arr.put(
                JSONObject().apply {
                    put("chapterIndex", ch.chapterIndex)
                    put("title", ch.title)
                    put("fileName", ch.fileName)
                    put("fileUri", ch.fileUri)
                    put("startMs", ch.startMs)
                    put("endMs", ch.endMs)
                    put("durationMs", ch.durationMs)
                    put("isDirectPath", ch.isDirectPath)
                }
            )
        }
        return arr.toString()
    }

    fun decodeChapterFiles(raw: String): List<Mp3ChapterFileMeta> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val uri = obj.optString("fileUri")
                if (uri.isBlank()) return@mapNotNull null
                Mp3ChapterFileMeta(
                    chapterIndex = obj.optInt("chapterIndex", idx),
                    title = obj.optString("title", "Chapter ${idx + 1}"),
                    fileName = obj.optString("fileName", "Chapter ${idx + 1}.mp3"),
                    fileUri = uri,
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L),
                    durationMs = obj.optLong("durationMs", 0L),
                    isDirectPath = obj.optBoolean("isDirectPath", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encodeBookSyncMap(map: BookSyncMap): String {
        val root = JSONObject().apply {
            put("bookTitle", map.bookTitle)
            put("author", map.author)
            put("narrator", map.narrator)
            put("year", map.year)
            put("genre", map.genre)
            put("coverPath", map.coverPath ?: JSONObject.NULL)
            put("sourceEpubKey", map.sourceEpubKey ?: JSONObject.NULL)
            put("sourceEpubIsDirect", map.sourceEpubIsDirect)
            val chArr = JSONArray()
            map.chapters.sortedBy { it.chapterIndex }.forEach { ch ->
                chArr.put(
                    JSONObject().apply {
                        put("chapterIndex", ch.chapterIndex)
                        put("title", ch.title)
                        put("fileName", ch.fileName)
                        put("fileUri", ch.fileUri)
                        put("durationMs", ch.durationMs)
                        put("isDirectPath", ch.isDirectPath)
                        val sentArr = JSONArray()
                        ch.sentences.forEach { s ->
                            sentArr.put(
                                JSONObject().apply {
                                    put("sentenceIndex", s.sentenceIndex)
                                    put("text", s.text)
                                    put("offsetMs", s.offsetMs)
                                    put("endOffsetMs", s.endOffsetMs)
                                }
                            )
                        }
                        put("sentences", sentArr)
                    }
                )
            }
            put("chapters", chArr)
        }
        return root.toString(2)
    }

    fun decodeBookSyncMap(raw: String): BookSyncMap? {
        if (raw.isBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val chArr = root.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until chArr.length()).mapNotNull { i ->
                val chObj = chArr.optJSONObject(i) ?: return@mapNotNull null
                val sentArr = chObj.optJSONArray("sentences") ?: JSONArray()
                val sentences = (0 until sentArr.length()).mapNotNull { j ->
                    val sObj = sentArr.optJSONObject(j) ?: return@mapNotNull null
                    SyncMapSentenceEntry(
                        sentenceIndex = sObj.optInt("sentenceIndex", j),
                        text = sObj.optString("text", ""),
                        offsetMs = sObj.optLong("offsetMs", 0L),
                        endOffsetMs = sObj.optLong("endOffsetMs", 0L)
                    )
                }
                SyncMapChapterEntry(
                    chapterIndex = chObj.optInt("chapterIndex", i),
                    title = chObj.optString("title", "Chapter ${i + 1}"),
                    fileName = chObj.optString("fileName", ""),
                    fileUri = chObj.optString("fileUri", ""),
                    durationMs = chObj.optLong("durationMs", 0L),
                    isDirectPath = chObj.optBoolean("isDirectPath", false),
                    sentences = sentences
                )
            }.sortedBy { it.chapterIndex }

            BookSyncMap(
                bookTitle = root.optString("bookTitle", "Audiobook"),
                author = root.optString("author", "Unknown Author"),
                narrator = root.optString("narrator", "LexiVoice TTS"),
                year = root.optString("year", "2026"),
                genre = root.optString("genre", "Audiobook"),
                coverPath = root.optString("coverPath", "").takeIf { it.isNotBlank() && it != "null" },
                sourceEpubKey = root.optString("sourceEpubKey", "").takeIf { it.isNotBlank() && it != "null" },
                sourceEpubIsDirect = root.optBoolean("sourceEpubIsDirect", false),
                chapters = chapters
            )
        }.getOrNull()
    }

    fun mergeChapterIntoSyncMap(
        existing: BookSyncMap?,
        newChapter: SyncMapChapterEntry,
        bookTitle: String,
        author: String,
        narrator: String,
        year: String,
        genre: String,
        coverPath: String?,
        sourceEpubKey: String?,
        sourceEpubIsDirect: Boolean
    ): BookSyncMap {
        val mergedChapters = (existing?.chapters.orEmpty()
            .filterNot { it.chapterIndex == newChapter.chapterIndex } + newChapter)
            .sortedBy { it.chapterIndex }

        return BookSyncMap(
            bookTitle = bookTitle.ifBlank { existing?.bookTitle ?: "Audiobook" },
            author = author.ifBlank { existing?.author ?: "Unknown Author" },
            narrator = narrator.ifBlank { existing?.narrator ?: "LexiVoice TTS" },
            year = year.ifBlank { existing?.year ?: "2026" },
            genre = genre.ifBlank { existing?.genre ?: "Audiobook" },
            coverPath = coverPath ?: existing?.coverPath,
            sourceEpubKey = sourceEpubKey ?: existing?.sourceEpubKey,
            sourceEpubIsDirect = if (sourceEpubKey != null) sourceEpubIsDirect else (existing?.sourceEpubIsDirect ?: false),
            chapters = mergedChapters
        )
    }

    fun flattenToTimeline(syncMap: BookSyncMap): FlattenedSyncTimeline {
        val sorted = syncMap.chapters.sortedBy { it.chapterIndex }
        val chapterMetas = mutableListOf<AudiobookChapterMeta>()
        val segments = mutableListOf<SyncAlignmentSegment>()
        val chapterFiles = mutableListOf<Mp3ChapterFileMeta>()
        var cursorMs = 0L

        sorted.forEachIndexed { ordinalIdx, ch ->
            val dur = ch.durationMs.coerceAtLeast(100L)
            val chStart = cursorMs
            val chEnd = chStart + dur

            chapterMetas.add(
                AudiobookChapterMeta(
                    index = ordinalIdx,
                    title = ch.title,
                    startMs = chStart,
                    endMs = chEnd
                )
            )

            if (ch.fileUri.isNotBlank()) {
                chapterFiles.add(
                    Mp3ChapterFileMeta(
                        chapterIndex = ch.chapterIndex,
                        title = ch.title,
                        fileName = ch.fileName,
                        fileUri = ch.fileUri,
                        startMs = chStart,
                        endMs = chEnd,
                        durationMs = dur,
                        isDirectPath = ch.isDirectPath
                    )
                )
            }

            ch.sentences.forEach { s ->
                val segStart = chStart + s.offsetMs
                val segEnd = (chStart + s.endOffsetMs).coerceAtLeast(segStart + 50L)
                segments.add(
                    SyncAlignmentSegment(
                        chapterIndex = ch.chapterIndex,
                        sentenceIndex = s.sentenceIndex,
                        text = s.text,
                        startMs = segStart,
                        endMs = segEnd
                    )
                )
            }

            cursorMs = chEnd
        }

        return FlattenedSyncTimeline(
            chapters = chapterMetas,
            segments = segments,
            chapterFiles = chapterFiles,
            totalDurationMs = cursorMs
        )
    }

    fun remapSyncSegmentsForM4b(
        compiledChapters: List<Pair<Int, Long>>,
        syncMap: BookSyncMap
    ): List<SyncAlignmentSegment> {
        val chapterByIdx = syncMap.chapters.associateBy { it.chapterIndex }
        val result = mutableListOf<SyncAlignmentSegment>()
        for ((epubChapterIndex, m4bStartMs) in compiledChapters) {
            val ch = chapterByIdx[epubChapterIndex] ?: continue
            ch.sentences.forEach { s ->
                val segStart = m4bStartMs + s.offsetMs
                val segEnd = (m4bStartMs + s.endOffsetMs).coerceAtLeast(segStart + 50L)
                result.add(
                    SyncAlignmentSegment(
                        chapterIndex = epubChapterIndex,
                        sentenceIndex = s.sentenceIndex,
                        text = s.text,
                        startMs = segStart,
                        endMs = segEnd
                    )
                )
            }
        }
        return result
    }

    fun saveSegments(file: File, segments: List<SyncAlignmentSegment>) {
        val parent = file.parentFile?.apply { mkdirs() }
        val arr = JSONArray()
        segments.forEach { seg ->
            arr.put(
                JSONObject().apply {
                    put("chapterIndex", seg.chapterIndex)
                    put("sentenceIndex", seg.sentenceIndex)
                    put("text", seg.text)
                    put("startMs", seg.startMs)
                    put("endMs", seg.endMs)
                }
            )
        }
        val payload = arr.toString()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
    }

    fun loadSegments(file: File): List<SyncAlignmentSegment> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText().trim()
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    SyncAlignmentSegment(
                        chapterIndex = obj.optInt("chapterIndex", 0),
                        sentenceIndex = obj.optInt("sentenceIndex", 0),
                        text = obj.optString("text", ""),
                        startMs = obj.optLong("startMs", 0L),
                        endMs = obj.optLong("endMs", 0L)
                    )
                }
            } else if (text.startsWith("{")) {
                decodeBookSyncMap(text)?.let { bsm ->
                    flattenToTimeline(bsm).segments
                }.orEmpty()
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Binary-searches the sorted alignment timeline in O(log N) for the active sentence at positionMs.
     */
    fun findSegmentAtPosition(
        segments: List<SyncAlignmentSegment>,
        positionMs: Long
    ): SyncAlignmentSegment? {
        if (segments.isEmpty()) return null
        var low = 0
        var high = segments.lastIndex
        var candidate: SyncAlignmentSegment? = segments.first()

        while (low <= high) {
            val mid = (low + high) ushr 1
            val seg = segments[mid]
            when {
                positionMs < seg.startMs -> high = mid - 1
                positionMs >= seg.endMs -> {
                    candidate = seg
                    low = mid + 1
                }
                else -> return seg
            }
        }
        return candidate
    }
}
