package com.ahad.lyricsoverlay

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var permissionButton: Button
    private lateinit var startButton: Button
    private lateinit var lyricsInput: EditText
    private lateinit var intervalInput: EditText

    private val preferences by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var startAfterOverlayPermission = false
    private var startAfterNotificationPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        buildScreen()
        restoreEditorState()
    }

    override fun onResume() {
        super.onResume()

        if (startAfterOverlayPermission && Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = false
            requestNotificationPermissionAndStart()
        }
        refreshStatus()
    }

    override fun onPause() {
        saveEditorState()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION && startAfterNotificationPermission) {
            // A foreground service may still run when notification permission is denied;
            // Android will show it in the active-apps area instead of the notification drawer.
            startAfterNotificationPermission = false
            startOverlayService()
        }
    }

    private fun buildScreen() {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(COLOR_BACKGROUND)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "Floating Lyrics"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "অন্য অ্যাপের উপর animated lyrics দেখান"
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(space(24))

        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(COLOR_CARD, 16f)
        }
        statusCard.addView(TextView(this).apply {
            text = "OVERLAY STATUS"
            setTextColor(COLOR_PURPLE_LIGHT)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(5), 0, 0)
        }
        statusCard.addView(statusText)
        content.addView(
            statusCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(space(12))

        permissionButton = primaryButton("Overlay permission দিন").apply {
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Overlay permission আগে থেকেই দেওয়া আছে",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    openOverlayPermission(startWhenGranted = false)
                }
            }
        }
        content.addView(permissionButton, matchWidthParams())
        content.addView(space(24))

        content.addView(sectionLabel("লিরিক্স"))
        content.addView(TextView(this).apply {
            text = "প্রতি লাইনে একটি lyric লিখুন। LRC timestamp থাকলে সেটি স্বয়ংক্রিয়ভাবে বাদ যাবে।"
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 13f
            setPadding(0, dp(5), 0, dp(10))
        })

        lyricsInput = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(COLOR_TEXT_MUTED)
            hint = "প্রতি লাইনে একটি lyric…"
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            maxLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(COLOR_INPUT, 14f, COLOR_BORDER)
        }
        content.addView(lyricsInput, matchWidthParams())
        content.addView(space(18))

        content.addView(sectionLabel("লাইন পরিবর্তনের সময় (সেকেন্ড)"))
        intervalInput = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(COLOR_TEXT_MUTED)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = roundedBackground(COLOR_INPUT, 14f, COLOR_BORDER)
        }
        content.addView(intervalInput, matchWidthParams())
        content.addView(space(22))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        startButton = primaryButton("▶  Overlay চালু").apply {
            setOnClickListener { handleStartClick() }
        }
        val stopButton = secondaryButton("■  বন্ধ করুন").apply {
            setOnClickListener {
                stopService(Intent(this@MainActivity, LyricsOverlayService::class.java))
                preferences.edit().putBoolean(LyricsOverlayService.PREF_IS_RUNNING, false).apply()
                refreshStatus()
                Toast.makeText(this@MainActivity, "Overlay বন্ধ হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(startButton, weightedParams(endMargin = 6))
        actionRow.addView(stopButton, weightedParams(startMargin = 6))
        content.addView(actionRow, matchWidthParams())
        content.addView(space(18))

        content.addView(TextView(this).apply {
            text = "টিপ: Floating card-এর উপরের অংশ ধরে drag করুন। ‹ / › দিয়ে line বদলান, আর মাঝের button দিয়ে pause/resume করুন।"
            setTextColor(COLOR_TEXT_MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
        })

        scrollView.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scrollView)
    }

    private fun handleStartClick() {
        saveEditorState()
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission(startWhenGranted = true)
            return
        }
        requestNotificationPermissionAndStart()
    }

    private fun requestNotificationPermissionAndStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startAfterNotificationPermission = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val rawLyrics = lyricsInput.text.toString().ifBlank { DEFAULT_LYRICS }
        val intervalMillis = ((intervalInput.text.toString().toDoubleOrNull() ?: 4.5) * 1_000)
            .toLong()
            .coerceIn(1_500L, 30_000L)

        val serviceIntent = Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_START
            putExtra(LyricsOverlayService.EXTRA_LYRICS, rawLyrics)
            putExtra(LyricsOverlayService.EXTRA_INTERVAL_MILLIS, intervalMillis)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        preferences.edit()
            .putString(LyricsOverlayService.PREF_LYRICS, rawLyrics)
            .putLong(LyricsOverlayService.PREF_INTERVAL_MILLIS, intervalMillis)
            .apply()

        Toast.makeText(this, "Floating lyrics চালু হয়েছে", Toast.LENGTH_SHORT).show()
        statusText.postDelayed({ refreshStatus() }, 250)
    }

    private fun openOverlayPermission(startWhenGranted: Boolean) {
        startAfterOverlayPermission = startWhenGranted
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun refreshStatus() {
        val hasPermission = Settings.canDrawOverlays(this)
        val isRunning = preferences.getBoolean(LyricsOverlayService.PREF_IS_RUNNING, false)

        statusText.text = when {
            !hasPermission -> "Permission প্রয়োজন"
            isRunning -> "চালু আছে • অন্য অ্যাপ খুলে দেখুন"
            else -> "Ready • permission দেওয়া আছে"
        }
        statusCard.background = roundedBackground(
            when {
                !hasPermission -> COLOR_WARNING_CARD
                isRunning -> COLOR_RUNNING_CARD
                else -> COLOR_CARD
            },
            16f
        )
        permissionButton.text = if (hasPermission) "✓ Overlay permission দেওয়া আছে" else "Overlay permission দিন"
        permissionButton.alpha = if (hasPermission) 0.72f else 1f
        startButton.isEnabled = hasPermission
        startButton.alpha = if (hasPermission) 1f else 0.55f
    }

    private fun restoreEditorState() {
        lyricsInput.setText(preferences.getString(LyricsOverlayService.PREF_LYRICS, DEFAULT_LYRICS))
        val savedInterval = preferences.getLong(LyricsOverlayService.PREF_INTERVAL_MILLIS, 4_500L)
        intervalInput.setText(formatSeconds(savedInterval))
    }

    private fun saveEditorState() {
        if (!::lyricsInput.isInitialized || !::intervalInput.isInitialized) return
        val interval = ((intervalInput.text.toString().toDoubleOrNull() ?: 4.5) * 1_000)
            .toLong()
            .coerceIn(1_500L, 30_000L)
        preferences.edit()
            .putString(LyricsOverlayService.PREF_LYRICS, lyricsInput.text.toString())
            .putLong(LyricsOverlayService.PREF_INTERVAL_MILLIS, interval)
            .apply()
    }

    private fun formatSeconds(milliseconds: Long): String {
        val seconds = milliseconds / 1_000.0
        return if (seconds % 1.0 == 0.0) seconds.toInt().toString() else seconds.toString()
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun primaryButton(label: String) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        backgroundTintList = ColorStateList.valueOf(COLOR_PURPLE)
        minHeight = dp(52)
    }

    private fun secondaryButton(label: String) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        backgroundTintList = ColorStateList.valueOf(COLOR_CARD_LIGHT)
        minHeight = dp(52)
    }

    private fun roundedBackground(color: Int, radiusDp: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun space(heightDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun matchWidthParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weightedParams(startMargin: Int = 0, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(startMargin)
            marginEnd = dp(endMargin)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 2001

        private val COLOR_BACKGROUND = Color.rgb(16, 19, 26)
        private val COLOR_CARD = Color.rgb(30, 34, 46)
        private val COLOR_CARD_LIGHT = Color.rgb(48, 53, 68)
        private val COLOR_INPUT = Color.rgb(25, 29, 39)
        private val COLOR_BORDER = Color.rgb(58, 64, 82)
        private val COLOR_PURPLE = Color.rgb(124, 77, 255)
        private val COLOR_PURPLE_LIGHT = Color.rgb(190, 167, 255)
        private val COLOR_TEXT_SECONDARY = Color.rgb(184, 188, 201)
        private val COLOR_TEXT_MUTED = Color.rgb(132, 138, 156)
        private val COLOR_WARNING_CARD = Color.rgb(66, 47, 32)
        private val COLOR_RUNNING_CARD = Color.rgb(25, 62, 52)

        private val DEFAULT_LYRICS = """
            রাতের আকাশ জুড়ে যত তারা
            তোমার নামে জ্বলে তারা
            Every beat becomes a light
            Floating gently through the night
            শব্দগুলো রঙ বদলে যায়
            গানটি তবু থেকে যায়
        """.trimIndent()
    }
}
