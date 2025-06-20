package com.nostudio.novolumeslider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import kotlin.math.roundToInt
import android.widget.SeekBar
import android.text.TextWatcher
import android.widget.Switch
import android.view.HapticFeedbackConstants
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import android.os.Looper

class MainActivity : AppCompatActivity() {

    private val PREFS_NAME = "AppPrefs"

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        val hideOverlayHandler = Handler(Looper.getMainLooper())
        val hideOverlayRunnable = Runnable { sendOverlayVisibilityUpdate(false) }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize modern activity result launcher
        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // Check if overlay permission was granted
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
            }
        }

        val overlayPermissionButton: Button = findViewById(R.id.overlayPermissionButton)
        overlayPermissionButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent) // Use modern API
            } else {
                startOverlayService()
            }
        }

        val accessibilitySettingsButton: Button = findViewById(R.id.accessibilitySettingsButton)
        accessibilitySettingsButton.setOnClickListener {
            val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(accessibilityIntent)
        }

        val positionSlider: SeekBar = findViewById(R.id.overlayPositionSlider)

        positionSlider.max = 2000

        // Retrieve saved position from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPosition = prefs.getInt("overlay_position", 1000) // Default to 499

        positionSlider.progress = savedPosition

        // Slider Listener
        positionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sendOverlayVisibilityUpdate(true)
                sendOverlayPositionUpdate(progress)

                // Save the position to SharedPreferences
                prefs.edit().putInt("overlay_position", progress).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
                hideOverlayHandler.postDelayed(hideOverlayRunnable, 1000)
            }
        })

        // Wheel Size Slider
        val wheelSizeSlider: SeekBar = findViewById(R.id.wheelSizeSlider)
        wheelSizeSlider.max = 100

        // Retrieve saved wheel size from SharedPreferences (default 75% for more shrinking)
        val savedWheelSize = prefs.getInt("wheel_size", 75)
        wheelSizeSlider.progress = savedWheelSize

        // Wheel size slider listener
        wheelSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Convert progress (0-100) to scale factor (0.6-1.1) - more shrinking than enlarging
                val scaleFactor = 0.6f + (progress / 100f) * 0.5f
                prefs.edit().putFloat("wheel_scale_factor", scaleFactor).apply()
                prefs.edit().putInt("wheel_size", progress).apply()

                // Update overlay service with new size
                sendWheelSizeUpdate(scaleFactor)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Haptic Feedback Switch (now in the toggle row)
        val hapticFeedbackSwitch: Switch = findViewById(R.id.hapticToggleSwitch)

        // Retrieve saved haptic feedback setting (default enabled)
        val hapticEnabled = prefs.getBoolean("haptic_feedback_enabled", true)
        hapticFeedbackSwitch.isChecked = hapticEnabled

        // Haptic feedback switch listener
        hapticFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("haptic_feedback_enabled", isChecked).apply()

            // Send update to overlay service
            sendHapticFeedbackUpdate(isChecked)

            // Provide haptic feedback when toggling the switch
            if (isChecked) {
                hapticFeedbackSwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        // Haptic Strength Slider
        val hapticStrengthSlider: SeekBar = findViewById(R.id.hapticStrengthSlider)
        hapticStrengthSlider.max = 2 // 0=Low, 1=Medium, 2=High

        // Retrieve saved haptic strength (default Medium = 1)
        val savedHapticStrength = prefs.getInt("haptic_strength", 1)
        hapticStrengthSlider.progress = savedHapticStrength

        // Haptic strength slider listener
        hapticStrengthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("haptic_strength", progress).apply()

                // Send update to overlay service
                sendHapticStrengthUpdate(progress)

                // Provide haptic feedback when changing strength
                if (hapticFeedbackSwitch.isChecked) {
                    hapticFeedbackSwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Volume Number Display Switch
        val volumeNumberDisplaySwitch: Switch = findViewById(R.id.volumeNumberDisplaySwitch)

        // Retrieve saved volume number display setting (default enabled)
        val volumeNumberEnabled = prefs.getBoolean("volume_number_display_enabled", true)
        volumeNumberDisplaySwitch.isChecked = volumeNumberEnabled

        // Volume number display switch listener
        volumeNumberDisplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("volume_number_display_enabled", isChecked).apply()

            // Send update to overlay service
            sendVolumeNumberDisplayUpdate(isChecked)

            // Provide haptic feedback when toggling the switch
            if (hapticFeedbackSwitch.isChecked) {
                volumeNumberDisplaySwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        // Progress Bar Display Switch
        val progressBarDisplaySwitch: Switch = findViewById(R.id.progressBarDisplaySwitch)

        // Retrieve saved progress bar display setting (default enabled)
        val progressBarEnabled = prefs.getBoolean("progress_bar_display_enabled", true)
        progressBarDisplaySwitch.isChecked = progressBarEnabled

        // Progress bar display switch listener
        progressBarDisplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("progress_bar_display_enabled", isChecked).apply()

            // Send update to overlay service
            sendProgressBarDisplayUpdate(isChecked)

            // Provide haptic feedback when toggling the switch
            if (hapticFeedbackSwitch.isChecked) {
                progressBarDisplaySwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        // EditText Listener

    }

    private fun sendOverlayVisibilityUpdate(isVisible: Boolean) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = if (isVisible) "SHOW_OVERLAY" else "HIDE_OVERLAY"
        startService(intent)
    }

    private fun sendOverlayPositionUpdate(position: Int) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = "UPDATE_OVERLAY_POSITION"
        intent.putExtra("POSITION", position)
        startService(intent)
    }

    private fun sendWheelSizeUpdate(scaleFactor: Float) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = "UPDATE_WHEEL_SIZE"
        intent.putExtra("SCALE_FACTOR", scaleFactor)
        startService(intent)
    }

    private fun sendHapticFeedbackUpdate(isEnabled: Boolean) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = "UPDATE_HAPTIC_FEEDBACK"
        intent.putExtra("HAPTIC_FEEDBACK_ENABLED", isEnabled)
        startService(intent)
    }

    private fun sendHapticStrengthUpdate(strength: Int) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = "UPDATE_HAPTIC_STRENGTH"
        intent.putExtra("HAPTIC_STRENGTH", strength)
        startService(intent)
    }

    private fun sendVolumeNumberDisplayUpdate(isEnabled: Boolean) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = "UPDATE_VOLUME_NUMBER_DISPLAY"
        intent.putExtra("VOLUME_NUMBER_DISPLAY_ENABLED", isEnabled)
        startService(intent)
    }

    private fun sendProgressBarDisplayUpdate(isEnabled: Boolean) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = "UPDATE_PROGRESS_BAR_DISPLAY"
        intent.putExtra("PROGRESS_BAR_DISPLAY_ENABLED", isEnabled)
        startService(intent)
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, VolumeOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

}
