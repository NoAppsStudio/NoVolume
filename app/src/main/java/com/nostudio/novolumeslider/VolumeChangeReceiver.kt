package com.nostudio.novolumeslider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log

class VolumeChangeReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.media.VOLUME_CHANGED_ACTION" -> {
                handleVolumeChange(context, intent)
            }
            "android.media.RINGER_MODE_CHANGED" -> {
                handleRingerModeChange(context, intent)
            }
            "android.app.action.INTERRUPTION_FILTER_CHANGED" -> {
                handleDndModeChange(context, intent)
            }
        }
    }
    
    private fun handleVolumeChange(context: Context, intent: Intent) {
        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
        val volume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
        val prevVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
        
        if (streamType != -1 && volume != -1) {
            Log.d("VolumeChangeReceiver", "Volume changed - Stream: $streamType, Volume: $volume, Previous: $prevVolume")
            
            val serviceIntent = Intent(context, VolumeOverlayService::class.java).apply {
                action = "VOLUME_CHANGED"
                putExtra("STREAM_TYPE", streamType)
                putExtra("CURRENT_VOLUME", volume)
                putExtra("PREVIOUS_VOLUME", prevVolume)
            }
            
            startServiceSafely(context, serviceIntent)
        }
    }
    
    private fun handleRingerModeChange(context: Context, intent: Intent) {
        val ringerMode = intent.getIntExtra("android.media.EXTRA_RINGER_MODE", -1)
        
        if (ringerMode != -1) {
            Log.d("VolumeChangeReceiver", "Ringer mode changed: $ringerMode")
            
            val serviceIntent = Intent(context, VolumeOverlayService::class.java).apply {
                action = "RINGER_MODE_CHANGED"
                putExtra("RINGER_MODE", ringerMode)
            }
            
            startServiceSafely(context, serviceIntent)
        }
    }
    
    private fun handleDndModeChange(context: Context, intent: Intent) {
        Log.d("VolumeChangeReceiver", "Do Not Disturb mode changed")
        
        val serviceIntent = Intent(context, VolumeOverlayService::class.java).apply {
            action = "DND_MODE_CHANGED"
        }
        
        startServiceSafely(context, serviceIntent)
    }
    
    private fun startServiceSafely(context: Context, serviceIntent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("VolumeChangeReceiver", "Error starting service: ${e.message}")
        }
    }
} 