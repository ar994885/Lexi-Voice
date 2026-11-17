package com.example.export

import de.sciss.jump3r.mp3.Jump3rBridge
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Mp3AudioEncoder {

    data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int
    )

    fun mergeWavChunksToMp3(
        wavFiles: List<File>,
        outputMp3: File,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        bitrateKbps: Int = 96,
        coverBytes: ByteArray? = null
    ): Boolean {
        if (wavFiles.isEmpty()) return false
        var bridge: Jump3rBridge? = null
        var baseSampleRate = 0
        var baseChannels = 0

        return try {
            for (wav in wavFiles) {
                if (!wav.exists() || wav.length() < 44L) continue
                val bytes = FileInputStream(wav).use { it.readBytes() }
                val header = parseWavHeader(bytes) ?: continue
                if (header.bitsPerSample != 16) continue

                if (bridge == null) {
                    baseSampleRate = header.sampleRate
                    baseChannels = header.channels
                    val out = BufferedOutputStream(FileOutputStream(outputMp3), 32 * 1024)
                    bridge = Jump3rBridge(
                        out,
                        baseSampleRate,
                        baseChannels,
                        bitrateKbps,
                        title,
                        artist,
                        album,
                        trackNumber
                    )
                }

                val dataEnd = (header.dataOffset + header.dataSize).coerceAtMost(bytes.size)
                val byteLen = dataEnd - header.dataOffset
                if (byteLen <= 1) continue

                val shortCount = byteLen / 2
                val rawShorts = ShortArray(shortCount)
                ByteBuffer.wrap(bytes, header.dataOffset, shortCount * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(rawShorts)

                val normalized = if (header.sampleRate == baseSampleRate && header.channels == baseChannels) {
                    rawShorts
                } else {
                    convertPcm(rawShorts, header.sampleRate, header.channels, baseSampleRate, baseChannels)
                }

                // Feed in 8192-sample blocks
                var offset = 0
                val blockSize = 8192
                while (offset < normalized.size) {
                    val len = (normalized.size - offset).coerceAtMost(blockSize)
                    val slice = if (offset == 0 && len == normalized.size) {
                        normalized
                    } else {
                        normalized.copyOfRange(offset, offset + len)
                    }
                    bridge.encodePcm16(slice, len)
                    offset += len
                }
            }

            bridge?.close()
            if (outputMp3.exists() && outputMp3.length() > 0L && coverBytes != null && coverBytes.isNotEmpty()) {
                prependId3v2WithCover(
                    mp3File = outputMp3,
                    title = title,
                    artist = artist,
                    album = album,
                    trackNumber = trackNumber,
                    coverJpegBytes = coverBytes
                )
            }
            outputMp3.exists() && outputMp3.length() > 0L
        } catch (_: Exception) {
            runCatching { bridge?.close() }
            false
        }
    }

    private fun prependId3v2WithCover(
        mp3File: File,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        coverJpegBytes: ByteArray
    ) {
        runCatching {
            val rawMp3 = mp3File.readBytes()
            val audioStart = if (rawMp3.size >= 10 &&
                rawMp3[0] == 'I'.code.toByte() &&
                rawMp3[1] == 'D'.code.toByte() &&
                rawMp3[2] == '3'.code.toByte()
            ) {
                val tagSize = ((rawMp3[6].toInt() and 0x7F) shl 21) or
                    ((rawMp3[7].toInt() and 0x7F) shl 14) or
                    ((rawMp3[8].toInt() and 0x7F) shl 7) or
                    (rawMp3[9].toInt() and 0x7F)
                (10 + tagSize).coerceAtMost(rawMp3.size)
            } else 0

            fun textFrame(id: String, value: String): ByteArray {
                val textBytes = value.toByteArray(Charsets.UTF_8)
                val payloadSize = 1 + textBytes.size
                val buf = ByteBuffer.allocate(10 + payloadSize).order(ByteOrder.BIG_ENDIAN)
                buf.put(id.toByteArray(Charsets.US_ASCII))
                buf.putInt(payloadSize)
                buf.putShort(0)
                buf.put(3) // UTF-8 encoding
                buf.put(textBytes)
                return buf.array()
            }

            val mimeBytes = "image/jpeg".toByteArray(Charsets.US_ASCII)
            val apicPayloadSize = 1 + mimeBytes.size + 1 + 1 + 1 + coverJpegBytes.size
            val apicBuf = ByteBuffer.allocate(10 + apicPayloadSize).order(ByteOrder.BIG_ENDIAN)
            apicBuf.put("APIC".toByteArray(Charsets.US_ASCII))
            apicBuf.putInt(apicPayloadSize)
            apicBuf.putShort(0)
            apicBuf.put(0) // ISO-8859-1
            apicBuf.put(mimeBytes)
            apicBuf.put(0) // null terminator
            apicBuf.put(3) // Front Cover picture type
            apicBuf.put(0) // empty description
            apicBuf.put(coverJpegBytes)

            val frames = listOf(
                textFrame("TIT2", title),
                textFrame("TPE1", artist),
                textFrame("TALB", album),
                textFrame("TRCK", trackNumber.toString()),
                apicBuf.array()
            )
            val totalTagPayload = frames.sumOf { it.size }
            val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
                put("ID3".toByteArray(Charsets.US_ASCII))
                put(3) // ID3v2.3
                put(0)
                put(0)
                put(((totalTagPayload shr 21) and 0x7F).toByte())
                put(((totalTagPayload shr 14) and 0x7F).toByte())
                put(((totalTagPayload shr 7) and 0x7F).toByte())
                put((totalTagPayload and 0x7F).toByte())
            }.array()

            FileOutputStream(mp3File).use { out ->
                out.write(header)
                for (f in frames) out.write(f)
                out.write(rawMp3, audioStart, rawMp3.size - audioStart)
            }
        }
    }

    fun computeWavDurationMs(wavFile: File): Long {
        if (!wavFile.exists() || wavFile.length() < 44L) return 0L
        return runCatching {
            val bytes = FileInputStream(wavFile).use { it.readBytes() }
            val h = parseWavHeader(bytes) ?: return@runCatching 0L
            val bytesPerSec = h.sampleRate.toLong() * h.channels.coerceAtLeast(1) * (h.bitsPerSample / 8).coerceAtLeast(1)
            if (bytesPerSec <= 0L) 0L else (h.dataSize.toLong() * 1000L) / bytesPerSec
        }.getOrDefault(0L)
    }

    fun parseWavHeader(bytes: ByteArray): WavHeader? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null

        var pos = 12
        var sampleRate = 22050
        var channels = 1
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val nextPos = pos + 8 + chunkSize + (chunkSize and 1)

            if (chunkId == "fmt " && pos + 24 <= bytes.size) {
                val bb = ByteBuffer.wrap(bytes, pos + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
                bb.short // audioFormat
                channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                bb.int // byteRate
                bb.short // blockAlign
                bitsPerSample = bb.short.toInt() and 0xFFFF
            } else if (chunkId == "data") {
                dataOffset = pos + 8
                dataSize = chunkSize.coerceAtMost(bytes.size - dataOffset)
                break
            }

            if (chunkSize < 0 || nextPos <= pos) break
            pos = nextPos
        }

        if (dataOffset < 0 || dataSize <= 0) return null
        return WavHeader(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
    }

    private fun convertPcm(
        input: ShortArray,
        srcRate: Int,
        srcCh: Int,
        dstRate: Int,
        dstCh: Int
    ): ShortArray {
        val srcFrames = input.size / srcCh.coerceAtLeast(1)
        if (srcFrames == 0) return ShortArray(0)

        val mono = if (srcCh == 1) {
            input
        } else {
            ShortArray(srcFrames) { f ->
                var sum = 0
                for (c in 0 until srcCh) sum += input[f * srcCh + c].toInt()
                (sum / srcCh).toShort()
            }
        }

        val resampled = if (srcRate == dstRate) {
            mono
        } else {
            val dstFrames = ((srcFrames.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
            val ratio = srcRate.toDouble() / dstRate.toDouble()
            ShortArray(dstFrames) { i ->
                val p = i * ratio
                val i0 = p.toInt().coerceIn(0, srcFrames - 1)
                val i1 = (i0 + 1).coerceIn(0, srcFrames - 1)
                val frac = p - i0
                ((mono[i0] * (1.0 - frac)) + (mono[i1] * frac)).toInt().toShort()
            }
        }

        return if (dstCh == 1) {
            resampled
        } else {
            ShortArray(resampled.size * dstCh) { idx -> resampled[idx / dstCh] }
        }
    }
}
