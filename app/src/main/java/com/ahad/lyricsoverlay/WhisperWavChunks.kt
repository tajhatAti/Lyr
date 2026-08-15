package com.ahad.lyricsoverlay

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/** Creates one short, overlapping Whisper input at a time from a decoded 16 kHz mono WAV. */
object WhisperWavChunks {

    data class Chunk(
        val index: Int,
        val count: Int,
        val startSample: Long,
        val sampleCount: Int,
        val file: File
    ) {
        val startMs: Long
            get() = startSample * 1_000L / LocalAudioDecoder.WHISPER_SAMPLE_RATE
        val durationMs: Long
            get() = sampleCount * 1_000L / LocalAudioDecoder.WHISPER_SAMPLE_RATE
        val hasPrevious: Boolean
            get() = index > 0
        val hasNext: Boolean
            get() = index + 1 < count
    }

    fun count(totalSamples: Long): Int {
        if (totalSamples <= 0L) return 0
        if (totalSamples <= CHUNK_SAMPLES) return 1
        val samplesAfterFirstChunk = totalSamples - CHUNK_SAMPLES
        return (1L + (samplesAfterFirstChunk + STEP_SAMPLES - 1L) / STEP_SAMPLES).toInt()
    }

    fun create(sourceWav: File, outputFile: File, totalSamples: Long, index: Int): Chunk {
        val count = count(totalSamples)
        if (index !in 0 until count) throw IndexOutOfBoundsException("Invalid audio chunk index.")
        val startSample = index * STEP_SAMPLES
        val sampleCount = min(CHUNK_SAMPLES.toLong(), totalSamples - startSample).toInt()
        if (sampleCount <= 0) throw IOException("The decoded audio chunk is empty.")

        outputFile.parentFile?.mkdirs()
        outputFile.delete()
        RandomAccessFile(sourceWav, "r").use { input ->
            RandomAccessFile(outputFile, "rw").use { output ->
                output.setLength(0L)
                writeHeader(output, sampleCount)
                input.seek(LocalAudioDecoder.WAV_HEADER_BYTES + startSample * BYTES_PER_SAMPLE)
                var bytesRemaining = sampleCount * BYTES_PER_SAMPLE
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (bytesRemaining > 0) {
                    val wanted = min(buffer.size, bytesRemaining)
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) throw IOException("The decoded audio ended unexpectedly.")
                    output.write(buffer, 0, read)
                    bytesRemaining -= read
                }
            }
        }
        return Chunk(index, count, startSample, sampleCount, outputFile)
    }

    /** The middle of the overlap owns boundary segments, preventing duplicated phrases. */
    fun acceptsSegment(chunk: Chunk, localStartMs: Long, localEndMs: Long): Boolean {
        val midpoint = (localStartMs.coerceAtLeast(0L) + localEndMs.coerceAtLeast(localStartMs)) / 2L
        val lowerBound = if (chunk.hasPrevious) OVERLAP_MS / 2L else 0L
        val upperBound = chunk.durationMs - if (chunk.hasNext) OVERLAP_MS / 2L else 0L
        return midpoint >= lowerBound && midpoint < upperBound.coerceAtLeast(lowerBound + 1L)
    }

    private fun writeHeader(output: RandomAccessFile, sampleCount: Int) {
        val dataSize = sampleCount * BYTES_PER_SAMPLE
        writeAscii(output, "RIFF")
        writeLeInt(output, 36 + dataSize)
        writeAscii(output, "WAVE")
        writeAscii(output, "fmt ")
        writeLeInt(output, 16)
        writeLeShort(output, 1)
        writeLeShort(output, 1)
        writeLeInt(output, LocalAudioDecoder.WHISPER_SAMPLE_RATE)
        writeLeInt(output, LocalAudioDecoder.WHISPER_SAMPLE_RATE * BYTES_PER_SAMPLE)
        writeLeShort(output, BYTES_PER_SAMPLE)
        writeLeShort(output, 16)
        writeAscii(output, "data")
        writeLeInt(output, dataSize)
    }

    private fun writeAscii(output: RandomAccessFile, value: String) {
        output.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeLeShort(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeLeInt(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val CHUNK_DURATION_SECONDS = 30
    private const val STEP_DURATION_SECONDS = 28
    private const val CHUNK_SAMPLES = CHUNK_DURATION_SECONDS * LocalAudioDecoder.WHISPER_SAMPLE_RATE
    private const val STEP_SAMPLES = STEP_DURATION_SECONDS * LocalAudioDecoder.WHISPER_SAMPLE_RATE.toLong()
    private const val OVERLAP_MS = (CHUNK_DURATION_SECONDS - STEP_DURATION_SECONDS) * 1_000L
    private const val COPY_BUFFER_BYTES = 64 * 1024
}
