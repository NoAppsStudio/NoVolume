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

        positionSlider.max = 999

        // Retrieve saved position from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPosition = prefs.getInt("overlay_position", 499) // Default to 499

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

        val sensitivitySlider: SeekBar = findViewById(R.id.volumeSensitivitySlider)

        sensitivitySlider.max = 90

        val savedSensitivityProgress = prefs.getInt("volume_sensitivity_progress", 45).also {
            // Ensure initial sensitivity is stored as float
            if (!prefs.contains("volume_sensitivity")) {
                val initialSensitivity = (it / 90f * 0.5f + 0.1f).toFloat()
                prefs.edit().putFloat("volume_sensitivity", initialSensitivity).apply()
            }
        }

        sensitivitySlider.progress = savedSensitivityProgress

        // Sensitivity slider listener
        sensitivitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivity = progress / 90f * 0.5f + 0.1f
                prefs.edit().putFloat("volume_sensitivity", sensitivity).apply()
                prefs.edit().putInt("volume_sensitivity_progress", progress).apply() // Save progress
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
