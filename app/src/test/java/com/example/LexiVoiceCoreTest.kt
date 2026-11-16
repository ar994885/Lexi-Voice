package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audiobook.AudiobookChapterMeta
import com.example.audiobook.BookSyncMap
import com.example.audiobook.M4bPlayerStateHolder
import com.example.audiobook.Mp3ChapterFileMeta
import com.example.audiobook.SyncAlignmentSegment
import com.example.audiobook.SyncAlignmentSerializer
import com.example.audiobook.SyncMapChapterEntry
import com.example.audiobook.SyncMapSentenceEntry
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookEntity
import com.example.data.db.LexiDatabase
import com.example.data.db.LibrarySortOption
import com.example.epub.EpubParser
import com.example.export.ChapterTiming
import com.example.export.M4aMetadataConfig
import com.example.export.Mp3AudioEncoder
import com.example.export.Mp4ChapterAndMetadataWriter
import com.example.overlay.FloatingOverlayController
import com.example.overlay.UnifiedOverlaySource
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LexiVoiceCoreTest {

    @Test
    fun `epub parser extracts metadata chapters webview spans and css`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val epubFile = File(context.cacheDir, "sample_test.epub")
        createSampleEpub(epubFile)

        val meta = EpubParser.extractMetadataForIndexing(
            context = context,
            fileKey = epubFile.absolutePath,
            isDirectPath = true,
            fallbackFileName = epubFile.name
        )
        assertEquals("The Time Machine", meta.title)
        assertEquals("H. G. Wells", meta.author)
        assertEquals(2, meta.chapterCount)

        val structure = EpubParser.parseBookStructure(
            context = context,
            fileKey = epubFile.absolutePath,
            isDirectPath = true,
            fallbackTitle = "Fallback"
        )
        assertEquals(2, structure.chapters.size)
        assertEquals("Chapter I: Introduction", structure.chapters[0].title)
        assertEquals("Chapter II: The Machine", structure.chapters[1].title)

        val webPayload = EpubParser.loadChapterForWebView(
            context = context,
            fileKey = epubFile.absolutePath,
            isDirectPath = true,
            chapterIndex = 0,
            chapterHref = structure.chapters[0].href,
            chapterTitle = structure.chapters[0].title
        )
        assertTrue(webPayload.paragraphs.size >= 2)
        assertTrue(webPayload.sentences.size >= 3)
        assertTrue(webPayload.htmlWithSentenceSpans.contains("lexi-s"))
        assertTrue(webPayload.htmlWithSentenceSpans.contains("data-idx=\"0\""))
        assertTrue(webPayload.htmlWithSentenceSpans.contains("styles.css"))
        assertTrue(webPayload.htmlWithSentenceSpans.contains("lexiHighlightSentence"))
        epubFile.delete()
    }

    @Test
    fun `sync alignment binary search resolves active sentence and json roundtrips`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val syncFile = File(context.cacheDir, "sync_test.json")
        val segments = listOf(
            SyncAlignmentSegment(0, 0, "First sentence.", 0L, 2100L),
            SyncAlignmentSegment(0, 1, "Second sentence.", 2100L, 4800L),
            SyncAlignmentSegment(1, 0, "Chapter two begins.", 4800L, 7500L)
        )
        SyncAlignmentSerializer.saveSegments(syncFile, segments)
        val decoded = SyncAlignmentSerializer.loadSegments(syncFile)
        assertEquals(3, decoded.size)

        val hit1 = SyncAlignmentSerializer.findSegmentAtPosition(decoded, 2500L)
        assertNotNull(hit1)
        assertEquals(0, hit1?.chapterIndex)
        assertEquals(1, hit1?.sentenceIndex)

        val hit2 = SyncAlignmentSerializer.findSegmentAtPosition(decoded, 6200L)
        assertEquals(1, hit2?.chapterIndex)
        assertEquals(0, hit2?.sentenceIndex)

        val chaptersJson = SyncAlignmentSerializer.encodeChapters(
            listOf(AudiobookChapterMeta(0, "Ch 1", 0L, 4800L))
        )
        val decodedCh = SyncAlignmentSerializer.decodeChapters(chaptersJson)
        assertEquals(1, decodedCh.size)
        assertEquals("Ch 1", decodedCh[0].title)
        syncFile.delete()
    }

    @Test
    fun `mp3 audio encoder merges wav chunks into valid mp3 with id3`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wav1 = File(context.cacheDir, "chunk1.wav")
        val wav2 = File(context.cacheDir, "chunk2.wav")
        val outMp3 = File(context.cacheDir, "merged_chapter.mp3")

        writeTestWav(wav1, sampleRate = 22050, durationMs = 200)
        writeTestWav(wav2, sampleRate = 22050, durationMs = 200)

        val ok = Mp3AudioEncoder.mergeWavChunksToMp3(
            wavFiles = listOf(wav1, wav2),
            outputMp3 = outMp3,
            title = "Chapter 1",
            artist = "Test Author",
            album = "Test Book",
            trackNumber = 1,
            bitrateKbps = 96
        )

        assertTrue(ok)
        assertTrue(outMp3.exists() && outMp3.length() > 500L)
        val header = outMp3.readBytes().take(3).map { it.toInt() and 0xFF }
        val hasId3 = header == listOf('I'.code, 'D'.code, '3'.code)
        val hasSync = header[0] == 0xFF && (header[1] and 0xE0) == 0xE0
        assertTrue(hasId3 || hasSync)

        wav1.delete()
        wav2.delete()
        outMp3.delete()
    }

    @Test
    fun `mp4 chapter writer injects m4b brand chpl covr and ilst metadata cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawMp4 = File(context.cacheDir, "raw_test.m4b")
        val taggedMp4 = File(context.cacheDir, "tagged_test.m4b")

        val ftyp = wrapAtom("ftyp", "M4A ".toByteArray() + ByteArray(4) + "M4A mp42isom".toByteArray())
        val mdat = wrapAtom("mdat", ByteArray(64))
        val stcoPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0)
                writeInt(1)
                writeInt(ftyp.size + 8)
            }
        }.toByteArray()
        val stco = wrapAtom("stco", stcoPayload)
        val stbl = wrapAtom("stbl", stco)
        val minf = wrapAtom("minf", stbl)
        val mdia = wrapAtom("mdia", minf)
        val trak = wrapAtom("trak", mdia)
        val moov = wrapAtom("moov", trak)

        FileOutputStream(rawMp4).use {
            it.write(ftyp)
            it.write(mdat)
            it.write(moov)
        }

        Mp4ChapterAndMetadataWriter.injectChaptersAndMetadata(
            inputFile = rawMp4,
            outputFile = taggedMp4,
            chapters = listOf(
                ChapterTiming("001 - Opening", 0L, 12000L),
                ChapterTiming("002 - Finale", 12000L, 25000L)
            ),
            metadata = M4aMetadataConfig(
                enabled = true,
                title = "My M4B Audiobook",
                author = "Author Name",
                album = "Book Series",
                narrator = "LexiVoice",
                year = "2026",
                genre = "Audiobook"
            ),
            coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            useM4bBrand = true
        )

        val resultText = String(taggedMp4.readBytes(), StandardCharsets.ISO_8859_1)
        assertTrue(resultText.contains("M4B "))
        assertTrue(resultText.contains("chpl"))
        assertTrue(resultText.contains("covr"))
        assertTrue(resultText.contains("001 - Opening"))
        assertTrue(resultText.contains("002 - Finale"))
        assertTrue(resultText.contains("My M4B Audiobook"))
        assertTrue(resultText.contains("Author Name"))

        rawMp4.delete()
        taggedMp4.delete()
    }

    @Test
    fun `room database indexes books and m4b audiobooks efficiently`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LexiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val bookDao = db.bookDao()
        val audiobookDao = db.audiobookDao()

        bookDao.upsertPreservingProgress(
            listOf(
                BookEntity(
                    fileKey = "/books/dune.epub",
                    folderKey = "/books",
                    isDirectPath = true,
                    title = "Dune",
                    author = "Frank Herbert",
                    chapterCount = 48,
                    addedAt = 100L
                ),
                BookEntity(
                    fileKey = "/books/foundation.epub",
                    folderKey = "/books",
                    isDirectPath = true,
                    title = "Foundation",
                    author = "Isaac Asimov",
                    chapterCount = 22,
                    addedAt = 200L
                )
            )
        )

        val searchResults = bookDao.observeLibraryBooks("Herbert", null, LibrarySortOption.TITLE).first()
        assertEquals(1, searchResults.size)
        assertEquals("Dune", searchResults.first().title)

        val sortedByAuthor = bookDao.observeLibraryBooks("", null, LibrarySortOption.AUTHOR).first()
        assertEquals("Dune", sortedByAuthor[0].title)
        assertEquals("Foundation", sortedByAuthor[1].title)

        val m4bId = audiobookDao.upsertAudiobook(
            AudiobookEntity(
                fileUri = "/books/Dune.m4b",
                isDirectPath = true,
                title = "Dune",
                author = "Frank Herbert",
                durationMs = 120_000L,
                chapterCount = 48,
                sourceEpubKey = "/books/dune.epub",
                syncJsonPath = "/files/sync/dune.json"
            )
        )
        assertTrue(m4bId > 0L)
        audiobookDao.updatePlaybackProgress(m4bId, 45_000L, 999L)
        val loaded = audiobookDao.getAudiobookById(m4bId)
        assertEquals(45_000L, loaded?.lastPositionMs)

        val folderFiles = listOf(
            Mp3ChapterFileMeta(0, "Chapter 1", "001 - Chapter 1.mp3", "/export/Book/001 - Chapter 1.mp3", 0L, 5000L, 5000L, true),
            Mp3ChapterFileMeta(2, "Chapter 3", "003 - Chapter 3.mp3", "/export/Book/003 - Chapter 3.mp3", 5000L, 12000L, 7000L, true)
        )
        val folderId = audiobookDao.upsertAudiobook(
            AudiobookEntity(
                fileUri = "folder:///export/Book",
                isDirectPath = true,
                title = "Dune MP3 Folder",
                author = "Frank Herbert",
                durationMs = 12_000L,
                chapterCount = 2,
                chaptersJson = SyncAlignmentSerializer.encodeChapters(
                    listOf(
                        AudiobookChapterMeta(0, "Chapter 1", 0L, 5000L),
                        AudiobookChapterMeta(1, "Chapter 3", 5000L, 12_000L)
                    )
                ),
                isFolderAudiobook = true,
                folderUri = "/export/Book",
                chapterFilesJson = SyncAlignmentSerializer.encodeChapterFiles(folderFiles)
            )
        )
        val loadedFolder = audiobookDao.getAudiobookById(folderId)
        assertNotNull(loadedFolder)
        assertTrue(loadedFolder!!.isFolderAudiobook)
        assertEquals(2, SyncAlignmentSerializer.decodeChapterFiles(loadedFolder.chapterFilesJson).size)

        db.close()
    }

    @Test
    fun `book sync map merges incremental chapters flattens timeline and remaps for m4b`() {
        val ch1 = SyncMapChapterEntry(
            chapterIndex = 0,
            title = "Chapter 1",
            fileName = "001 - Chapter 1.mp3",
            fileUri = "/books/Dune/001 - Chapter 1.mp3",
            durationMs = 4000L,
            isDirectPath = true,
            sentences = listOf(
                SyncMapSentenceEntry(0, "Ch1 S1", 0L, 1800L),
                SyncMapSentenceEntry(1, "Ch1 S2", 1800L, 4000L)
            )
        )
        val ch3 = SyncMapChapterEntry(
            chapterIndex = 2,
            title = "Chapter 3",
            fileName = "003 - Chapter 3.mp3",
            fileUri = "/books/Dune/003 - Chapter 3.mp3",
            durationMs = 6000L,
            isDirectPath = true,
            sentences = listOf(
                SyncMapSentenceEntry(0, "Ch3 S1", 0L, 3000L),
                SyncMapSentenceEntry(1, "Ch3 S2", 3000L, 6000L)
            )
        )

        val step1 = SyncAlignmentSerializer.mergeChapterIntoSyncMap(
            existing = null,
            newChapter = ch1,
            bookTitle = "Dune",
            author = "Frank Herbert",
            narrator = "LexiVoice",
            year = "2026",
            genre = "Audiobook",
            coverPath = null,
            sourceEpubKey = "/books/dune.epub",
            sourceEpubIsDirect = true
        )
        val step2 = SyncAlignmentSerializer.mergeChapterIntoSyncMap(
            existing = step1,
            newChapter = ch3,
            bookTitle = "Dune",
            author = "Frank Herbert",
            narrator = "LexiVoice",
            year = "2026",
            genre = "Audiobook",
            coverPath = null,
            sourceEpubKey = "/books/dune.epub",
            sourceEpubIsDirect = true
        )

        val encoded = SyncAlignmentSerializer.encodeBookSyncMap(step2)
        val decoded = SyncAlignmentSerializer.decodeBookSyncMap(encoded)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.chapters.size)

        val timeline = SyncAlignmentSerializer.flattenToTimeline(decoded)
        assertEquals(10_000L, timeline.totalDurationMs)
        assertEquals(2, timeline.chapters.size)
        assertEquals(0, timeline.chapters[0].index)
        assertEquals(1, timeline.chapters[1].index)
        assertEquals(4, timeline.segments.size)
        assertEquals(2, timeline.segments[2].chapterIndex)
        assertEquals(4000L, timeline.segments[2].startMs)

        val remapped = SyncAlignmentSerializer.remapSyncSegmentsForM4b(
            compiledChapters = listOf(2 to 0L, 0 to 6000L),
            syncMap = decoded
        )
        assertEquals(4, remapped.size)
        assertEquals(2, remapped[0].chapterIndex)
        assertEquals(0L, remapped[0].startMs)
        assertEquals(0, remapped[2].chapterIndex)
        assertEquals(6000L, remapped[2].startMs)
    }

    @Test
    fun `floating overlay controller tracks active source and builds deep link intent`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TtsStateHolder.updateState {
            it.copy(
                isActive = false,
                isPlaying = false,
                sourceType = TtsSourceType.NONE,
                bookId = -1L,
                currentSentenceText = ""
            )
        }
        M4bPlayerStateHolder.updateState {
            it.copy(
                isActive = true,
                isPlaying = true,
                audiobookId = 42L,
                title = "Dune M4B",
                hasSyncedEpub = true,
                activeSyncChapterIndex = 3,
                activeSyncSentenceIndex = 7,
                activeSyncText = "Fear is the mind-killer."
            )
        }
        FloatingOverlayController.markActiveSource(UnifiedOverlaySource.AUDIOBOOK)
        kotlinx.coroutines.delay(100)

        val snapshot = FloatingOverlayController.state.value
        assertEquals(UnifiedOverlaySource.AUDIOBOOK, snapshot.activeSource)
        assertTrue(snapshot.isPlaying)
        assertEquals("Fear is the mind-killer.", snapshot.displayText)
        assertEquals(42L, snapshot.audiobookId)
        assertEquals(3, snapshot.chapterIndex)
        assertEquals(7, snapshot.sentenceIndex)

        val intent = FloatingOverlayController.buildDeepLinkIntent(context, snapshot)
        assertEquals(UnifiedOverlaySource.AUDIOBOOK.name, intent.getStringExtra(FloatingOverlayController.EXTRA_OVERLAY_SOURCE))
        assertEquals(42L, intent.getLongExtra(FloatingOverlayController.EXTRA_AUDIOBOOK_ID, -1L))
        assertEquals(3, intent.getIntExtra(FloatingOverlayController.EXTRA_CHAPTER_INDEX, -1))
        assertEquals(7, intent.getIntExtra(FloatingOverlayController.EXTRA_SENTENCE_INDEX, -1))

        M4bPlayerStateHolder.updateState { com.example.audiobook.M4bPlaybackState() }
    }

    @Test
    fun `pronunciation processor applies book rules before global rules cleanly`() {
        val rules = listOf(
            com.example.data.db.PronunciationRuleEntity(
                id = 1L,
                findText = "qi",
                replaceWith = "chee",
                scope = com.example.data.db.PronunciationScope.GLOBAL,
                bookId = null,
                matchCase = false,
                wholeWordOnly = true,
                isEnabled = true
            ),
            com.example.data.db.PronunciationRuleEntity(
                id = 2L,
                findText = "martial arts",
                replaceWith = "maartial arts",
                scope = com.example.data.db.PronunciationScope.GLOBAL,
                bookId = null,
                matchCase = false,
                wholeWordOnly = true,
                isEnabled = true
            ),
            com.example.data.db.PronunciationRuleEntity(
                id = 3L,
                findText = "qi",
                replaceWith = "internal energy",
                scope = com.example.data.db.PronunciationScope.BOOK,
                bookId = 7L,
                matchCase = false,
                wholeWordOnly = true,
                isEnabled = true
            ),
            com.example.data.db.PronunciationRuleEntity(
                id = 4L,
                findText = "hero",
                replaceWith = "champion",
                scope = com.example.data.db.PronunciationScope.GLOBAL,
                bookId = null,
                matchCase = true,
                wholeWordOnly = true,
                isEnabled = true
            ),
            com.example.data.db.PronunciationRuleEntity(
                id = 5L,
                findText = "disabled",
                replaceWith = "SHOULD_NOT_APPLY",
                scope = com.example.data.db.PronunciationScope.GLOBAL,
                bookId = null,
                matchCase = false,
                wholeWordOnly = true,
                isEnabled = false
            )
        )

        val input = "True martial arts channels qi quickly while quiet hero Hero stays disabled."
        val spoken = com.example.tts.PronunciationProcessor.applyRules(
            rawText = input,
            rules = rules,
            bookId = 7L
        )
        assertTrue(spoken.contains("maartial arts"))
        assertTrue(spoken.contains("internal energy"))
        assertTrue(spoken.contains("quiet"))
        assertTrue(spoken.contains("champion Hero"))
        assertTrue(spoken.contains("disabled."))
        assertTrue(!spoken.contains("SHOULD_NOT_APPLY"))
    }

    @Test
    fun `room v4 stores pronunciation rules and orders book rules before global`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LexiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.pronunciationRuleDao()

        dao.insertRule(
            com.example.data.db.PronunciationRuleEntity(
                findText = "Dao",
                replaceWith = "Dow",
                scope = com.example.data.db.PronunciationScope.GLOBAL,
                bookId = null
            )
        )
        dao.insertRule(
            com.example.data.db.PronunciationRuleEntity(
                findText = "Dao",
                replaceWith = "Way",
                scope = com.example.data.db.PronunciationScope.BOOK,
                bookId = 15L
            )
        )
        dao.insertRule(
            com.example.data.db.PronunciationRuleEntity(
                findText = "OtherBookTerm",
                replaceWith = "IgnoreMe",
                scope = com.example.data.db.PronunciationScope.BOOK,
                bookId = 99L
            )
        )

        val rulesForBook15 = dao.getEnabledRulesForPlayback(15L)
        assertEquals(2, rulesForBook15.size)
        assertEquals(com.example.data.db.PronunciationScope.BOOK, rulesForBook15[0].scope)
        assertEquals("Way", rulesForBook15[0].replaceWith)
        assertEquals(com.example.data.db.PronunciationScope.GLOBAL, rulesForBook15[1].scope)

        val globalOnly = dao.getEnabledRulesForPlayback(-1L)
        assertEquals(1, globalOnly.size)
        assertEquals("Dow", globalOnly[0].replaceWith)

        db.close()
    }

    @Test
    fun `floating overlay style helper resolves custom contrast and master switch`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lightCustom = com.example.data.prefs.FloatingOverlaySettings(
            masterEnabled = true,
            appearanceMode = com.example.data.prefs.FloatingAppearanceMode.CUSTOM,
            backgroundColorArgb = 0xFFFDF6E3.toInt(),
            borderColorArgb = 0xFF00BFA5.toInt(),
            borderWidthDp = 2
        )
        val resolvedLight = com.example.overlay.OverlayStyleHelper.resolveColors(lightCustom)
        assertEquals(0xFFFDF6E3.toInt(), resolvedLight.backgroundArgb)
        assertEquals(0xFF00BFA5.toInt(), resolvedLight.borderArgb)
        assertEquals(2, resolvedLight.borderWidthDp)
        assertEquals(0xFF1A1A22.toInt(), resolvedLight.primaryTextArgb)

        FloatingOverlayController.setVisible(context, true)
        FloatingOverlayController.setMasterEnabled(context, false)
        kotlinx.coroutines.delay(50)
        assertEquals(false, FloatingOverlayController.state.value.isVisible)
        FloatingOverlayController.setMasterEnabled(context, true)
    }

    @Test
    fun `audiobook progressive export updates chapter count 2 to 10 preserving id and progress`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LexiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.audiobookDao()

        val initialFiles = (1..2).map { idx ->
            val start = (idx - 1) * 5000L
            Mp3ChapterFileMeta(
                chapterIndex = idx - 1,
                title = "Chapter $idx",
                fileName = "%03d - Chapter %d.mp3".format(idx, idx),
                fileUri = "/export/Dune/%03d - Chapter %d.mp3".format(idx, idx),
                startMs = start,
                endMs = start + 5000L,
                durationMs = 5000L,
                isDirectPath = true
            )
        }
        val initialChapters = initialFiles.mapIndexed { i, cf ->
            AudiobookChapterMeta(i, cf.title, cf.startMs, cf.endMs)
        }

        val initialId = dao.upsertAudiobookPreservingProgress(
            AudiobookEntity(
                fileUri = "folder:///export/Dune",
                isDirectPath = true,
                title = "Dune",
                author = "Frank Herbert",
                durationMs = 10_000L,
                chapterCount = 2,
                chaptersJson = SyncAlignmentSerializer.encodeChapters(initialChapters),
                isFolderAudiobook = true,
                folderUri = "/export/Dune",
                chapterFilesJson = SyncAlignmentSerializer.encodeChapterFiles(initialFiles)
            )
        )
        assertTrue(initialId > 0L)
        dao.updatePlaybackProgress(initialId, 7500L, 123456L)

        val expandedFiles = (1..10).map { idx ->
            val start = (idx - 1) * 5000L
            Mp3ChapterFileMeta(
                chapterIndex = idx - 1,
                title = "Chapter $idx",
                fileName = "%03d - Chapter %d.mp3".format(idx, idx),
                fileUri = "/export/Dune/%03d - Chapter %d.mp3".format(idx, idx),
                startMs = start,
                endMs = start + 5000L,
                durationMs = 5000L,
                isDirectPath = true
            )
        }
        val expandedChapters = expandedFiles.mapIndexed { i, cf ->
            AudiobookChapterMeta(i, cf.title, cf.startMs, cf.endMs)
        }

        val updatedId = dao.upsertAudiobookPreservingProgress(
            AudiobookEntity(
                fileUri = "folder:///export/Dune",
                isDirectPath = true,
                title = "Dune",
                author = "Frank Herbert",
                durationMs = 50_000L,
                chapterCount = 10,
                chaptersJson = SyncAlignmentSerializer.encodeChapters(expandedChapters),
                isFolderAudiobook = true,
                folderUri = "/export/Dune",
                chapterFilesJson = SyncAlignmentSerializer.encodeChapterFiles(expandedFiles)
            )
        )

        assertEquals(initialId, updatedId)
        val observed = dao.observeAudiobookById(initialId).first()
        assertNotNull(observed)
        assertEquals(10, observed!!.chapterCount)
        assertEquals(50_000L, observed.durationMs)
        assertEquals(7500L, observed.lastPositionMs)
        assertEquals(10, SyncAlignmentSerializer.decodeChapters(observed.chaptersJson).size)
        assertEquals(10, SyncAlignmentSerializer.decodeChapterFiles(observed.chapterFilesJson).size)

        db.close()
    }

    @Test
    fun `linked audiobook lookup and bidirectional library progress inheritance works cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LexiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val bookDao = db.bookDao()
        val audiobookDao = db.audiobookDao()

        val epubKey = "/books/mistborn.epub"
        bookDao.upsertPreservingProgress(
            listOf(
                BookEntity(
                    fileKey = epubKey,
                    folderKey = "/books",
                    isDirectPath = true,
                    title = "Mistborn",
                    author = "Brandon Sanderson",
                    chapterCount = 10,
                    addedAt = 100L
                )
            )
        )
        val book = bookDao.getBookByFileKey(epubKey)!!

        val syncFile = File(context.cacheDir, "mistborn_sync.json")
        val segments = listOf(
            SyncAlignmentSegment(0, 0, "Ash fell from the sky.", 0L, 2500L),
            SyncAlignmentSegment(1, 0, "Chapter two begins.", 2500L, 5000L),
            SyncAlignmentSegment(1, 4, "Kelsier smiled.", 5000L, 8000L)
        )
        SyncAlignmentSerializer.saveSegments(syncFile, segments)

        val audiobookId = audiobookDao.upsertAudiobookPreservingProgress(
            AudiobookEntity(
                fileUri = "folder:///export/Mistborn",
                isDirectPath = true,
                title = "Mistborn",
                author = "Brandon Sanderson",
                durationMs = 8000L,
                chapterCount = 2,
                sourceEpubKey = epubKey,
                sourceEpubIsDirect = true,
                syncJsonPath = syncFile.absolutePath,
                isFolderAudiobook = true,
                folderUri = "/export/Mistborn"
            )
        )

        val linkedList = audiobookDao.observeAudiobooksBySourceEpubKey(epubKey).first()
        assertEquals(1, linkedList.size)
        assertEquals(audiobookId, linkedList.first().id)

        val latest = audiobookDao.getLatestAudiobookForEpub(epubKey)
        assertNotNull(latest)
        assertEquals(audiobookId, latest?.id)

        // Simulate Audiobook Player syncing progress back to Library BookEntity
        val syncTs = 500_000L
        val seg = SyncAlignmentSerializer.findSegmentAtPosition(segments, 6000L)!!
        audiobookDao.updatePlaybackProgress(audiobookId, 6000L, syncTs)
        bookDao.updateReadingProgress(
            bookId = book.id,
            chapterIndex = seg.chapterIndex,
            sentenceIndex = seg.sentenceIndex,
            scrollOffset = 0,
            progressPercent = (seg.chapterIndex + 1).toFloat() / 10f,
            lastOpenedAt = syncTs
        )

        val syncedBook = bookDao.getBookById(book.id)!!
        assertEquals(1, syncedBook.currentChapterIndex)
        assertEquals(4, syncedBook.currentSentenceIndex)
        assertEquals(syncTs, syncedBook.lastOpenedAt)
        assertEquals(0.2f, syncedBook.progressPercent, 0.001f)

        syncFile.delete()
        db.close()
    }

    private fun wrapAtom(type: String, payload: ByteArray): ByteArray {
        val sz = 8 + payload.size
        return ByteBuffer.allocate(sz).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(sz)
            put(type.toByteArray(StandardCharsets.US_ASCII))
            put(payload)
        }.array()
    }

    private fun writeTestWav(file: File, sampleRate: Int, durationMs: Int) {
        val sampleCount = (sampleRate * durationMs) / 1000
        val dataBytes = sampleCount * 2
        val totalSize = 36 + dataBytes
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(totalSize)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataBytes)
        for (i in 0 until sampleCount) {
            val s = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 12000.0).toInt().toShort()
            bb.putShort(s)
        }
        file.writeBytes(bb.array())
    }

    private fun createSampleEpub(dest: File) {
        ZipOutputStream(FileOutputStream(dest)).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>The Time Machine</dc:title>
                    <dc:creator>H. G. Wells</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="css" href="styles.css" media-type="text/css"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/styles.css"))
            zip.write("body { font-family: serif; }".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="np1" playOrder="1">
                      <navLabel><text>Chapter I: Introduction</text></navLabel>
                      <content src="ch1.xhtml"/>
                    </navPoint>
                    <navPoint id="np2" playOrder="2">
                      <navLabel><text>Chapter II: The Machine</text></navLabel>
                      <content src="ch2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zip.write(
                """
                <html><head><link rel="stylesheet" href="styles.css"/></head><body>
                <h1>Chapter I</h1>
                <p>The Time Traveller was expounding a recondite matter to us. His grey eyes shone and twinkled.</p>
                <p>Wait a minute! Listen carefully to what happens next.</p>
                </body></html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/ch2.xhtml"))
            zip.write(
                """
                <html><body>
                <h1>Chapter II</h1>
                <p>The lever moved smoothly forward into the future.</p>
                </body></html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
    }
}
