package com.ahad.lyricsoverlay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private val preferences by lazy {
        getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var permissionStatus: TextView
    private lateinit var permissionButton: Button
    private lateinit var previewText: TextView
    private lateinit var fontSizeValue: TextView
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var fontStyleSpinner: Spinner
    private lateinit var animationSpinner: Spinner

    private var selectedColor: Int = OverlayService.DEFAULT_TEXT_COLOR
    private val colorViews = linkedMapOf<Int, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        window.statusBarColor = ContextCompat.getColor(this, R.color.background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.background)

        bindViews()
        setupPermissionControls()
        setupFontSize()
        setupFontStyle()
        setupColorPalette()
        setupAnimationStyle()
        setupResetPosition()
        applyPreview()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun bindViews() {
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        permissionStatus = findViewById(R.id.overlayPermissionStatus)
        permissionButton = findViewById(R.id.overlayPermissionButton)
        previewText = findViewById(R.id.previewText)
        fontSizeValue = findViewById(R.id.fontSizeValue)
        fontSizeSeekBar = findViewById(R.id.fontSizeSeekBar)
        fontStyleSpinner = findViewById(R.id.fontStyleSpinner)
        animationSpinner = findViewById(R.id.animationSpinner)
    }

    private fun setupPermissionControls() {
        permissionButton.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.overlay_permission_granted, Toast.LENGTH_SHORT).show()
            } else {
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
        }
    }

    private fun setupFontSize() {
        val initialSize = preferences.getFloat(
            OverlayService.PREF_FONT_SIZE,
            OverlayService.DEFAULT_FONT_SIZE
        ).coerceIn(OverlayService.MIN_FONT_SIZE, OverlayService.MAX_FONT_SIZE)
        fontSizeSeekBar.max = (OverlayService.MAX_FONT_SIZE - OverlayService.MIN_FONT_SIZE).toInt()
        fontSizeSeekBar.progress = (initialSize - OverlayService.MIN_FONT_SIZE).toInt()
        fontSizeValue.text = "${initialSize.toInt()} sp"

        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = OverlayService.MIN_FONT_SIZE + progress
                fontSizeValue.text = "$size sp"
                previewText.textSize = size
                if (fromUser) {
                    preferences.edit().putFloat(OverlayService.PREF_FONT_SIZE, size).apply()
                    notifyOverlayChanged()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupFontStyle() {
        val labels = listOf("Bold Sans", "Regular Sans", "Serif", "Monospace")
        val values = listOf(
            OverlayService.FONT_BOLD,
            OverlayService.FONT_REGULAR,
            OverlayService.FONT_SERIF,
            OverlayService.FONT_MONOSPACE
        )
        fontStyleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val saved = preferences.getString(OverlayService.PREF_FONT_STYLE, OverlayService.FONT_BOLD)
        fontStyleSpinner.setSelection(values.indexOf(saved).coerceAtLeast(0), false)
        fontStyleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                preferences.edit().putString(OverlayService.PREF_FONT_STYLE, values[position]).apply()
                applyPreview()
                notifyOverlayChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupColorPalette() {
        colorViews[Color.WHITE] = findViewById(R.id.colorWhite)
        colorViews[Color.rgb(190, 167, 255)] = findViewById(R.id.colorPurple)
        colorViews[Color.rgb(112, 216, 255)] = findViewById(R.id.colorBlue)
        colorViews[Color.rgb(255, 145, 199)] = findViewById(R.id.colorPink)
        colorViews[Color.rgb(111, 227, 180)] = findViewById(R.id.colorGreen)

        selectedColor = preferences.getInt(
            OverlayService.PREF_TEXT_COLOR,
            OverlayService.DEFAULT_TEXT_COLOR
        )
        colorViews.forEach { (color, view) ->
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener {
                selectedColor = color
                preferences.edit().putInt(OverlayService.PREF_TEXT_COLOR, color).apply()
                updateColorPalette()
                applyPreview()
                notifyOverlayChanged()
            }
        }
        updateColorPalette()
    }

    private fun setupAnimationStyle() {
        val labels = listOf("Fade", "Fade + scale", "Slide")
        val values = listOf(
            OverlayService.ANIMATION_FADE,
            OverlayService.ANIMATION_SCALE,
            OverlayService.ANIMATION_SLIDE
        )
        animationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val saved = preferences.getString(
            OverlayService.PREF_ANIMATION_STYLE,
            OverlayService.ANIMATION_SCALE
        )
        animationSpinner.setSelection(values.indexOf(saved).coerceAtLeast(0), false)
        animationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                preferences.edit()
                    .putString(OverlayService.PREF_ANIMATION_STYLE, values[position])
                    .apply()
                notifyOverlayChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupResetPosition() {
        findViewById<View>(R.id.resetPositionButton).setOnClickListener {
            preferences.edit()
                .remove(OverlayService.PREF_POSITION_X)
                .remove(OverlayService.PREF_POSITION_Y)
                .apply()
            notifyOverlayChanged()
            Toast.makeText(this, "Overlay position reset", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateColorPalette() {
        val selectedStroke = ContextCompat.getColor(this, R.color.primary_light)
        colorViews.forEach { (color, view) ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(if (color == selectedColor) 3 else 1), if (color == selectedColor) selectedStroke else Color.DKGRAY)
            }
            view.alpha = if (color == selectedColor) 1f else 0.68f
            view.scaleX = if (color == selectedColor) 1.08f else 1f
            view.scaleY = if (color == selectedColor) 1.08f else 1f
        }
    }

    private fun applyPreview() {
        val size = preferences.getFloat(
            OverlayService.PREF_FONT_SIZE,
            OverlayService.DEFAULT_FONT_SIZE
        )
        val style = preferences.getString(OverlayService.PREF_FONT_STYLE, OverlayService.FONT_BOLD)
        previewText.textSize = size
        previewText.setTextColor(selectedColor)
        previewText.typeface = when (style) {
            OverlayService.FONT_REGULAR -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            OverlayService.FONT_SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            OverlayService.FONT_MONOSPACE -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }

    private fun refreshPermissionState() {
        val granted = Settings.canDrawOverlays(this)
        permissionStatus.setText(
            if (granted) R.string.overlay_permission_granted
            else R.string.overlay_permission_description
        )
        permissionStatus.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.success else R.color.text_secondary)
        )
        permissionButton.setText(
            if (granted) R.string.overlay_permission_granted
            else R.string.open_overlay_permission
        )
        permissionButton.alpha = if (granted) 0.75f else 1f
    }

    private fun notifyOverlayChanged() {
        sendBroadcast(
            Intent(OverlayService.ACTION_SETTINGS_CHANGED).setPackage(packageName)
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
