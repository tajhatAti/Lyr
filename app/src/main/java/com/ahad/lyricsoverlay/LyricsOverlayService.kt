package com.ahad.lyricsoverlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

class LyricsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var preferences: android.content.SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var previousLineText: TextView? = null
    private var currentLineText: TextView? = null
    private var nextLineText: TextView? = null
    private var pauseButton: TextView? = null

    private var lines: List<String> = emptyList()
    private var currentIndex = 0
    private var intervalMillis = DEFAULT_INTERVAL_MILLIS
    private var isPaused = false
    private var colorAnimator: ValueAnimator? = null
    private var animationGeneration = 0

    private val autoAdvanceRunnable = object : Runnable {
        override fun run() {
            if (!isPaused && lines.size > 1) {
                showLine((currentIndex + 1) % lines.size, animate = true)
            }
            scheduleNextLine()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        restoreSavedState()

        // This is called immediately so startForegroundService() always satisfies its deadline.
        startForeground(NOTIFICATION_ID, buildNotification())
        preferences.edit().putBoolean(PREF_IS_RUNNING, true).apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PREVIOUS -> {
                ensureOverlayIsVisible()
                if (lines.isNotEmpty()) {
                    val index = if (currentIndex == 0) lines.lastIndex else currentIndex - 1
                    showLine(index, animate = true)
                    restartAutoAdvance()
                }
            }

            ACTION_NEXT -> {
                ensureOverlayIsVisible()
                if (lines.isNotEmpty()) {
                    showLine((currentIndex + 1) % lines.size, animate = true)
                    restartAutoAdvance()
                }
            }

            ACTION_TOGGLE_PAUSE -> {
                ensureOverlayIsVisible()
                togglePaused()
            }

            ACTION_START, null -> {
                val suppliedLyrics = intent?.getStringExtra(EXTRA_LYRICS)
                intervalMillis = intent?.getLongExtra(
                    EXTRA_INTERVAL_MILLIS,
                    preferences.getLong(PREF_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS)
                )?.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
                    ?: DEFAULT_INTERVAL_MILLIS

                if (!suppliedLyrics.isNullOrBlank()) {
                    lines = parseLyrics(suppliedLyrics)
                    currentIndex = 0
                    preferences.edit()
                        .putString(PREF_LYRICS, suppliedLyrics)
                        .putLong(PREF_INTERVAL_MILLIS, intervalMillis)
                        .apply()
                }

                if (lines.isEmpty()) lines = listOf(getString(R.string.app_name))
                isPaused = false
                ensureOverlayIsVisible()
                showLine(currentIndex.coerceIn(0, lines.lastIndex), animate = false)
                restartAutoAdvance()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        colorAnimator?.cancel()
        animationGeneration++

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
                // The window was already detached.
            }
        }
        overlayView = null
        preferences.edit().putBoolean(PREF_IS_RUNNING, false).apply()
        stopForeground(true)
        super.onDestroy()
    }

    private fun restoreSavedState() {
        val rawLyrics = preferences.getString(PREF_LYRICS, null).orEmpty()
        lines = parseLyrics(rawLyrics)
        intervalMillis = preferences.getLong(PREF_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS)
            .coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
    }

    private fun ensureOverlayIsVisible() {
        if (overlayView != null) return

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission পাওয়া যায়নি", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val view = createOverlayView()
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // TYPE_APPLICATION_OVERLAY was introduced in API 26. This fallback keeps minSdk 24 usable.
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = preferences.getInt(PREF_WINDOW_X, 0)
            y = preferences.getInt(PREF_WINDOW_Y, dp(110))
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
        } catch (_: SecurityException) {
            Toast.makeText(this, "Overlay permission প্রয়োজন", Toast.LENGTH_LONG).show()
            stopSelf()
        } catch (_: WindowManager.BadTokenException) {
            Toast.makeText(this, "Floating window তৈরি করা যায়নি", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun createOverlayView(): View {
        val displayWidth = resources.displayMetrics.widthPixels
        val cardWidth = min(dp(360), displayWidth - dp(28))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = roundedBackground(Color.argb(238, 20, 23, 31), 20f, Color.argb(100, 255, 255, 255))
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, 0, dp(6))
        }
        header.addView(TextView(this).apply {
            text = "♫  FLOATING LYRICS"
            setTextColor(Color.rgb(184, 162, 255))
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(controlButton("×", "Floating lyrics বন্ধ করুন").apply {
            setOnClickListener { stopSelf() }
        })
        attachDragListener(header)
        card.addView(header, matchWidthParams())

        previousLineText = TextView(this).apply {
            setTextColor(Color.rgb(126, 130, 143))
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            alpha = 0.72f
            setPadding(dp(4), dp(2), dp(4), dp(3))
        }
        card.addView(previousLineText, matchWidthParams())

        currentLineText = TextView(this).apply {
            text = "Lyrics loading…"
            setTextColor(Color.WHITE)
            textSize = 21f
            gravity = Gravity.CENTER
            maxLines = 3
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        card.addView(currentLineText, matchWidthParams())

        nextLineText = TextView(this).apply {
            setTextColor(Color.rgb(151, 155, 169))
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            alpha = 0.82f
            setPadding(dp(4), dp(3), dp(4), dp(7))
        }
        card.addView(nextLineText, matchWidthParams())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(controlButton("‹", "আগের lyric line").apply {
            setOnClickListener {
                if (lines.isNotEmpty()) {
                    val index = if (currentIndex == 0) lines.lastIndex else currentIndex - 1
                    showLine(index, animate = true)
                    restartAutoAdvance()
                }
            }
        }, controlParams())

        pauseButton = controlButton("Ⅱ", "Lyric animation pause করুন").apply {
            setOnClickListener { togglePaused() }
        }
        controls.addView(pauseButton, controlParams(horizontalMargin = 8))

        controls.addView(controlButton("›", "পরের lyric line").apply {
            setOnClickListener {
                if (lines.isNotEmpty()) {
                    showLine((currentIndex + 1) % lines.size, animate = true)
                    restartAutoAdvance()
                }
            }
        }, controlParams())
        card.addView(controls, matchWidthParams())

        return card
    }

    private fun showLine(newIndex: Int, animate: Boolean) {
        if (lines.isEmpty()) return
        currentIndex = newIndex.coerceIn(0, lines.lastIndex)
        val lyricView = currentLineText ?: return
        val generation = ++animationGeneration

        lyricView.animate().cancel()
        colorAnimator?.cancel()

        if (!animate || lyricView.text.isNullOrEmpty()) {
            applyLineContent(lyricView, generation, animateIncoming = false)
            return
        }

        lyricView.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(150L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (generation == animationGeneration) {
                    applyLineContent(lyricView, generation, animateIncoming = true)
                }
            }
            .start()
    }

    private fun applyLineContent(
        lyricView: TextView,
        generation: Int,
        animateIncoming: Boolean
    ) {
        if (generation != animationGeneration || lines.isEmpty()) return

        val oldColor = lyricView.currentTextColor
        val targetColor = ACTIVE_COLORS[currentIndex % ACTIVE_COLORS.size]
        lyricView.text = lines[currentIndex]
        previousLineText?.text = if (currentIndex > 0) lines[currentIndex - 1] else ""
        nextLineText?.text = if (currentIndex < lines.lastIndex) lines[currentIndex + 1] else ""

        colorAnimator = ValueAnimator.ofArgb(oldColor, targetColor).apply {
            duration = if (animateIncoming) 420L else 1L
            addUpdateListener { animator ->
                lyricView.setTextColor(animator.animatedValue as Int)
            }
            start()
        }

        if (animateIncoming) {
            lyricView.alpha = 0f
            lyricView.scaleX = 0.90f
            lyricView.scaleY = 0.90f
            lyricView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(360L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            lyricView.alpha = 1f
            lyricView.scaleX = 1f
            lyricView.scaleY = 1f
            lyricView.setTextColor(targetColor)
        }

        lyricView.announceForAccessibility(lines[currentIndex])
        updateNotification()
    }

    private fun togglePaused() {
        isPaused = !isPaused
        pauseButton?.text = if (isPaused) "▶" else "Ⅱ"
        pauseButton?.contentDescription = if (isPaused) "Lyric animation চালু করুন" else "Lyric animation pause করুন"
        if (isPaused) {
            mainHandler.removeCallbacks(autoAdvanceRunnable)
        } else {
            scheduleNextLine()
        }
        updateNotification()
    }

    private fun restartAutoAdvance() {
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        if (!isPaused) scheduleNextLine()
    }

    private fun scheduleNextLine() {
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        if (!isPaused && lines.size > 1) {
            mainHandler.postDelayed(autoAdvanceRunnable, intervalMillis)
        }
    }

    private fun attachDragListener(dragHandle: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            val params = overlayParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: preferences.getInt(PREF_WINDOW_X, 0)
                    initialY = params?.y ?: preferences.getInt(PREF_WINDOW_Y, dp(110))
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (params != null && overlayView != null) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceAtLeast(0)
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (params != null) {
                        preferences.edit()
                            .putInt(PREF_WINDOW_X, params.x)
                            .putInt(PREF_WINDOW_Y, params.y)
                            .apply()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun parseLyrics(raw: String): List<String> = raw
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .filterNot { line -> METADATA_PATTERN.matches(line) }
        .map { line -> TIMESTAMP_PATTERN.replace(line, "").trim() }
        .filter { line -> line.isNotEmpty() }
        .toList()

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
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags()
        )
        val previousIntent = servicePendingIntent(101, ACTION_PREVIOUS)
        val pauseIntent = servicePendingIntent(102, ACTION_TOGGLE_PAUSE)
        val nextIntent = servicePendingIntent(103, ACTION_NEXT)
        val stopIntent = servicePendingIntent(104, ACTION_STOP)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("Floating lyrics চালু আছে")
            .setContentText(lines.getOrNull(currentIndex) ?: "Lyrics overlay প্রস্তুত")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(Color.rgb(124, 77, 255))
            .addAction(R.drawable.ic_music_note, "আগের", previousIntent)
            .addAction(R.drawable.ic_music_note, if (isPaused) "চালু" else "Pause", pauseIntent)
            .addAction(R.drawable.ic_music_note, "পরের", nextIntent)
            .addAction(R.drawable.ic_music_note, "বন্ধ", stopIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Android 13+ can deny notification permission; the foreground service remains valid.
        }
    }

    private fun servicePendingIntent(requestCode: Int, actionName: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, LyricsOverlayService::class.java).setAction(actionName),
            pendingIntentFlags()
        )

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun controlButton(value: String, description: String) = TextView(this).apply {
        text = value
        contentDescription = description
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minWidth = dp(46)
        minHeight = dp(38)
        setPadding(dp(10), dp(2), dp(10), dp(2))
        background = roundedBackground(Color.argb(170, 52, 57, 72), 12f)
        isClickable = true
        isFocusable = true
    }

    private fun roundedBackground(color: Int, radiusDp: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun matchWidthParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun controlParams(horizontalMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = dp(horizontalMargin)
        marginEnd = dp(horizontalMargin)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "com.ahad.lyricsoverlay.action.START"
        const val ACTION_STOP = "com.ahad.lyricsoverlay.action.STOP"
        const val ACTION_PREVIOUS = "com.ahad.lyricsoverlay.action.PREVIOUS"
        const val ACTION_NEXT = "com.ahad.lyricsoverlay.action.NEXT"
        const val ACTION_TOGGLE_PAUSE = "com.ahad.lyricsoverlay.action.TOGGLE_PAUSE"

        const val EXTRA_LYRICS = "extra_lyrics"
        const val EXTRA_INTERVAL_MILLIS = "extra_interval_millis"

        const val PREFS_NAME = "lyrics_overlay_preferences"
        const val PREF_LYRICS = "lyrics"
        const val PREF_INTERVAL_MILLIS = "interval_millis"
        const val PREF_IS_RUNNING = "is_running"
        private const val PREF_WINDOW_X = "window_x"
        private const val PREF_WINDOW_Y = "window_y"

        private const val NOTIFICATION_CHANNEL_ID = "floating_lyrics_channel"
        private const val NOTIFICATION_ID = 2714
        private const val DEFAULT_INTERVAL_MILLIS = 4_500L
        private const val MIN_INTERVAL_MILLIS = 1_500L
        private const val MAX_INTERVAL_MILLIS = 30_000L

        private val TIMESTAMP_PATTERN = Regex("""\[(?:\d{1,2}:)?\d{1,2}(?:[.:]\d{1,3})?]""")
        private val METADATA_PATTERN = Regex("""^\[[A-Za-z]+:.*]$""")
        private val ACTIVE_COLORS = intArrayOf(
            Color.rgb(255, 255, 255),
            Color.rgb(190, 167, 255),
            Color.rgb(106, 220, 255),
            Color.rgb(255, 145, 199),
            Color.rgb(126, 238, 186),
            Color.rgb(255, 207, 110)
        )
    }
}
