package com.ahad.lyricsoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WhisperWavChunksTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun countDoesNotCreateAnOverlapOnlyFinalChunk() {
        val sampleRate = LocalAudioDecoder.WHISPER_SAMPLE_RATE.toLong()

        assertEquals(0, WhisperWavChunks.count(0L))
        assertEquals(1, WhisperWavChunks.count(30L * sampleRate))
        assertEquals(2, WhisperWavChunks.count(30L * sampleRate + 1L))
        assertEquals(2, WhisperWavChunks.count(58L * sampleRate))
        assertEquals(3, WhisperWavChunks.count(58L * sampleRate + 1L))
    }

    @Test
    fun overlapMidpointHasExactlyOneOwner() {
        val first = WhisperWavChunks.Chunk(
            index = 0,
            count = 2,
            startSample = 0L,
            sampleCount = 30 * LocalAudioDecoder.WHISPER_SAMPLE_RATE,
            file = File("first.wav")
        )
        val second = WhisperWavChunks.Chunk(
            index = 1,
            count = 2,
            startSample = 28L * LocalAudioDecoder.WHISPER_SAMPLE_RATE,
            sampleCount = 30 * LocalAudioDecoder.WHISPER_SAMPLE_RATE,
            file = File("second.wav")
        )

        assertTrue(WhisperWavChunks.acceptsSegment(first, 28_900L, 29_098L))
        assertFalse(WhisperWavChunks.acceptsSegment(second, 900L, 1_098L))
        assertFalse(WhisperWavChunks.acceptsSegment(first, 28_900L, 29_100L))
        assertTrue(WhisperWavChunks.acceptsSegment(second, 900L, 1_100L))
    }

    @Test
    fun createWritesACompletePcmWavChunk() {
        val source = temporaryFolder.newFile("source.wav")
        val samples = 80
        source.writeBytes(ByteArray(LocalAudioDecoder.WAV_HEADER_BYTES + samples * 2) { index ->
            (index and 0xff).toByte()
        })
        val output = temporaryFolder.newFile("chunk.wav")

        val chunk = WhisperWavChunks.create(
            sourceWav = source,
            outputFile = output,
            totalSamples = samples.toLong(),
            index = 0
        )

        assertEquals(samples, chunk.sampleCount)
        assertEquals(LocalAudioDecoder.WAV_HEADER_BYTES + samples * 2L, output.length())
        assertEquals("RIFF", output.inputStream().use { input ->
            String(input.readNBytes(4), Charsets.US_ASCII)
        })
    }
}
