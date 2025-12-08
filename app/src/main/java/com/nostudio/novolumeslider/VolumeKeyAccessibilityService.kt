package com.nostudio.novolumeslider

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class VolumeKeyAccessibilityService : AccessibilityService() {
    
    private lateinit var volumeStreamManager: VolumeStreamManager
    private val handler = Handler(Looper.getMainLooper())
    private var isVolumeKeyPressed = false
    private val volumeChangeInterval = 100L
    private var isVolumeUp = false

    private val adjustVolumeRunnable = object : Runnable {
        override fun run() {
            if (isVolumeKeyPressed) {
                volumeStreamManager.handleVolumeKeyPress(isVolumeUp)
                
                val activeStream = volumeStreamManager.getActiveStream()
                val volumePercentage = volumeStreamManager.getStreamVolumePercentage(activeStream)
                
                startOverlayWithVolume(volumePercentage, activeStream)
                handler.postDelayed(this, volumeChangeInterval)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        volumeStreamManager = VolumeStreamManager(this)
        Log.d("VolumeKeyAccessibilityService", "Service created")
    }

    private fun startOverlayWithVolume(volumePercentage: Int, activeStream: VolumeStreamManager.VolumeStream) {
        val serviceIntent = Intent(this, VolumeOverlayService::class.java).apply {
            putExtra("CURRENT_VOLUME", volumePercentage)
            putExtra("ACTIVE_STREAM", activeStream.name)
            putExtra("STREAM_TYPE", activeStream.streamType)
            putExtra("MAX_VOLUME", volumeStreamManager.getStreamMaxVolume(activeStream))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onInterrupt() {
        isVolumeKeyPressed = false
        handler.removeCallbacks(adjustVolumeRunnable)
        Log.d("VolumeKeyAccessibilityService", "Service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        if (!isVolumeKeyPressed) {
                            handleVolumeKeyDown(true)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (!isVolumeKeyPressed) {
                            handleVolumeKeyDown(false)
                        }
                        return true
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        isVolumeKeyPressed = false
                        handler.removeCallbacks(adjustVolumeRunnable)
                        return true
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun handleVolumeKeyDown(isUp: Boolean) {
        isVolumeKeyPressed = true
        isVolumeUp = isUp
        
        // CRITICAL FIX: Check for both centered and selected streams to handle volume keys correctly
        // Priority: 1) Centered stream (fully centered button), 2) Selected stream (button clicked but not centered), 3) Contextual stream
        val centeredStream = getCenteredButtonStream()
        val selectedStream = getSelectedButtonStream()
        
        val activeStream = when {
            centeredStream != null -> {
                // If a button is centered, use that stream (highest priority)
                volumeStreamManager.setActiveStream(centeredStream)
                centeredStream
            }
            selectedStream != null -> {
                // If a button is selected but not centered, use that stream
                volumeStreamManager.setActiveStream(selectedStream)
                selectedStream
            }
            else -> {
                // Fallback to contextual stream (voice call or media)
                val inVoiceCall = volumeStreamManager.isInVoiceCall()
                val contextualStream = if (inVoiceCall) {
                    VolumeStreamManager.VolumeStream.CALL
                } else {
                    VolumeStreamManager.VolumeStream.MEDIA
                }
                volumeStreamManager.setActiveStream(contextualStream)
                contextualStream
            }
        }
        
        volumeStreamManager.handleVolumeKeyPress(isUp)
        
        val volumePercentage = volumeStreamManager.getStreamVolumePercentage(activeStream)
        startOverlayWithVolume(volumePercentage, activeStream)
        handler.postDelayed(adjustVolumeRunnable, volumeChangeInterval)
    }

    private fun getCenteredButtonStream(): VolumeStreamManager.VolumeStream? {
        // Check if the overlay service has a centered button via shared preferences
        try {
            val prefs = getSharedPreferences("VolumeOverlayState", android.content.Context.MODE_PRIVATE)
            val centeredStreamName = prefs.getString("centered_stream", null)
            return if (centeredStreamName != null) {
                VolumeStreamManager.VolumeStream.valueOf(centeredStreamName)
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun getSelectedButtonStream(): VolumeStreamManager.VolumeStream? {
        // Check if the overlay service has a selected button via shared preferences
        try {
            val prefs = getSharedPreferences("VolumeOverlayState", android.content.Context.MODE_PRIVATE)
            val selectedStreamName = prefs.getString("selected_stream", null)
            return if (selectedStreamName != null) {
                VolumeStreamManager.VolumeStream.valueOf(selectedStreamName)
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info

        Log.d("VolumeKeyAccessibilityService", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for volume key handling
    }

    override fun onDestroy() {
        handler.removeCallbacks(adjustVolumeRunnable)
        super.onDestroy()
    }
}