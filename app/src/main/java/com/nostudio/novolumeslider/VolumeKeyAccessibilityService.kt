package com.nostudio.novolumeslider

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class VolumeKeyAccessibilityService : AccessibilityService() {
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var isVolumeKeyPressed = false
    private val volumeChangeInterval = 100L // Adjust interval as needed
    private var isVolumeUp = false // Track which key is pressed

    private val adjustVolumeRunnable = object : Runnable {
        override fun run() {
            if (isVolumeKeyPressed) {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val newVolume = if (isVolumeUp) {
                    minOf(currentVolume + 1, maxVolume)
                } else {
                    maxOf(currentVolume - 1, 0)
                }
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                startOverlayWithVolume(newVolume)
                handler.postDelayed(this, volumeChangeInterval)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d("VolumeKeyAccessibilityService", "Service created and audio manager initialized")
    }

    private fun startOverlayWithVolume(volume: Int) {
        val serviceIntent = Intent(this, VolumeOverlayService::class.java).apply {
            putExtra("CURRENT_VOLUME", volume)
            putExtra("MAX_VOLUME", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d("VolumeKeyAccessibilityService", "Overlay service started with volume: $volume")
    }

    override fun onInterrupt() {
        Log.d("VolumeKeyAccessibilityService", "Service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        Log.d("VolumeKeyAccessibilityService", "Volume UP pressed")
                        if (!isVolumeKeyPressed) {
                            isVolumeKeyPressed = true
                            isVolumeUp = true
                            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val newVolume = minOf(currentVolume + 1, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            startOverlayWithVolume(newVolume)
                            handler.postDelayed(adjustVolumeRunnable, volumeChangeInterval)
                        }
                        return true // Prevent system volume UI from showing
                    }

                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        Log.d("VolumeKeyAccessibilityService", "Volume DOWN pressed")
                        if (!isVolumeKeyPressed) {
                            isVolumeKeyPressed = true
                            isVolumeUp = false
                            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val newVolume = maxOf(currentVolume - 1, 0)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            startOverlayWithVolume(newVolume)
                            handler.postDelayed(adjustVolumeRunnable, volumeChangeInterval)
                        }
                        return true // Prevent system volume UI from showing
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        Log.d("VolumeKeyAccessibilityService", "Volume key released")
                        isVolumeKeyPressed = false
                        return true // Prevent system volume UI from showing
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }



    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configure service to receive key events
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info

        Log.d("VolumeKeyAccessibilityService", "Accessibility service connected and configured for key events")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for volume key handling
    }
}