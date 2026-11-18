package com.example.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.example.audiobook.AudiobookChapterMeta
import com.example.audiobook.SyncAlignmentSegment
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class ChapterInputFile(
    val chapterTitle: String,
    val mp3File: File
)

data class ChapterTiming(
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long
)

data class M4bSentenceWavInput(
    val sentenceIndex: Int,
    val text: String,
    val wavFiles: List<File>
)

data class M4bChapterWavGroup(
    val chapterIndex: Int,
    val chapterTitle: String,
    val sentences: List<M4bSentenceWavInput>
)

data class M4bBuildResult(
    val success: Boolean,
    val totalDurationMs: Long,
    val chapterMetas: List<AudiobookChapterMeta>,
    val syncSegments: List<SyncAlignmentSegment>
)

data class Mp3UriChapterInput(
    val chapterIndex: Int,
    val chapterTitle: String,
    val uriString: String,
    val isDirectPath: Boolean
)

data class M4bFromMp3Result(
    val success: Boolean,
    val totalDurationMs: Long,
    val chapterMetas: List<AudiobookChapterMeta>,
    val compiledOffsets: List<Pair<Int, Long>>
)

object M4aAudiobookBuilder {

    fun buildChapteredM4bFromWavs(
        chapters: List<M4bChapterWavGroup>,
        outputM4bFile: File,
        metadata: M4aMetadataConfig,
        coverBytes: ByteArray?,
        onProgress: (percent: Int) -> Unit
    ): M4bBuildResult {
        if (chapters.isEmpty()) return M4bBuildResult(false, 0L, emptyList(), emptyList())
        val rawMuxedFile = File(outputM4bFile.parentFile, "${outputM4bFile.nameWithoutExtension}_raw.m4b")
        if (rawMuxedFile.exists()) rawMuxedFile.delete()

        val muxSession = AacMuxerSession(rawMuxedFile)
        return try {
            muxSession.start()

            val chapterTimings = mutableListOf<ChapterTiming>()
            val chapterMetas = mutableListOf<AudiobookChapterMeta>()
            val syncSegments = mutableListOf<SyncAlignmentSegment>()

            chapters.forEachIndexed { cIdx, chapter ->
                val chStartMs = muxSession.currentElapsedMs()
                for (sentence in chapter.sentences) {
                    val sStartMs = muxSession.currentElapsedMs()
                    for (wavFile in sentence.wavFiles) {
                        val pcm = readWavAndResample(wavFile, muxSession.targetSampleRate, muxSession.targetChannels)
                        if (pcm.isNotEmpty()) {
                            muxSession.feedPcmShorts(pcm, pcm.size)
                        }
                    }
                    val sEndMs = muxSession.currentElapsedMs()
                    syncSegments.add(
                        SyncAlignmentSegment(
                            chapterIndex = chapter.chapterIndex,
                            sentenceIndex = sentence.sentenceIndex,
                            text = sentence.text,
                            startMs = sStartMs,
                            endMs = sEndMs.coerceAtLeast(sStartMs + 50L)
                        )
                    )
                }
                // Insert a short 400ms chapter pause
                val pauseSamples = ShortArray((muxSession.targetSampleRate * 4) / 10)
                muxSession.feedPcmShorts(pauseSamples, pauseSamples.size)

                val chEndMs = muxSession.currentElapsedMs()
                chapterTimings.add(
                    ChapterTiming(
                        title = chapter.chapterTitle,
                        startTimeMs = chStartMs,
                        endTimeMs = chEndMs.coerceAtLeast(chStartMs + 100L)
                    )
                )
                chapterMetas.add(
                    AudiobookChapterMeta(
                        index = cIdx,
                        title = chapter.chapterTitle,
                        startMs = chStartMs,
                        endMs = chEndMs.coerceAtLeast(chStartMs + 100L)
                    )
                )
                onProgress((((cIdx + 1).toFloat() / chapters.size.toFloat()) * 85f).toInt())
            }

            val totalDurationMs = muxSession.currentElapsedMs()
            val muxedOk = muxSession.finishAndClose()
            if (!muxedOk) {
                rawMuxedFile.delete()
                return M4bBuildResult(false, 0L, emptyList(), emptyList())
            }

            onProgress(92)
            Mp4ChapterAndMetadataWriter.injectChaptersAndMetadata(
                inputFile = rawMuxedFile,
                outputFile = outputM4bFile,
                chapters = chapterTimings,
                metadata = metadata,
                coverBytes = if (metadata.includeCover) coverBytes else null,
                useM4bBrand = true
            )
            rawMuxedFile.delete()
            onProgress(100)
            val ok = outputM4bFile.exists() && outputM4bFile.length() > 0L
            M4bBuildResult(ok, totalDurationMs, chapterMetas, syncSegments)
        } catch (_: Exception) {
            rawMuxedFile.delete()
            M4bBuildResult(false, 0L, emptyList(), emptyList())
        } finally {
            muxSession.closeQuietly()
        }
    }

    fun buildChapteredM4a(
        chapters: List<ChapterInputFile>,
        outputM4aFile: File,
        metadata: M4aMetadataConfig,
        coverBytes: ByteArray?,
        onProgress: (percent: Int) -> Unit
    ): Boolean {
        if (chapters.isEmpty()) return false
        val rawMuxedFile = File(outputM4aFile.parentFile, "${outputM4aFile.nameWithoutExtension}_raw.m4a")
        if (rawMuxedFile.exists()) rawMuxedFile.delete()

        val muxSession = AacMuxerSession(rawMuxedFile)
        return try {
            muxSession.start()

            val chapterTimings = mutableListOf<ChapterTiming>()

            chapters.forEachIndexed { idx, chapter ->
                val startMs = muxSession.currentElapsedMs()
                decodeMp3ToPcm(
                    mp3File = chapter.mp3File,
                    targetSampleRate = muxSession.targetSampleRate,
                    targetChannels = muxSession.targetChannels
                ) { pcmChunk, count ->
                    muxSession.feedPcmShorts(pcmChunk, count)
                }
                val endMs = muxSession.currentElapsedMs()
                chapterTimings.add(
                    ChapterTiming(
                        title = chapter.chapterTitle,
                        startTimeMs = startMs,
                        endTimeMs = endMs.coerceAtLeast(startMs + 100L)
                    )
                )
                onProgress((((idx + 1).toFloat() / chapters.size.toFloat()) * 85f).toInt())
            }

            val muxedOk = muxSession.finishAndClose()
            if (!muxedOk) {
                rawMuxedFile.delete()
                return false
            }

            onProgress(92)
            Mp4ChapterAndMetadataWriter.injectChaptersAndMetadata(
                inputFile = rawMuxedFile,
                outputFile = outputM4aFile,
                chapters = chapterTimings,
                metadata = metadata,
                coverBytes = if (metadata.includeCover) coverBytes else null,
                useM4bBrand = outputM4aFile.extension.equals("m4b", ignoreCase = true)
            )
            rawMuxedFile.delete()
            onProgress(100)
            outputM4aFile.exists() && outputM4aFile.length() > 0L
        } catch (_: Exception) {
            rawMuxedFile.delete()
            false
        } finally {
            muxSession.closeQuietly()
        }
    }

    fun buildChapteredM4bFromMp3Inputs(
        context: Context,
        chapters: List<Mp3UriChapterInput>,
        outputM4bFile: File,
        metadata: M4aMetadataConfig,
        coverBytes: ByteArray?,
        onProgress: (percent: Int) -> Unit
    ): M4bFromMp3Result {
        if (chapters.isEmpty()) return M4bFromMp3Result(false, 0L, emptyList(), emptyList())
        val rawMuxedFile = File(outputM4bFile.parentFile, "${outputM4bFile.nameWithoutExtension}_raw.m4b")
        if (rawMuxedFile.exists()) rawMuxedFile.delete()

        val muxSession = AacMuxerSession(rawMuxedFile)
        return try {
            muxSession.start()

            val chapterTimings = mutableListOf<ChapterTiming>()
            val chapterMetas = mutableListOf<AudiobookChapterMeta>()
            val compiledOffsets = mutableListOf<Pair<Int, Long>>()

            chapters.forEachIndexed { ordinalIdx, chapter ->
                val startMs = muxSession.currentElapsedMs()
                compiledOffsets.add(chapter.chapterIndex to startMs)

                decodeAudioSourceToPcm(
                    configureExtractor = { extractor ->
                        if (chapter.isDirectPath) {
                            extractor.setDataSource(chapter.uriString)
                        } else {
                            extractor.setDataSource(context, Uri.parse(chapter.uriString), null)
                        }
                    },
                    targetSampleRate = muxSession.targetSampleRate,
                    targetChannels = muxSession.targetChannels
                ) { pcmChunk, count ->
                    muxSession.feedPcmShorts(pcmChunk, count)
                }

                val endMs = muxSession.currentElapsedMs()
                chapterTimings.add(
                    ChapterTiming(
                        title = chapter.chapterTitle,
                        startTimeMs = startMs,
                        endTimeMs = endMs.coerceAtLeast(startMs + 100L)
                    )
                )
                chapterMetas.add(
                    AudiobookChapterMeta(
                        index = ordinalIdx,
                        title = chapter.chapterTitle,
                        startMs = startMs,
                        endMs = endMs.coerceAtLeast(startMs + 100L)
                    )
                )
                onProgress((((ordinalIdx + 1).toFloat() / chapters.size.toFloat()) * 85f).toInt())
            }

            val totalDurationMs = muxSession.currentElapsedMs()
            val muxedOk = muxSession.finishAndClose()
            if (!muxedOk) {
                rawMuxedFile.delete()
                return M4bFromMp3Result(false, 0L, emptyList(), emptyList())
            }

            onProgress(92)
            Mp4ChapterAndMetadataWriter.injectChaptersAndMetadata(
                inputFile = rawMuxedFile,
                outputFile = outputM4bFile,
                chapters = chapterTimings,
                metadata = metadata,
                coverBytes = if (metadata.includeCover) coverBytes else null,
                useM4bBrand = true
            )
            rawMuxedFile.delete()
            onProgress(100)
            val ok = outputM4bFile.exists() && outputM4bFile.length() > 0L
            M4bFromMp3Result(ok, totalDurationMs, chapterMetas, compiledOffsets)
        } catch (_: Exception) {
            rawMuxedFile.delete()
            M4bFromMp3Result(false, 0L, emptyList(), emptyList())
        } finally {
            muxSession.closeQuietly()
        }
    }

    private class AacMuxerSession(
        private val rawMuxedFile: File,
        val targetSampleRate: Int = 22050,
        val targetChannels: Int = 1,
        private val bitRate: Int = 64_000
    ) {
        private var encoder: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var trackIndex: Int = -1
        private var encoderStarted: Boolean = false
        private var encoderStopped: Boolean = false
        private var muxerStarted: Boolean = false
        private var muxerStopped: Boolean = false
        private var hasWrittenSamples: Boolean = false
        private var totalSamplesWritten: Long = 0L
        private val bufferInfo = MediaCodec.BufferInfo()

        fun start() {
            val encoderFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                targetSampleRate,
                targetChannels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = codec
            codec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoderStarted = true

            muxer = MediaMuxer(rawMuxedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        fun currentElapsedMs(): Long = (totalSamplesWritten * 1000L) / targetSampleRate

        fun feedPcmShorts(samples: ShortArray, count: Int) {
            val codec = encoder ?: return
            var offset = 0
            while (offset < count) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx) ?: continue
                    inBuf.clear()
                    val maxShorts = (inBuf.remaining() / 2).coerceAtMost(count - offset)
                    inBuf.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until maxShorts) {
                        inBuf.putShort(samples[offset + i])
                    }
                    val ptsUs = (totalSamplesWritten * 1_000_000L) / targetSampleRate
                    codec.queueInputBuffer(inIdx, 0, maxShorts * 2, ptsUs, 0)
                    totalSamplesWritten += maxShorts
                    offset += maxShorts
                }
                drainEncoder(endOfStream = false)
            }
        }

        private fun drainEncoder(endOfStream: Boolean) {
            val codec = encoder ?: return
            val m = muxer ?: return
            var eosQueued = !endOfStream
            var stallCycles = 0

            while (true) {
                if (!eosQueued) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        codec.queueInputBuffer(
                            idx,
                            0,
                            0,
                            (totalSamplesWritten * 1_000_000L) / targetSampleRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) break
                        stallCycles++
                        if (stallCycles > 250 && eosQueued) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        stallCycles = 0
                        if (!muxerStarted && trackIndex < 0) {
                            trackIndex = m.addTrack(codec.outputFormat)
                            m.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        stallCycles = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            muxerStarted && trackIndex >= 0
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            m.writeSampleData(trackIndex, outBuf, bufferInfo)
                            hasWrittenSamples = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        }

        fun finishAndClose(): Boolean {
            if (encoderStarted) {
                runCatching { drainEncoder(endOfStream = true) }
            }
            closeQuietly()
            return hasWrittenSamples && rawMuxedFile.exists() && rawMuxedFile.length() > 0L
        }

        fun closeQuietly() {
            if (encoderStarted && !encoderStopped) {
                encoderStopped = true
                runCatching { encoder?.stop() }
            }
            runCatching { encoder?.release() }
            encoder = null

            if (muxerStarted && !muxerStopped) {
                muxerStopped = true
                // Native MPEG4Writer prints/throws if stop() is called with zero samples written
                if (hasWrittenSamples) {
                    runCatching { muxer?.stop() }
                }
            }
            runCatching { muxer?.release() }
            muxer = null
        }
    }

    private fun readWavAndResample(
        wavFile: File,
        targetSampleRate: Int,
        targetChannels: Int
    ): ShortArray {
        val bytes = FileInputStream(wavFile).use { it.readBytes() }
        if (bytes.size < 44) return ShortArray(0)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF") return ShortArray(0)

        var pos = 12
        var channels = 1
        var sampleRate = targetSampleRate
        var dataOffset = 44
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4)
            when (chunkId) {
                "fmt " -> {
                    if (pos + 24 <= bytes.size) {
                        channels = bb.getShort(pos + 10).toInt().coerceAtLeast(1)
                        sampleRate = bb.getInt(pos + 12).coerceAtLeast(8000)
                    }
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataSize = chunkSize.coerceAtMost(bytes.size - dataOffset)
                    break
                }
            }
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        if (dataSize <= 0) return ShortArray(0)
        val shortCount = dataSize / 2
        val shorts = ShortArray(shortCount)
        ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)

        return resampleAndMixToMono(shorts, sampleRate, channels, targetSampleRate, targetChannels)
    }

    private fun decodeMp3ToPcm(
        mp3File: File,
        targetSampleRate: Int,
        targetChannels: Int,
        onPcmChunk: (ShortArray, Int) -> Unit
    ) {
        decodeAudioSourceToPcm(
            configureExtractor = { it.setDataSource(mp3File.absolutePath) },
            targetSampleRate = targetSampleRate,
            targetChannels = targetChannels,
            onPcmChunk = onPcmChunk
        )
    }

    private fun decodeAudioSourceToPcm(
        configureExtractor: (MediaExtractor) -> Unit,
        targetSampleRate: Int,
        targetChannels: Int,
        onPcmChunk: (ShortArray, Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        try {
            configureExtractor(extractor)
            var audioTrackIdx = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || trackFormat == null) {
                return
            }
            extractor.selectTrack(audioTrackIdx)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(trackFormat, null, null, 0)
            codec.start()
            decoderStarted = true

            var srcSampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shortCount = info.size / 2
                            val srcShorts = ShortArray(shortCount)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(srcShorts)
                            val converted = resampleAndMixToMono(
                                input = srcShorts,
                                srcSampleRate = srcSampleRate,
                                srcChannels = srcChannels,
                                dstSampleRate = targetSampleRate,
                                dstChannels = targetChannels
                            )
                            if (converted.isNotEmpty()) {
                                onPcmChunk(converted, converted.size)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }
        } finally {
            if (decoderStarted) {
                runCatching { decoder?.stop() }
            }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun resampleAndMixToMono(
        input: ShortArray,
        srcSampleRate: Int,
        srcChannels: Int,
        dstSampleRate: Int,
        dstChannels: Int
    ): ShortArray {
        val frames = input.size / srcChannels.coerceAtLeast(1)
        if (frames == 0) return ShortArray(0)

        val mono = if (srcChannels == 1) {
            input
        } else {
            ShortArray(frames) { f ->
                var sum = 0
                for (c in 0 until srcChannels) {
                    sum += input[f * srcChannels + c].toInt()
                }
                (sum / srcChannels).toShort()
            }
        }

        val resampled = if (srcSampleRate == dstSampleRate) {
            mono
        } else {
            val outFrames = ((frames.toLong() * dstSampleRate) / srcSampleRate).toInt().coerceAtLeast(1)
            val ratio = srcSampleRate.toDouble() / dstSampleRate.toDouble()
            ShortArray(outFrames) { i ->
                val pos = i * ratio
                val idx0 = pos.toInt().coerceIn(0, frames - 1)
                val idx1 = (idx0 + 1).coerceIn(0, frames - 1)
                val frac = pos - idx0
                ((mono[idx0] * (1.0 - frac)) + (mono[idx1] * frac)).toInt().toShort()
            }
        }

        return if (dstChannels == 1) {
            resampled
        } else {
            ShortArray(resampled.size * dstChannels) { idx -> resampled[idx / dstChannels] }
        }
    }
}

/**
 * Pure-Kotlin MP4 Atom Editor that injects Nero/iTunes `chpl` chapters, `M4B ` ftyp brand,
 * and `meta/ilst` metadata inside `moov/udta` while preserving valid chunk offsets (`stco` / `co64`).
 */
object Mp4ChapterAndMetadataWriter {

    private data class Mp4Box(
        val type: String,
        val offset: Long,
        val size: Long,
        val data: ByteArray
    )

    fun injectChaptersAndMetadata(
        inputFile: File,
        outputFile: File,
        chapters: List<ChapterTiming>,
        metadata: M4aMetadataConfig,
        coverBytes: ByteArray?,
        useM4bBrand: Boolean = true
    ) {
        val fileBytes = FileInputStream(inputFile).use { it.readBytes() }
        val boxes = parseTopLevelBoxes(fileBytes)
        val moovBox = boxes.firstOrNull { it.type == "moov" }
        val mdatBox = boxes.firstOrNull { it.type == "mdat" }

        if (moovBox == null) {
            FileOutputStream(outputFile).use { it.write(fileBytes) }
            return
        }

        val udtaAtom = buildUdtaBox(chapters, metadata, coverBytes)
        val newMoovPayload = replaceOrAppendChildBox(
            containerPayload = moovBox.data.copyOfRange(8, moovBox.data.size),
            childType = "udta",
            newChildBox = udtaAtom
        )
        val rawNewMoov = wrapBox("moov", newMoovPayload)

        val moovBeforeMdat = mdatBox != null && moovBox.offset < mdatBox.offset
        val sizeDelta = rawNewMoov.size.toLong() - moovBox.size
        val finalMoov = if (moovBeforeMdat && sizeDelta != 0L) {
            shiftChunkOffsetsInMoov(rawNewMoov, sizeDelta)
        } else {
            rawNewMoov
        }

        FileOutputStream(outputFile).use { out ->
            for (box in boxes) {
                when (box.type) {
                    "ftyp" -> {
                        if (useM4bBrand && box.data.size >= 16) {
                            val patched = box.data.copyOf()
                            System.arraycopy("M4B ".toByteArray(StandardCharsets.US_ASCII), 0, patched, 8, 4)
                            out.write(patched)
                        } else {
                            out.write(box.data)
                        }
                    }
                    "moov" -> out.write(finalMoov)
                    else -> out.write(box.data)
                }
            }
        }
    }

    private fun parseTopLevelBoxes(bytes: ByteArray): List<Mp4Box> {
        val list = mutableListOf<Mp4Box>()
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size32 = readUInt32(bytes, pos)
            val type = String(bytes, pos + 4, 4, StandardCharsets.US_ASCII)
            val actualSize = if (size32 == 0L) (bytes.size - pos).toLong() else size32
            if (actualSize < 8 || pos + actualSize > bytes.size) break
            val end = (pos + actualSize).toInt()
            list.add(Mp4Box(type, pos.toLong(), actualSize, bytes.copyOfRange(pos, end)))
            pos = end
        }
        return list
    }

    private fun replaceOrAppendChildBox(
        containerPayload: ByteArray,
        childType: String,
        newChildBox: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        var replaced = false
        while (pos + 8 <= containerPayload.size) {
            val sz = readUInt32(containerPayload, pos).toInt()
            if (sz < 8 || pos + sz > containerPayload.size) break
            val type = String(containerPayload, pos + 4, 4, StandardCharsets.US_ASCII)
            if (type == childType) {
                out.write(newChildBox)
                replaced = true
            } else {
                out.write(containerPayload, pos, sz)
            }
            pos += sz
        }
        if (!replaced) {
            out.write(newChildBox)
        }
        return out.toByteArray()
    }

    private fun buildUdtaBox(
        chapters: List<ChapterTiming>,
        metadata: M4aMetadataConfig,
        coverBytes: ByteArray?
    ): ByteArray {
        val udtaContent = ByteArrayOutputStream()
        udtaContent.write(buildMetaBox(metadata, coverBytes))
        if (chapters.isNotEmpty()) {
            udtaContent.write(buildChplBox(chapters))
        }
        return wrapBox("udta", udtaContent.toByteArray())
    }

    private fun buildChplBox(chapters: List<ChapterTiming>): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(0x01000000) // version 1, flags 0
        dos.writeByte(0) // reserved
        dos.writeInt(chapters.size)
        for (ch in chapters) {
            val time100ns = ch.startTimeMs * 10_000L
            dos.writeLong(time100ns)
            val titleBytes = ch.title.toByteArray(StandardCharsets.UTF_8).let {
                if (it.size > 255) it.copyOfRange(0, 255) else it
            }
            dos.writeByte(titleBytes.size)
            dos.write(titleBytes)
        }
        dos.flush()
        return wrapBox("chpl", baos.toByteArray())
    }

    private fun buildMetaBox(metadata: M4aMetadataConfig, coverBytes: ByteArray?): ByteArray {
        val metaBaos = ByteArrayOutputStream()
        metaBaos.write(byteArrayOf(0, 0, 0, 0))

        val hdlrPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0))
            write(byteArrayOf(0, 0, 0, 0))
            write("mdir".toByteArray(StandardCharsets.US_ASCII))
            write("appl".toByteArray(StandardCharsets.US_ASCII))
            write(ByteArray(9))
        }.toByteArray()
        metaBaos.write(wrapBox("hdlr", hdlrPayload))

        val ilstPayload = ByteArrayOutputStream()
        fun addTextTag(fourCc: ByteArray, value: String) {
            if (value.isBlank()) return
            val dataPayload = ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0, 1))
                write(byteArrayOf(0, 0, 0, 0))
                write(value.toByteArray(StandardCharsets.UTF_8))
            }.toByteArray()
            val dataBox = wrapBox("data", dataPayload)
            ilstPayload.write(wrapRawFourCcBox(fourCc, dataBox))
        }

        addTextTag(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), metadata.title)
        addTextTag(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), metadata.author)
        addTextTag(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), metadata.album.ifBlank { metadata.title })
        addTextTag(byteArrayOf(0xA9.toByte(), 'w'.code.toByte(), 'r'.code.toByte(), 't'.code.toByte()), metadata.narrator)
        addTextTag(byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()), metadata.year)
        addTextTag(byteArrayOf(0xA9.toByte(), 'g'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte()), metadata.genre)

        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val isPng = coverBytes.size >= 4 &&
                coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
            val dataPayload = ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0, if (isPng) 14 else 13))
                write(byteArrayOf(0, 0, 0, 0))
                write(coverBytes)
            }.toByteArray()
            val dataBox = wrapBox("data", dataPayload)
            ilstPayload.write(wrapBox("covr", dataBox))
        }

        metaBaos.write(wrapBox("ilst", ilstPayload.toByteArray()))
        return wrapBox("meta", metaBaos.toByteArray())
    }

    private fun shiftChunkOffsetsInMoov(moovBytes: ByteArray, delta: Long): ByteArray {
        val copy = moovBytes.copyOf()
        shiftOffsetsRecursive(copy, 8, copy.size, delta)
        return copy
    }

    private fun shiftOffsetsRecursive(buf: ByteArray, start: Int, end: Int, delta: Long) {
        var pos = start
        while (pos + 8 <= end) {
            val sz = readUInt32(buf, pos).toInt()
            if (sz < 8 || pos + sz > end) break
            val type = String(buf, pos + 4, 4, StandardCharsets.US_ASCII)
            when (type) {
                "stco" -> {
                    if (pos + 16 <= end) {
                        val count = readUInt32(buf, pos + 12).toInt()
                        for (i in 0 until count) {
                            val entryOff = pos + 16 + i * 4
                            if (entryOff + 4 <= pos + sz) {
                                val oldVal = readUInt32(buf, entryOff)
                                writeUInt32(buf, entryOff, oldVal + delta)
                            }
                        }
                    }
                }
                "co64" -> {
                    if (pos + 16 <= end) {
                        val count = readUInt32(buf, pos + 12).toInt()
                        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
                        for (i in 0 until count) {
                            val entryOff = pos + 16 + i * 8
                            if (entryOff + 8 <= pos + sz) {
                                val oldVal = bb.getLong(entryOff)
                                bb.putLong(entryOff, oldVal + delta)
                            }
                        }
                    }
                }
                "trak", "mdia", "minf", "stbl" -> {
                    shiftOffsetsRecursive(buf, pos + 8, pos + sz, delta)
                }
            }
            pos += sz
        }
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        return wrapRawFourCcBox(type.toByteArray(StandardCharsets.US_ASCII), payload)
    }

    private fun wrapRawFourCcBox(fourCc: ByteArray, payload: ByteArray): ByteArray {
        val totalSize = 8 + payload.size
        val out = ByteArray(totalSize)
        writeUInt32(out, 0, totalSize.toLong())
        System.arraycopy(fourCc, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)
    }

    private fun writeUInt32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
