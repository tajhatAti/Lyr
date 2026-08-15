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
import org.json.JSONArray
import org.json.JSONObject
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
    SEARCHING_ONLINE,
    DOWNLOADING_MODEL,
    PREPARING_AUDIO,
    PROCESSING,
    SEARCHING_RECOGNIZED,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELED
}

enum class AiLyricsResultSource {
    ONLINE,
    LOCAL_FILE,
    ALIGNED_ON_DEVICE,
    ON_DEVICE
}

data class AiLyricsJobState(
    val songId: Long? = null,
    val phase: AiJobPhase = AiJobPhase.IDLE,
    val progress: Int = 0,
    val message: String? = null,
    val rawLrc: String? = null,
    val resultSource: AiLyricsResultSource? = null,
    val resumed: Boolean = false
) {
    val isRunning: Boolean
        get() = phase == AiJobPhase.SEARCHING_ONLINE ||
            phase == AiJobPhase.DOWNLOADING_MODEL ||
            phase == AiJobPhase.PREPARING_AUDIO ||
            phase == AiJobPhase.PROCESSING ||
            phase == AiJobPhase.SEARCHING_RECOGNIZED ||
            phase == AiJobPhase.FINALIZING
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

    private data class JobRequest(
        val requestId: Long,
        val song: Song,
        val mode: AiLyricsMode,
        val knownLyrics: String,
        val language: String
    )

    private data class RecognitionCheckpoint(
        val nextChunkIndex: Int,
        val segments: List<OnDeviceLyricsProcessor.Segment>
    )

    private data class LocalProcessingResult(
        val rawLrc: String,
        val recognizedPhrases: List<String>
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val generation = AtomicInteger(0)

    @Volatile
    private var state = AiLyricsJobState()

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var currentRequest: JobRequest? = null

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

        val appContext = context.applicationContext
        applicationContext = appContext
        clearPersistedJob(appContext, deleteWork = true)
        val request = JobRequest(
            requestId = System.currentTimeMillis(),
            song = song,
            mode = mode,
            knownLyrics = knownLyrics,
            language = if (prioritizeBengali) "bn" else "auto"
        )
        currentRequest = request
        persistRequest(appContext, request)
        val token = generation.incrementAndGet()
        updateState(
            AiLyricsJobState(
                songId = song.id,
                phase = AiJobPhase.SEARCHING_ONLINE,
                progress = 1,
                message = "Checking song details online first…"
            )
        )
        OnDeviceAiService.ensureRunning(appContext)
        executor.execute { runJob(appContext, request, token, resume = false) }
        return true
    }

    /** Restores a completed draft or resumes an interrupted job from its last durable chunk. */
    @Synchronized
    fun restorePending(context: Context) {
        if (currentRequest != null || state.isRunning) return
        val appContext = context.applicationContext
        applicationContext = appContext
        val request = readPersistedRequest(appContext) ?: return
        currentRequest = request
        val savedState = readPersistedState(appContext)
        if (savedState?.phase == AiJobPhase.COMPLETED && !savedState.rawLrc.isNullOrBlank()) {
            state = savedState.copy(resumed = true)
            mainHandler.post {
                listeners.forEach { listener -> listener.onAiLyricsJobChanged(state) }
            }
            return
        }
        if (savedState?.phase == AiJobPhase.FAILED) {
            state = savedState.copy(resumed = true)
            mainHandler.post {
                listeners.forEach { listener -> listener.onAiLyricsJobChanged(state) }
            }
            return
        }
        if (savedState?.phase == AiJobPhase.CANCELED) {
            clearPersistedJob(appContext, deleteWork = true)
            currentRequest = null
            return
        }

        val token = generation.incrementAndGet()
        updateState(
            AiLyricsJobState(
                songId = request.song.id,
                phase = savedState?.phase ?: AiJobPhase.SEARCHING_ONLINE,
                progress = savedState?.progress ?: 1,
                message = "Resuming saved lyrics work…",
                resumed = true
            )
        )
        OnDeviceAiService.ensureRunning(appContext)
        executor.execute { runJob(appContext, request, token, resume = true) }
    }

    fun cancel() {
        val previous = state
        if (!previous.isRunning) return
        generation.incrementAndGet()
        activeConnection?.disconnect()
        updateState(
            previous.copy(
                phase = AiJobPhase.CANCELED,
                message = "Saved AI work was canceled.",
                rawLrc = null
            )
        )
        applicationContext?.let { clearPersistedJob(it, deleteWork = true) }
        currentRequest = null
    }

    fun clearFinishedResult() {
        if (state.isRunning) return
        applicationContext?.let { clearPersistedJob(it, deleteWork = true) }
        currentRequest = null
        updateState(AiLyricsJobState())
    }

    fun deleteDownloadedModels(context: Context): Boolean {
        if (state.isRunning) return false
        var deletedAny = false
        listOf(BASE_MODEL, SMALL_MODEL).forEach { spec ->
            val file = modelFile(context.applicationContext, spec)
            val partial = File(file.parentFile, "${spec.fileName}.part")
            val verified = verifiedModelMarker(file)
            if (file.exists()) deletedAny = file.delete() || deletedAny
            if (partial.exists()) deletedAny = partial.delete() || deletedAny
            if (verified.exists()) deletedAny = verified.delete() || deletedAny
        }
        return deletedAny
    }

    private fun runJob(
        context: Context,
        request: JobRequest,
        token: Int,
        resume: Boolean
    ) {
        val song = request.song
        val workDir = jobWorkDirectory(context)
        if (!resume) workDir.deleteRecursively()
        workDir.mkdirs()

        val metadataSearchMarker = File(workDir, INITIAL_SEARCH_MARKER)
        if (!metadataSearchMarker.isFile) {
            try {
                updateIfCurrent(
                    token,
                    AiLyricsJobState(
                        songId = song.id,
                        phase = AiJobPhase.SEARCHING_ONLINE,
                        progress = 2,
                        message = "Searching LRCLIB with title and artist…",
                        resumed = resume
                    )
                )
                val existing = LyricsRepository(context).findSmartInitialLyrics(song)
                ensureCurrent(token)
                if (existing != null) {
                    val alignPastedLyrics = request.mode == AiLyricsMode.ALIGN_KNOWN_LYRICS &&
                        existing.source in ONLINE_TIMING_SOURCES
                    val completedLrc = if (alignPastedLyrics) {
                        alignKnownLyricsToTimedResult(request, existing.rawLrc)
                    } else {
                        existing.rawLrc
                    }
                    val source = when {
                        alignPastedLyrics -> AiLyricsResultSource.ALIGNED_ON_DEVICE
                        existing.source == LyricsSource.AI_GENERATED -> AiLyricsResultSource.ON_DEVICE
                        else -> AiLyricsResultSource.ONLINE
                    }
                    complete(
                        request,
                        token,
                        completedLrc,
                        source,
                        when {
                            alignPastedLyrics -> "Aligned your pasted lyrics to downloaded timing on this phone."
                            existing.source == LyricsSource.DOWNLOADED_CACHE -> {
                                "Reused downloaded synchronized lyrics."
                            }
                            else -> "Found synchronized lyrics without local generation."
                        }
                    )
                    workDir.deleteRecursively()
                    return
                }
                writeAtomically(metadataSearchMarker, "done")
            } catch (_: LocalAiCanceledException) {
                return
            } catch (_: Throwable) {
                // Offline or unavailable LRCLIB must not prevent the private local fallback.
                writeAtomically(metadataSearchMarker, "unavailable")
            }
        }

        if (!supportsNativeRuntime()) {
            val localFallback = LyricsRepository(context).findLocalFallback(song)
            ensureCurrent(token)
            if (localFallback != null) {
                val alignPastedLyrics = request.mode == AiLyricsMode.ALIGN_KNOWN_LYRICS
                complete(
                    request,
                    token,
                    if (alignPastedLyrics) {
                        alignKnownLyricsToTimedResult(request, localFallback.rawLrc)
                    } else {
                        localFallback.rawLrc
                    },
                    if (alignPastedLyrics) {
                        AiLyricsResultSource.ALIGNED_ON_DEVICE
                    } else {
                        AiLyricsResultSource.LOCAL_FILE
                    },
                    "Used a same-folder LRC after the online search."
                )
                workDir.deleteRecursively()
            } else {
                fail(
                    song,
                    token,
                    "No synchronized online or same-folder LRC was found. The local audio fallback needs a 64-bit ARM phone (arm64-v8a)."
                )
            }
            return
        }

        val recommended = recommendedModel(context)
        val attempts = if (recommended == SMALL_MODEL) listOf(SMALL_MODEL, BASE_MODEL) else listOf(BASE_MODEL)
        var lastMemoryFailure: OutOfMemoryError? = null
        for ((attemptIndex, spec) in attempts.withIndex()) {
            try {
                ensureCurrent(token)
                if (attemptIndex > 0) {
                    System.gc()
                    Thread.sleep(500L)
                }
                val model = ensureModel(context, spec, song.id, token)
                val localResult = processSong(
                    context = context,
                    song = song,
                    mode = request.mode,
                    knownLyrics = request.knownLyrics,
                    language = request.language,
                    modelFile = model,
                    modelSpec = spec,
                    token = token
                )
                ensureCurrent(token)
                if (LrcParser.parse(localResult.rawLrc).isEmpty()) {
                    throw LocalAiException(
                        "The local model did not find usable sung words. Try Known lyrics mode or another recording."
                    )
                }

                updateIfCurrent(
                    token,
                    AiLyricsJobState(
                        songId = song.id,
                        phase = AiJobPhase.SEARCHING_RECOGNIZED,
                        progress = 95,
                        message = "Searching online again with words heard on this phone…"
                    )
                )
                val recognizedMatch = try {
                    LyricsRepository(context).findOnlineFromRecognizedPhrases(
                        song,
                        localResult.recognizedPhrases
                    )
                } catch (_: Throwable) {
                    null
                }
                ensureCurrent(token)
                if (recognizedMatch != null) {
                    val alignPastedLyrics = request.mode == AiLyricsMode.ALIGN_KNOWN_LYRICS
                    complete(
                        request,
                        token,
                        if (alignPastedLyrics) {
                            alignKnownLyricsToTimedResult(request, recognizedMatch.rawLrc)
                        } else {
                            recognizedMatch.rawLrc
                        },
                        if (alignPastedLyrics) {
                            AiLyricsResultSource.ALIGNED_ON_DEVICE
                        } else {
                            AiLyricsResultSource.ONLINE
                        },
                        if (alignPastedLyrics) {
                            "Matched online timing and aligned your pasted lyrics on this phone."
                        } else {
                            "Matched synchronized lyrics after local listening."
                        }
                    )
                    jobWorkDirectory(context).deleteRecursively()
                    return
                }

                val localFallback = LyricsRepository(context).findLocalFallback(song)
                ensureCurrent(token)
                if (localFallback != null) {
                    val alignPastedLyrics = request.mode == AiLyricsMode.ALIGN_KNOWN_LYRICS
                    complete(
                        request,
                        token,
                        if (alignPastedLyrics) {
                            alignKnownLyricsToTimedResult(request, localFallback.rawLrc)
                        } else {
                            localFallback.rawLrc
                        },
                        if (alignPastedLyrics) {
                            AiLyricsResultSource.ALIGNED_ON_DEVICE
                        } else {
                            AiLyricsResultSource.LOCAL_FILE
                        },
                        "Used a same-folder LRC after local listening and both online searches."
                    )
                    jobWorkDirectory(context).deleteRecursively()
                    return
                }

                updateIfCurrent(
                    token,
                    state.copy(
                        phase = AiJobPhase.FINALIZING,
                        progress = 98,
                        message = "No reliable online match · building local phrase timing…"
                    )
                )
                complete(
                    request,
                    token,
                    localResult.rawLrc,
                    AiLyricsResultSource.ON_DEVICE,
                    spec.displayName
                )
                jobWorkDirectory(context).deleteRecursively()
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
                updateIfCurrent(
                    token,
                    state.copy(
                        phase = AiJobPhase.DOWNLOADING_MODEL,
                        progress = 0,
                        message = "Memory was tight, so Lyr switched automatically to the compact model."
                    )
                )
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

    /** Keeps Known lyrics mode exact while reusing already synchronized online cue timing. */
    private fun alignKnownLyricsToTimedResult(request: JobRequest, rawLrc: String): String =
        OnDeviceLyricsProcessor.alignKnownLyricsToTimedLrc(
            knownLyrics = request.knownLyrics,
            timedLrc = rawLrc,
            songDurationMs = request.song.durationMs
        )

    private fun processSong(
        context: Context,
        song: Song,
        mode: AiLyricsMode,
        knownLyrics: String,
        language: String,
        modelFile: File,
        modelSpec: ModelSpec,
        token: Int
    ): LocalProcessingResult {
        val workDir = jobWorkDirectory(context)
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            throw LocalAiException("Lyr could not create temporary space for local processing.")
        }

        var whisperModel: WhisperModel? = null
        try {
            val decoded = readDecodedCheckpoint(workDir) ?: run {
                checkAudioStorage(context, song.durationMs)
                updateIfCurrent(
                    token,
                    AiLyricsJobState(
                        songId = song.id,
                        phase = AiJobPhase.PREPARING_AUDIO,
                        progress = 36,
                        message = "Preparing MP3/M4A/WAV/FLAC locally…"
                    )
                )
                LocalAudioDecoder.decodeToWhisperWav(
                    context = context,
                    source = song.contentUri,
                    outputFile = File(workDir, DECODED_AUDIO_FILE),
                    fallbackDurationMs = song.durationMs,
                    checkCanceled = { ensureCurrent(token) },
                    onProgress = { decodeProgress ->
                        updateIfCurrent(
                            token,
                            state.copy(
                                phase = AiJobPhase.PREPARING_AUDIO,
                                progress = 36 + (decodeProgress * 12 / 100),
                                message = "Preparing audio entirely on this phone…"
                            )
                        )
                    }
                ).also { persistDecodedCheckpoint(workDir, it) }
            }
            ensureCurrent(token)

            val chunkCount = WhisperWavChunks.count(decoded.sampleCount)
            if (chunkCount <= 0) throw LocalAiException("The decoded recording is empty.")
            var checkpoint = readRecognitionCheckpoint(workDir)
                ?: RecognitionCheckpoint(0, emptyList())
            if (checkpoint.nextChunkIndex !in 0..chunkCount) {
                checkpoint = RecognitionCheckpoint(0, emptyList())
            }
            val recognized = checkpoint.segments.toMutableList()
            val chunkFile = File(workDir, "current-chunk.wav")
            val threads = inferenceThreads(context)

            var loadedModel: WhisperModel? = null
            if (checkpoint.nextChunkIndex < chunkCount) {
                updateIfCurrent(
                    token,
                    state.copy(
                        phase = AiJobPhase.PROCESSING,
                        progress = 50,
                        message = "Loading ${modelSpec.displayName} into phone memory…"
                    )
                )
                loadedModel = runBlocking { Whisper.loadModel(context, modelFile.absolutePath) }
                whisperModel = loadedModel
                ensureCurrent(token)
            }

            for (chunkIndex in checkpoint.nextChunkIndex until chunkCount) {
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
                val progressBefore = 51 + ((chunkIndex * 42f) / chunkCount).roundToInt()
                updateIfCurrent(
                    token,
                    state.copy(
                        phase = AiJobPhase.PROCESSING,
                        progress = progressBefore.coerceIn(51, 93),
                        message = "Listening locally · part ${chunkIndex + 1} of $chunkCount"
                    )
                )
                val result = runBlocking {
                    Whisper.transcribe(
                        model = loadedModel
                            ?: throw LocalAiException("The local AI model was not loaded."),
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
                persistRecognitionCheckpoint(
                    workDir,
                    RecognitionCheckpoint(chunkIndex + 1, recognized.toList())
                )
                chunkFile.delete()
                updateIfCurrent(
                    token,
                    state.copy(
                        phase = AiJobPhase.PROCESSING,
                        progress = 51 + (((chunkIndex + 1) * 42f) / chunkCount).roundToInt(),
                        message = "Saved local part ${chunkIndex + 1} of $chunkCount"
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
                    progress = 94,
                    message = "Analyzing locally heard phrases…"
                )
            )
            val rawLrc = when (mode) {
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
            return LocalProcessingResult(
                rawLrc = rawLrc,
                recognizedPhrases = cleanSegments.map { it.text }
            )
        } finally {
            whisperModel?.let { model ->
                try {
                    Whisper.releaseModel(model)
                } catch (_: Throwable) {
                    // The process is already unwinding; a later run can resume its saved chunks.
                }
            }
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

        // Private app files cannot be changed by another app. After the first complete SHA-256
        // verification, a length/mtime marker avoids hashing 60–190 MB again on every song.
        usableModelFile(context, spec)?.let { existingModel ->
            if (isPreviouslyVerified(existingModel, spec)) return existingModel
            updateIfCurrent(
                token,
                AiLyricsJobState(
                    songId = songId,
                    phase = AiJobPhase.DOWNLOADING_MODEL,
                    progress = 35,
                    message = "Verifying the downloaded model once…"
                )
            )
            if (sha256(existingModel, token).equals(spec.sha256, ignoreCase = true)) {
                markModelVerified(existingModel, spec)
                return existingModel
            }
            verifiedModelMarker(existingModel).delete()
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
        markModelVerified(destination, spec)
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
        if (availableBytes(jobDirectory(context)) < required) {
            throw InsufficientStorageException(
                "Lyr needs about ${formatMegabytes(required)} MB free for temporary local audio processing."
            )
        }
    }

    private fun usableModelFile(context: Context, spec: ModelSpec): File? {
        val file = modelFile(context, spec)
        if (!file.exists()) return null
        if (file.length() == spec.bytes) return file
        verifiedModelMarker(file).delete()
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
        val maximum = when {
            manager.isLowRamDevice || manager.memoryClass < 256 -> 2
            totalRam(context) >= SMALL_MODEL_RAM_THRESHOLD_BYTES -> 6
            else -> 4
        }
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

    private fun complete(
        request: JobRequest,
        token: Int,
        rawLrc: String,
        source: AiLyricsResultSource,
        message: String
    ) {
        updateIfCurrent(
            token,
            AiLyricsJobState(
                songId = request.song.id,
                phase = AiJobPhase.COMPLETED,
                progress = 100,
                rawLrc = rawLrc,
                resultSource = source,
                message = message,
                resumed = state.resumed
            )
        )
    }

    private fun jobDirectory(context: Context): File = File(context.filesDir, JOB_DIRECTORY)

    private fun jobWorkDirectory(context: Context): File = File(jobDirectory(context), JOB_WORK_DIRECTORY)

    private fun persistRequest(context: Context, request: JobRequest) {
        val song = request.song
        val json = JSONObject().apply {
            put("requestId", request.requestId)
            put("mode", request.mode.name)
            put("knownLyrics", request.knownLyrics)
            put("language", request.language)
            put("song", JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("sourceTitle", song.sourceTitle)
                put("artist", song.artist)
                put("album", song.album)
                put("durationMs", song.durationMs)
                put("dateAddedSeconds", song.dateAddedSeconds)
                put("contentUri", song.contentUri.toString())
                put("albumId", song.albumId)
                put("albumArtUri", song.albumArtUri?.toString() ?: "")
                put("fileName", song.fileName)
                put("relativePath", song.relativePath ?: "")
                put("legacyDataPath", song.legacyDataPath ?: "")
            })
        }
        synchronized(PERSISTENCE_LOCK) {
            writeAtomically(File(jobDirectory(context), REQUEST_FILE), json.toString())
        }
    }

    private fun readPersistedRequest(context: Context): JobRequest? {
        return try {
        val file = File(jobDirectory(context), REQUEST_FILE)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val songJson = json.getJSONObject("song")
        val albumArt = songJson.optString("albumArtUri").takeIf { it.isNotBlank() }
        JobRequest(
            requestId = json.getLong("requestId"),
            song = Song(
                id = songJson.getLong("id"),
                title = songJson.getString("title"),
                sourceTitle = songJson.getString("sourceTitle"),
                artist = songJson.getString("artist"),
                album = songJson.getString("album"),
                durationMs = songJson.getLong("durationMs"),
                dateAddedSeconds = songJson.optLong("dateAddedSeconds", 0L),
                contentUri = android.net.Uri.parse(songJson.getString("contentUri")),
                albumId = songJson.optLong("albumId", -1L),
                albumArtUri = albumArt?.let(android.net.Uri::parse),
                fileName = songJson.optString("fileName"),
                relativePath = songJson.optString("relativePath").takeIf { it.isNotBlank() },
                legacyDataPath = songJson.optString("legacyDataPath").takeIf { it.isNotBlank() }
            ),
            mode = AiLyricsMode.valueOf(json.getString("mode")),
            knownLyrics = json.optString("knownLyrics"),
            language = json.optString("language", "auto")
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun persistState(context: Context, value: AiLyricsJobState) {
        if (currentRequest == null) return
        val json = JSONObject().apply {
            value.songId?.let { put("songId", it) }
            put("phase", value.phase.name)
            put("progress", value.progress)
            put("message", value.message ?: "")
            put("rawLrc", value.rawLrc ?: "")
            put("resultSource", value.resultSource?.name ?: "")
            put("resumed", value.resumed)
        }
        synchronized(PERSISTENCE_LOCK) {
            writeAtomically(File(jobDirectory(context), STATE_FILE), json.toString())
        }
    }

    private fun readPersistedState(context: Context): AiLyricsJobState? {
        return try {
        val file = File(jobDirectory(context), STATE_FILE)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        AiLyricsJobState(
            songId = json.optLong("songId", -1L).takeIf { it >= 0L },
            phase = AiJobPhase.valueOf(json.getString("phase")),
            progress = json.optInt("progress", 0),
            message = json.optString("message").takeIf { it.isNotBlank() },
            rawLrc = json.optString("rawLrc").takeIf { it.isNotBlank() },
            resultSource = json.optString("resultSource").takeIf { it.isNotBlank() }
                ?.let(AiLyricsResultSource::valueOf),
            resumed = json.optBoolean("resumed", false)
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPersistedJob(context: Context, deleteWork: Boolean) {
        synchronized(PERSISTENCE_LOCK) {
            File(jobDirectory(context), REQUEST_FILE).delete()
            File(jobDirectory(context), STATE_FILE).delete()
            if (deleteWork) jobWorkDirectory(context).deleteRecursively()
        }
    }

    private fun persistDecodedCheckpoint(
        workDir: File,
        decoded: LocalAudioDecoder.DecodedAudio
    ) {
        val json = JSONObject().apply {
            put("durationMs", decoded.durationMs)
            put("sampleCount", decoded.sampleCount)
            put("fileLength", decoded.wavFile.length())
        }
        writeAtomically(File(workDir, DECODED_AUDIO_STATE_FILE), json.toString())
    }

    private fun readDecodedCheckpoint(workDir: File): LocalAudioDecoder.DecodedAudio? {
        return try {
        val wav = File(workDir, DECODED_AUDIO_FILE)
        val stateFile = File(workDir, DECODED_AUDIO_STATE_FILE)
        if (!wav.isFile || !stateFile.isFile) return null
        val json = JSONObject(stateFile.readText(Charsets.UTF_8))
        val sampleCount = json.getLong("sampleCount")
        val expectedLength = json.getLong("fileLength")
        if (sampleCount <= 0L || wav.length() != expectedLength || expectedLength != 44L + sampleCount * 2L) {
            return null
        }
        LocalAudioDecoder.DecodedAudio(
            wavFile = wav,
            durationMs = json.getLong("durationMs"),
            sampleCount = sampleCount
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun persistRecognitionCheckpoint(
        workDir: File,
        checkpoint: RecognitionCheckpoint
    ) {
        val json = JSONObject().apply {
            put("nextChunkIndex", checkpoint.nextChunkIndex)
            put("segments", JSONArray().apply {
                checkpoint.segments.forEach { segment ->
                    put(JSONObject().apply {
                        put("startMs", segment.startMs)
                        put("endMs", segment.endMs)
                        put("text", segment.text)
                    })
                }
            })
        }
        writeAtomically(File(workDir, RECOGNITION_STATE_FILE), json.toString())
    }

    private fun readRecognitionCheckpoint(workDir: File): RecognitionCheckpoint? {
        return try {
        val file = File(workDir, RECOGNITION_STATE_FILE)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val array = json.getJSONArray("segments")
        val segments = ArrayList<OnDeviceLyricsProcessor.Segment>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            segments += OnDeviceLyricsProcessor.Segment(
                startMs = item.getLong("startMs"),
                endMs = item.getLong("endMs"),
                text = item.getString("text")
            )
        }
        RecognitionCheckpoint(json.getInt("nextChunkIndex"), segments)
        } catch (_: Exception) {
            null
        }
    }

    private fun verifiedModelMarker(model: File): File = File(model.parentFile, "${model.name}.verified")

    private fun isPreviouslyVerified(model: File, spec: ModelSpec): Boolean {
        return try {
        val marker = verifiedModelMarker(model)
        if (!marker.isFile) return false
        val json = JSONObject(marker.readText(Charsets.UTF_8))
        json.optString("sha256").equals(spec.sha256, ignoreCase = true) &&
            json.optLong("bytes") == model.length() &&
            json.optLong("lastModified") == model.lastModified()
        } catch (_: Exception) {
            false
        }
    }

    private fun markModelVerified(model: File, spec: ModelSpec) {
        val json = JSONObject().apply {
            put("sha256", spec.sha256)
            put("bytes", model.length())
            put("lastModified", model.lastModified())
        }
        writeAtomically(verifiedModelMarker(model), json.toString())
    }

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun updateIfCurrent(token: Int, newState: AiLyricsJobState) {
        if (generation.get() == token) updateState(newState)
    }

    private fun updateState(newState: AiLyricsJobState) {
        state = newState
        applicationContext?.let { context ->
            try {
                persistState(context, newState)
            } catch (_: Exception) {
                // Runtime work can continue; the next checkpoint/state update will retry persistence.
            }
        }
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

    private val ONLINE_TIMING_SOURCES = setOf(
        LyricsSource.DOWNLOADED_CACHE,
        LyricsSource.LOCAL_SIDECAR,
        LyricsSource.ONLINE_AUTO
    )

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
    private const val JOB_DIRECTORY = "on-device-ai-job"
    private const val JOB_WORK_DIRECTORY = "work"
    private const val REQUEST_FILE = "request.json"
    private const val STATE_FILE = "state.json"
    private const val INITIAL_SEARCH_MARKER = "metadata-search.done"
    private const val DECODED_AUDIO_FILE = "decoded-16k-mono.wav"
    private const val DECODED_AUDIO_STATE_FILE = "decoded-audio.json"
    private const val RECOGNITION_STATE_FILE = "recognized-chunks.json"
    private val PERSISTENCE_LOCK = Any()
    private const val SMALL_MODEL_RAM_THRESHOLD_BYTES = 5_500L * 1024L * 1024L
    private const val SMALL_MODEL_MIN_MEMORY_CLASS_MB = 384
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 90_000
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    private const val MODEL_STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
    private const val AUDIO_STORAGE_RESERVE_BYTES = 24L * 1024L * 1024L
    private const val UNKNOWN_AUDIO_TEMP_BYTES = 160L * 1024L * 1024L
}
