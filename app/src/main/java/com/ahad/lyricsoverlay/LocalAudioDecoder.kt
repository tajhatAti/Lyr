package com.ahad.lyricsoverlay

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes any Android-supported MP3, M4A/AAC, WAV or FLAC stream to the exact PCM format used by
 * whisper.cpp. The source never leaves the phone. A streaming resampler keeps temporary storage
 * bounded to roughly 1.9 MB per minute of audio.
 */
object LocalAudioDecoder {

    data class DecodedAudio(
        val wavFile: File,
        val durationMs: Long,
        val sampleCount: Long
    )

    fun decodeToWhisperWav(
        context: Context,
        source: Uri,
        outputFile: File,
        fallbackDurationMs: Long,
        checkCanceled: () -> Unit,
        onProgress: (Int) -> Unit
    ): DecodedAudio {
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val writer = Pcm16WavWriter(outputFile, WHISPER_SAMPLE_RATE)
        try {
            extractor.setDataSource(context, source, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("No readable audio track was found in this file.")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw AudioDecodeException("Android could not identify this audio format.")
            val reportedDurationUs = inputFormat.longValue(MediaFormat.KEY_DURATION)
                ?: fallbackDurationMs.coerceAtLeast(0L) * 1_000L

            // Ask Android's decoder for ordinary 16-bit PCM. Some codecs may still announce float
            // output later; both representations are handled below.
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val activeCodec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: Exception) {
                throw AudioDecodeException("This phone has no decoder for $mime.", error)
            }
            codec = activeCodec
            activeCodec.configure(inputFormat, null, null, 0)
            activeCodec.start()

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputSampleRate = 0
            var outputChannels = 0
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingMonoResampler? = null
            var lastProgress = -1
            var idleIterations = 0

            while (!outputEnded) {
                checkCanceled()
                var madeProgress = false

                if (!inputEnded) {
                    val inputIndex = activeCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                            ?: throw AudioDecodeException("Android returned an unavailable decoder buffer.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            activeCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            activeCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                        madeProgress = true
                    }
                }

                when (val outputIndex = activeCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = activeCodec.outputFormat
                        val newRate = format.integerValue(MediaFormat.KEY_SAMPLE_RATE)
                            ?: throw AudioDecodeException("The decoded audio has no sample rate.")
                        val newChannels = format.integerValue(MediaFormat.KEY_CHANNEL_COUNT)
                            ?: throw AudioDecodeException("The decoded audio has no channel count.")
                        val newEncoding = format.integerValue(MediaFormat.KEY_PCM_ENCODING)
                            ?: AudioFormat.ENCODING_PCM_16BIT
                        if (newRate <= 0 || newChannels <= 0 || newChannels > MAX_CHANNELS) {
                            throw AudioDecodeException("The decoded audio layout is not supported.")
                        }
                        if (resampler != null &&
                            (newRate != outputSampleRate || newChannels != outputChannels)
                        ) {
                            throw AudioDecodeException("The audio format changed unexpectedly while decoding.")
                        }
                        outputSampleRate = newRate
                        outputChannels = newChannels
                        outputEncoding = newEncoding
                        resampler = StreamingMonoResampler(newRate, writer)
                        madeProgress = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                                ?: throw AudioDecodeException("Android returned an unavailable audio buffer.")
                            val activeResampler = resampler
                                ?: throw AudioDecodeException("Android did not describe the decoded audio.")
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            consumePcm(
                                buffer = outputBuffer,
                                channelCount = outputChannels,
                                encoding = outputEncoding,
                                resampler = activeResampler
                            )
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        activeCodec.releaseOutputBuffer(outputIndex, false)
                        madeProgress = true

                        if (reportedDurationUs > 0L && info.presentationTimeUs >= 0L) {
                            val progress = ((info.presentationTimeUs * 100L) / reportedDurationUs)
                                .toInt()
                                .coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }

                idleIterations = if (madeProgress) 0 else idleIterations + 1
                if (idleIterations > MAX_IDLE_ITERATIONS) {
                    throw AudioDecodeException("Android's audio decoder stopped responding.")
                }
            }

            writer.finish()
            val sampleCount = writer.sampleCount
            if (sampleCount < WHISPER_SAMPLE_RATE / 2L) {
                throw AudioDecodeException("The selected file did not contain enough readable audio.")
            }
            onProgress(100)
            return DecodedAudio(
                wavFile = outputFile,
                durationMs = sampleCount * 1_000L / WHISPER_SAMPLE_RATE,
                sampleCount = sampleCount
            )
        } catch (error: AudioDecodeException) {
            outputFile.delete()
            throw error
        } catch (error: SecurityException) {
            outputFile.delete()
            throw AudioDecodeException("Lyr no longer has permission to read this audio file.", error)
        } catch (error: IOException) {
            outputFile.delete()
            throw AudioDecodeException("The selected audio file could not be read.", error)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
                // The decoder may already have failed.
            }
            try {
                codec?.release()
            } catch (_: Exception) {
                // Nothing else can release this native decoder.
            }
            extractor.release()
            writer.closeQuietly()
        }
    }

    private fun consumePcm(
        buffer: ByteBuffer,
        channelCount: Int,
        encoding: Int,
        resampler: StreamingMonoResampler
    ) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                while (buffer.remaining() >= channelCount * 2) {
                    var sum = 0L
                    repeat(channelCount) { sum += buffer.short.toLong() }
                    resampler.accept((sum / channelCount).toInt())
                }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (buffer.remaining() >= channelCount * 4) {
                    var sum = 0.0
                    repeat(channelCount) { sum += buffer.float.coerceIn(-1f, 1f) }
                    val mono = ((sum / channelCount) * Short.MAX_VALUE)
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    resampler.accept(mono)
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                while (buffer.remaining() >= channelCount) {
                    var sum = 0L
                    repeat(channelCount) {
                        sum += ((buffer.get().toInt() and 0xff) - 128) shl 8
                    }
                    resampler.accept((sum / channelCount).toInt())
                }
            }

            else -> throw AudioDecodeException(
                "This phone returned an unsupported PCM encoding ($encoding)."
            )
        }
    }

    private class StreamingMonoResampler(
        private val inputRate: Int,
        private val writer: Pcm16WavWriter
    ) {
        private var phase = 0L
        private var bucketSum = 0L
        private var bucketCount = 0

        fun accept(sample: Int) {
            bucketSum += sample
            bucketCount++
            phase += WHISPER_SAMPLE_RATE.toLong()
            while (phase >= inputRate) {
                val averaged = if (bucketCount > 0) {
                    (bucketSum / bucketCount).toInt()
                } else {
                    sample
                }
                writer.writeSample(averaged.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
                phase -= inputRate.toLong()
                bucketSum = 0L
                bucketCount = 0
            }
        }
    }

    private class Pcm16WavWriter(file: File, private val sampleRate: Int) {
        private val output = RandomAccessFile(file, "rw")
        private val buffer = ByteArray(16 * 1024)
        private var bufferPosition = 0
        var sampleCount: Long = 0L
            private set

        init {
            output.setLength(0L)
            output.write(ByteArray(WAV_HEADER_BYTES))
        }

        fun writeSample(value: Int) {
            if (bufferPosition + 2 > buffer.size) flushBuffer()
            buffer[bufferPosition++] = (value and 0xff).toByte()
            buffer[bufferPosition++] = ((value ushr 8) and 0xff).toByte()
            sampleCount++
        }

        fun finish() {
            flushBuffer()
            val dataBytes = sampleCount * 2L
            if (dataBytes > Int.MAX_VALUE - WAV_HEADER_BYTES) {
                throw AudioDecodeException("This recording is too long for local WAV processing.")
            }
            output.seek(0L)
            writeAscii("RIFF")
            writeLeInt((36L + dataBytes).toInt())
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeLeInt(16)
            writeLeShort(1)
            writeLeShort(1)
            writeLeInt(sampleRate)
            writeLeInt(sampleRate * 2)
            writeLeShort(2)
            writeLeShort(16)
            writeAscii("data")
            writeLeInt(dataBytes.toInt())
            output.fd.sync()
            output.close()
        }

        fun closeQuietly() {
            try {
                output.close()
            } catch (_: Exception) {
                // Best-effort cleanup after a decoder failure.
            }
        }

        private fun flushBuffer() {
            if (bufferPosition > 0) {
                output.write(buffer, 0, bufferPosition)
                bufferPosition = 0
            }
        }

        private fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))

        private fun writeLeShort(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }

        private fun writeLeInt(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            output.write((value ushr 16) and 0xff)
            output.write((value ushr 24) and 0xff)
        }
    }

    private fun MediaFormat.longValue(key: String): Long? = try {
        if (containsKey(key)) getLong(key) else null
    } catch (_: Exception) {
        null
    }

    private fun MediaFormat.integerValue(key: String): Int? = try {
        if (containsKey(key)) getInteger(key) else null
    } catch (_: Exception) {
        null
    }

    class AudioDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    const val WHISPER_SAMPLE_RATE = 16_000
    const val WAV_HEADER_BYTES = 44
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_ITERATIONS = 6_000
    private const val MAX_CHANNELS = 8
}
