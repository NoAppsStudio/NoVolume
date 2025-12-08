package com.nostudio.novolumeslider

import android.content.Context
import android.media.AudioManager
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.content.Intent

/**
 * Comprehensive volume stream manager that handles all Android audio streams
 * and provides proper system integration including Do Not Disturb functionality.
 * 
 * IMPORTANT FIX: This implementation includes safeguards to prevent unwanted ringer mode
 * changes when setting non-ring volume streams (MEDIA, ALARM, NOTIFICATION, SYSTEM, CALL).
 * The Android AudioManager can sometimes incorrectly link volume streams, causing ringer
 * volume to be affected when it shouldn't be. This class prevents such cross-stream interference.
 */
class VolumeStreamManager(private val context: Context) {
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    // Audio stream types and their corresponding information
    enum class VolumeStream(
        val streamType: Int,
        val displayName: String,
        val shortName: String,
        val iconRes: Int
    ) {
        MEDIA(AudioManager.STREAM_MUSIC, "Media", "M", R.drawable.volume_media_0),
        RING(AudioManager.STREAM_RING, "Ring", "R", R.drawable.volume_ring_0),
        NOTIFICATION(AudioManager.STREAM_NOTIFICATION, "Notification", "N", R.drawable.volume_ring_0),
        ALARM(AudioManager.STREAM_ALARM, "Alarm", "A", R.drawable.volume_alarm),
        CALL(AudioManager.STREAM_VOICE_CALL, "Call", "C", R.drawable.volume_voice_call),
        SYSTEM(AudioManager.STREAM_SYSTEM, "System", "S", R.drawable.volume_system)
    }
    
    // Current active stream for volume control
    private var currentActiveStream = VolumeStream.MEDIA
    
    // Reference to VolumeDialView for volume restoration
    private var volumeDialView: Any? = null // Using Any to avoid circular dependency
    
    // Track which streams were muted via quick action vs manually set to 0
    private val quickActionMutedStreams = mutableSetOf<VolumeStream>()
    
    // Listeners for volume changes
    interface VolumeChangeListener {
        fun onVolumeChanged(stream: VolumeStream, volume: Int, maxVolume: Int)
        fun onRingerModeChanged(ringerMode: Int)
        fun onDndModeChanged(dndMode: Int)
    }
    
    private val listeners = mutableListOf<VolumeChangeListener>()
    
    fun addVolumeChangeListener(listener: VolumeChangeListener) {
        listeners.add(listener)
    }
    
    fun removeVolumeChangeListener(listener: VolumeChangeListener) {
        listeners.remove(listener)
    }
    
    /**
     * Set the currently active volume stream
     */
    fun setActiveStream(stream: VolumeStream) {
        currentActiveStream = stream
        Log.d("VolumeStreamManager", "Active stream changed to: ${stream.displayName}")
    }
    
    /**
     * Get the currently active volume stream
     */
    fun getActiveStream(): VolumeStream = currentActiveStream
    
    /**
     * Get current volume for a specific stream
     */
    fun getStreamVolume(stream: VolumeStream): Int {
        return audioManager.getStreamVolume(stream.streamType)
    }
    
    /**
     * Get maximum volume for a specific stream
     */
    fun getStreamMaxVolume(stream: VolumeStream): Int {
        return audioManager.getStreamMaxVolume(stream.streamType)
    }
    
    /**
     * Get current volume as percentage (0-100) for a specific stream
     */
    fun getStreamVolumePercentage(stream: VolumeStream): Int {
        val current = getStreamVolume(stream)
        val max = getStreamMaxVolume(stream)
        return if (max > 0) (current * 100) / max else 0
    }
    
    /**
     * Set volume for a specific stream
     */
    fun setStreamVolume(stream: VolumeStream, volume: Int, showUI: Boolean = false) {
        // CRITICAL: Use different flags for different streams to prevent unwanted ringer mode changes
        val flags = when (stream) {
            VolumeStream.RING -> {
                // Only for ring stream, allow ringer mode changes when volume reaches 0
                if (showUI) {
                    AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_ALLOW_RINGER_MODES
                } else {
                    AudioManager.FLAG_ALLOW_RINGER_MODES
                }
            }
            else -> {
                // For all other streams (notification, alarm, media, system, call), 
                // NEVER use FLAG_ALLOW_RINGER_MODES to prevent ringer mode changes
                if (showUI) {
                    AudioManager.FLAG_SHOW_UI
                } else {
                    0 // No flags - this prevents ringer mode changes
                }
            }
        }
        
        val clampedVolume = volume.coerceIn(0, getStreamMaxVolume(stream))
        
        try {
            // Store current ringer mode before volume change to detect unwanted changes
            val originalRingerMode = audioManager.ringerMode
            
            performVolumeChange(stream, clampedVolume, flags)
            
            // Check if ringer mode was unintentionally changed and restore it
            if (stream != VolumeStream.RING) {
                val currentRingerMode = audioManager.ringerMode
                if (currentRingerMode != originalRingerMode) {
                    Log.w("VolumeStreamManager", "Ringer mode was unintentionally changed from $originalRingerMode to $currentRingerMode when setting ${stream.displayName} volume. Restoring original mode.")
                    try {
                        audioManager.ringerMode = originalRingerMode
                    } catch (e: Exception) {
                        Log.e("VolumeStreamManager", "Failed to restore ringer mode: ${e.message}")
                    }
                }
            }
            
            Log.d("VolumeStreamManager", "Set ${stream.displayName} volume to $clampedVolume (flags: $flags)")
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error setting ${stream.displayName} volume: ${e.message}")
        }
    }
    
    /**
     * Perform the actual volume change with proper error handling
     */
    private fun performVolumeChange(stream: VolumeStream, volume: Int, flags: Int) {
        try {
            val currentVolume = audioManager.getStreamVolume(stream.streamType)
            val maxVolume = audioManager.getStreamMaxVolume(stream.streamType)
            
            if (volume != currentVolume) {
                // For non-ring streams, use extra caution to prevent ringer mode interference
                if (stream != VolumeStream.RING) {
                    // Use the most conservative approach: direct setStreamVolume with no flags that could affect ringer
                    val safeFlags = if (flags and AudioManager.FLAG_SHOW_UI != 0) AudioManager.FLAG_SHOW_UI else 0
                    audioManager.setStreamVolume(stream.streamType, volume, safeFlags)
                } else {
                    // For ring stream, use the provided flags as they may include FLAG_ALLOW_RINGER_MODES
                    audioManager.setStreamVolume(stream.streamType, volume, flags)
                }
                
                // Verify the change took effect
                val newVolume = audioManager.getStreamVolume(stream.streamType)
                
                if (newVolume != volume && stream != VolumeStream.RING) {
                    // If direct setting didn't work for non-ring streams, try adjustStreamVolume with safe flags
                    Log.d("VolumeStreamManager", "Direct volume set didn't work for ${stream.displayName}, trying adjustStreamVolume with safe flags")
                    
                    val safeFlags = if (flags and AudioManager.FLAG_SHOW_UI != 0) AudioManager.FLAG_SHOW_UI else 0
                    val volumeDiff = volume - newVolume
                    if (volumeDiff > 0) {
                        // Increase volume
                        repeat(volumeDiff) {
                            audioManager.adjustStreamVolume(stream.streamType, AudioManager.ADJUST_RAISE, safeFlags)
                        }
                    } else if (volumeDiff < 0) {
                        // Decrease volume
                        repeat(-volumeDiff) {
                            audioManager.adjustStreamVolume(stream.streamType, AudioManager.ADJUST_LOWER, safeFlags)
                        }
                    }
                } else if (newVolume != volume && stream == VolumeStream.RING) {
                    // For ring stream, use the original fallback method
                    Log.d("VolumeStreamManager", "Direct volume set didn't work for ring stream, trying adjustStreamVolume")
                    
                    val volumeDiff = volume - newVolume
                    if (volumeDiff > 0) {
                        repeat(volumeDiff) {
                            audioManager.adjustStreamVolume(stream.streamType, AudioManager.ADJUST_RAISE, flags)
                        }
                    } else if (volumeDiff < 0) {
                        repeat(-volumeDiff) {
                            audioManager.adjustStreamVolume(stream.streamType, AudioManager.ADJUST_LOWER, flags)
                        }
                    }
                }
                
                Log.d("VolumeStreamManager", "Set ${stream.displayName} volume from $currentVolume to $volume")
            }
            
            // Get the final volume that was actually set
            val finalVolume = audioManager.getStreamVolume(stream.streamType)
            Log.d("VolumeStreamManager", "Final ${stream.displayName} volume: $finalVolume (target: $volume)")
            
            // Notify listeners with the actual volume that was set
            listeners.forEach { listener ->
                listener.onVolumeChanged(stream, finalVolume, maxVolume)
            }
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error in performVolumeChange for ${stream.displayName}: ${e.message}")
            
            // Notify listeners with the target volume even if setting failed
            listeners.forEach { listener ->
                listener.onVolumeChanged(stream, volume, getStreamMaxVolume(stream))
            }
        }
    }
    
    /**
     * Set volume percentage (0-100) for a specific stream
     */
    fun setStreamVolumePercentage(stream: VolumeStream, percentage: Int, showUI: Boolean = false) {
        val clampedPercentage = percentage.coerceIn(0, 100)
        val maxVolume = getStreamMaxVolume(stream)
        val targetVolume = (clampedPercentage * maxVolume) / 100
        setStreamVolume(stream, targetVolume, showUI)
    }
    
    /**
     * Set volume for the currently active stream
     */
    fun setActiveStreamVolume(volume: Int, showUI: Boolean = false) {
        setStreamVolume(currentActiveStream, volume, showUI)
    }
    
    /**
     * Set volume percentage for the currently active stream
     */
    fun setActiveStreamVolumePercentage(percentage: Int, showUI: Boolean = false) {
        setStreamVolumePercentage(currentActiveStream, percentage, showUI)
    }
    
    /**
     * Get current ringer mode
     */
    fun getRingerMode(): Int {
        return audioManager.ringerMode
    }
    
    /**
     * Set ringer mode (Normal, Vibrate, Silent)
     */
    fun setRingerMode(mode: Int) {
        try {
            val oldMode = audioManager.ringerMode
            
            // Check if Do Not Disturb access is needed for silent mode
            if (mode == AudioManager.RINGER_MODE_SILENT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!isDndAccessGranted()) {
                    Log.w("VolumeStreamManager", "DND access required for silent mode")
                    requestDndAccess()
                    return
                }
            }
            
            audioManager.ringerMode = mode
            Log.d("VolumeStreamManager", "Ringer mode changed from $oldMode to $mode")
            
            // Handle volume changes based on ringer mode
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> {
                    // In silent mode, both ring and notification are silenced
                    Log.d("VolumeStreamManager", "Silent mode: ring and notification volumes will be managed by system")
                }
                AudioManager.RINGER_MODE_VIBRATE -> {
                    // In vibrate mode, volumes are set to 0 but can be restored
                    Log.d("VolumeStreamManager", "Vibrate mode: ring and notification volumes will be managed by system")
                }
                AudioManager.RINGER_MODE_NORMAL -> {
                    // Normal mode allows volume control
                    Log.d("VolumeStreamManager", "Normal mode: ring and notification volumes can be controlled")
                }
            }
            
            // Notify listeners
            listeners.forEach { listener ->
                listener.onRingerModeChanged(mode)
            }
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error setting ringer mode: ${e.message}")
        }
    }
    
    /**
     * Toggle between normal, vibrate, and silent modes
     */
    fun toggleRingerMode() {
        val currentMode = getRingerMode()
        val nextMode = when (currentMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_SILENT -> AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        setRingerMode(nextMode)
    }
    
    /**
     * Get current Do Not Disturb mode
     */
    fun getDndMode(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.currentInterruptionFilter
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
    }
    
    /**
     * Check if Do Not Disturb policy access is granted
     */
    fun isDndAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else {
            true
        }
    }
    
    /**
     * Request Do Not Disturb policy access
     */
    fun requestDndAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isDndAccessGranted()) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
    
    /**
     * Set Do Not Disturb mode
     */
    fun setDndMode(mode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isDndAccessGranted()) {
                try {
                    notificationManager.setInterruptionFilter(mode)
                    Log.d("VolumeStreamManager", "DND mode set to: $mode")
                    
                    // Notify listeners
                    listeners.forEach { listener ->
                        listener.onDndModeChanged(mode)
                    }
                } catch (e: Exception) {
                    Log.e("VolumeStreamManager", "Error setting DND mode: ${e.message}")
                }
            } else {
                Log.w("VolumeStreamManager", "DND access not granted")
                requestDndAccess()
            }
        }
    }
    
    /**
     * Toggle Do Not Disturb mode
     */
    fun toggleDndMode() {
        val currentMode = getDndMode()
        val nextMode = when (currentMode) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> NotificationManager.INTERRUPTION_FILTER_NONE
            NotificationManager.INTERRUPTION_FILTER_NONE -> NotificationManager.INTERRUPTION_FILTER_ALL
            else -> NotificationManager.INTERRUPTION_FILTER_ALL
        }
        setDndMode(nextMode)
    }
    
    /**
     * Get ringer mode display name
     */
    fun getRingerModeDisplayName(mode: Int): String {
        return when (mode) {
            AudioManager.RINGER_MODE_NORMAL -> "Normal"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            else -> "Unknown"
        }
    }
    
    /**
     * Get DND mode display name
     */
    fun getDndModeDisplayName(mode: Int): String {
        return when (mode) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "Off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority Only"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "Total Silence"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms Only"
            else -> "Unknown"
        }
    }
    
    /**
     * Get appropriate stream based on current system state
     */
    fun getContextualStream(): VolumeStream {
        // Logic to determine which stream should be active based on context
        if (audioManager.isMusicActive) {
            return VolumeStream.MEDIA
        }
        
        if (audioManager.mode == AudioManager.MODE_IN_CALL || 
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            return VolumeStream.CALL
        }
        
        // Default to current active stream or media
        return currentActiveStream
    }

    /**
     * Check if device is currently in a voice call
     */
    fun isInVoiceCall(): Boolean {
        return audioManager.mode == AudioManager.MODE_IN_CALL || 
               audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    }
    
    /**
     * Handle volume key press for the current active stream
     */
    fun handleVolumeKeyPress(isVolumeUp: Boolean): Boolean {
        val currentVolume = getStreamVolume(currentActiveStream)
        val maxVolume = getStreamMaxVolume(currentActiveStream)
        
        // CRITICAL FIX: Special handling for ring/notification streams when in vibrate/silent mode
        // This ensures that when user selects ring button and uses volume keys, it properly restores from vibrate
        if (isVolumeUp && (currentActiveStream == VolumeStream.RING || currentActiveStream == VolumeStream.NOTIFICATION)) {
            val ringerMode = getRingerMode()
            if (ringerMode == AudioManager.RINGER_MODE_VIBRATE || ringerMode == AudioManager.RINGER_MODE_SILENT) {
                // Always restore from vibrate/silent when volume up is pressed on ring/notification streams
                setRingerMode(AudioManager.RINGER_MODE_NORMAL)
                
                Handler(Looper.getMainLooper()).postDelayed({
                    val targetRingVolume = getLastSavedRingVolume()
                    val targetNotificationVolume = getLastSavedNotificationVolume()
                    setStreamVolumePercentage(VolumeStream.RING, targetRingVolume, false)
                    setStreamVolumePercentage(VolumeStream.NOTIFICATION, targetNotificationVolume, false)
                    Log.d("VolumeStreamManager", "Ring/Notification restored from vibrate/silent - Ring: $targetRingVolume%, Notification: $targetNotificationVolume%")
                }, 100)
                
                return true
            }
        }
        
        // CRITICAL FIX: Special handling for volume up when stream is muted/silenced
        // Distinguish between quick action muted (restore to saved) vs manually muted (gradual increase)
        if (isVolumeUp && currentVolume == 0) {
            when (currentActiveStream) {
                VolumeStream.MEDIA -> {
                    if (isStreamQuickActionMuted(VolumeStream.MEDIA)) {
                        // Quick action muted - restore to saved volume
                        val targetVolume = getLastSavedMediaVolume()
                        setStreamVolumePercentage(currentActiveStream, targetVolume, false)
                        clearQuickActionMuteStatus(VolumeStream.MEDIA)
                        Log.d("VolumeStreamManager", "Media restored from quick action mute to $targetVolume%")
                        return true
                    } else {
                        // Manually muted - use gradual increase (fall through to normal behavior)
                        Log.d("VolumeStreamManager", "Media manually muted - using gradual increase")
                    }
                }
                VolumeStream.RING -> {
                    // If in vibrate/silent mode, restore to normal with saved volume
                    val ringerMode = getRingerMode()
                    if (ringerMode == AudioManager.RINGER_MODE_VIBRATE || ringerMode == AudioManager.RINGER_MODE_SILENT) {
                        setRingerMode(AudioManager.RINGER_MODE_NORMAL)
                        
                        // Use a small delay to ensure ringer mode is set before setting volumes
                        Handler(Looper.getMainLooper()).postDelayed({
                            val targetRingVolume = getLastSavedRingVolume()
                            val targetNotificationVolume = getLastSavedNotificationVolume()
                            setStreamVolumePercentage(VolumeStream.RING, targetRingVolume, false)
                            setStreamVolumePercentage(VolumeStream.NOTIFICATION, targetNotificationVolume, false)
                            Log.d("VolumeStreamManager", "Ring restored from silence/vibrate to $targetRingVolume%, notification to $targetNotificationVolume%")
                        }, 100)
                        
                        return true
                    } else {
                        // If in normal mode but volume is 0, check if quick action muted
                        if (isStreamQuickActionMuted(VolumeStream.RING)) {
                            val targetVolume = getLastSavedRingVolume()
                            setStreamVolumePercentage(VolumeStream.RING, targetVolume, false)
                            clearQuickActionMuteStatus(VolumeStream.RING)
                            Log.d("VolumeStreamManager", "Ring restored from quick action mute to $targetVolume%")
                            return true
                        } else {
                            // Manually muted - use gradual increase
                            Log.d("VolumeStreamManager", "Ring manually muted - using gradual increase")
                        }
                    }
                }
                VolumeStream.NOTIFICATION -> {
                    // If ringer is in silent/vibrate, restore ring mode first
                    val ringerMode = getRingerMode()
                    if (ringerMode == AudioManager.RINGER_MODE_VIBRATE || ringerMode == AudioManager.RINGER_MODE_SILENT) {
                        setRingerMode(AudioManager.RINGER_MODE_NORMAL)
                        
                        Handler(Looper.getMainLooper()).postDelayed({
                            val targetRingVolume = getLastSavedRingVolume()
                            val targetNotificationVolume = getLastSavedNotificationVolume()
                            setStreamVolumePercentage(VolumeStream.RING, targetRingVolume, false)
                            setStreamVolumePercentage(VolumeStream.NOTIFICATION, targetNotificationVolume, false)
                            Log.d("VolumeStreamManager", "Notification restored from silence/vibrate to $targetNotificationVolume%, ring to $targetRingVolume%")
                        }, 100)
                        
                        return true
                    } else {
                        // If in normal mode but volume is 0, check if quick action muted
                        if (isStreamQuickActionMuted(VolumeStream.NOTIFICATION)) {
                            val targetVolume = getLastSavedNotificationVolume()
                            setStreamVolumePercentage(VolumeStream.NOTIFICATION, targetVolume, false)
                            clearQuickActionMuteStatus(VolumeStream.NOTIFICATION)
                            Log.d("VolumeStreamManager", "Notification restored from quick action mute to $targetVolume%")
                            return true
                        } else {
                            // Manually muted - use gradual increase
                            Log.d("VolumeStreamManager", "Notification manually muted - using gradual increase")
                        }
                    }
                }
                else -> {
                    // For other streams, use normal volume up behavior (gradual increase)
                }
            }
        }
        
        val newVolume = if (isVolumeUp) {
            (currentVolume + 1).coerceAtMost(maxVolume)
        } else {
            (currentVolume - 1).coerceAtLeast(0)
        }
        
        // CRITICAL FIX: Track manual muting when volume keys set volume to 0
        if (newVolume == 0 && currentVolume > 0) {
            // Volume key pressed to set volume to 0 - mark as manually muted
            markStreamAsManuallyMuted(currentActiveStream)
            Log.d("VolumeStreamManager", "${currentActiveStream.displayName} manually muted via volume key")
        } else if (newVolume > 0) {
            // Volume increased - clear any mute status
            clearQuickActionMuteStatus(currentActiveStream)
        }
        
        setStreamVolume(currentActiveStream, newVolume, false)
        return true // Consume the key event
    }
    
    /**
     * Set VolumeDialView reference for volume restoration
     */
    fun setVolumeDialView(dialView: Any) {
        volumeDialView = dialView
    }
    
    /**
     * Mark a stream as muted via quick action (should restore to saved volume)
     */
    fun markStreamAsQuickActionMuted(stream: VolumeStream) {
        quickActionMutedStreams.add(stream)
        Log.d("VolumeStreamManager", "${stream.displayName} marked as quick action muted")
    }
    
    /**
     * Mark a stream as manually muted (should gradually increase)
     */
    fun markStreamAsManuallyMuted(stream: VolumeStream) {
        quickActionMutedStreams.remove(stream)
        Log.d("VolumeStreamManager", "${stream.displayName} marked as manually muted")
    }
    
    /**
     * Check if a stream was muted via quick action
     */
    fun isStreamQuickActionMuted(stream: VolumeStream): Boolean {
        return quickActionMutedStreams.contains(stream)
    }
    
    /**
     * Clear quick action mute status for a stream
     */
    fun clearQuickActionMuteStatus(stream: VolumeStream) {
        quickActionMutedStreams.remove(stream)
        Log.d("VolumeStreamManager", "Cleared quick action mute status for ${stream.displayName}")
    }
    
    /**
     * Get last saved media volume (used for restoration)
     */
    fun getLastSavedMediaVolume(): Int {
        return try {
            val dialView = volumeDialView
            if (dialView != null) {
                val method = dialView.javaClass.getMethod("getSavedMediaVolume")
                method.invoke(dialView) as? Int ?: 50
            } else {
                50
            }
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error getting saved media volume: ${e.message}")
            50
        }
    }
    
    /**
     * Get last saved ring volume (used for restoration)
     */
    fun getLastSavedRingVolume(): Int {
        return try {
            val dialView = volumeDialView
            if (dialView != null) {
                val method = dialView.javaClass.getMethod("getSavedRingVolume")
                method.invoke(dialView) as? Int ?: 50
            } else {
                50
            }
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error getting saved ring volume: ${e.message}")
            50
        }
    }
    
    /**
     * Get last saved notification volume (used for restoration)
     */
    fun getLastSavedNotificationVolume(): Int {
        return try {
            val dialView = volumeDialView
            if (dialView != null) {
                val method = dialView.javaClass.getMethod("getSavedNotificationVolume")
                method.invoke(dialView) as? Int ?: 50
            } else {
                50
            }
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error getting saved notification volume: ${e.message}")
            50
        }
    }
    
    /**
     * Get all available streams
     */
    fun getAllStreams(): Array<VolumeStream> {
        return VolumeStream.values()
    }
    
    /**
     * Get stream by type
     */
    fun getStreamByType(streamType: Int): VolumeStream? {
        return VolumeStream.values().find { it.streamType == streamType }
    }
    
    /**
     * Check if stream is available on current device
     */
    fun isStreamAvailable(stream: VolumeStream): Boolean {
        return try {
            getStreamMaxVolume(stream) > 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get volume info for all streams
     */
    fun getAllStreamVolumes(): Map<VolumeStream, Pair<Int, Int>> {
        val volumes = mutableMapOf<VolumeStream, Pair<Int, Int>>()
        
        VolumeStream.values().forEach { stream ->
            if (isStreamAvailable(stream)) {
                val current = getStreamVolume(stream)
                val max = getStreamMaxVolume(stream)
                volumes[stream] = Pair(current, max)
            }
        }
        
        return volumes
    }
    
    /**
     * Mute/unmute a specific stream
     */
    fun toggleStreamMute(stream: VolumeStream) {
        val currentVolume = getStreamVolume(stream)
        if (currentVolume > 0) {
            // Mute by setting to 0
            setStreamVolume(stream, 0)
        } else {
            // Unmute by setting to a reasonable level (30% of max)
            val maxVolume = getStreamMaxVolume(stream)
            val targetVolume = (maxVolume * 0.3f).toInt().coerceAtLeast(1)
            setStreamVolume(stream, targetVolume)
        }
    }
    
    /**
     * Check if Bluetooth audio is connected and active
     */
    fun isBluetoothAudioConnected(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error checking Bluetooth audio state: ${e.message}")
            false
        }
    }
    
    /**
     * Safe volume setting method that monitors for unwanted ringer mode changes
     */
    fun setStreamVolumeSafely(stream: VolumeStream, volume: Int, showUI: Boolean = false) {
        if (stream == VolumeStream.RING) {
            // For ring stream, use normal method as ringer mode changes are expected
            setStreamVolume(stream, volume, showUI)
            return
        }
        
        // For non-ring streams, monitor ringer mode and restore if changed
        val originalRingerMode = audioManager.ringerMode
        val originalRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        
        try {
            setStreamVolume(stream, volume, showUI)
            
            // Double-check that ringer wasn't affected
            val currentRingerMode = audioManager.ringerMode
            val currentRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            
            if (currentRingerMode != originalRingerMode || currentRingVolume != originalRingVolume) {
                Log.w("VolumeStreamManager", "Detected unwanted ringer changes after setting ${stream.displayName} volume. Restoring ringer state.")
                
                // Restore ringer mode
                if (currentRingerMode != originalRingerMode) {
                    try {
                        audioManager.ringerMode = originalRingerMode
                    } catch (e: Exception) {
                        Log.e("VolumeStreamManager", "Failed to restore ringer mode: ${e.message}")
                    }
                }
                
                // Restore ring volume
                if (currentRingVolume != originalRingVolume) {
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, originalRingVolume, 0)
                    } catch (e: Exception) {
                        Log.e("VolumeStreamManager", "Failed to restore ring volume: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VolumeStreamManager", "Error in setStreamVolumeSafely: ${e.message}")
        }
    }
}
