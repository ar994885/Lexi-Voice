package com.example.epub

data class EpubIndexedMetadata(
    val title: String,
    val author: String,
    val language: String,
    val coverPath: String?,
    val chapterCount: Int
)

data class EpubChapterSummary(
    val index: Int,
    val title: String,
    val href: String,
    val wordCount: Int = 0,
    val estimatedMinutes: Int = 1
)

data class ReaderSentence(
    val globalIndex: Int,
    val paragraphIndex: Int,
    val startInParagraph: Int,
    val endInParagraph: Int,
    val text: String
)

data class ReaderParagraph(
    val index: Int,
    val text: String,
    val isHeading: Boolean,
    val headingLevel: Int = 0,
    val sentences: List<ReaderSentence>
)

data class EpubChapterContent(
    val index: Int,
    val title: String,
    val paragraphs: List<ReaderParagraph>,
    val sentences: List<ReaderSentence>,
    val plainText: String
)

data class EpubBookStructure(
    val title: String,
    val author: String,
    val language: String,
    val coverPath: String?,
    val chapters: List<EpubChapterSummary>
)
