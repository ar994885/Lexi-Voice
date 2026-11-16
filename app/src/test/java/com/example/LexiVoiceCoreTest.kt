package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.BookEntity
import com.example.data.db.LexiDatabase
import com.example.data.db.LibrarySortOption
import com.example.epub.EpubParser
import com.example.export.ChapterTiming
import com.example.export.M4aMetadataConfig
import com.example.export.Mp3AudioEncoder
import com.example.export.Mp4ChapterAndMetadataWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun `epub parser extracts metadata chapters and indexed sentences`() {
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

        val ch1 = EpubParser.loadChapterContent(
            context = context,
            fileKey = epubFile.absolutePath,
            isDirectPath = true,
            chapterIndex = 0,
            chapterHref = structure.chapters[0].href,
            chapterTitle = structure.chapters[0].title
        )
        assertTrue(ch1.paragraphs.size >= 2)
        assertTrue(ch1.sentences.size >= 3)
        assertTrue(ch1.plainText.contains("Time Traveller"))
        epubFile.delete()
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
        // Either ID3v2 ("ID3") or direct MPEG frame sync (0xFF 0xFB / 0xFF 0xF3)
        val hasId3 = header == listOf('I'.code, 'D'.code, '3'.code)
        val hasSync = header[0] == 0xFF && (header[1] and 0xE0) == 0xE0
        assertTrue(hasId3 || hasSync)

        wav1.delete()
        wav2.delete()
        outMp3.delete()
    }

    @Test
    fun `mp4 chapter writer injects chpl and ilst metadata cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawMp4 = File(context.cacheDir, "raw_test.m4a")
        val taggedMp4 = File(context.cacheDir, "tagged_test.m4a")

        // Minimal valid ftyp + mdat + moov(stco) container
        val ftyp = wrapAtom("ftyp", "M4A ".toByteArray() + ByteArray(4) + "M4A mp42isom".toByteArray())
        val mdat = wrapAtom("mdat", ByteArray(64))
        val stcoPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0) // version/flags
                writeInt(1) // entry count
                writeInt(ftyp.size + 8) // offset pointing to mdat payload
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
                title = "My Audiobook",
                author = "Author Name",
                album = "Book Series",
                narrator = "LexiVoice",
                year = "2026",
                genre = "Audiobook"
            ),
            coverBytes = null
        )

        val resultText = String(taggedMp4.readBytes(), StandardCharsets.ISO_8859_1)
        assertTrue(resultText.contains("chpl"))
        assertTrue(resultText.contains("001 - Opening"))
        assertTrue(resultText.contains("002 - Finale"))
        assertTrue(resultText.contains("My Audiobook"))
        assertTrue(resultText.contains("Author Name"))

        rawMp4.delete()
        taggedMp4.delete()
    }

    @Test
    fun `room library dao searches and sorts books efficiently`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LexiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val bookDao = db.bookDao()

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
        bb.putShort(1) // PCM
        bb.putShort(1) // Mono
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
                <html><body>
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
