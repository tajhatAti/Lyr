package com.ahad.lyricsoverlay

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat

class OverlayService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): OverlayService = this@OverlayService
    }

    private val binder = LocalBinder()
    private lateinit var windowManager: WindowManager
    private lateinit var preferences: android.content.SharedPreferences

    private var lyricTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lyrics: List<LrcLine> = emptyList()
    private var currentLineIndex = -1
    private var animationGeneration = 0
    private var colorAnimator: ValueAnimator? = null

    private var fontSizeSp = DEFAULT_FONT_SIZE
    private var fontStyle = FONT_BOLD
    private var preferredColor = DEFAULT_TEXT_COLOR
    private var animationStyle = ANIMATION_SCALE

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SETTINGS_CHANGED) {
                loadPreferences()
                applyTextAppearance()
                applySavedPosition()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPreferences()
        ContextCompat.registerReceiver(
            this,
            settingsReceiver,
            IntentFilter(ACTION_SETTINGS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        animationGeneration++
        colorAnimator?.cancel()
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered.
        }
        removeOverlay()
        super.onDestroy()
    }

    fun setLyrics(rawLrc: String?) {
        lyrics = rawLrc?.let(LrcParser::parse).orEmpty()
        currentLineIndex = -1
        animationGeneration++
        if (lyrics.isEmpty()) removeOverlay()
    }

    fun updatePlayback(positionMs: Long, isPlaying: Boolean) {
        if (lyrics.isEmpty()) {
            removeOverlay()
            return
        }

        val lineIndex = LrcParser.lineIndexAt(lyrics, positionMs)
        if (lineIndex < 0) {
            lyricTextView?.visibility = View.INVISIBLE
            currentLineIndex = -1
            return
        }

        if (lineIndex != currentLineIndex) {
            currentLineIndex = lineIndex
            showLine(lyrics[lineIndex].text, lineIndex)
        } else if (lyricTextView?.visibility != View.VISIBLE) {
            lyricTextView?.visibility = View.VISIBLE
        }

        // Pausing intentionally leaves the current line visible.
        @Suppress("UNUSED_VARIABLE")
        val playbackActive = isPlaying
    }

    fun clearLyrics() {
        lyrics = emptyList()
        currentLineIndex = -1
        removeOverlay()
    }

    private fun showLine(text: String, lineIndex: Int) {
        if (text.isBlank() || !Settings.canDrawOverlays(this)) return
        val textView = ensureOverlay() ?: return
        val generation = ++animationGeneration
        textView.animate().cancel()
        colorAnimator?.cancel()
        textView.visibility = View.VISIBLE

        if (textView.text.isNullOrEmpty() || textView.alpha == 0f) {
            setAndAnimateIn(textView, text, lineIndex, generation)
            return
        }

        val outgoing = textView.animate()
            .alpha(0f)
            .setDuration(150L)
            .setInterpolator(AccelerateDecelerateInterpolator())

        when (animationStyle) {
            ANIMATION_SLIDE -> outgoing.translationY(-dp(8f).toFloat()).scaleX(0.97f).scaleY(0.97f)
            ANIMATION_FADE -> outgoing.scaleX(0.97f).scaleY(0.97f)
            else -> outgoing.scaleX(0.88f).scaleY(0.88f)
        }

        outgoing.withEndAction {
            if (generation == animationGeneration) {
                setAndAnimateIn(textView, text, lineIndex, generation)
            }
        }.start()
    }

    private fun setAndAnimateIn(
        textView: TextView,
        text: String,
        lineIndex: Int,
        generation: Int
    ) {
        if (generation != animationGeneration) return

        val startColor = textView.currentTextColor
        val targetColor = colorVariantForLine(preferredColor, lineIndex)
        textView.text = text
        textView.alpha = 0f
        textView.visibility = View.VISIBLE

        when (animationStyle) {
            ANIMATION_SLIDE -> {
                textView.translationY = dp(10f).toFloat()
                textView.scaleX = 0.97f
                textView.scaleY = 0.97f
            }
            ANIMATION_FADE -> {
                textView.translationY = 0f
                textView.scaleX = 0.97f
                textView.scaleY = 0.97f
            }
            else -> {
                textView.translationY = 0f
                textView.scaleX = 0.88f
                textView.scaleY = 0.88f
            }
        }

        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor).apply {
            duration = 420L
            addUpdateListener { animator ->
                textView.setTextColor(animator.animatedValue as Int)
            }
            start()
        }

        textView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(360L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun ensureOverlay(): TextView? {
        lyricTextView?.let { return it }
        if (!Settings.canDrawOverlays(this)) return null

        val textView = TextView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            background = null
            gravity = Gravity.CENTER
            maxLines = 3
            maxWidth = resources.displayMetrics.widthPixels - dp(28f)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            includeFontPadding = false
            alpha = 0f
        }
        applyTextAppearance(textView)
        attachDragListener(textView)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX()
            y = savedY()
        }

        return try {
            windowManager.addView(textView, params)
            lyricTextView = textView
            layoutParams = params
            textView
        } catch (_: Exception) {
            lyricTextView = null
            layoutParams = null
            null
        }
    }

    private fun removeOverlay() {
        lyricTextView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // The overlay may already have been detached by the system.
            }
        }
        lyricTextView = null
        layoutParams = null
    }

    private fun attachDragListener(view: TextView) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            val params = layoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: savedX()
                    initialY = params?.y ?: savedY()
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (params != null) {
                        val maxX = (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
                        params.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, maxX)
                        params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, maxY)
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                            // Ignore a race with window removal.
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (params != null) {
                        preferences.edit()
                            .putInt(PREF_POSITION_X, params.x)
                            .putInt(PREF_POSITION_Y, params.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun loadPreferences() {
        fontSizeSp = preferences.getFloat(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        fontStyle = preferences.getString(PREF_FONT_STYLE, FONT_BOLD) ?: FONT_BOLD
        preferredColor = preferences.getInt(PREF_TEXT_COLOR, DEFAULT_TEXT_COLOR)
        animationStyle = preferences.getString(PREF_ANIMATION_STYLE, ANIMATION_SCALE)
            ?: ANIMATION_SCALE
    }

    private fun applyTextAppearance(textView: TextView? = lyricTextView) {
        textView ?: return
        textView.textSize = fontSizeSp
        textView.typeface = when (fontStyle) {
            FONT_REGULAR -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            FONT_SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            FONT_MONOSPACE -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        textView.setTextColor(preferredColor)
    }

    private fun applySavedPosition() {
        val view = lyricTextView ?: return
        val params = layoutParams ?: return
        params.x = savedX()
        params.y = savedY()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignore a race with window removal.
        }
    }

    private fun savedX(): Int = preferences.getInt(PREF_POSITION_X, dp(24f))

    private fun savedY(): Int = preferences.getInt(
        PREF_POSITION_Y,
        (resources.displayMetrics.heightPixels * 0.68f).toInt()
    )

    private fun colorVariantForLine(baseColor: Int, index: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        if (hsv[1] < 0.08f) {
            hsv[0] = floatArrayOf(260f, 205f, 325f, 155f)[index % 4]
            hsv[1] = 0.12f
            hsv[2] = 1f
        } else {
            hsv[0] = (hsv[0] + floatArrayOf(-7f, 0f, 7f)[index % 3] + 360f) % 360f
            hsv[2] = (hsv[2] * if (index % 2 == 0) 1f else 0.92f).coerceIn(0.55f, 1f)
        }
        return Color.HSVToColor(Color.alpha(baseColor), hsv)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "lyrics_overlay_settings"
        const val PREF_FONT_SIZE = "font_size"
        const val PREF_FONT_STYLE = "font_style"
        const val PREF_TEXT_COLOR = "text_color"
        const val PREF_ANIMATION_STYLE = "animation_style"
        const val PREF_POSITION_X = "position_x"
        const val PREF_POSITION_Y = "position_y"

        const val FONT_REGULAR = "regular"
        const val FONT_BOLD = "bold"
        const val FONT_SERIF = "serif"
        const val FONT_MONOSPACE = "monospace"

        const val ANIMATION_FADE = "fade"
        const val ANIMATION_SCALE = "scale"
        const val ANIMATION_SLIDE = "slide"

        const val ACTION_SETTINGS_CHANGED = "com.ahad.lyricsoverlay.SETTINGS_CHANGED"

        const val DEFAULT_FONT_SIZE = 24f
        const val MIN_FONT_SIZE = 14f
        const val MAX_FONT_SIZE = 42f
        val DEFAULT_TEXT_COLOR: Int = Color.WHITE
    }
}
