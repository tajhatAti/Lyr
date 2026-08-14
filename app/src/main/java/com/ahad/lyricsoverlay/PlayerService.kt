package com.ahad.lyricsoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.random.Random

enum class LyricsLoadState { IDLE, SEARCHING, READY, NOT_FOUND }

class PlayerService : Service() {

    interface PlayerListener {
        fun onPlayerStateChanged(
            song: Song?,
            isPlaying: Boolean,
            isBuffering: Boolean,
            positionMs: Long,
            durationMs: Long
        )

        fun onLyricsLoadStateChanged(state: LyricsLoadState)

        fun onQueueChanged(queue: List<Song>, currentIndex: Int) = Unit

        fun onPlaybackModeChanged(
            shuffleEnabled: Boolean,
            repeatMode: PlayerRepeatMode
        ) = Unit
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lyricsExecutor = Executors.newSingleThreadExecutor()

    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var lyricsRepository: LyricsRepository

    private var mediaPlayer: MediaPlayer? = null
    private var queue: List<Song> = emptyList()
    private var currentIndex = -1
    private var playerPrepared = false
    private var playWhenPrepared = true
    private var playing = false
    private var buffering = false
    private var playbackGeneration = 0
    private var lyricsGeneration = 0
    private var consecutiveErrors = 0
    private val listeners = CopyOnWriteArraySet<PlayerListener>()
    private var shuffleEnabled = false
    private var repeatMode = PlayerRepeatMode.OFF
    private var resumeOnFocusGain = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private var overlayService: OverlayService? = null
    private var overlayBound = false
    private var resolvedLyrics: String? = null
    private var lyricsResolutionComplete = false
    private var lyricsLoadState = LyricsLoadState.IDLE

    private val overlayConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            overlayService = (service as? OverlayService.LocalBinder)?.getService()
            overlayBound = overlayService != null
            if (lyricsResolutionComplete) {
                resolvedLyrics?.let { overlayService?.setLyrics(it) } ?: overlayService?.clearLyrics()
            }
            overlayService?.updatePlayback(currentPosition(), playing)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayBound = false
            overlayService = null
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resumePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.25f, 0.25f)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = playing
                pausePlayback(abandonFocus = false)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pausePlayback(abandonFocus = true)
            }
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val position = currentPosition()
            overlayService?.updatePlayback(position, playing)
            updateMediaSessionState(position)
            notifyListener(position)
            mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lyricsRepository = LyricsRepository(applicationContext)
        shuffleEnabled = AppPreferences.playerShuffleEnabled()
        repeatMode = AppPreferences.playerRepeatMode()
        createNotificationChannel()
        createMediaSession()

        overlayBound = bindService(
            Intent(this, OverlayService::class.java),
            overlayConnection,
            Context.BIND_AUTO_CREATE
        )
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        mainHandler.post(progressRunnable)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> promoteToForeground()
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_PREVIOUS -> previous()
            ACTION_NEXT -> next()
            ACTION_STOP -> {
                stopPlaybackAndService()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        lyricsGeneration++
        lyricsExecutor.shutdownNow()
        releasePlayer()
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        overlayService?.clearLyrics()
        if (overlayBound) {
            try {
                unbindService(overlayConnection)
            } catch (_: IllegalArgumentException) {
                // Already unbound by the system.
            }
        }
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        super.onDestroy()
    }

    fun addListener(newListener: PlayerListener) {
        listeners += newListener
        newListener.onPlayerStateChanged(
            currentSong(),
            playing,
            buffering,
            currentPosition(),
            currentDuration()
        )
        newListener.onLyricsLoadStateChanged(lyricsLoadState)
        newListener.onQueueChanged(queue.toList(), currentIndex)
        newListener.onPlaybackModeChanged(shuffleEnabled, repeatMode)
    }

    fun removeListener(oldListener: PlayerListener) {
        listeners -= oldListener
    }

    fun queueSnapshot(): List<Song> = queue.toList()

    fun currentQueueIndex(): Int = currentIndex

    fun isShuffleEnabled(): Boolean = shuffleEnabled

    fun currentRepeatMode(): PlayerRepeatMode = repeatMode

    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        AppPreferences.setPlayerShuffleEnabled(shuffleEnabled)
        notifyPlaybackModeChanged()
        return shuffleEnabled
    }

    fun cycleRepeatMode(): PlayerRepeatMode {
        repeatMode = when (repeatMode) {
            PlayerRepeatMode.OFF -> PlayerRepeatMode.ALL
            PlayerRepeatMode.ALL -> PlayerRepeatMode.ONE
            PlayerRepeatMode.ONE -> PlayerRepeatMode.OFF
        }
        AppPreferences.setPlayerRepeatMode(repeatMode)
        notifyPlaybackModeChanged()
        return repeatMode
    }

    fun playQueueIndex(index: Int) {
        if (index in queue.indices) playAt(index)
    }

    fun playSongs(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        queue = songs.toList()
        consecutiveErrors = 0
        playAt(startIndex)
    }

    fun updateSongTitle(songId: Long, title: String) {
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty() || queue.none { it.id == songId }) return
        queue = queue.map { song ->
            if (song.id == songId) song.copy(title = cleanedTitle) else song
        }
        publishQueue()
        val current = currentSong()
        if (current?.id == songId) {
            updateMediaMetadata(current)
            publishState()
        }
    }

    fun synchronizeSongMetadata(scannedSongs: List<Song>) {
        if (queue.isEmpty() || scannedSongs.isEmpty()) return
        val updatedById = scannedSongs.associateBy(Song::id)
        val previousCurrent = currentSong()
        queue = queue.map { queuedSong -> updatedById[queuedSong.id] ?: queuedSong }
        publishQueue()
        val updatedCurrent = currentSong()
        if (updatedCurrent != null && updatedCurrent != previousCurrent) {
            updateMediaMetadata(updatedCurrent)
            if (updatedCurrent.sourceTitle != previousCurrent?.sourceTitle ||
                updatedCurrent.artist != previousCurrent?.artist
            ) {
                resolvedLyrics = null
                lyricsResolutionComplete = false
                overlayService?.clearLyrics()
                resolveLyrics(updatedCurrent)
            }
            publishState()
        }
    }

    fun refreshOverlayNow() {
        if (!lyricsResolutionComplete) return
        resolvedLyrics?.let { overlayService?.setLyrics(it) }
        overlayService?.updatePlayback(currentPosition(), playing)
    }

    fun retryLyrics() {
        val song = currentSong() ?: return
        resolvedLyrics = null
        lyricsResolutionComplete = false
        overlayService?.clearLyrics()
        resolveLyrics(song)
    }

    fun togglePlayPause() {
        if (playing) pausePlayback() else resumePlayback()
    }

    fun resumePlayback() {
        playWhenPrepared = true
        val player = mediaPlayer
        if (player == null) {
            if (queue.isNotEmpty()) playAt(currentIndex.coerceIn(0, queue.lastIndex))
            return
        }
        if (!playerPrepared) {
            buffering = true
            publishState()
            return
        }
        if (!requestAudioFocus()) return

        try {
            player.start()
            playing = true
            buffering = false
            mediaSession.isActive = true
            publishState()
        } catch (_: IllegalStateException) {
            playing = false
        }
    }

    fun pausePlayback(abandonFocus: Boolean = true) {
        playWhenPrepared = false
        if (playerPrepared) {
            try {
                mediaPlayer?.pause()
            } catch (_: IllegalStateException) {
                // Player changed state while the command was handled.
            }
        }
        playing = false
        buffering = false
        if (abandonFocus) abandonAudioFocus()
        publishState()
    }

    fun next() {
        if (queue.isEmpty()) return
        playAt(nextQueueIndex(wrapAtEnd = true))
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (currentPosition() > 5_000L) {
            seekTo(0L)
        } else if (shuffleEnabled && queue.size > 1) {
            playAt(randomQueueIndex())
        } else {
            playAt(if (currentIndex <= 0) queue.lastIndex else currentIndex - 1)
        }
    }

    fun seekTo(positionMs: Long) {
        if (!playerPrepared) return
        val target = positionMs.coerceIn(0L, currentDuration())
        try {
            mediaPlayer?.seekTo(target.toInt())
            overlayService?.updatePlayback(target, playing)
            publishState(updateNotification = false)
        } catch (_: IllegalStateException) {
            // Ignore a seek racing with a track change.
        }
    }

    private fun playAt(index: Int) {
        if (index !in queue.indices) return
        val song = queue[index]
        currentIndex = index
        publishQueue()
        val generation = ++playbackGeneration
        releasePlayer()
        playerPrepared = false
        playWhenPrepared = true
        playing = false
        buffering = true
        resolvedLyrics = null
        lyricsResolutionComplete = false
        updateLyricsLoadState(LyricsLoadState.SEARCHING)
        overlayService?.clearLyrics()

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(this, song.contentUri)
            player.setOnPreparedListener { preparedPlayer ->
                if (generation != playbackGeneration) return@setOnPreparedListener
                playerPrepared = true
                buffering = false
                consecutiveErrors = 0
                updateMediaMetadata(song)
                if (playWhenPrepared && requestAudioFocus()) {
                    preparedPlayer.start()
                    playing = true
                }
                publishState()
            }
            player.setOnCompletionListener {
                if (generation == playbackGeneration) {
                    consecutiveErrors = 0
                    handleTrackCompletion()
                }
            }
            player.setOnErrorListener { _, _, _ ->
                if (generation == playbackGeneration) handlePlaybackError()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            handlePlaybackError()
            return
        }

        resolveLyrics(song)
        updateMediaMetadata(song)
        mediaSession.isActive = true
        promoteToForeground()
        publishState()
    }

    private fun handleTrackCompletion() {
        when {
            repeatMode == PlayerRepeatMode.ONE -> playAt(currentIndex)
            shuffleEnabled && queue.size > 1 -> playAt(randomQueueIndex())
            currentIndex < queue.lastIndex -> playAt(currentIndex + 1)
            repeatMode == PlayerRepeatMode.ALL && queue.isNotEmpty() -> playAt(0)
            else -> {
                playing = false
                buffering = false
                playWhenPrepared = false
                abandonAudioFocus()
                publishState()
            }
        }
    }

    private fun nextQueueIndex(wrapAtEnd: Boolean): Int {
        if (shuffleEnabled && queue.size > 1) return randomQueueIndex()
        if (currentIndex < queue.lastIndex) return currentIndex + 1
        return if (wrapAtEnd) 0 else currentIndex
    }

    private fun randomQueueIndex(): Int {
        if (queue.size <= 1) return currentIndex.coerceAtLeast(0)
        var nextIndex = Random.nextInt(queue.size - 1)
        if (nextIndex >= currentIndex) nextIndex++
        return nextIndex.coerceIn(queue.indices)
    }

    private fun handlePlaybackError() {
        playing = false
        buffering = false
        consecutiveErrors++
        if (queue.size > 1 && consecutiveErrors < queue.size) {
            mainHandler.postDelayed({ next() }, 250L)
        } else {
            pausePlayback()
        }
    }

    private fun resolveLyrics(song: Song) {
        updateLyricsLoadState(LyricsLoadState.SEARCHING)
        val requestGeneration = ++lyricsGeneration
        lyricsExecutor.execute {
            val lyrics = lyricsRepository.findLyrics(song)
            mainHandler.post {
                if (requestGeneration != lyricsGeneration || currentSong()?.id != song.id) {
                    return@post
                }
                resolvedLyrics = lyrics
                lyricsResolutionComplete = true
                if (lyrics.isNullOrBlank()) {
                    updateLyricsLoadState(LyricsLoadState.NOT_FOUND)
                    overlayService?.clearLyrics()
                } else {
                    updateLyricsLoadState(LyricsLoadState.READY)
                    overlayService?.setLyrics(lyrics)
                    overlayService?.updatePlayback(currentPosition(), playing)
                }
            }
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            try {
                player.reset()
            } catch (_: Exception) {
                // Ignore an already released player.
            }
            player.release()
        }
        mediaPlayer = null
        playerPrepared = false
    }

    private fun createMediaSession() {
        val activityIntent = PendingIntent.getActivity(
            this,
            200,
            Intent(this, NowPlayingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags()
        )
        mediaSession = MediaSessionCompat(this, "LyrMusicSession").apply {
            setSessionActivity(activityIntent)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSkipToQueueItem(id: Long) = playQueueIndex(id.toInt())
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stopPlaybackAndService()
            })
            isActive = true
        }
        updateMediaSessionState(0L)
    }

    private fun updateMediaMetadata(song: Song) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.id.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration().takeIf { it > 0 } ?: song.durationMs)
                .build()
        )
    }

    private fun updateMediaSessionState(position: Long) {
        val state = when {
            buffering -> PlaybackStateCompat.STATE_BUFFERING
            playing -> PlaybackStateCompat.STATE_PLAYING
            currentSong() != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    position,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val song = currentSong()
        val contentIntent = PendingIntent.getActivity(
            this,
            201,
            Intent(this, NowPlayingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags()
        )
        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (playing) getString(R.string.pause) else getString(R.string.play)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song?.title ?: getString(R.string.app_name))
            .setContentText(song?.artist ?: getString(R.string.nothing_playing))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing || buffering)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_previous, getString(R.string.previous), servicePendingIntent(202, ACTION_PREVIOUS))
            .addAction(playPauseIcon, playPauseLabel, servicePendingIntent(203, ACTION_PLAY_PAUSE))
            .addAction(R.drawable.ic_next, getString(R.string.next), servicePendingIntent(204, ACTION_NEXT))
            .addAction(R.drawable.ic_stop, "Stop", servicePendingIntent(205, ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun promoteToForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Android 13+ may hide notifications when permission is denied.
        }
    }

    private fun publishState(updateNotification: Boolean = true) {
        val position = currentPosition()
        updateMediaSessionState(position)
        notifyListener(position)
        overlayService?.updatePlayback(position, playing)
        if (updateNotification) updateNotification()
    }

    private fun updateLyricsLoadState(state: LyricsLoadState) {
        if (lyricsLoadState == state) return
        lyricsLoadState = state
        listeners.forEach { it.onLyricsLoadStateChanged(state) }
    }

    private fun notifyListener(position: Long) {
        val song = currentSong()
        val duration = currentDuration()
        listeners.forEach { listener ->
            listener.onPlayerStateChanged(
                song,
                playing,
                buffering,
                position,
                duration
            )
        }
    }

    private fun publishQueue() {
        mediaSession.setQueue(
            queue.mapIndexed { index, song ->
                MediaSessionCompat.QueueItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId(song.id.toString())
                        .setTitle(song.title)
                        .setSubtitle(song.artist)
                        .setMediaUri(song.contentUri)
                        .build(),
                    index.toLong()
                )
            }
        )
        mediaSession.setQueueTitle(getString(R.string.up_next))
        val snapshot = queue.toList()
        listeners.forEach { it.onQueueChanged(snapshot, currentIndex) }
    }

    private fun notifyPlaybackModeChanged() {
        listeners.forEach { it.onPlaybackModeChanged(shuffleEnabled, repeatMode) }
    }

    private fun stopPlaybackAndService() {
        playing = false
        buffering = false
        playWhenPrepared = false
        playbackGeneration++
        lyricsGeneration++
        releasePlayer()
        abandonAudioFocus()
        overlayService?.clearLyrics()
        updateLyricsLoadState(LyricsLoadState.IDLE)
        queue = emptyList()
        currentIndex = -1
        publishQueue()
        updateMediaSessionState(0L)
        notifyListener(0L)
        stopForeground(true)
        stopSelf()
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun currentSong(): Song? = queue.getOrNull(currentIndex)

    private fun currentPosition(): Long = if (playerPrepared) {
        try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (_: IllegalStateException) {
            0L
        }
    } else {
        0L
    }

    private fun currentDuration(): Long = if (playerPrepared) {
        try {
            mediaPlayer?.duration?.toLong()?.coerceAtLeast(0L) ?: currentSong()?.durationMs ?: 0L
        } catch (_: IllegalStateException) {
            currentSong()?.durationMs ?: 0L
        }
    } else {
        currentSong()?.durationMs ?: 0L
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlayerService::class.java).setAction(action),
            pendingIntentFlags()
        )

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val ACTION_START = "com.ahad.lyricsoverlay.player.START"
        const val ACTION_PLAY_PAUSE = "com.ahad.lyricsoverlay.player.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.ahad.lyricsoverlay.player.PREVIOUS"
        const val ACTION_NEXT = "com.ahad.lyricsoverlay.player.NEXT"
        const val ACTION_STOP = "com.ahad.lyricsoverlay.player.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 4102
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}
