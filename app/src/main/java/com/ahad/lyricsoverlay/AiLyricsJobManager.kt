package com.ahad.lyricsoverlay

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

enum class AiLyricsMode(val apiValue: String) {
    AUDIO_ONLY("transcribe"),
    ALIGN_KNOWN_LYRICS("align")
}

enum class AiJobPhase {
    IDLE,
    UPLOADING,
    QUEUED,
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
    val rawLrc: String? = null,
    val jobId: String? = null
) {
    val isRunning: Boolean
        get() = phase == AiJobPhase.UPLOADING ||
            phase == AiJobPhase.QUEUED ||
            phase == AiJobPhase.PROCESSING
}

/**
 * Application-scoped uploader and poller. It deliberately uses no API key: the endpoint is a
 * user-controlled Lyr backend, while expensive model credentials never belong in the APK.
 */
object AiLyricsJobManager {

    fun interface Listener {
        fun onAiLyricsJobChanged(state: AiLyricsJobState)
    }

    private class HttpFailure(message: String) : IOException(message)

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val generation = AtomicInteger(0)

    @Volatile
    private var state = AiLyricsJobState()

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var activeJobId: String? = null

    fun currentState(): AiLyricsJobState = state

    fun addListener(listener: Listener) {
        listeners += listener
        mainHandler.post { listener.onAiLyricsJobChanged(state) }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun start(
        context: Context,
        endpoint: String,
        song: Song,
        mode: AiLyricsMode,
        knownLyrics: String,
        prioritizeBengali: Boolean
    ): Boolean {
        if (state.isRunning) return false
        val safeEndpoint = normalizeEndpoint(endpoint) ?: return false
        if (mode == AiLyricsMode.ALIGN_KNOWN_LYRICS && knownLyrics.isBlank()) return false

        val token = generation.incrementAndGet()
        activeJobId = null
        updateState(
            AiLyricsJobState(
                songId = song.id,
                phase = AiJobPhase.UPLOADING,
                progress = 0
            )
        )
        val appContext = context.applicationContext
        executor.execute {
            runJob(
                context = appContext,
                endpoint = safeEndpoint,
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

    private fun runJob(
        context: Context,
        endpoint: String,
        song: Song,
        mode: AiLyricsMode,
        knownLyrics: String,
        language: String,
        token: Int
    ) {
        var serverJobId: String? = null
        try {
            val createResponse = upload(
                context = context,
                url = "$endpoint/v1/jobs",
                song = song,
                mode = mode,
                knownLyrics = knownLyrics,
                language = language,
                token = token
            )
            ensureCurrent(token)
            serverJobId = createResponse.optString("job_id").takeIf { it.isNotBlank() }
                ?: throw HttpFailure("The AI server did not return a job ID.")
            activeJobId = serverJobId
            updateIfCurrent(
                token,
                AiLyricsJobState(
                    songId = song.id,
                    phase = AiJobPhase.QUEUED,
                    progress = 60,
                    jobId = serverJobId
                )
            )

            while (true) {
                ensureCurrent(token)
                val response = requestJson("$endpoint/v1/jobs/$serverJobId", "GET", token)
                ensureCurrent(token)
                val status = response.optString("status").lowercase(Locale.US)
                val serverProgress = response.optInt("progress", 0).coerceIn(0, 100)
                val totalProgress = 60 + (serverProgress * 0.4f).roundToInt()
                val message = response.optString("message").takeIf { it.isNotBlank() }
                when (status) {
                    "queued" -> updateIfCurrent(
                        token,
                        state.copy(
                            phase = AiJobPhase.QUEUED,
                            progress = totalProgress,
                            message = message,
                            jobId = serverJobId
                        )
                    )

                    "processing" -> updateIfCurrent(
                        token,
                        state.copy(
                            phase = AiJobPhase.PROCESSING,
                            progress = totalProgress,
                            message = message,
                            jobId = serverJobId
                        )
                    )

                    "completed" -> {
                        val rawLrc = response.optJSONObject("result")
                            ?.optString("lrc")
                            ?.takeIf { it.isNotBlank() }
                            ?: throw HttpFailure("The AI server completed without synchronized lyrics.")
                        if (LrcParser.parse(rawLrc).isEmpty()) {
                            throw HttpFailure("The AI server returned invalid synchronized lyrics.")
                        }
                        updateIfCurrent(
                            token,
                            AiLyricsJobState(
                                songId = song.id,
                                phase = AiJobPhase.COMPLETED,
                                progress = 100,
                                message = message,
                                rawLrc = rawLrc,
                                jobId = serverJobId
                            )
                        )
                        return
                    }

                    "failed" -> throw HttpFailure(
                        response.optString("error").takeIf { it.isNotBlank() }
                            ?: message
                            ?: "AI processing failed."
                    )

                    "canceled", "cancelled" -> {
                        updateIfCurrent(
                            token,
                            state.copy(
                                phase = AiJobPhase.CANCELED,
                                progress = totalProgress,
                                message = null,
                                rawLrc = null,
                                jobId = serverJobId
                            )
                        )
                        return
                    }

                    else -> throw HttpFailure("The AI server returned an unknown job status.")
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: JobCanceledException) {
            // The visible canceled state is set immediately by cancel().
        } catch (error: Exception) {
            if (generation.get() == token) {
                updateState(
                    AiLyricsJobState(
                        songId = song.id,
                        phase = AiJobPhase.FAILED,
                        progress = state.progress,
                        message = error.userFacingMessage(),
                        jobId = serverJobId
                    )
                )
            }
        } finally {
            activeConnection = null
            if (generation.get() != token && serverJobId != null) {
                deleteServerJob(endpoint, serverJobId)
            }
            if (activeJobId == serverJobId) activeJobId = null
        }
    }

    private fun upload(
        context: Context,
        url: String,
        song: Song,
        mode: AiLyricsMode,
        knownLyrics: String,
        language: String,
        token: Int
    ): JSONObject {
        val fileInfo = queryFileInfo(context, song.contentUri, song.fileName)
        if (fileInfo.size > MAX_AUDIO_BYTES) {
            throw HttpFailure("This audio file is larger than the 250 MB upload limit.")
        }
        val boundary = "Lyr-${UUID.randomUUID()}"
        val connection = openConnection(url, "POST", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(STREAM_BUFFER_SIZE)
            readTimeout = UPLOAD_READ_TIMEOUT_MS
        }
        activeConnection = connection

        try {
            BufferedOutputStream(connection.outputStream, STREAM_BUFFER_SIZE).use { output ->
                writeFormField(output, boundary, "mode", mode.apiValue)
                writeFormField(output, boundary, "language", language)
                if (mode == AiLyricsMode.ALIGN_KNOWN_LYRICS) {
                    writeFormField(output, boundary, "lyrics", knownLyrics)
                }
                output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
                output.write(
                    ("Content-Disposition: form-data; name=\"audio\"; filename=\"${fileInfo.name}\"\r\n")
                        .toByteArray(StandardCharsets.UTF_8)
                )
                output.write(
                    "Content-Type: ${fileInfo.mimeType}\r\n\r\n"
                        .toByteArray(StandardCharsets.UTF_8)
                )

                context.contentResolver.openInputStream(song.contentUri)?.use { rawInput ->
                    BufferedInputStream(rawInput, STREAM_BUFFER_SIZE).use { input ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        var uploaded = 0L
                        while (true) {
                            ensureCurrent(token)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            uploaded += count
                            val uploadProgress = if (fileInfo.size > 0L) {
                                ((uploaded * 58L) / fileInfo.size).toInt().coerceIn(0, 58)
                            } else {
                                state.progress.coerceAtMost(58)
                            }
                            if (uploadProgress != state.progress) {
                                updateIfCurrent(
                                    token,
                                    state.copy(
                                        phase = AiJobPhase.UPLOADING,
                                        progress = uploadProgress
                                    )
                                )
                            }
                        }
                    }
                } ?: throw HttpFailure("Android could not open the selected audio file.")

                output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            return readResponse(connection)
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun requestJson(url: String, method: String, token: Int): JSONObject {
        val connection = openConnection(url, method, token)
        activeConnection = connection
        return try {
            readResponse(connection)
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, method: String, token: Int): HttpURLConnection {
        ensureCurrent(token)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = REQUEST_READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Lyr/${BuildConfig.VERSION_NAME} Android")
        }
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            output.toString(StandardCharsets.UTF_8.name())
        }.orEmpty()
        if (statusCode !in 200..299) {
            val serverMessage = try {
                JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            throw HttpFailure(serverMessage ?: "AI server request failed (HTTP $statusCode).")
        }
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            throw HttpFailure("The AI server returned an unreadable response.")
        }
    }

    private fun deleteServerJob(endpoint: String, jobId: String) {
        try {
            (URL("$endpoint/v1/jobs/$jobId").openConnection() as HttpURLConnection).run {
                requestMethod = "DELETE"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = REQUEST_READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Lyr/${BuildConfig.VERSION_NAME} Android")
                responseCode
                disconnect()
            }
        } catch (_: Exception) {
            // The server also deletes every uploaded audio file in its own processing finally block.
        }
    }

    private fun writeFormField(
        output: BufferedOutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$name\"\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private data class AudioFileInfo(
        val name: String,
        val size: Long,
        val mimeType: String
    )

    private fun queryFileInfo(context: Context, uri: Uri, fallbackName: String): AudioFileInfo {
        var displayName = fallbackName
        var size = -1L
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
            cursor?.let { activeCursor ->
                if (activeCursor.moveToFirst()) {
                    val nameIndex = activeCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = activeCursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) {
                        displayName = activeCursor.getString(nameIndex) ?: displayName
                    }
                    if (sizeIndex >= 0 && !activeCursor.isNull(sizeIndex)) {
                        size = activeCursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            // The provider may expose only a stream; upload can still proceed without a known size.
        } finally {
            cursor?.close()
        }
        if (size < 0L) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    size = descriptor.length
                }
            } catch (_: Exception) {
                // Keep unknown size.
            }
        }
        val safeName = displayName
            .replace(Regex("[\\r\\n\\\"]"), "_")
            .take(180)
            .ifBlank { "audio" }
        return AudioFileInfo(
            name = safeName,
            size = size,
            mimeType = context.contentResolver.getType(uri)
                ?.takeIf { it.startsWith("audio/") }
                ?: "application/octet-stream"
        )
    }

    private fun normalizeEndpoint(value: String): String? = try {
        val url = URL(value.trim().trimEnd('/'))
        if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null) null
        else url.toString().trimEnd('/')
    } catch (_: Exception) {
        null
    }

    private fun ensureCurrent(token: Int) {
        if (generation.get() != token) throw JobCanceledException()
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

    private fun Exception.userFacingMessage(): String = when (this) {
        is HttpFailure -> message ?: "AI processing failed."
        is java.net.SocketTimeoutException -> "The AI server took too long to respond. Try again; free servers can be slow to wake."
        is java.net.UnknownHostException -> "The AI server address could not be reached."
        else -> message?.takeIf { it.isNotBlank() } ?: "AI processing failed."
    }

    private class JobCanceledException : RuntimeException()

    private const val POLL_INTERVAL_MS = 1_500L
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val REQUEST_READ_TIMEOUT_MS = 60_000
    private const val UPLOAD_READ_TIMEOUT_MS = 10 * 60_000
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private const val MAX_AUDIO_BYTES = 250L * 1024L * 1024L
}
