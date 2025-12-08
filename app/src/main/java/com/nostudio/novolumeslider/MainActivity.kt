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
import android.app.NotificationManager
import android.util.Log

class MainActivity : AppCompatActivity() {

    private val PREFS_NAME = "AppPrefs"

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var dndPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var volumeStreamManager: VolumeStreamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val hideOverlayHandler = Handler(Looper.getMainLooper())
        val hideOverlayRunnable = Runnable { sendOverlayVisibilityUpdate(false) }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize volume stream manager
        volumeStreamManager = VolumeStreamManager(this)

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
        
        // Initialize DND permission launcher
        dndPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // Check if DND permission was granted
            if (volumeStreamManager.isDndAccessGranted()) {
                Toast.makeText(this, "Do Not Disturb access granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Do Not Disturb access is required for full functionality", Toast.LENGTH_SHORT).show()
            }
        }

        val overlayPermissionButton: Button = findViewById(R.id.overlayPermissionButton)
        overlayPermissionButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                startOverlayService()
            }
        }

        val accessibilitySettingsButton: Button = findViewById(R.id.accessibilitySettingsButton)
        accessibilitySettingsButton.setOnClickListener {
            val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(accessibilityIntent)
        }
        
        val dndPermissionButton: Button = findViewById(R.id.dndPermissionButton)
        dndPermissionButton.setOnClickListener {
            if (!volumeStreamManager.isDndAccessGranted()) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                dndPermissionLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Do Not Disturb access already granted", Toast.LENGTH_SHORT).show()
            }
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
        val hapticFeedbackSwitch: CustomAnimatedSwitch = findViewById(R.id.hapticToggleSwitch)

        // Retrieve saved haptic feedback setting (default enabled)
        val hapticEnabled = prefs.getBoolean("haptic_feedback_enabled", true)
        hapticFeedbackSwitch.setChecked(hapticEnabled, false)

        // Haptic feedback switch listener
        hapticFeedbackSwitch.onCheckedChangeListener = { isChecked ->
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
                if (hapticFeedbackSwitch.isChecked()) {
                    hapticFeedbackSwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Volume Number Display Switch
        val volumeNumberDisplaySwitch: CustomAnimatedSwitch = findViewById(R.id.volumeNumberDisplaySwitch)

        // Retrieve saved volume number display setting (default enabled)
        val volumeNumberEnabled = prefs.getBoolean("volume_number_display_enabled", true)
        volumeNumberDisplaySwitch.setChecked(volumeNumberEnabled, false)

        // Volume number display switch listener
        volumeNumberDisplaySwitch.onCheckedChangeListener = { isChecked ->
            prefs.edit().putBoolean("volume_number_display_enabled", isChecked).apply()

            // Send update to overlay service
            sendVolumeNumberDisplayUpdate(isChecked)

            // Provide haptic feedback when toggling the switch
            if (hapticFeedbackSwitch.isChecked()) {
                volumeNumberDisplaySwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        // Progress Bar Display Switch
        val progressBarDisplaySwitch: CustomAnimatedSwitch = findViewById(R.id.progressBarDisplaySwitch)

        // Retrieve saved progress bar display setting (default enabled)
        val progressBarEnabled = prefs.getBoolean("progress_bar_display_enabled", true)
        progressBarDisplaySwitch.setChecked(progressBarEnabled, false)

        // Progress bar display switch listener
        progressBarDisplaySwitch.onCheckedChangeListener = { isChecked ->
            prefs.edit().putBoolean("progress_bar_display_enabled", isChecked).apply()

            // Send update to overlay service
            sendProgressBarDisplayUpdate(isChecked)

            // Provide haptic feedback when toggling the switch
            if (hapticFeedbackSwitch.isChecked()) {
                progressBarDisplaySwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        // Volume Theme Button
        val volumeThemeButton: Button = findViewById(R.id.volumeThemeButton)
        
        // Initialize theme button text
        val currentTheme = prefs.getInt("volume_theme", 0)
        val themeText = when (currentTheme) {
            0 -> "Light"
            1 -> "Dark"
            2 -> "System"
            else -> "Light"
        }
        volumeThemeButton.text = themeText

        volumeThemeButton.setOnClickListener {
            val currentThemeValue = prefs.getInt("volume_theme", 0)
            val nextThemeValue = (currentThemeValue + 1) % 3 // Cycle through 0, 1, 2
            
            val (nextThemeText, nextTheme) = when (nextThemeValue) {
                0 -> "Light" to VolumeDialView.VolumeTheme.LIGHT
                1 -> "Dark" to VolumeDialView.VolumeTheme.DARK
                2 -> "System" to VolumeDialView.VolumeTheme.SYSTEM
                else -> "Light" to VolumeDialView.VolumeTheme.LIGHT
            }
            
            // Update button text
            volumeThemeButton.text = nextThemeText
            
            // Save preference
            prefs.edit().putInt("volume_theme", nextThemeValue).apply()
            
            // Update active overlay if it exists
            sendVolumeThemeUpdate(nextTheme)
            
            // Provide haptic feedback when toggling the theme
            if (hapticFeedbackSwitch.isChecked()) {
                volumeThemeButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            
            Log.d("MainActivity", "Volume theme updated: $nextThemeText")
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

    private fun sendVolumeThemeUpdate(theme: VolumeDialView.VolumeTheme) {
        val intent = Intent(this, VolumeOverlayService::class.java)
        intent.action = "UPDATE_VOLUME_THEME"
        val themeValue = when (theme) {
            VolumeDialView.VolumeTheme.LIGHT -> 0
            VolumeDialView.VolumeTheme.DARK -> 1
            VolumeDialView.VolumeTheme.SYSTEM -> 2
        }
        intent.putExtra("VOLUME_THEME", themeValue)
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
