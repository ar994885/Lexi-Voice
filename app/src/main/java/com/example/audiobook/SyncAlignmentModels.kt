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

data class SyncAlignmentSegment(
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long
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

    fun saveSegments(file: File, segments: List<SyncAlignmentSegment>) {
        file.parentFile?.mkdirs()
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
        file.writeText(arr.toString())
    }

    fun loadSegments(file: File): List<SyncAlignmentSegment> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
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
