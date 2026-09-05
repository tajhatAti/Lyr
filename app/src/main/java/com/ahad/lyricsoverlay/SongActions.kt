package com.ahad.lyricsoverlay

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast

/**
 * Central place for the music-player actions that act on a single song:
 * sharing the actual audio file, sharing its lyrics as text, and deleting it
 * from the device. Every entry point (library long-press and the Now Playing
 * overflow menu) funnels through here so the behaviour stays identical.
 */
object SongActions {

    /** Popular messengers we surface as one-tap share targets when installed. */
    private val DIRECT_TARGETS = listOf(
        DirectTarget("org.telegram.messenger", R.string.share_via_telegram),
        DirectTarget("org.telegram.plus", R.string.share_via_telegram),
        DirectTarget("org.thunderdog.challegram", R.string.share_via_telegram),
        DirectTarget("com.whatsapp", R.string.share_via_whatsapp),
        DirectTarget("com.whatsapp.w4b", R.string.share_via_whatsapp)
    )

    private data class DirectTarget(val packageName: String, val labelRes: Int)

    /**
     * Shares the real audio file. MediaStore content URIs are directly
     * shareable, so Telegram, WhatsApp, Gmail and Drive all receive the song
     * itself instead of a link.
     */
    fun shareSongFile(context: Context, song: Song) {
        val shareIntent = buildAudioShareIntent(context, song, targetPackage = null)
        val chooser = Intent.createChooser(
            shareIntent,
            context.getString(R.string.share_song_via)
        ).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val shortcuts = installedDirectShareIntents(context, song)
            if (shortcuts.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, shortcuts.toTypedArray())
            }
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_to_share, Toast.LENGTH_SHORT).show()
        }
    }

    /** Sends the audio file straight to one app (Telegram / WhatsApp shortcut). */
    fun shareSongFileTo(context: Context, song: Song, packageName: String) {
        val intent = buildAudioShareIntent(context, song, packageName)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            shareSongFile(context, song)
        }
    }

    /** Shares the lyrics of a song as plain text. */
    fun shareLyricsText(context: Context, song: Song, lyrics: String) {
        val body = buildString {
            append("🎵 ")
            append(song.title)
            append(" — ")
            append(song.artist)
            append("\n\n")
            append(lyrics.trim())
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${song.title} — ${song.artist}")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.share_lyrics))
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_to_share, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAudioShareIntent(
        context: Context,
        song: Song,
        targetPackage: String?
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        type = audioMimeType(song)
        putExtra(Intent.EXTRA_STREAM, song.contentUri)
        putExtra(Intent.EXTRA_SUBJECT, song.title)
        putExtra(Intent.EXTRA_TEXT, "🎵 ${song.title} — ${song.artist}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newUri(
            context.contentResolver,
            song.title,
            song.contentUri
        )
        if (targetPackage != null) setPackage(targetPackage)
    }

    private fun installedDirectShareIntents(context: Context, song: Song): List<Intent> {
        val seenLabels = mutableSetOf<Int>()
        return DIRECT_TARGETS.mapNotNull { target ->
            if (!isInstalled(context, target.packageName)) return@mapNotNull null
            if (!seenLabels.add(target.labelRes)) return@mapNotNull null
            buildAudioShareIntent(context, song, target.packageName).apply {
                putExtra(Intent.EXTRA_TITLE, context.getString(target.labelRes))
            }
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** True when at least one Telegram-like client is installed. */
    fun telegramInstalled(context: Context): Boolean =
        DIRECT_TARGETS.any { it.labelRes == R.string.share_via_telegram && isInstalled(context, it.packageName) }

    /** True when at least one WhatsApp client is installed. */
    fun whatsAppInstalled(context: Context): Boolean =
        DIRECT_TARGETS.any { it.labelRes == R.string.share_via_whatsapp && isInstalled(context, it.packageName) }

    fun telegramPackage(context: Context): String? =
        DIRECT_TARGETS.firstOrNull {
            it.labelRes == R.string.share_via_telegram && isInstalled(context, it.packageName)
        }?.packageName

    fun whatsAppPackage(context: Context): String? =
        DIRECT_TARGETS.firstOrNull {
            it.labelRes == R.string.share_via_whatsapp && isInstalled(context, it.packageName)
        }?.packageName

    private fun audioMimeType(song: Song): String = when {
        song.fileName.endsWith(".mp3", true) -> "audio/mpeg"
        song.fileName.endsWith(".m4a", true) -> "audio/mp4"
        song.fileName.endsWith(".aac", true) -> "audio/aac"
        song.fileName.endsWith(".wav", true) -> "audio/x-wav"
        song.fileName.endsWith(".flac", true) -> "audio/flac"
        song.fileName.endsWith(".ogg", true) || song.fileName.endsWith(".oga", true) -> "audio/ogg"
        else -> "audio/*"
    }

    /**
     * Result of a delete attempt. [PENDING_CONFIRMATION] means the OS is showing
     * its own confirmation dialog and the caller must wait for the activity result.
     */
    enum class DeleteResult { DELETED, PENDING_CONFIRMATION, FAILED }

    /**
     * Deletes the song from device storage. On Android 11+ this asks the system
     * to show the standard delete confirmation; on Android 10 it recovers from
     * [RecoverableSecurityException]; below that it deletes directly.
     */
    fun deleteSong(
        activity: Activity,
        song: Song,
        requestConfirmation: (android.content.IntentSender) -> Unit
    ): DeleteResult {
        val resolver = activity.contentResolver
        val uri: Uri = song.contentUri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val pending = MediaStore.createDeleteRequest(resolver, listOf(uri))
                requestConfirmation(pending.intentSender)
                DeleteResult.PENDING_CONFIRMATION
            } catch (_: Exception) {
                DeleteResult.FAILED
            }
        }

        return try {
            val deleted = resolver.delete(uri, null, null)
            if (deleted > 0) DeleteResult.DELETED else DeleteResult.FAILED
        } catch (security: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                security is RecoverableSecurityException
            ) {
                try {
                    requestConfirmation(security.userAction.actionIntent.intentSender)
                    DeleteResult.PENDING_CONFIRMATION
                } catch (_: Exception) {
                    DeleteResult.FAILED
                }
            } else {
                DeleteResult.FAILED
            }
        }
    }

    /** Confirms a song really disappeared from MediaStore after a delete request. */
    fun stillExists(context: Context, song: Song): Boolean {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(collection, song.id)
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
