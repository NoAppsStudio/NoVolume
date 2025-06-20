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

class MainActivity : AppCompatActivity() {

    private val PREFS_NAME = "AppPrefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        val hideOverlayHandler = Handler()
        val hideOverlayRunnable = Runnable { sendOverlayVisibilityUpdate(false) }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val overlayPermissionButton: Button = findViewById(R.id.overlayPermissionButton)
        overlayPermissionButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 100) // Request overlay permission
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

        // Haptic Feedback Switch
        val hapticFeedbackSwitch: Switch = findViewById(R.id.hapticFeedbackSwitch)

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

    private fun startOverlayService() {
        val serviceIntent = Intent(this, VolumeOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100) { // Overlay permission request code
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
