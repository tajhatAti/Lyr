package com.ahad.lyricsoverlay

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/** Identifies where the currently displayed synchronized lyrics came from. */
enum class LyricsSource {
    USER_EDITED,
    IMPORTED_FILE,
    ONLINE_SELECTED,
    DOWNLOADED_CACHE,
    LOCAL_SIDECAR,
    ONLINE_AUTO
}

data class LyricsResult(
    val rawLrc: String,
    val source: LyricsSource,
    val providerName: String? = null,
    val providerRecordId: Long? = null
)

data class OnlineLyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Double,
    val syncedLyrics: String,
    val plainLyrics: String
)

enum class LyricsNetworkError {
    NO_CONNECTION,
    RATE_LIMITED,
    SERVER_ERROR,
    INVALID_RESPONSE
}

data class LyricsSearchResponse(
    val results: List<OnlineLyricsCandidate>,
    val error: LyricsNetworkError? = null,
    val retryAfterSeconds: Int? = null
)

data class LyricsPublishResult(
    val successful: Boolean,
    val message: String? = null
)

/**
 * Resolves lyrics from authoritative user files first, then offline cache/local sidecars, and
 * finally LRCLIB. All public methods in this class perform disk or network work and must run on a
 * worker thread.
 */
class LyricsRepository(private val context: Context) {

    private val downloadedDirectory = File(context.filesDir, "lyrics_cache").apply { mkdirs() }
    private val userDirectory = File(context.filesDir, "lyrics_user").apply { mkdirs() }

    /** Normal playback lookup. User choices are never silently replaced by an online result. */
    fun findLyrics(song: Song): LyricsResult? {
        readUserLyrics(song)?.let { return it }
        readDownloadedLyrics(song)?.let { return it }
        findLocalSidecar(song)?.let {
            return LyricsResult(it, LyricsSource.LOCAL_SIDECAR)
        }
        return refreshFromOnline(song)
    }

    /** Ignores existing files, performs a fresh LRCLIB lookup, and updates the offline cache. */
    fun refreshFromOnline(song: Song): LyricsResult? {
        val candidate = fetchBestOnline(song) ?: return null
        writeDownloadedLyrics(song, candidate.syncedLyrics)
        return LyricsResult(
            rawLrc = candidate.syncedLyrics,
            source = LyricsSource.ONLINE_AUTO,
            providerName = PROVIDER_NAME,
            providerRecordId = candidate.id
        )
    }

    /** Searches LRCLIB for a user-visible result picker. Only valid synchronized results remain. */
    fun searchOnline(song: Song, query: String): LyricsSearchResponse {
        val trimmedQuery = query.trim()
        val queryString = if (trimmedQuery.isNotEmpty()) {
            "q=${encode(trimmedQuery)}"
        } else {
            val artist = knownArtist(song)
            if (artist != null) {
                "track_name=${encode(song.sourceTitle)}&artist_name=${encode(artist)}"
            } else {
                "q=${encode(song.sourceTitle)}"
            }
        }

        val response = executeRequest("https://lrclib.net/api/search?$queryString")
        if (!response.successful) return response.toSearchResponse()

        return try {
            val json = JSONArray(response.body)
            val uniqueResults = LinkedHashMap<Long, OnlineLyricsCandidate>()
            for (index in 0 until json.length()) {
                json.optJSONObject(index)?.toCandidate()?.let { candidate ->
                    uniqueResults[candidate.id] = candidate
                }
            }
            LyricsSearchResponse(
                uniqueResults.values
                    .sortedByDescending { candidateScore(it, song, cleanSearchText(song.sourceTitle), knownArtist(song)) }
                    .take(MAX_VISIBLE_RESULTS)
            )
        } catch (_: Exception) {
            LyricsSearchResponse(emptyList(), LyricsNetworkError.INVALID_RESPONSE)
        }
    }

    /** Saves an explicit online choice above the automatic cache, so the chosen version persists. */
    fun saveOnlineSelection(song: Song, candidate: OnlineLyricsCandidate): Boolean =
        saveUserLyrics(song, candidate.syncedLyrics, LyricsSource.ONLINE_SELECTED)

    fun saveUserLyrics(song: Song, rawLrc: String, source: LyricsSource): Boolean {
        if (source !in USER_SOURCES || LrcParser.parse(rawLrc).isEmpty()) return false
        return try {
            userDirectory.mkdirs()
            userFile(song).writeText(rawLrc.trim(), Charsets.UTF_8)
            userSourceFile(song).writeText(source.name, Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteUserLyrics(song: Song): Boolean = try {
        val lyricsDeleted = !userFile(song).exists() || userFile(song).delete()
        val sourceDeleted = !userSourceFile(song).exists() || userSourceFile(song).delete()
        lyricsDeleted && sourceDeleted
    } catch (_: Exception) {
        false
    }

    /**
     * Publishes lyrics to LRCLIB only after the UI obtains informed confirmation. LRCLIB requires a
     * fresh proof-of-work challenge. The nonce callback reports attempts and may run for minutes.
     */
    fun publishToLrclib(
        song: Song,
        rawLrc: String,
        onNonceProgress: (Long) -> Unit
    ): LyricsPublishResult {
        if (LrcParser.parse(rawLrc).isEmpty()) {
            return LyricsPublishResult(false, "No valid timed LRC lines were found.")
        }
        val artist = knownArtist(song)
            ?: return LyricsPublishResult(false, "Artist metadata is required for community publishing.")

        val challengeResponse = executeRequest(
            url = "https://lrclib.net/api/request-challenge",
            method = "POST",
            requestBody = ""
        )
        if (!challengeResponse.successful) {
            return LyricsPublishResult(false, challengeResponse.failureMessage())
        }

        val prefix: String
        val target: ByteArray
        try {
            val challenge = JSONObject(challengeResponse.body)
            prefix = challenge.getString("prefix")
            target = challenge.getString("target").hexToBytes()
            if (target.size != 32) throw IllegalArgumentException("Invalid challenge target")
        } catch (_: Exception) {
            return LyricsPublishResult(false, "LRCLIB returned an invalid publish challenge.")
        }

        val nonce = solveChallenge(prefix, target, onNonceProgress)
        if (nonce < 0L) return LyricsPublishResult(false, "Public upload was cancelled.")
        val publishBody = JSONObject().apply {
            put("trackName", song.sourceTitle)
            put("artistName", artist)
            put("albumName", song.album.takeUnless(::isUnknownAlbum).orEmpty())
            put("duration", song.durationMs.coerceAtLeast(0L) / 1_000.0)
            put("plainLyrics", LrcParser.toPlainLyrics(rawLrc))
            put("syncedLyrics", rawLrc.trim())
        }.toString()

        val publishResponse = executeRequest(
            url = "https://lrclib.net/api/publish",
            method = "POST",
            requestBody = publishBody,
            headers = mapOf("X-Publish-Token" to "$prefix:$nonce")
        )
        return if (publishResponse.statusCode in 200..299) {
            LyricsPublishResult(true)
        } else {
            LyricsPublishResult(false, publishResponse.failureMessage())
        }
    }

    private fun fetchBestOnline(song: Song): OnlineLyricsCandidate? {
        val artist = knownArtist(song)
        val album = song.album.takeUnless(::isUnknownAlbum)
        if (artist != null) {
            val parameters = buildList {
                add("track_name=${encode(song.sourceTitle)}")
                add("artist_name=${encode(artist)}")
                if (!album.isNullOrBlank()) add("album_name=${encode(album)}")
                if (song.durationMs > 0L) add("duration=${song.durationMs / 1_000}")
            }.joinToString("&")
            val exactResponse = executeRequest("https://lrclib.net/api/get?$parameters")
            if (exactResponse.successful) {
                try {
                    JSONObject(exactResponse.body).toCandidate()?.let { return it }
                } catch (_: Exception) {
                    // Fall through to ranked search.
                }
            } else if (exactResponse.error == LyricsNetworkError.NO_CONNECTION ||
                exactResponse.error == LyricsNetworkError.RATE_LIMITED
            ) {
                return null
            }
        }

        val searchQueries = linkedSetOf<String>()
        val cleanedTitle = cleanSearchText(song.sourceTitle)
        if (artist != null) {
            searchQueries += "track_name=${encode(cleanedTitle)}&artist_name=${encode(artist)}"
            searchQueries += "q=${encode("$cleanedTitle $artist")}"
        }
        searchQueries += "q=${encode(cleanedTitle)}"

        searchQueries.take(MAX_AUTO_SEARCH_ATTEMPTS).forEach { query ->
            val response = executeRequest("https://lrclib.net/api/search?$query")
            if (!response.successful) return null
            try {
                val results = JSONArray(response.body)
                var best: OnlineLyricsCandidate? = null
                var bestScore = MINIMUM_AUTOMATIC_SCORE
                for (index in 0 until results.length()) {
                    val candidate = results.optJSONObject(index)?.toCandidate() ?: continue
                    val score = candidateScore(candidate, song, cleanedTitle, artist)
                    if (score > bestScore) {
                        best = candidate
                        bestScore = score
                    }
                }
                if (best != null) return best
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun JSONObject.toCandidate(): OnlineLyricsCandidate? {
        val synced = optString("syncedLyrics")
            .takeUnless { it.isBlank() || it == "null" }
            ?: return null
        if (LrcParser.parse(synced).isEmpty()) return null
        return OnlineLyricsCandidate(
            id = optLong("id", -1L),
            trackName = optString("trackName").trim(),
            artistName = optString("artistName").trim(),
            albumName = optString("albumName").takeUnless { it == "null" }.orEmpty().trim(),
            durationSeconds = optDouble("duration", 0.0),
            syncedLyrics = synced,
            plainLyrics = optString("plainLyrics").takeUnless { it == "null" }.orEmpty()
        )
    }

    private fun candidateScore(
        candidate: OnlineLyricsCandidate,
        song: Song,
        cleanedTitle: String,
        knownArtist: String?
    ): Long {
        val wantedTitle = normalizeForMatch(cleanedTitle)
        val wantedArtist = normalizeForMatch(knownArtist.orEmpty())
        val resultTitle = normalizeForMatch(candidate.trackName)
        val resultArtist = normalizeForMatch(candidate.artistName)
        val resultDurationMs = (candidate.durationSeconds * 1_000).toLong()
        val durationDifference = if (song.durationMs > 0L && resultDurationMs > 0L) {
            abs(song.durationMs - resultDurationMs)
        } else {
            0L
        }

        val titleScore = when {
            resultTitle == wantedTitle -> 8_000L
            resultTitle.contains(wantedTitle) || wantedTitle.contains(resultTitle) -> 3_500L
            else -> -8_000L
        }
        var score = titleScore - (durationDifference / 100L)
        if (wantedArtist.isNotBlank()) {
            score += when {
                resultArtist == wantedArtist -> 3_000L
                resultArtist.contains(wantedArtist) || wantedArtist.contains(resultArtist) -> 1_200L
                else -> -1_500L
            }
        }
        return score
    }

    private fun cleanSearchText(value: String): String = value
        .replace(Regex("""^\s*\d{1,3}\s*[.\-_)]+\s*"""), "")
        .replace(
            Regex(
                """\s*(?:\(|\[)(official|lyrics?|audio|video|remaster(ed)?|visualizer).*?(?:\)|\])\s*""",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        .replace(Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { value.trim() }

    private fun normalizeForMatch(value: String): String = value
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun readUserLyrics(song: Song): LyricsResult? {
        val text = readValidLrc(userFile(song)) ?: return null
        val source = try {
            LyricsSource.valueOf(userSourceFile(song).readText(Charsets.UTF_8).trim())
                .takeIf { it in USER_SOURCES }
        } catch (_: Exception) {
            null
        } ?: LyricsSource.USER_EDITED
        return LyricsResult(text, source, PROVIDER_NAME.takeIf { source == LyricsSource.ONLINE_SELECTED })
    }

    private fun readDownloadedLyrics(song: Song): LyricsResult? =
        readValidLrc(downloadedFile(song))?.let {
            LyricsResult(it, LyricsSource.DOWNLOADED_CACHE, PROVIDER_NAME)
        }

    private fun readValidLrc(file: File): String? = try {
        if (!file.isFile) return null
        file.readText(Charsets.UTF_8).takeIf { LrcParser.parse(it).isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun writeDownloadedLyrics(song: Song, lyrics: String) {
        try {
            downloadedDirectory.mkdirs()
            downloadedFile(song).writeText(lyrics.trim(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Playback remains usable even when private storage is unexpectedly unavailable.
        }
    }

    private fun downloadedFile(song: Song): File = File(downloadedDirectory, "${songKey(song)}.lrc")
    private fun userFile(song: Song): File = File(userDirectory, "${songKey(song)}.lrc")
    private fun userSourceFile(song: Song): File = File(userDirectory, "${songKey(song)}.source")

    private fun songKey(song: Song): String {
        val identity = "${song.sourceTitle.lowercase()}|${song.artist.lowercase()}|${song.durationMs}"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun findLocalSidecar(song: Song): String? {
        findLegacySidecar(song)?.let { return it }
        return findMediaStoreSidecar(song)
    }

    private fun findLegacySidecar(song: Song): String? {
        val audioFile = song.legacyDataPath?.let(::File) ?: return null
        val baseName = audioFile.nameWithoutExtension
        val parentDirectory = audioFile.parentFile ?: return null
        for (candidate in listOf(File(parentDirectory, "$baseName.lrc"), File(parentDirectory, "$baseName.LRC"))) {
            readValidLrc(candidate)?.let { return it }
        }
        return null
    }

    private fun findMediaStoreSidecar(song: Song): String? {
        val baseName = song.fileName.substringBeforeLast('.', song.fileName)
        if (baseName.isBlank()) return null
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        for (displayName in listOf("$baseName.lrc", "$baseName.LRC")) {
            val selectionParts = mutableListOf("${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?")
            val selectionArgs = mutableListOf(displayName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !song.relativePath.isNullOrBlank()) {
                selectionParts += "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
                selectionArgs += song.relativePath
            }
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Files.FileColumns._ID),
                    selectionParts.joinToString(" AND "),
                    selectionArgs.toTypedArray(),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                        if (!text.isNullOrBlank() && LrcParser.parse(text).isNotEmpty()) return text
                    }
                }
            } catch (_: Exception) {
                // Scoped storage may hide sidecar files; import remains available in Lyrics Center.
            }
        }
        return null
    }

    private fun solveChallenge(prefix: String, target: ByteArray, progress: (Long) -> Unit): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) return -1L
            val result = digest.digest("$prefix$nonce".toByteArray(Charsets.UTF_8))
            if (result.isLessThanOrEqualTo(target)) return nonce
            nonce++
            if (nonce % PUBLISH_PROGRESS_INTERVAL == 0L) progress(nonce)
        }
    }

    private fun ByteArray.isLessThanOrEqualTo(other: ByteArray): Boolean {
        if (size != other.size) return false
        for (index in indices) {
            val left = this[index].toInt() and 0xff
            val right = other[index].toInt() and 0xff
            if (left < right) return true
            if (left > right) return false
        }
        return true
    }

    private fun String.hexToBytes(): ByteArray {
        if (length % 2 != 0) throw IllegalArgumentException("Invalid hex")
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun executeRequest(
        url: String,
        method: String = "GET",
        requestBody: String? = null,
        headers: Map<String, String> = emptyMap()
    ): NetworkResponse = synchronized(NETWORK_LOCK) {
        val now = System.currentTimeMillis()
        if (nextRequestAtMs > now) {
            try {
                Thread.sleep(nextRequestAtMs - now)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized NetworkResponse(error = LyricsNetworkError.NO_CONNECTION)
            }
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (requestBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
                }
            }
            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")
                ?.trim()
                ?.toIntOrNull()
                ?.coerceIn(1, MAX_RETRY_AFTER_SECONDS)
            nextRequestAtMs = System.currentTimeMillis() + if (status == 429) {
                (retryAfter ?: DEFAULT_RATE_LIMIT_SECONDS) * 1_000L
            } else {
                REQUEST_SPACING_MS
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            NetworkResponse(
                statusCode = status,
                body = body,
                error = when {
                    status in 200..299 -> null
                    status == 404 -> LyricsNetworkError.INVALID_RESPONSE
                    status == 429 -> LyricsNetworkError.RATE_LIMITED
                    status >= 500 -> LyricsNetworkError.SERVER_ERROR
                    else -> LyricsNetworkError.INVALID_RESPONSE
                },
                retryAfterSeconds = retryAfter
            )
        } catch (_: Exception) {
            nextRequestAtMs = System.currentTimeMillis() + REQUEST_SPACING_MS
            NetworkResponse(error = LyricsNetworkError.NO_CONNECTION)
        } finally {
            connection?.disconnect()
        }
    }

    private fun knownArtist(song: Song): String? = song.artist
        .takeUnless { it.equals(context.getString(R.string.unknown_artist), ignoreCase = true) }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun isUnknownAlbum(value: String): Boolean = value.isBlank() ||
        value.equals(context.getString(R.string.unknown_album), ignoreCase = true)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class NetworkResponse(
        val statusCode: Int = -1,
        val body: String = "",
        val error: LyricsNetworkError? = null,
        val retryAfterSeconds: Int? = null
    ) {
        val successful: Boolean get() = statusCode in 200..299 && error == null

        fun toSearchResponse() = LyricsSearchResponse(emptyList(), error, retryAfterSeconds)

        fun failureMessage(): String = when (error) {
            LyricsNetworkError.NO_CONNECTION -> "Could not connect to LRCLIB."
            LyricsNetworkError.RATE_LIMITED -> retryAfterSeconds?.let {
                "LRCLIB is busy. Try again in $it seconds."
            } ?: "LRCLIB is busy. Please try again shortly."
            LyricsNetworkError.SERVER_ERROR -> "LRCLIB is temporarily unavailable."
            LyricsNetworkError.INVALID_RESPONSE, null -> {
                try {
                    JSONObject(body).optString("message").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                } ?: "LRCLIB rejected the request."
            }
        }
    }

    companion object {
        private const val PROVIDER_NAME = "LRCLIB"
        private const val USER_AGENT =
            "LyrMusic/2.0 (com.ahad.lyricsoverlay; https://github.com/tajhatAti/Lyr)"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val REQUEST_SPACING_MS = 350L
        private const val DEFAULT_RATE_LIMIT_SECONDS = 10
        private const val MAX_RETRY_AFTER_SECONDS = 300
        private const val MAX_AUTO_SEARCH_ATTEMPTS = 2
        private const val MAX_VISIBLE_RESULTS = 20
        private const val MINIMUM_AUTOMATIC_SCORE = 1_500L
        private const val PUBLISH_PROGRESS_INTERVAL = 250_000L
        private val USER_SOURCES = setOf(
            LyricsSource.USER_EDITED,
            LyricsSource.IMPORTED_FILE,
            LyricsSource.ONLINE_SELECTED
        )
        private val NETWORK_LOCK = Any()
        @Volatile
        private var nextRequestAtMs = 0L
    }
}
