package com.ahad.lyricsoverlay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(),
    PlayerService.PlayerListener,
    AppPreferenceListener {

    private lateinit var mainRoot: View
    private lateinit var libraryContentContainer: ViewGroup
    private lateinit var librarySummary: TextView
    private lateinit var layoutModeButton: ImageButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyMessage: TextView
    private lateinit var permissionBanner: View
    private lateinit var permissionMessage: TextView
    private lateinit var adapter: MusicListAdapter

    private lateinit var miniPlayer: MaterialCardView
    private lateinit var miniAlbumArt: ImageView
    private lateinit var miniSongTitle: TextView
    private lateinit var miniSongArtist: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekBar: SeekBar

    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val scannedSongs = mutableListOf<Song>()
    private val visibleSongs = mutableListOf<Song>()
    private var customization = AppPreferences.snapshot()
    private var playerService: PlayerService? = null
    private var serviceBound = false
    private var pendingSongPosition: Int? = null
    private var userSeeking = false
    private var latestDurationMs = 0L
    private var displayedSongId: Long? = null
    private var displayedPlayingState: Boolean? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playerService = (binder as? PlayerService.LocalBinder)?.getService()
            serviceBound = playerService != null
            playerService?.setListener(this@MainActivity)
            pendingSongPosition?.let { position ->
                pendingSongPosition = null
                playSong(position)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            playerService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        customization = AppPreferences.snapshot()
        bindViews()
        setupRecyclerView()
        setupPlayerControls()
        setupTopControls()
        AppUi.apply(this, mainRoot, customization)
        updateLayoutButton()
        updateLibrarySummary()
        AppPreferences.registerListener(this)
        requestMissingPermissions()

        if (hasAudioPermission()) scanMusicLibrary()
    }

    override fun onStart() {
        super.onStart()
        applyLatestCustomization()
        serviceBound = bindService(
            Intent(this, PlayerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        playerService?.setListener(null)
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // Service was already disconnected.
            }
        }
        serviceBound = false
        playerService = null
        super.onStop()
    }

    override fun onDestroy() {
        AppPreferences.unregisterListener(this)
        scannerExecutor.shutdownNow()
        adapter.release()
        super.onDestroy()
    }

    override fun onAppPreferenceChanged(snapshot: CustomizationSnapshot, changedKey: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val previous = customization
            customization = snapshot

            if (previous.themeMode != snapshot.themeMode) {
                LyrApplication.applyThemeMode(snapshot.themeMode)
                return@runOnUiThread
            }

            AppUi.apply(this, mainRoot, snapshot)
            val structureChanged = previous.layoutMode != snapshot.layoutMode ||
                previous.gridColumns != snapshot.gridColumns ||
                previous.itemStyle != snapshot.itemStyle
            val sortChanged = previous.sortOrder != snapshot.sortOrder

            if (sortChanged) {
                sortAndDisplaySongs(preserveAnchor = true)
            }
            if (structureChanged) {
                rebuildRecyclerView(snapshot, animate = true)
            } else if (previous.accentColor != snapshot.accentColor ||
                previous.appFont != snapshot.appFont
            ) {
                adapter.updateConfiguration(snapshot)
            }
            updateLayoutButton()
            updateLibrarySummary()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasAudioPermission()) {
                permissionBanner.visibility = View.GONE
                scanMusicLibrary()
            } else {
                showPermissionRequired()
            }
        }
    }

    override fun onPlayerStateChanged(
        song: Song?,
        isPlaying: Boolean,
        isBuffering: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        if (song == null) {
            miniPlayer.visibility = View.GONE
            displayedSongId = null
            adapter.setPlayingSong(null)
            return
        }

        miniPlayer.visibility = View.VISIBLE
        adapter.setPlayingSong(song.id)
        latestDurationMs = durationMs.coerceAtLeast(song.durationMs)

        if (displayedSongId != song.id) {
            displayedSongId = song.id
            miniSongTitle.text = song.title
            miniSongArtist.text = song.artist
            miniAlbumArt.animate().cancel()
            miniAlbumArt.animate().alpha(0f).setDuration(120L).withEndAction {
                adapter.loadArtworkInto(miniAlbumArt, song)
                miniAlbumArt.animate().alpha(1f).setDuration(260L).start()
            }.start()
        }

        updatePlayPauseIcon(isPlaying, isBuffering)
        if (!userSeeking) {
            seekBar.progress = if (latestDurationMs > 0) {
                ((positionMs.coerceIn(0L, latestDurationMs) * SEEK_MAX) / latestDurationMs).toInt()
            } else {
                0
            }
        }
    }

    private fun bindViews() {
        mainRoot = findViewById(R.id.mainRoot)
        libraryContentContainer = findViewById(R.id.libraryContentContainer)
        librarySummary = findViewById(R.id.librarySummary)
        layoutModeButton = findViewById(R.id.layoutModeButton)
        recyclerView = findViewById(R.id.songRecyclerView)
        emptyMessage = findViewById(R.id.emptyMessage)
        permissionBanner = findViewById(R.id.permissionBanner)
        permissionMessage = findViewById(R.id.permissionMessage)

        miniPlayer = findViewById(R.id.miniPlayer)
        miniAlbumArt = findViewById(R.id.miniAlbumArt)
        miniSongTitle = findViewById(R.id.miniSongTitle)
        miniSongArtist = findViewById(R.id.miniSongArtist)
        playPauseButton = findViewById(R.id.playPauseButton)
        seekBar = findViewById(R.id.playerSeekBar)
        seekBar.max = SEEK_MAX
    }

    private fun setupRecyclerView() {
        adapter = MusicListAdapter(this) { position -> playSong(position) }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = createLayoutManager(customization)
        recyclerView.setHasFixedSize(false)
        adapter.updateConfiguration(customization)
    }

    private fun setupTopControls() {
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        layoutModeButton.setOnClickListener {
            val nextMode = if (customization.layoutMode == LibraryLayoutMode.LIST) {
                LibraryLayoutMode.GRID
            } else {
                LibraryLayoutMode.LIST
            }
            AppPreferences.setLayoutMode(nextMode)
        }
        findViewById<View>(R.id.grantPermissionButton).setOnClickListener {
            requestMissingPermissions(forceAudioRequest = true)
        }
    }

    private fun setupPlayerControls() {
        playPauseButton.setOnClickListener { playerService?.togglePlayPause() }
        findViewById<View>(R.id.previousButton).setOnClickListener { playerService?.previous() }
        findViewById<View>(R.id.nextButton).setOnClickListener { playerService?.next() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val target = if (latestDurationMs > 0) {
                    latestDurationMs * progress / SEEK_MAX
                } else {
                    0L
                }
                playerService?.seekTo(target)
                userSeeking = false
            }
        })
    }

    private fun playSong(position: Int) {
        if (position !in visibleSongs.indices) return
        val service = playerService
        if (service == null) {
            pendingSongPosition = position
            Toast.makeText(this, R.string.preparing_player, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_START)
            )
            service.playSongs(visibleSongs, position)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.unable_to_start_playback, Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanMusicLibrary() {
        emptyMessage.visibility = View.VISIBLE
        emptyMessage.setText(R.string.loading_music)
        scannerExecutor.execute {
            val result = MusicScannerUtil.scan(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                scannedSongs.clear()
                scannedSongs.addAll(result)
                sortAndDisplaySongs(preserveAnchor = false)
                emptyMessage.visibility = if (visibleSongs.isEmpty()) View.VISIBLE else View.GONE
                if (visibleSongs.isEmpty()) emptyMessage.setText(R.string.no_songs)
                updateLibrarySummary()
            }
        }
    }

    private fun sortAndDisplaySongs(preserveAnchor: Boolean) {
        val anchor = if (preserveAnchor) captureScrollAnchor() else null
        val titleComparator = compareBy<Song>(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.id }
        val sorted = when (customization.sortOrder) {
            LibrarySortOrder.TITLE -> scannedSongs.sortedWith(titleComparator)
            LibrarySortOrder.ARTIST -> scannedSongs.sortedWith(
                compareBy<Song>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy { it.id }
            )
            LibrarySortOrder.DATE_ADDED -> scannedSongs.sortedWith(
                compareByDescending<Song> { it.dateAddedSeconds }
                    .then(titleComparator)
            )
            LibrarySortOrder.DURATION -> scannedSongs.sortedWith(
                compareByDescending<Song> { it.durationMs }
                    .then(titleComparator)
            )
        }
        visibleSongs.clear()
        visibleSongs.addAll(sorted)
        adapter.submitList(sorted)
        restoreScrollAnchor(anchor)
        emptyMessage.visibility = if (visibleSongs.isEmpty()) View.VISIBLE else View.GONE
        updateLibrarySummary()
    }

    private fun rebuildRecyclerView(snapshot: CustomizationSnapshot, animate: Boolean) {
        val anchor = captureScrollAnchor()
        val applyChange = {
            recyclerView.layoutManager = createLayoutManager(snapshot)
            recyclerView.recycledViewPool.clear()
            adapter.updateConfiguration(snapshot)
            restoreScrollAnchor(anchor)
        }

        recyclerView.animate().cancel()
        if (!animate || recyclerView.visibility != View.VISIBLE) {
            applyChange()
            return
        }
        recyclerView.animate()
            .alpha(0f)
            .setDuration(120L)
            .withEndAction {
                applyChange()
                recyclerView.animate().alpha(1f).setDuration(190L).start()
            }
            .start()
    }

    private fun createLayoutManager(snapshot: CustomizationSnapshot): LinearLayoutManager =
        if (snapshot.layoutMode == LibraryLayoutMode.GRID) {
            GridLayoutManager(this, snapshot.gridColumns)
        } else {
            LinearLayoutManager(this)
        }

    private fun captureScrollAnchor(): ScrollAnchor? {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val view = layoutManager.findViewByPosition(position)
        return ScrollAnchor(
            songId = visibleSongs.getOrNull(position)?.id,
            fallbackPosition = position,
            topOffset = (view?.top ?: recyclerView.paddingTop) - recyclerView.paddingTop
        )
    }

    private fun restoreScrollAnchor(anchor: ScrollAnchor?) {
        anchor ?: return
        recyclerView.post {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return@post
            val byId = anchor.songId?.let { id -> visibleSongs.indexOfFirst { it.id == id } } ?: -1
            val position = if (byId >= 0) byId else anchor.fallbackPosition
                .coerceIn(0, (visibleSongs.size - 1).coerceAtLeast(0))
            if (visibleSongs.isNotEmpty()) {
                layoutManager.scrollToPositionWithOffset(position, anchor.topOffset)
            }
        }
    }

    private fun applyLatestCustomization() {
        val latest = AppPreferences.snapshot()
        if (latest != customization) onAppPreferenceChanged(latest, "resume")
        else AppUi.apply(this, mainRoot, latest)
    }

    private fun updateLayoutButton() {
        val isList = customization.layoutMode == LibraryLayoutMode.LIST
        layoutModeButton.setImageResource(if (isList) R.drawable.ic_grid_view else R.drawable.ic_list_view)
        layoutModeButton.contentDescription = getString(
            if (isList) R.string.switch_to_grid else R.string.switch_to_list
        )
        layoutModeButton.imageTintList = android.content.res.ColorStateList.valueOf(customization.accentColor)
    }

    private fun updateLibrarySummary() {
        val sortLabel = when (customization.sortOrder) {
            LibrarySortOrder.TITLE -> getString(R.string.sort_title_short)
            LibrarySortOrder.ARTIST -> getString(R.string.sort_artist_short)
            LibrarySortOrder.DATE_ADDED -> getString(R.string.sort_date_short)
            LibrarySortOrder.DURATION -> getString(R.string.sort_duration_short)
        }
        val modeLabel = if (customization.layoutMode == LibraryLayoutMode.LIST) {
            getString(R.string.list_view)
        } else {
            getString(R.string.grid_view_columns, customization.gridColumns)
        }
        librarySummary.text = getString(
            R.string.library_summary,
            visibleSongs.size,
            sortLabel,
            modeLabel
        )
    }

    private fun requestMissingPermissions(forceAudioRequest: Boolean = false) {
        val missing = mutableListOf<String>()
        val audioPermission = requiredAudioPermission()
        if (forceAudioRequest ||
            ContextCompat.checkSelfPermission(this, audioPermission) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += audioPermission
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.distinct().toTypedArray(), REQUEST_PERMISSIONS)
        }
        if (!hasAudioPermission()) showPermissionRequired()
    }

    private fun showPermissionRequired() {
        permissionBanner.visibility = View.VISIBLE
        permissionMessage.setText(R.string.permission_needed)
        emptyMessage.visibility = View.VISIBLE
        emptyMessage.setText(R.string.permission_needed)
    }

    private fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        requiredAudioPermission()
    ) == PackageManager.PERMISSION_GRANTED

    private fun requiredAudioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun updatePlayPauseIcon(isPlaying: Boolean, isBuffering: Boolean) {
        val effectivePlaying = isPlaying || isBuffering
        if (displayedPlayingState == effectivePlaying) return
        displayedPlayingState = effectivePlaying

        playPauseButton.animate().cancel()
        playPauseButton.animate()
            .alpha(0.35f)
            .scaleX(0.72f)
            .scaleY(0.72f)
            .rotationBy(70f)
            .setDuration(110L)
            .withEndAction {
                playPauseButton.setImageResource(
                    if (effectivePlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                playPauseButton.contentDescription = getString(
                    if (effectivePlaying) R.string.pause else R.string.play
                )
                playPauseButton.imageTintList = android.content.res.ColorStateList.valueOf(
                    AppUi.contrastTextColor(customization.accentColor)
                )
                playPauseButton.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .setDuration(190L)
                    .start()
            }
            .start()
    }

    private data class ScrollAnchor(
        val songId: Long?,
        val fallbackPosition: Int,
        val topOffset: Int
    )

    companion object {
        private const val REQUEST_PERMISSIONS = 601
        private const val SEEK_MAX = 1_000
    }
}
