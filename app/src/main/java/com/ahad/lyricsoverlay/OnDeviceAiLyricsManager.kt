package com.ahad.lyricsoverlay

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

enum class AiLyricsMode {
    AUDIO_ONLY,
    ALIGN_KNOWN_LYRICS
}

enum class AiJobPhase {
    IDLE,
    DOWNLOADING_MODEL,
    PREPARING_AUDIO,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELED
}

data class AiLyricsJobState(
    val songId: Long? = null,
    val phase: AiJobPhase = AiJobPhase.IDLE,
    val progress: Int = 0,
    val message: String? = null,
    val rawLrc: String? = null
) {
    val isRunning: Boolean
        get() = phase == AiJobPhase.DOWNLOADING_MODEL ||
            phase == AiJobPhase.PREPARING_AUDIO ||
            phase == AiJobPhase.PROCESSING
}

data class OnDeviceModelStatus(
    val displayName: String,
    val downloadBytes: Long,
    val downloaded: Boolean,
    val totalRamBytes: Long,
    val supported: Boolean
) {
    val downloadMegabytes: Int
        get() = (downloadBytes / (1_000L * 1_000L)).toInt()
    val totalRamGigabytes: Float
        get() = totalRamBytes / (1024f * 1024f * 1024f)
}

/**
 * Runs the complete AI workflow locally: model download, Android media decoding, chunked
 * whisper.cpp inference, phrase timing, and an unsaved LRC draft. Song audio is never uploaded.
 */
object OnDeviceAiLyricsManager {

    fun interface Listener {
        fun onAiLyricsJobChanged(state: AiLyricsJobState)
    }

    private data class ModelSpec(
        val fileName: String,
        val displayName: String,
        val bytes: Long,
        val sha256: String
    ) {
        val url: String
            get() = "$MODEL_REPOSITORY/$fileName"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val generation = AtomicInteger(0)

    @Volatile
    private var state = AiLyricsJobState()

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun currentState(): AiLyricsJobState = state

    fun addListener(listener: Listener) {
        listeners += listener
        mainHandler.post { listener.onAiLyricsJobChanged(state) }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun modelStatus(context: Context): OnDeviceModelStatus {
        val spec = recommendedModel(context)
        val file = modelFile(context, spec)
        return OnDeviceModelStatus(
            displayName = spec.displayName,
            downloadBytes = spec.bytes,
            downloaded = file.isFile && file.length() == spec.bytes,
            totalRamBytes = totalRam(context),
            supported = supportsNativeRuntime()
        )
    }

    fun start(
        context: Context,
        song: Song,
        mode: AiLyricsMode,
        knownLyrics: String,
        prioritizeBengali: Boolean
    ): Boolean {
        if (state.isRunning) return false
        if (mode == AiLyricsMode.ALIGN_KNOWN_LYRICS && knownLyrics.isBlank()) return false

        val token = generation.incrementAndGet()
        val appContext = context.applicationContext
        updateState(
            AiLyricsJobState(
                songId = song.id,
                phase = if (usableModelFile(appContext, recommendedModel(appContext)) == null) {
                    AiJobPhase.DOWNLOADING_MODEL
                } else {
                    AiJobPhase.PREPARING_AUDIO
                },
                progress = 0
            )
        )
        executor.execute {
            runJob(
                context = appContext,
                song = song,
                mode = mode,
                knownLyrics = knownLyrics,
                language = if (prioritizeBengali) "bn" else "auto",
                token = token
            )
        }
        return true
    }

    fun cancel() {
        val previous = state
        if (!previous.isRunning) return
        generation.incrementAndGet()
        activeConnection?.disconnect()
        updateState(
            previous.copy(
                phase = AiJobPhase.CANCELED,
                message = null,
                rawLrc = null
            )
        )
    }

    fun clearFinishedResult() {
        if (!state.isRunning) updateState(AiLyricsJobState())
    }

    fun deleteDownloadedModels(context: Context): Boolean {
        if (state.isRunning) return false
        var deletedAny = false
        listOf(BASE_MODEL, SMALL_MODEL).forEach { spec ->
            val file = modelFile(context.applicationContext, spec)
            val partial = File(file.parentFile, "${spec.fileName}.part")
            if (file.exists()) deletedAny = file.delete() || deletedAny
            if (partial.exists()) deletedAny = partial.delete() || deletedAny
        }
        return deletedAny
    }

    private fun runJob(
        context: Context,
        song: Song,
        mode: AiLyricsMode,
        knownLyrics: String,
        language: String,
        token: Int
    ) {
        if (!supportsNativeRuntime()) {
            fail(
                song,
                token,
                "On-device AI needs a 64-bit ARM phone (arm64-v8a). Playback and all other lyrics features still work."
            )
            return
        }

        val recommended = recommendedModel(context)
        val attempts = if (recommended == SMALL_MODEL) listOf(SMALL_MODEL, BASE_MODEL) else listOf(BASE_MODEL)
        var lastMemoryFailure: OutOfMemoryError? = null
        for ((attemptIndex, spec) in attempts.withIndex()) {
            try {
                ensureCurrent(token)
                if (attemptIndex > 0) {
                    updateIfCurrent(
                        token,
                        state.copy(
                            phase = AiJobPhase.DOWNLOADING_MODEL,
                            progress = 0,
                            message = "Memory was tight, so Lyr switched automatically to the compact model."
                        )
                    )
                    System.gc()
                    Thread.sleep(500L)
                }
                val model = ensureModel(context, spec, song.id, token)
                val rawLrc = processSong(
                    context = context,
                    song = song,
                    mode = mode,
                    knownLyrics = knownLyrics,
                    language = language,
                    modelFile = model,
                    modelSpec = spec,
                    token = token
                )
                ensureCurrent(token)
                if (LrcParser.parse(rawLrc).isEmpty()) {
                    throw LocalAiException(
                        "The local model did not find usable sung words. Try Known lyrics mode or another recording."
                    )
                }
                updateIfCurrent(
                    token,
                    AiLyricsJobState(
                        songId = song.id,
                        phase = AiJobPhase.COMPLETED,
                        progress = 100,
                        rawLrc = rawLrc,
                        message = spec.displayName
                    )
                )
                return
            } catch (_: LocalAiCanceledException) {
                return
            } catch (storageError: ModelStorageException) {
                if (spec == SMALL_MODEL && attemptIndex < attempts.lastIndex) {
                    updateIfCurrent(
                        token,
                        state.copy(
                            phase = AiJobPhase.DOWNLOADING_MODEL,
                            progress = 0,
                            message = "Storage is tight, so Lyr selected the smaller model automatically."
                        )
                    )
                    continue
                }
                if (generation.get() == token) fail(song, token, storageError.userFacingMessage())
                return
            } catch (memoryError: OutOfMemoryError) {
                lastMemoryFailure = memoryError
                if (spec == BASE_MODEL || attemptIndex == attempts.lastIndex) break
            } catch (error: Throwable) {
                if (error is ThreadDeath) throw error
                if (generation.get() == token) fail(song, token, error.userFacingMessage())
                return
            }
        }
        if (generation.get() == token) {
            fail(
                song,
                token,
                "This phone ran out of memory while loading the compact AI model. Close other apps, restart Lyr, and retry.",
                lastMemoryFailure
            )
        }
    }

    private fun processSong(
        context: Context,
        song: Song,
        mode: AiLyricsMode,
        knownLyrics: String,
        language: String,
        modelFile: File,
        modelSpec: ModelSpec,
        token: Int
    ): String {
        val workDir = File(context.cacheDir, "local-ai/${song.id}-$token")
        workDir.deleteRecursively()
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            throw LocalAiException("Lyr could not create temporary space for local processing.")
        }
        checkAudioStorage(context, song.durationMs)

        var whisperModel: WhisperModel? = null
        try {
            updateIfCurrent(
                token,
                AiLyricsJobState(
                    songId = song.id,
                    phase = AiJobPhase.PREPARING_AUDIO,
                    progress = 36,
                    message = "Decoding MP3/M4A/WAV/FLAC locally…"
                )
            )
            val decoded = LocalAudioDecoder.decodeToWhisperWav(
                context = context,
                source = song.contentUri,
                outputFile = File(workDir, "decoded-16k-mono.wav"),
                fallbackDurationMs = song.durationMs,
                checkCanceled = { ensureCurrent(token) },
                onProgress = { decodeProgress ->
                    updateIfCurrent(
                        token,
                        state.copy(
                            phase = AiJobPhase.PREPARING_AUDIO,
                            progress = 36 + (decodeProgress * 12 / 100),
                            message = "Decoding audio entirely on this phone…"
                        )
                    )
                }
            )
            ensureCurrent(token)

            updateIfCurrent(
                token,
                state.copy(
                    phase = AiJobPhase.PROCESSING,
                    progress = 50,
                    message = "Loading ${modelSpec.displayName} into phone memory…"
                )
            )
            val loadedModel = runBlocking { Whisper.loadModel(context, modelFile.absolutePath) }
            whisperModel = loadedModel
            ensureCurrent(token)

            val chunkCount = WhisperWavChunks.count(decoded.sampleCount)
            if (chunkCount <= 0) throw LocalAiException("The decoded recording is empty.")
            val recognized = mutableListOf<OnDeviceLyricsProcessor.Segment>()
            val chunkFile = File(workDir, "current-chunk.wav")
            val threads = inferenceThreads(context)

            for (chunkIndex in 0 until chunkCount) {
                ensureCurrent(token)
                if (modelSpec == SMALL_MODEL && isSystemLowOnMemory(context)) {
                    throw OutOfMemoryError("Android reported low memory before local inference.")
                }
                val chunk = WhisperWavChunks.create(
                    sourceWav = decoded.wavFile,
                    outputFile = chunkFile,
                    totalSamples = decoded.sampleCount,
                    index = chunkIndex
                )
                val progressBefore = 51 + ((chunkIndex * 46f) / chunkCount).roundToInt()
                updateIfCurrent(
                    token,
                    state.copy(
                        phase = AiJobPhase.PROCESSING,
                        progress = progressBefore.coerceIn(51, 96),
                        message = "Listening locally · part ${chunkIndex + 1} of $chunkCount"
                    )
                )
                val result = runBlocking {
                    Whisper.transcribe(
                        model = loadedModel,
                        audioPath = chunk.file.absolutePath,
                        config = WhisperConfig(
                            language = language,
                            translate = false,
                            threads = threads
                        )
                    )
                }
                ensureCurrent(token)
                result.segments.forEach { segment ->
                    if (WhisperWavChunks.acceptsSegment(
                            chunk,
                            segment.startMs,
                            segment.endMs
                        )
                    ) {
                        recognized += OnDeviceLyricsProcessor.Segment(
                            startMs = chunk.startMs + segment.startMs,
                            endMs = chunk.startMs + segment.endMs,
                            text = segment.text
                        )
                    }
                }
                chunkFile.delete()
                updateIfCurrent(
                    token,
                    state.copy(
                        phase = AiJobPhase.PROCESSING,
                        progress = 51 + (((chunkIndex + 1) * 46f) / chunkCount).roundToInt(),
                        message = "Finished local part ${chunkIndex + 1} of $chunkCount"
                    )
                )
            }

            ensureCurrent(token)
            val cleanSegments = OnDeviceLyricsProcessor.cleanAndMergeSegments(
                recognized,
                decoded.durationMs
            )
            if (cleanSegments.isEmpty()) {
                throw LocalAiException(
                    "No sung words were recognized. Bengali singing can be difficult; try Known lyrics mode for this song."
                )
            }
            updateIfCurrent(
                token,
                state.copy(
                    phase = AiJobPhase.PROCESSING,
                    progress = 98,
                    message = "Building editable phrase start/end times…"
                )
            )
            return when (mode) {
                AiLyricsMode.AUDIO_ONLY -> OnDeviceLyricsProcessor.audioOnlyLrc(
                    cleanSegments,
                    decoded.durationMs
                )
                AiLyricsMode.ALIGN_KNOWN_LYRICS -> OnDeviceLyricsProcessor.alignKnownLyricsLrc(
                    knownLyrics,
                    cleanSegments,
                    decoded.durationMs
                )
            }
        } finally {
            whisperModel?.let { model ->
                try {
                    Whisper.releaseModel(model)
                } catch (_: Throwable) {
                    // The process is already unwinding; temporary files must still be removed.
                }
            }
            workDir.deleteRecursively()
        }
    }

    private fun ensureModel(
        context: Context,
        spec: ModelSpec,
        songId: Long,
        token: Int
    ): File {
        val destination = modelFile(context, spec)
        destination.parentFile?.mkdirs()

        // A size check is deliberately enough for fast UI status updates, but every inference
        // job verifies the complete persisted model before native code is allowed to load it.
        usableModelFile(context, spec)?.let { existingModel ->
            updateIfCurrent(
                token,
                AiLyricsJobState(
                    songId = songId,
                    phase = AiJobPhase.DOWNLOADING_MODEL,
                    progress = 35,
                    message = "Verifying the downloaded model…"
                )
            )
            if (sha256(existingModel, token).equals(spec.sha256, ignoreCase = true)) {
                return existingModel
            }
            existingModel.delete()
        }

        val partial = File(destination.parentFile, "${destination.name}.part")
        if (partial.length() > spec.bytes) partial.delete()

        val remainingBytes = spec.bytes - partial.length()
        if (remainingBytes > 0L) {
            val freeBytes = availableBytes(destination.parentFile ?: context.filesDir)
            if (freeBytes < remainingBytes + MODEL_STORAGE_RESERVE_BYTES) {
                throw ModelStorageException(
                    "Lyr needs about ${formatMegabytes(remainingBytes + MODEL_STORAGE_RESERVE_BYTES)} MB free to download the ${spec.displayName}."
                )
            }
        }

        updateIfCurrent(
            token,
            AiLyricsJobState(
                songId = songId,
                phase = AiJobPhase.DOWNLOADING_MODEL,
                progress = ((partial.length() * 34L) / spec.bytes).toInt().coerceIn(0, 34),
                message = "One-time ${formatMegabytes(spec.bytes)} MB model download · audio stays on phone"
            )
        )
        // A process can be killed after the final network byte is flushed but before promotion.
        // In that case the complete .part file is verified directly instead of issuing an invalid
        // Range request at exactly the server's end-of-file offset.
        if (partial.length() < spec.bytes) {
            downloadModel(spec, partial, songId, token)
        }
        ensureCurrent(token)
        if (partial.length() != spec.bytes) {
            throw LocalAiException("The AI model download was incomplete. Retry to resume it.")
        }

        updateIfCurrent(
            token,
            state.copy(
                phase = AiJobPhase.DOWNLOADING_MODEL,
                progress = 35,
                message = "Verifying the downloaded model…"
            )
        )
        val digest = sha256(partial, token)
        if (!digest.equals(spec.sha256, ignoreCase = true)) {
            partial.delete()
            throw LocalAiException("The AI model failed its safety check and was removed. Please retry the download.")
        }
        destination.delete()
        if (!partial.renameTo(destination)) {
            FileInputStream(partial).use { input ->
                FileOutputStream(destination).use(input::copyTo)
            }
            partial.delete()
        }
        return destination
    }

    private fun downloadModel(spec: ModelSpec, partial: File, songId: Long, token: Int) {
        var existing = partial.length()
        var retriedWithoutResume = false
        while (true) {
            ensureCurrent(token)
            val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("User-Agent", "Lyr Android on-device model downloader")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            activeConnection = connection
            try {
                val response = connection.responseCode
                if (existing > 0L && response == HttpURLConnection.HTTP_OK && !retriedWithoutResume) {
                    partial.delete()
                    existing = 0L
                    retriedWithoutResume = true
                    continue
                }
                if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                    throw LocalAiException("The public AI model download failed (HTTP $response). Retry later.")
                }
                BufferedInputStream(connection.inputStream, DOWNLOAD_BUFFER_BYTES).use { input ->
                    BufferedOutputStream(
                        FileOutputStream(partial, existing > 0L),
                        DOWNLOAD_BUFFER_BYTES
                    ).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var downloaded = existing
                        var lastPercent = -1
                        while (true) {
                            ensureCurrent(token)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded > spec.bytes) {
                                throw LocalAiException("The model server returned an unexpected file.")
                            }
                            val percent = ((downloaded * 100L) / spec.bytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                updateIfCurrent(
                                    token,
                                    AiLyricsJobState(
                                        songId = songId,
                                        phase = AiJobPhase.DOWNLOADING_MODEL,
                                        progress = (percent * 34 / 100).coerceIn(0, 34),
                                        message = "Downloading ${spec.displayName} once · $percent%"
                                    )
                                )
                            }
                        }
                    }
                }
                return
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }
    }

    private fun sha256(file: File, token: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                ensureCurrent(token)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    private fun checkAudioStorage(context: Context, durationMs: Long) {
        val expectedPcmBytes = if (durationMs > 0L) {
            durationMs * LocalAudioDecoder.WHISPER_SAMPLE_RATE * 2L / 1_000L
        } else {
            UNKNOWN_AUDIO_TEMP_BYTES
        }
        val required = expectedPcmBytes + AUDIO_STORAGE_RESERVE_BYTES
        if (availableBytes(context.cacheDir) < required) {
            throw InsufficientStorageException(
                "Lyr needs about ${formatMegabytes(required)} MB free for temporary local audio processing."
            )
        }
    }

    private fun usableModelFile(context: Context, spec: ModelSpec): File? {
        val file = modelFile(context, spec)
        if (!file.exists()) return null
        if (file.length() == spec.bytes) return file
        file.delete()
        return null
    }

    private fun modelFile(context: Context, spec: ModelSpec): File =
        File(File(context.filesDir, MODEL_DIRECTORY), spec.fileName)

    private fun recommendedModel(context: Context): ModelSpec {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val total = totalRam(context)
        return if (total >= SMALL_MODEL_RAM_THRESHOLD_BYTES &&
            manager.largeMemoryClass >= SMALL_MODEL_MIN_MEMORY_CLASS_MB &&
            !manager.isLowRamDevice
        ) {
            SMALL_MODEL
        } else {
            BASE_MODEL
        }
    }

    private fun totalRam(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
    }

    private fun isSystemLowOnMemory(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).lowMemory
    }

    private fun inferenceThreads(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val maximum = if (manager.isLowRamDevice || manager.memoryClass < 256) 2 else 4
        return Runtime.getRuntime().availableProcessors().coerceIn(1, maximum)
    }

    private fun supportsNativeRuntime(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    private fun availableBytes(directory: File): Long {
        directory.mkdirs()
        return try {
            StatFs(directory.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun updateIfCurrent(token: Int, newState: AiLyricsJobState) {
        if (generation.get() == token) updateState(newState)
    }

    private fun updateState(newState: AiLyricsJobState) {
        state = newState
        mainHandler.post {
            listeners.forEach { listener -> listener.onAiLyricsJobChanged(newState) }
        }
    }

    private fun fail(song: Song, token: Int, message: String, cause: Throwable? = null) {
        if (generation.get() != token) return
        updateState(
            AiLyricsJobState(
                songId = song.id,
                phase = AiJobPhase.FAILED,
                progress = state.progress,
                message = message
            )
        )
        cause?.printStackTrace()
    }

    private fun ensureCurrent(token: Int) {
        if (generation.get() != token) throw LocalAiCanceledException()
    }

    private fun Throwable.userFacingMessage(): String = when (this) {
        is LocalAiException,
        is InsufficientStorageException,
        is ModelStorageException,
        is LocalAudioDecoder.AudioDecodeException -> message ?: "Local AI processing failed."
        is java.net.SocketTimeoutException -> "The one-time AI model download timed out. Retry to resume it."
        is java.net.UnknownHostException -> "The public AI model could not be reached. Check internet, then retry."
        is UnsatisfiedLinkError -> "This phone cannot load the on-device AI engine. A 64-bit ARM phone is required."
        is IllegalArgumentException -> message ?: "The lyrics or audio could not be processed."
        else -> message?.takeIf(String::isNotBlank) ?: "Local AI processing failed on this phone."
    }

    private fun formatMegabytes(bytes: Long): Int =
        ((bytes + 999_999L) / 1_000_000L).toInt()

    private class LocalAiCanceledException : RuntimeException()
    private class LocalAiException(message: String, cause: Throwable? = null) : IOException(message, cause)
    private class ModelStorageException(message: String) : IOException(message)
    private class InsufficientStorageException(message: String) : IOException(message)

    private val BASE_MODEL = ModelSpec(
        fileName = "ggml-base-q5_1.bin",
        displayName = "Compact multilingual model",
        bytes = 59_707_625L,
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    )
    private val SMALL_MODEL = ModelSpec(
        fileName = "ggml-small-q5_1.bin",
        displayName = "Balanced multilingual model",
        bytes = 190_085_487L,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    )

    private const val MODEL_REPOSITORY =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val MODEL_DIRECTORY = "on-device-ai-models"
    private const val SMALL_MODEL_RAM_THRESHOLD_BYTES = 5_500L * 1024L * 1024L
    private const val SMALL_MODEL_MIN_MEMORY_CLASS_MB = 384
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 90_000
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    private const val MODEL_STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
    private const val AUDIO_STORAGE_RESERVE_BYTES = 24L * 1024L * 1024L
    private const val UNKNOWN_AUDIO_TEMP_BYTES = 160L * 1024L * 1024L
}
