package com.ahad.lyricsoverlay

import android.app.Activity
import android.view.Gravity
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The music-player style options sheet shown for a song: play / pause-resume,
 * play next, share the audio file (with Telegram and WhatsApp shortcuts),
 * share lyrics, rename, details and delete.
 */
object SongOptionsMenu {

    /**
     * @param isCurrent    true when [song] is the track loaded in the player.
     * @param isPlaying    true when the player is actively playing.
     * @param lyricsText   plain lyrics for [song], or null when none are loaded.
     * @param onPlay       play this song immediately.
     * @param onTogglePlayback pause or resume the current song.
     * @param onPlayNext   queue the song right after the current one.
     * @param onRename     open the rename dialog.
     * @param onDelete     start the delete flow (already confirmed by the user).
     */
    @Suppress("RestrictedApi")
    fun show(
        activity: Activity,
        anchor: View,
        song: Song,
        isCurrent: Boolean,
        isPlaying: Boolean,
        lyricsText: String?,
        onPlay: () -> Unit,
        onTogglePlayback: () -> Unit,
        onPlayNext: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit
    ) {
        val popup = PopupMenu(activity, anchor, Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_song_options, popup.menu)
        val menu = popup.menu

        // Playback entries depend on whether this song is the live one.
        menu.findItem(R.id.action_play_now).isVisible = !isCurrent
        menu.findItem(R.id.action_play_next).isVisible = !isCurrent
        menu.findItem(R.id.action_toggle_playback).apply {
            isVisible = isCurrent
            setTitle(if (isPlaying) R.string.pause_playback else R.string.resume_playback)
            setIcon(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }

        // Messenger shortcuts only when the app is actually installed.
        val telegram = SongActions.telegramPackage(activity)
        val whatsApp = SongActions.whatsAppPackage(activity)
        menu.findItem(R.id.action_share_telegram).isVisible = telegram != null
        menu.findItem(R.id.action_share_whatsapp).isVisible = whatsApp != null
        menu.findItem(R.id.action_share_lyrics).isVisible = !lyricsText.isNullOrBlank()

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_play_now -> onPlay()
                R.id.action_toggle_playback -> onTogglePlayback()
                R.id.action_play_next -> onPlayNext()
                R.id.action_share_file -> SongActions.shareSongFile(activity, song)
                R.id.action_share_telegram ->
                    telegram?.let { SongActions.shareSongFileTo(activity, song, it) }
                R.id.action_share_whatsapp ->
                    whatsApp?.let { SongActions.shareSongFileTo(activity, song, it) }
                R.id.action_share_lyrics -> {
                    val lyrics = lyricsText
                    if (lyrics.isNullOrBlank()) {
                        android.widget.Toast
                            .makeText(activity, R.string.no_lyrics_to_share, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        SongActions.shareLyricsText(activity, song, lyrics)
                    }
                }
                R.id.action_rename -> onRename()
                R.id.action_song_info -> showSongInfo(activity, song)
                R.id.action_delete -> confirmDelete(activity, song, onDelete)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        // Show the icons next to each entry, like a normal music player menu.
        (menu as? MenuBuilder)?.let { builder ->
            builder.setOptionalIconsVisible(true)
            MenuPopupHelper(activity, builder, anchor).apply {
                setForceShowIcon(true)
                gravity = Gravity.END
                show()
            }
        } ?: popup.show()
    }

    fun confirmDelete(activity: Activity, song: Song, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_song_title)
            .setMessage(activity.getString(R.string.delete_song_message, song.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> onConfirmed() }
            .show()
    }

    private fun showSongInfo(activity: Activity, song: Song) {
        val duration = formatDuration(song.durationMs)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.song_info)
            .setMessage(
                activity.getString(
                    R.string.song_info_body,
                    song.title,
                    song.artist,
                    song.album,
                    song.fileName,
                    duration
                )
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}
