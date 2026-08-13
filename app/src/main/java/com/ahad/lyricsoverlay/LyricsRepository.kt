package com.ahad.lyricsoverlay

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

class LyricsRepository(private val context: Context) {

    private val cacheDirectory = File(context.filesDir, "lyrics_cache").apply { mkdirs() }

    /** This method performs disk and network I/O and must be called from a worker thread. */
    fun findLyrics(song: Song): String? {
        readCache(song)?.let { return it }

        fetchOnline(song)?.let { onlineLyrics ->
            writeCache(song, onlineLyrics)
            return onlineLyrics
        }

        return findLocalSidecar(song)
    }

    private fun fetchOnline(song: Song): String? {
        val knownArtist = song.artist.takeUnless {
            it.equals(context.getString(R.string.unknown_artist), ignoreCase = true)
        }
        val query = if (knownArtist.isNullOrBlank()) {
            "q=${encode(song.title)}"
        } else {
            "track_name=${encode(song.title)}&artist_name=${encode(knownArtist)}"
        }
        val url = URL("https://lrclib.net/api/search?$query")
        var connection: HttpURLConnection? = null

        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty(
                    "User-Agent",
                    "LyrMusic/2.0 (com.ahad.lyricsoverlay; Android)"
                )
                setRequestProperty("Accept", "application/json")
            }

            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val results = JSONArray(body)
            chooseBestSyncedLyrics(results, song.durationMs)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun chooseBestSyncedLyrics(results: JSONArray, songDurationMs: Long): String? {
        var bestLyrics: String? = null
        var bestDifference = Long.MAX_VALUE

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val syncedLyrics = item.optString("syncedLyrics")
                .takeUnless { it.isBlank() || it == "null" }
                ?: continue
            if (LrcParser.parse(syncedLyrics).isEmpty()) continue

            val resultDurationMs = (item.optDouble("duration", 0.0) * 1_000).toLong()
            val difference = if (songDurationMs > 0 && resultDurationMs > 0) {
                abs(songDurationMs - resultDurationMs)
            } else {
                0L
            }
            if (difference < bestDifference) {
                bestDifference = difference
                bestLyrics = syncedLyrics
            }
        }
        return bestLyrics
    }

    private fun readCache(song: Song): String? {
        val cacheFile = cacheFile(song)
        return try {
            if (!cacheFile.isFile) return null
            cacheFile.readText(Charsets.UTF_8).takeIf { LrcParser.parse(it).isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(song: Song, lyrics: String) {
        try {
            cacheDirectory.mkdirs()
            cacheFile(song).writeText(lyrics, Charsets.UTF_8)
        } catch (_: Exception) {
            // A cache failure should never interrupt playback.
        }
    }

    private fun cacheFile(song: Song): File {
        val identity = "${song.title.lowercase()}|${song.artist.lowercase()}|${song.durationMs}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(cacheDirectory, "$digest.lrc")
    }

    private fun findLocalSidecar(song: Song): String? {
        findLegacySidecar(song)?.let { return it }
        return findMediaStoreSidecar(song)
    }

    private fun findLegacySidecar(song: Song): String? {
        val audioFile = song.legacyDataPath?.let(::File) ?: return null
        val baseName = audioFile.nameWithoutExtension
        val parentDirectory = audioFile.parentFile ?: return null
        val candidates = listOf(
            File(parentDirectory, "$baseName.lrc"),
            File(parentDirectory, "$baseName.LRC")
        )
        for (candidate in candidates) {
            try {
                if (candidate.isFile) {
                    val text = candidate.readText(Charsets.UTF_8)
                    if (LrcParser.parse(text).isNotEmpty()) return text
                }
            } catch (_: Exception) {
                // Continue to the MediaStore fallback.
            }
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
                        val id = cursor.getLong(0)
                        val uri = ContentUris.withAppendedId(collection, id)
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                        if (!text.isNullOrBlank() && LrcParser.parse(text).isNotEmpty()) {
                            return text
                        }
                    }
                }
            } catch (_: Exception) {
                // Scoped storage may hide non-media sidecar files. Failing silently is intentional.
            }
        }
        return null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
