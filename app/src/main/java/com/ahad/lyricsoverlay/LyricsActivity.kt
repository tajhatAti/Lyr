package com.ahad.lyricsoverlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import java.util.concurrent.Executors

class LyricsActivity : AppCompatActivity(),
    PlayerService.PlayerListener,
    AppPreferenceListener {

    private lateinit var root: View
    private lateinit var songSubtitle: TextView
    private lateinit var tabs: TabLayout
    private lateinit var liveSection: View
    private lateinit var onlineSection: View
    private lateinit var editSection: View
    private lateinit var liveRecyclerView: RecyclerView
    private lateinit var liveEmptyState: View
    private lateinit var liveEmptyTitle: TextView
    private lateinit var sourceBadge: TextView
    private lateinit var overlayStatusButton: MaterialButton
    private lateinit var searchInput: TextInputEditText
    private lateinit var searchButton: MaterialButton
    private lateinit var searchStatus: TextView
    private lateinit var onlineRecyclerView: RecyclerView
    private lateinit var editor: EditText
    private lateinit var saveButton: MaterialButton
    private lateinit var publishButton: MaterialButton
    private lateinit var publishStatus: TextView
    private lateinit var elapsedTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseCard: MaterialCardView
    private lateinit var playPauseButton: ImageButton
    private lateinit var timerButton: ImageButton

    private lateinit var lyricAdapter: LyricLineAdapter
    private lateinit var onlineAdapter: OnlineLyricsAdapter
    private lateinit var repository: LyricsRepository
    private val worker = Executors.newSingleThreadExecutor()

    private var customization = AppPreferences.snapshot()
    private var playerService: PlayerService? = null
    private var serviceBound = false
    private var currentSong: Song? = null
    private var currentLyrics: LyricsResult? = null
    private var currentLines: List<LrcLine> = emptyList()
    private var latestPositionMs = 0L
    private var latestDurationMs = 0L
    private var activeLineIndex = -1
    private var autoScrollSuppressedUntil = 0L
    private var userSeeking = false
    private var editorProgrammaticChange = false
    private var editorDirty = false
    private var importedPending = false
    private var searchGeneration = 0
    private var publishGeneration = 0
    private var selectedTab = TAB_LIVE

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playerService = (binder as? PlayerService.LocalBinder)?.getService()
            serviceBound = playerService != null
            playerService?.addListener(this@LyricsActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            playerService = null
        }
    }

    private val importLrcLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importLrc(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)
        repository = LyricsRepository(applicationContext)
        customization = AppPreferences.snapshot()
        selectedTab = savedInstanceState?.getInt(STATE_TAB, TAB_LIVE) ?: TAB_LIVE
        bindViews()
        setupTabs()
        setupLists()
        setupControls()
        applyCustomization()
        AppPreferences.registerListener(this)
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, PlayerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        playerService?.removeListener(this)
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // Already disconnected by Android.
            }
        }
        serviceBound = false
        playerService = null
        super.onStop()
    }

    override fun onDestroy() {
        searchGeneration++
        publishGeneration++
        worker.shutdownNow()
        AppPreferences.unregisterListener(this)
        super.onDestroy()
    }

    override fun onPlayerStateChanged(
        song: Song?,
        isPlaying: Boolean,
        isBuffering: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        val songChanged = song?.id != currentSong?.id
        currentSong = song
        latestPositionMs = positionMs.coerceAtLeast(0L)
        latestDurationMs = durationMs.coerceAtLeast(song?.durationMs ?: 0L)
        songSubtitle.text = song?.let { "${it.title} · ${it.artist}" }
            ?: getString(R.string.nothing_playing)
        if (songChanged) {
            editorDirty = false
            importedPending = false
            setEditorText("")
            onlineAdapter.submitList(emptyList())
            searchStatus.setText(R.string.search_select_download_hint)
            searchInput.setText(song?.let { "${it.sourceTitle} ${knownArtistText(it)}" }?.trim().orEmpty())
        }
        updatePlayPause(isPlaying, isBuffering)
        if (!userSeeking) {
            seekBar.progress = if (latestDurationMs > 0L) {
                ((latestPositionMs * SEEK_MAX) / latestDurationMs).toInt()
            } else {
                0
            }
            elapsedTime.text = MusicScannerUtil.formatDuration(latestPositionMs)
        }
        updateActiveLine(latestPositionMs)
    }

    override fun onLyricsLoadStateChanged(state: LyricsLoadState) {
        when (state) {
            LyricsLoadState.IDLE -> showLyricsEmpty(R.string.lyrics_choose_song)
            LyricsLoadState.SEARCHING -> showLyricsEmpty(R.string.lyrics_loading)
            LyricsLoadState.NOT_FOUND -> showLyricsEmpty(R.string.no_timed_lyrics)
            LyricsLoadState.READY -> if (currentLines.isEmpty()) showLyricsEmpty(R.string.no_timed_lyrics)
        }
    }

    override fun onLyricsContentChanged(result: LyricsResult?) {
        currentLyrics = result
        currentLines = result?.rawLrc?.let(LrcParser::parse).orEmpty()
        lyricAdapter.updateLines(currentLines)
        activeLineIndex = -1
        if (currentLines.isEmpty()) {
            liveRecyclerView.visibility = View.INVISIBLE
            liveEmptyState.visibility = View.VISIBLE
            sourceBadge.setText(R.string.no_timed_lyrics)
        } else {
            liveRecyclerView.visibility = View.VISIBLE
            liveEmptyState.visibility = View.GONE
            sourceBadge.setText(sourceLabel(result?.source))
            updateActiveLine(latestPositionMs, force = true)
        }
        if (!editorDirty && result != null) setEditorText(result.rawLrc)
    }

    override fun onSleepTimerChanged(endAtMs: Long, afterCurrentSong: Boolean) {
        val active = afterCurrentSong || endAtMs > System.currentTimeMillis()
        timerButton.imageTintList = ColorStateList.valueOf(
            if (active) customization.accentColor
            else ContextCompat.getColor(this, R.color.text_secondary)
        )
        timerButton.animate().cancel()
        timerButton.animate().rotation(if (active) 12f else 0f).setDuration(180L).start()
    }

    override fun onAppPreferenceChanged(snapshot: CustomizationSnapshot, changedKey: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val oldTheme = customization.themeMode
            customization = snapshot
            if (oldTheme != snapshot.themeMode) {
                LyrApplication.applyThemeMode(snapshot.themeMode)
                return@runOnUiThread
            }
            applyCustomization()
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.lyricsRoot)
        songSubtitle = findViewById(R.id.lyricsSongSubtitle)
        tabs = findViewById(R.id.lyricsTabs)
        liveSection = findViewById(R.id.liveLyricsSection)
        onlineSection = findViewById(R.id.onlineLyricsSection)
        editSection = findViewById(R.id.editLyricsSection)
        liveRecyclerView = findViewById(R.id.liveLyricsRecyclerView)
        liveEmptyState = findViewById(R.id.liveLyricsEmptyState)
        liveEmptyTitle = findViewById(R.id.liveLyricsEmptyTitle)
        sourceBadge = findViewById(R.id.lyricsSourceBadge)
        overlayStatusButton = findViewById(R.id.overlayStatusButton)
        searchInput = findViewById(R.id.lyricsSearchInput)
        searchButton = findViewById(R.id.searchLyricsButton)
        searchStatus = findViewById(R.id.onlineLyricsStatus)
        onlineRecyclerView = findViewById(R.id.onlineLyricsRecyclerView)
        editor = findViewById(R.id.lrcEditor)
        saveButton = findViewById(R.id.saveLyricsButton)
        publishButton = findViewById(R.id.publishLyricsButton)
        publishStatus = findViewById(R.id.publishLyricsStatus)
        elapsedTime = findViewById(R.id.lyricsElapsedTime)
        seekBar = findViewById(R.id.lyricsSeekBar)
        playPauseCard = findViewById(R.id.lyricsPlayPauseCard)
        playPauseButton = findViewById(R.id.lyricsPlayPauseButton)
        timerButton = findViewById(R.id.lyricsTimerButton)
        seekBar.max = SEEK_MAX
    }

    private fun setupTabs() {
        tabs.addTab(tabs.newTab().setText(R.string.lyrics_tab_live).setIcon(R.drawable.ic_lyrics))
        tabs.addTab(tabs.newTab().setText(R.string.lyrics_tab_online).setIcon(R.drawable.ic_search))
        tabs.addTab(tabs.newTab().setText(R.string.lyrics_tab_edit).setIcon(R.drawable.ic_edit))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position, animate = true)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == TAB_LIVE && activeLineIndex >= 0) centerActiveLine(smooth = true)
            }
        })
        tabs.getTabAt(selectedTab)?.select()
        showTab(selectedTab, animate = false)
    }

    private fun setupLists() {
        lyricAdapter = LyricLineAdapter { line ->
            autoScrollSuppressedUntil = 0L
            playerService?.seekTo(line.timestampMs)
            updateActiveLine(line.timestampMs, force = true)
        }
        lyricAdapter.updateConfiguration(customization)
        liveRecyclerView.layoutManager = LinearLayoutManager(this)
        liveRecyclerView.adapter = lyricAdapter
        (liveRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        liveRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    autoScrollSuppressedUntil = android.os.SystemClock.elapsedRealtime() + USER_SCROLL_PAUSE_MS
                }
            }
        })

        onlineAdapter = OnlineLyricsAdapter(::confirmOnlineSelection)
        onlineAdapter.updateConfiguration(customization)
        onlineRecyclerView.layoutManager = LinearLayoutManager(this)
        onlineRecyclerView.adapter = onlineAdapter
        (onlineRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupControls() {
        findViewById<View>(R.id.lyricsBackButton).setOnClickListener { closeLyrics() }
        timerButton.setOnClickListener { SleepTimerDialog.show(this, playerService) }
        findViewById<View>(R.id.retryAutomaticLyricsButton).setOnClickListener {
            playerService?.retryLyrics()
        }
        findViewById<View>(R.id.findOnlineLyricsButton).setOnClickListener { tabs.getTabAt(TAB_ONLINE)?.select() }
        findViewById<View>(R.id.openLyricsEditorButton).setOnClickListener { tabs.getTabAt(TAB_EDIT)?.select() }
        searchButton.setOnClickListener { searchOnline() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchOnline()
                true
            } else {
                false
            }
        }
        editor.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !editorProgrammaticChange) editorDirty = true
        }
        findViewById<View>(R.id.importLrcButton).setOnClickListener {
            importLrcLauncher.launch(arrayOf("text/*", "application/octet-stream"))
        }
        findViewById<View>(R.id.insertTimestampButton).setOnClickListener { insertCurrentTimestamp() }
        saveButton.setOnClickListener { saveEditedLyrics() }
        findViewById<View>(R.id.restoreAutomaticLyricsButton).setOnClickListener {
            restoreAutomaticLyrics()
        }
        publishButton.setOnClickListener { confirmPublish() }
        overlayStatusButton.setOnClickListener { openOrRefreshOverlay() }
        findViewById<View>(R.id.lyricsPreviousButton).setOnClickListener { playerService?.previous() }
        playPauseButton.setOnClickListener { playerService?.togglePlayPause() }
        findViewById<View>(R.id.lyricsNextButton).setOnClickListener { playerService?.next() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val position = if (latestDurationMs > 0L) latestDurationMs * progress / SEEK_MAX else 0L
                    elapsedTime.text = MusicScannerUtil.formatDuration(position)
                    updateActiveLine(position)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                autoScrollSuppressedUntil = 0L
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = if (latestDurationMs > 0L) {
                    latestDurationMs * (seekBar?.progress ?: 0) / SEEK_MAX
                } else {
                    0L
                }
                playerService?.seekTo(target)
                userSeeking = false
            }
        })
    }

    private fun showTab(index: Int, animate: Boolean) {
        selectedTab = index.coerceIn(TAB_LIVE, TAB_EDIT)
        val sections = listOf(liveSection, onlineSection, editSection)
        sections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex == selectedTab) {
                section.visibility = View.VISIBLE
                section.animate().cancel()
                if (animate) {
                    section.alpha = 0f
                    section.translationX = if (sectionIndex > TAB_LIVE) {
                        dp(18f).toFloat()
                    } else {
                        -dp(18f).toFloat()
                    }
                    section.animate().alpha(1f).translationX(0f).setDuration(230L).start()
                } else {
                    section.alpha = 1f
                    section.translationX = 0f
                }
            } else {
                section.animate().cancel()
                section.visibility = View.GONE
            }
        }
    }

    private fun updateActiveLine(positionMs: Long, force: Boolean = false) {
        if (currentLines.isEmpty()) return
        val index = LrcParser.lineIndexAt(currentLines, positionMs)
        if (!force && index == activeLineIndex) return
        activeLineIndex = index
        lyricAdapter.setActiveIndex(index)
        if (index >= 0 && android.os.SystemClock.elapsedRealtime() >= autoScrollSuppressedUntil) {
            centerActiveLine(smooth = !force)
        }
    }

    private fun centerActiveLine(smooth: Boolean) {
        val target = activeLineIndex
        if (target !in currentLines.indices) return
        if (!smooth) {
            (liveRecyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(target, liveRecyclerView.height / 2 - dp(38f))
            return
        }
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int = (boxStart + boxEnd) / 2 - (viewStart + viewEnd) / 2
        }
        scroller.targetPosition = target
        liveRecyclerView.layoutManager?.startSmoothScroll(scroller)
    }

    private fun searchOnline() {
        val song = currentSong
        if (song == null) {
            Toast.makeText(this, R.string.no_song_for_lyrics, Toast.LENGTH_SHORT).show()
            return
        }
        val query = searchInput.text?.toString().orEmpty()
        val generation = ++searchGeneration
        searchButton.isEnabled = false
        searchStatus.setText(R.string.lyrics_searching_online)
        onlineAdapter.submitList(emptyList())
        worker.execute {
            val response = repository.searchOnline(song, query)
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != searchGeneration || currentSong?.id != song.id) {
                    return@runOnUiThread
                }
                searchButton.isEnabled = true
                onlineAdapter.submitList(response.results)
                searchStatus.text = when {
                    response.error == LyricsNetworkError.RATE_LIMITED -> getString(
                        R.string.lyrics_rate_limited,
                        response.retryAfterSeconds ?: 10
                    )
                    response.error != null -> getString(R.string.lyrics_search_failed)
                    response.results.isEmpty() -> getString(R.string.lyrics_no_search_results)
                    else -> getString(R.string.lyrics_results_found, response.results.size)
                }
            }
        }
    }

    private fun confirmOnlineSelection(candidate: OnlineLyricsCandidate) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.use_online_lyrics_title)
            .setMessage(
                getString(
                    R.string.use_online_lyrics_message,
                    candidate.trackName,
                    candidate.artistName,
                    candidate.albumName.ifBlank { getString(R.string.unknown_album) },
                    MusicScannerUtil.formatDuration((candidate.durationSeconds * 1_000).toLong())
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_and_use) { _, _ ->
                if (playerService?.useOnlineLyrics(candidate) == true) {
                    editorDirty = false
                    importedPending = false
                    tabs.getTabAt(TAB_LIVE)?.select()
                    Toast.makeText(this, R.string.lyrics_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun importLrc(uri: Uri) {
        val songId = currentSong?.id
        worker.execute {
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val content = reader.readText()
                    content.takeIf { it.length <= MAX_LRC_CHARACTERS }
                }
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || currentSong?.id != songId) return@runOnUiThread
                if (text == null) {
                    Toast.makeText(this, R.string.unable_to_import_lrc, Toast.LENGTH_LONG).show()
                } else {
                    setEditorText(text)
                    editorDirty = true
                    importedPending = true
                    Toast.makeText(this, R.string.lrc_imported, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun insertCurrentTimestamp() {
        if (currentSong == null) {
            Toast.makeText(this, R.string.no_song_for_lyrics, Toast.LENGTH_SHORT).show()
            return
        }
        val totalCentiseconds = latestPositionMs.coerceAtLeast(0L) / 10L
        val minutes = totalCentiseconds / 6_000L
        val seconds = (totalCentiseconds % 6_000L) / 100L
        val centiseconds = totalCentiseconds % 100L
        val stamp = String.format(Locale.US, "[%02d:%02d.%02d] ", minutes, seconds, centiseconds)
        val selection = editor.selectionStart.coerceAtLeast(0)
        val current = editor.text ?: return
        val needsNewline = selection > 0 && current.getOrNull(selection - 1) != '\n'
        current.insert(selection, if (needsNewline) "\n$stamp" else stamp)
        editor.setSelection((selection + stamp.length + if (needsNewline) 1 else 0).coerceAtMost(current.length))
        editorDirty = true
        editor.requestFocus()
    }

    private fun saveEditedLyrics() {
        if (currentSong == null) {
            Toast.makeText(this, R.string.no_song_for_lyrics, Toast.LENGTH_SHORT).show()
            return
        }
        val raw = editor.text?.toString().orEmpty()
        if (LrcParser.parse(raw).isEmpty()) {
            Toast.makeText(this, R.string.invalid_lrc, Toast.LENGTH_LONG).show()
            return
        }
        val source = if (importedPending) LyricsSource.IMPORTED_FILE else LyricsSource.USER_EDITED
        if (playerService?.saveUserLyrics(raw, source) == true) {
            editorDirty = false
            importedPending = false
            tabs.getTabAt(TAB_LIVE)?.select()
            Toast.makeText(this, R.string.lyrics_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreAutomaticLyrics() {
        if (currentSong == null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_automatic_lyrics)
            .setMessage(R.string.automatic_lyrics_restored)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_automatic_lyrics) { _, _ ->
                editorDirty = false
                importedPending = false
                setEditorText("")
                playerService?.restoreAutomaticLyrics()
                tabs.getTabAt(TAB_LIVE)?.select()
            }
            .show()
    }

    private fun confirmPublish() {
        val song = currentSong
        val raw = editor.text?.toString().orEmpty()
        if (song == null) {
            Toast.makeText(this, R.string.no_song_for_lyrics, Toast.LENGTH_SHORT).show()
            return
        }
        if (LrcParser.parse(raw).isEmpty()) {
            Toast.makeText(this, R.string.invalid_lrc, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.publish_confirmation_title)
            .setMessage(R.string.publish_confirmation_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.publish_publicly) { _, _ -> publishLyrics(song, raw) }
            .show()
    }

    private fun publishLyrics(song: Song, raw: String) {
        val generation = ++publishGeneration
        publishButton.isEnabled = false
        publishStatus.text = getString(R.string.publish_working, "0")
        worker.execute {
            val result = repository.publishToLrclib(song, raw) { attempts ->
                runOnUiThread {
                    if (generation == publishGeneration) {
                        publishStatus.text = getString(
                            R.string.publish_working,
                            String.format(Locale.US, "%,d", attempts)
                        )
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != publishGeneration) return@runOnUiThread
                publishButton.isEnabled = true
                publishStatus.text = if (result.successful) {
                    getString(R.string.publish_success)
                } else {
                    getString(R.string.publish_failed, result.message ?: getString(R.string.lyrics_search_failed))
                }
            }
        }
    }

    private fun setEditorText(value: String) {
        editorProgrammaticChange = true
        editor.setText(value)
        editor.setSelection(editor.text?.length ?: 0)
        editorProgrammaticChange = false
    }

    private fun showLyricsEmpty(titleRes: Int) {
        if (currentLines.isNotEmpty()) return
        liveRecyclerView.visibility = View.INVISIBLE
        liveEmptyState.visibility = View.VISIBLE
        liveEmptyTitle.setText(titleRes)
    }

    private fun sourceLabel(source: LyricsSource?): Int = when (source) {
        LyricsSource.USER_EDITED -> R.string.lyrics_source_edited
        LyricsSource.IMPORTED_FILE -> R.string.lyrics_source_imported
        LyricsSource.ONLINE_SELECTED -> R.string.lyrics_source_selected
        LyricsSource.DOWNLOADED_CACHE -> R.string.lyrics_source_cache
        LyricsSource.LOCAL_SIDECAR -> R.string.lyrics_source_local
        LyricsSource.ONLINE_AUTO -> R.string.lyrics_source_online
        null -> R.string.no_timed_lyrics
    }

    private fun knownArtistText(song: Song): String = song.artist.takeUnless {
        it.equals(getString(R.string.unknown_artist), ignoreCase = true)
    }.orEmpty()

    private fun openOrRefreshOverlay() {
        if (Settings.canDrawOverlays(this)) {
            playerService?.refreshOverlayNow()
            Toast.makeText(this, R.string.floating_overlay_verified, Toast.LENGTH_LONG).show()
            updateOverlayButton()
        } else {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.unable_to_open_overlay_settings, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateOverlayButton() {
        if (!::overlayStatusButton.isInitialized) return
        val allowed = Settings.canDrawOverlays(this)
        overlayStatusButton.setText(
            if (allowed) R.string.floating_overlay_ready else R.string.enable_floating_overlay
        )
        overlayStatusButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_lyrics)
        overlayStatusButton.iconTint = ColorStateList.valueOf(
            if (allowed) ContextCompat.getColor(this, R.color.success) else customization.accentColor
        )
        if (allowed) playerService?.refreshOverlayNow()
    }

    private fun updatePlayPause(isPlaying: Boolean, isBuffering: Boolean) {
        val active = isPlaying || isBuffering
        val expected = if (active) R.drawable.ic_pause else R.drawable.ic_play
        if (playPauseButton.tag == expected) return
        playPauseButton.tag = expected
        playPauseButton.animate().cancel()
        playPauseButton.animate().scaleX(0.72f).scaleY(0.72f).alpha(0.4f).setDuration(80L)
            .withEndAction {
                playPauseButton.setImageResource(expected)
                playPauseButton.contentDescription = getString(if (active) R.string.pause else R.string.play)
                playPauseButton.animate().scaleX(1f).scaleY(1f)
                    .alpha(if (isBuffering) 0.7f else 1f).setDuration(160L).start()
            }.start()
    }

    private fun applyCustomization() {
        AppUi.applySystemBars(this)
        AppUi.applyTypeface(root, customization.appFont)
        if (::lyricAdapter.isInitialized) lyricAdapter.updateConfiguration(customization)
        if (::onlineAdapter.isInitialized) onlineAdapter.updateConfiguration(customization)
        val accent = customization.accentColor
        tabs.setSelectedTabIndicatorColor(accent)
        tabs.setTabTextColors(ContextCompat.getColor(this, R.color.text_secondary), accent)
        seekBar.progressTintList = ColorStateList.valueOf(accent)
        seekBar.thumbTintList = ColorStateList.valueOf(accent)
        playPauseCard.setCardBackgroundColor(accent)
        playPauseCard.rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(AppUi.contrastTextColor(accent), 45)
        )
        playPauseButton.imageTintList = ColorStateList.valueOf(AppUi.contrastTextColor(accent))
        val onAccent = AppUi.contrastTextColor(accent)
        listOf(
            searchButton,
            saveButton,
            findViewById<MaterialButton>(R.id.retryAutomaticLyricsButton)
        ).forEach { button ->
            button.backgroundTintList = ColorStateList.valueOf(accent)
            button.setTextColor(onAccent)
            button.iconTint = ColorStateList.valueOf(onAccent)
        }
        listOf(
            R.id.findOnlineLyricsButton,
            R.id.openLyricsEditorButton,
            R.id.importLrcButton,
            R.id.insertTimestampButton,
            R.id.restoreAutomaticLyricsButton,
            R.id.publishLyricsButton,
            R.id.overlayStatusButton
        ).forEach { id ->
            findViewById<MaterialButton>(id).apply {
                setTextColor(accent)
                iconTint = ColorStateList.valueOf(accent)
                strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 150))
            }
        }
        updateOverlayButton()
        playerService?.let { service ->
            onSleepTimerChanged(service.sleepTimerEndAtMs(), service.sleepsAfterCurrentSong())
        }
    }

    private fun closeLyrics() {
        finish()
        overridePendingTransition(R.anim.player_background_fade, R.anim.lyrics_exit)
    }

    override fun onBackPressed() = closeLyrics()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_LIVE = 0
        private const val TAB_ONLINE = 1
        private const val TAB_EDIT = 2
        private const val SEEK_MAX = 1_000
        private const val USER_SCROLL_PAUSE_MS = 5_000L
        private const val MAX_LRC_CHARACTERS = 1_000_000
        private const val STATE_TAB = "lyrics_selected_tab"
    }
}
