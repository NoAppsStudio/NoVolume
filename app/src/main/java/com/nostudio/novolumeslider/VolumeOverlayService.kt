package com.nostudio.novolumeslider

import android.animation.AnimatorSet
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.animation.ObjectAnimator
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.edit
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.pow
import kotlin.math.sqrt
import android.widget.FrameLayout
import android.content.res.Configuration

class VolumeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var fullScreenTouchView: View // Full screen touch view
    private lateinit var volumeStreamManager: VolumeStreamManager
    private lateinit var volumeDial: VolumeDialView
    private lateinit var volumeNumber: TextView

    private val prefs by lazy { getSharedPreferences("volume_slider_prefs", Context.MODE_PRIVATE) }

    private val hideNumberHandler = Handler(Looper.getMainLooper())
    private val hideOverlayHandler = Handler(Looper.getMainLooper())

    private val hideNumberRunnable = Runnable {
        volumeNumber.visibility = View.INVISIBLE
    }

    private val hideOverlayRunnable = Runnable {
        hideOverlayCompletely()
    }

    private var preciseVolumeLevel = 0

    // Flag to track if user is currently touching the dial
    private var isTouching = false

    // Flag to track if user is currently interacting with multistream buttons
    private var isInteractingWithButtons = false

    private var accumulatedYOffset: Float = 0f

    private var isOverlayVisible = false

    private var isShowingAnimation = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("VolumeOverlayService", "Service created")

        createNotificationChannel()
        startForegroundServiceWithNotification()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        volumeStreamManager = VolumeStreamManager(this)

        // Inflate the overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.custom_volume_overlay, null)

        // Get references to our views
        volumeDial = overlayView.findViewById(R.id.volumeDial)
        volumeNumber = overlayView.findViewById(R.id.volumeNumber)

        // Set up the touch volume change listener
        volumeDial.setOnVolumeChangeListener(object : VolumeDialView.OnVolumeChangeListener {
            override fun onVolumeChanged(volume: Int) {
                val activeStream = volumeStreamManager.getActiveStream()
                
                // CRITICAL FIX: Track manual muting vs quick action muting
                if (volume == 0) {
                    // User manually set volume to 0 - mark as manually muted
                    volumeStreamManager.markStreamAsManuallyMuted(activeStream)
                } else {
                    // Volume is not 0 - clear any mute status
                    volumeStreamManager.clearQuickActionMuteStatus(activeStream)
                }
                
                // Use the safer method to prevent unwanted ringer mode changes
                volumeStreamManager.setStreamVolumeSafely(activeStream, (volume * volumeStreamManager.getStreamMaxVolume(activeStream)) / 100)
                val actualVolume = volumeStreamManager.getStreamVolumePercentage(activeStream)

                // Update UI - but check mode first
                val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)

                // Never show volume number in pop-out mode or quick action mode, regardless of touch interaction
                if (volumeNumberDisplayEnabled && !volumeDial.isInPopOutMode() && !volumeDial.isInQuickActionMode()) {
                    volumeNumber.text = actualVolume.toString()  // Use actual volume instead of requested
                    volumeNumber.visibility = View.VISIBLE

                    // Don't hide number while touching
                    hideNumberHandler.removeCallbacks(hideNumberRunnable)
                } else {
                    volumeNumber.visibility = View.GONE
                    // Cancel number hiding timer when hiding the number
                    hideNumberHandler.removeCallbacks(hideNumberRunnable)
                }

                // Update stream icons to reflect new volume levels
                volumeDial.updateStreamIcons()

                // Handle overlay timer extension for touch interaction (same as volume buttons)
                if (volumeDial.isInPopOutMode()) {
                    // In pop-out mode, extend overlay visibility timer
                    hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
                    hideOverlayHandler.postDelayed(hideOverlayRunnable, 4000)
                }


            }

            override fun onTouchStart() {
                // User started touching the dial or interacting with buttons
                isTouching = true

                // If we're in pop-out mode, this could be button interaction
                if (volumeDial.isInPopOutMode()) {
                    isInteractingWithButtons = true
                }

                // Cancel any pending hide operations
                hideNumberHandler.removeCallbacks(hideNumberRunnable)
                hideOverlayHandler.removeCallbacks(hideOverlayRunnable)

    
            }

            override fun onTouchEnd() {
                // User stopped touching the dial or buttons
                isTouching = false
                isInteractingWithButtons = false

                // Schedule hiding the number after 2 seconds (only if not in pop-out mode or quick action mode)
                if (!volumeDial.isInPopOutMode() && !volumeDial.isInQuickActionMode()) {
                    hideNumberHandler.postDelayed(hideNumberRunnable, 2000)
                }

                // Schedule hiding the overlay - use different timing based on mode
                val timeout = when {
                    volumeDial.isInPopOutMode() -> 4000L
                    volumeDial.isInQuickActionMode() -> 3000L
                    else -> 2000L
                }
                hideOverlayHandler.postDelayed(hideOverlayRunnable, timeout)


            }

            override fun onDismiss() {
                // Reset quick action state immediately when dismissed by outside tap
                if (volumeDial.isInQuickActionMode()) {
                    volumeDial.forceExitQuickActionMode()
                    Log.d("VolumeOverlayService", "Quick action state reset due to outside tap dismissal")
                }
                
                // For outside tap dismissal, use smooth reset during overlay fade
                hideOverlayWithSmoothReset()
                Log.d("VolumeOverlayService", "Overlay dismissed by outside tap - using smooth reset")
            }

            override fun onSlideRight() {  // Add this method
                hideOverlayCompletely()
                // Open classic Android volume bar
                showSystemVolumeBar()
                Log.d("VolumeOverlayService", "Slide right detected, opening classic volume bar")
            }
            
            override fun onStreamChanged(stream: VolumeStreamManager.VolumeStream) {
                // Update the active stream in the volume manager
                volumeStreamManager.setActiveStream(stream)
                
                // CRITICAL FIX: Save the selected stream to shared preferences so volume keys work correctly
                // This ensures that when the user selects a button (even without centering it), 
                // the hardware volume keys will control the correct stream
                val overlayStatePrefs = getSharedPreferences("VolumeOverlayState", Context.MODE_PRIVATE)
                overlayStatePrefs.edit().putString("selected_stream", stream.name).apply()
                
                // Update the volume display to show the new stream's current volume
                val volumePercentage = volumeStreamManager.getStreamVolumePercentage(stream)
                
                // Update the dial volume without triggering system volume change
                volumeDial.volume = volumePercentage
                
                // Update stream icons to reflect current state
                volumeDial.updateStreamIcons()
                
                // Update volume number display
                val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)
                
                if (volumeNumberDisplayEnabled && !volumeDial.isInPopOutMode() && !volumeDial.isInQuickActionMode()) {
                    volumeNumber.text = volumePercentage.toString()
                    volumeNumber.visibility = View.VISIBLE
                    
                    // Cancel any pending number hiding
                    hideNumberHandler.removeCallbacks(hideNumberRunnable)
                    hideNumberHandler.postDelayed(hideNumberRunnable, 2000)
                } else {
                    volumeNumber.visibility = View.GONE
                    hideNumberHandler.removeCallbacks(hideNumberRunnable)
                }
                
                Log.d("VolumeOverlayService", "Stream changed to ${stream.displayName}, volume: $volumePercentage%, saved to prefs for volume key handling")
            }
        })

        // Set up pop-out animation listener
        volumeDial.setPopOutAnimationListener(object : VolumeDialView.PopOutAnimationListener {
            override fun onPopOutProgressChanged(progress: Float) {
                updateOverlayForPopOut(progress)
            }

            override fun onPopOutModeChanged(isInPopOutMode: Boolean) {
                handlePopOutModeChange(isInPopOutMode)
            }
        })

            // Initialize wheel size and haptic feedback settings
    initializeSettings()
    
    // Set the VolumeStreamManager reference for dynamic icon selection
    volumeDial.setVolumeStreamManager(volumeStreamManager)
    
    // Initialize theme
    initializeTheme()
        
        // Set the VolumeDialView reference in VolumeStreamManager for volume restoration
        volumeStreamManager.setVolumeDialView(volumeDial)

        volumeDial.setInteractionListener(object : VolumeDialView.InteractionListener {
            override fun onInteractionStart() {
                if (isShowingAnimation) return
                // Interaction is allowed, proceed with touch handling
            }
        })

        // Create full-screen touch view for detecting taps outside
        fullScreenTouchView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // Check if the touch is outside the dial
                    val dialLocation = IntArray(2)
                    volumeDial.getLocationOnScreen(dialLocation)

                    val dialCenterX = dialLocation[0] + volumeDial.width / 2
                    val dialCenterY = dialLocation[1] + volumeDial.height / 2
                    val dialRadius = Math.min(volumeDial.width, volumeDial.height) / 2

                    val touchX = event.rawX
                    val touchY = event.rawY
                    val distance = sqrt((touchX - dialCenterX).toDouble().pow(2.0) + (touchY - dialCenterY).toDouble().pow(2.0))

                    if (distance > dialRadius) {
                        hideOverlayCompletely()
                        volumeDial.volumeChangeListener?.onDismiss()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }

        // Create layout parameters for the full-screen touch view
        val fullScreenParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
            windowAnimations = 0
        }

        try {
            // Add full-screen touch view to window
            windowManager.addView(fullScreenTouchView, fullScreenParams)
            fullScreenTouchView.visibility = View.GONE // Initially hidden
            Log.d("VolumeOverlayService", "Full screen touch view added")
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error adding full-screen touch view: ${e.message}")
        }

        // Calculate initial width that can accommodate full circle at maximum wheel size
        val metrics = resources.displayMetrics
        val baseHeight = 350 // This matches the volumeDialContainer height in XML
        val basePadding = 40f
        val baseRadius = (Math.min(baseHeight, baseHeight) / 2f - basePadding)
        val maxScaleFactor = 1.1f // Maximum possible wheel size
        val maxRadius = baseRadius * maxScaleFactor
        val progressArcRadius = maxRadius + (60f * maxScaleFactor) // Account for progress arc
        val calculatedWidth = (progressArcRadius * 2.2f).toInt() // Wide enough for full circle with generous padding
        val minWidth = 600 // Minimum width to ensure no clipping issues
        val initialWidth = maxOf(calculatedWidth, minWidth)

        Log.d("VolumeOverlayService", "Calculated overlay width: $initialWidth (calculated: $calculatedWidth, min: $minWidth, baseRadius: $baseRadius, maxRadius: $maxRadius)")

        // Create overlay layout parameters
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            width = initialWidth
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }

        try {
            windowManager.addView(overlayView, params)
            overlayView.visibility = View.GONE // Initially hidden
            Log.d("VolumeOverlayService", "Overlay view added with width: ${params.width}")
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error adding overlay view: ${e.message}")
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("VolumeOverlayService", "onStartCommand called")

        // Load saved vertical offset
        val savedYOffset = prefs.getInt("overlay_y_offset", 0)
        updateOverlayPosition(savedYOffset)

        if (intent != null) {
            // Handle different intent actions
            when (intent.action) {
                "VOLUME_CHANGED" -> {
                    val streamType = intent.getIntExtra("STREAM_TYPE", -1)
                    val currentVolume = intent.getIntExtra("CURRENT_VOLUME", -1)
                    val previousVolume = intent.getIntExtra("PREVIOUS_VOLUME", -1)
                    
                    if (streamType != -1) {
                        val stream = volumeStreamManager.getStreamByType(streamType)
                        if (stream != null) {
                            volumeStreamManager.setActiveStream(stream)
                            val volumePercentage = volumeStreamManager.getStreamVolumePercentage(stream)
                            updateVolumeUI(volumePercentage, stream)
                        }
                    }
                }
                "RINGER_MODE_CHANGED" -> {
                    val ringerMode = intent.getIntExtra("RINGER_MODE", -1)
                    handleRingerModeChange(ringerMode)
                }
                "DND_MODE_CHANGED" -> {
                    handleDndModeChange()
                }
                else -> {
                    // Legacy handling for direct volume updates
                    val currentVolume = intent.getIntExtra("CURRENT_VOLUME", -1)
                    val activeStreamName = intent.getStringExtra("ACTIVE_STREAM")
                    val streamType = intent.getIntExtra("STREAM_TYPE", -1)
                    
                    if (currentVolume != -1) {
                        // Try to get the active stream from intent
                        var activeStream = volumeStreamManager.getActiveStream()
                        if (activeStreamName != null) {
                            try {
                                activeStream = VolumeStreamManager.VolumeStream.valueOf(activeStreamName)
                                volumeStreamManager.setActiveStream(activeStream)
                            } catch (e: IllegalArgumentException) {
                                Log.w("VolumeOverlayService", "Invalid stream name: $activeStreamName")
                            }
                        }
                        
                        updateVolumeUI(currentVolume, activeStream)
                    } else {
                        // If no volume data, use current system volume for active stream
                        val activeStream = volumeStreamManager.getActiveStream()
                        val volumePercentage = volumeStreamManager.getStreamVolumePercentage(activeStream)
                        updateVolumeUI(volumePercentage, activeStream)
                    }
                }
            }
        }

        if (intent?.action == "SHOW_OVERLAY") {
            showOverlay()
        }

        if (intent?.action == "HIDE_OVERLAY") {
            hideOverlayCompletely()
        }

        if (intent?.action == "UPDATE_OVERLAY_POSITION") {
            val position = intent.getIntExtra("POSITION", 50)
            updateOverlayPosition(position)
        }

        if (intent?.action == "UPDATE_WHEEL_SIZE") {
            val scaleFactor = intent.getFloatExtra("SCALE_FACTOR", 1.0f)
            updateWheelSize(scaleFactor)
        }

        if (intent?.action == "UPDATE_HAPTIC_FEEDBACK") {
            val enabled = intent.getBooleanExtra("HAPTIC_FEEDBACK_ENABLED", true)
            updateHapticFeedback(enabled)
        }

        if (intent?.action == "UPDATE_HAPTIC_STRENGTH") {
            val strength = intent.getIntExtra("HAPTIC_STRENGTH", 1)
            updateHapticStrength(strength)
        }

        if (intent?.action == "UPDATE_VOLUME_NUMBER_DISPLAY") {
            val enabled = intent.getBooleanExtra("VOLUME_NUMBER_DISPLAY_ENABLED", true)
            updateVolumeNumberDisplay(enabled)
        }

        if (intent?.action == "UPDATE_PROGRESS_BAR_DISPLAY") {
            val enabled = intent.getBooleanExtra("PROGRESS_BAR_DISPLAY_ENABLED", true)
            updateProgressBarDisplay(enabled)
        }

        if (intent?.action == "UPDATE_VOLUME_THEME") {
            val themeValue = intent.getIntExtra("VOLUME_THEME", 0)
            val theme = when (themeValue) {
                0 -> VolumeDialView.VolumeTheme.LIGHT
                1 -> VolumeDialView.VolumeTheme.DARK
                2 -> VolumeDialView.VolumeTheme.SYSTEM
                else -> VolumeDialView.VolumeTheme.LIGHT
            }
            updateVolumeTheme(theme)
        }

        if (intent?.action != "HIDE_OVERLAY") {
            showOverlay()
        }


        return START_NOT_STICKY
    }

    private fun updateVolumeUI(volumePercentage: Int, activeStream: VolumeStreamManager.VolumeStream) {
        // Update the stored precise volume level
        preciseVolumeLevel = volumePercentage

        // Update the dial view
        volumeDial.volume = volumePercentage
        
        // Update the dial with the active stream information
        volumeDial.setActiveStream(activeStream)

        // Notify dial of external volume change to reset pop-out timer
        volumeDial.onExternalVolumeChange()

        // Handle overlay timer extension for volume button interaction
        if (volumeDial.isInPopOutMode()) {
            // In pop-out mode, extend overlay visibility timer
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            hideOverlayHandler.postDelayed(hideOverlayRunnable, 4000)
            Log.d("VolumeOverlayService", "Volume button pressed in pop-out mode - extended overlay timer")
        } else {
            // In normal mode, reset overlay timer if not currently touching or interacting with buttons
            if (!isTouching && !isInteractingWithButtons) {
                hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
                val timeout = if (volumeDial.isInQuickActionMode()) 3000L else 2000L
                hideOverlayHandler.postDelayed(hideOverlayRunnable, timeout)
            }
        }

        // Update the number only if display is enabled AND not in pop-out mode
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)

        if (volumeNumberDisplayEnabled && !volumeDial.isInPopOutMode() && !volumeDial.isInQuickActionMode()) {
            volumeNumber.text = volumePercentage.toString()
            volumeNumber.visibility = View.VISIBLE

            // Cancel any pending number hiding
            hideNumberHandler.removeCallbacks(hideNumberRunnable)

            // Only schedule hiding if not currently touching or interacting with buttons
            if (!isTouching && !isInteractingWithButtons) {
                hideNumberHandler.postDelayed(hideNumberRunnable, 2000)
            }
        } else {
            volumeNumber.visibility = View.GONE
            // Also cancel number hiding timer when hiding the number
            hideNumberHandler.removeCallbacks(hideNumberRunnable)
        }


    }
    
    // Legacy method for backward compatibility
    private fun updateVolumeUI(volumePercentage: Int) {
        val activeStream = volumeStreamManager.getActiveStream()
        updateVolumeUI(volumePercentage, activeStream)
    }

    private fun updateSystemVolume(volumePercentage: Int) {
        preciseVolumeLevel = volumePercentage
        // Use the VolumeStreamManager to set the active stream volume
        volumeStreamManager.setActiveStreamVolumePercentage(volumePercentage)
        Log.d("VolumeOverlayService", "System volume set to $volumePercentage% for active stream")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_service_channel",
                "Volume Control Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Manages the custom volume overlay service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationCompat.Builder(this, "overlay_service_channel")
            .setContentTitle("Volume Control Active")
            .setContentText("Custom volume control overlay is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        startForeground(1, notification)
    }

    private fun showOverlay() {
        // val bezierRevealView = overlayView.findViewById<BezierRevealView>(R.id.bezierRevealView)

        if (overlayView.visibility != View.VISIBLE) {
            isOverlayVisible = true
            isShowingAnimation = true

            // Ensure start in semi-mode when showing overlay
            if (volumeDial.isInPopOutMode()) {
                volumeDial.forceReturnToSemiCircle()
            }
            
            // Reset any click animation state to ensure we always start with 4 buttons
            volumeDial.resetToFourButtonState()

            // Position the overlay initially
            overlayView.translationX = -overlayView.width.toFloat() * 0.5f
            overlayView.alpha = 0f
            overlayView.visibility = View.VISIBLE

            // First start the bezier animation
            //   bezierRevealView.visibility = View.VISIBLE
            //  bezierRevealView.startRevealAnimation()

            // Then with a slight delay, start sliding in the actual overlay
            Handler(Looper.getMainLooper()).postDelayed({
                val slideInAnim = ObjectAnimator.ofFloat(overlayView, "translationX", -overlayView.width.toFloat() * 0.5f, 0f)
                val fadeInAnim = ObjectAnimator.ofFloat(overlayView, "alpha", 0f, 1f)

                AnimatorSet().apply {
                    playTogether(slideInAnim, fadeInAnim)
                    duration = 400
                    interpolator = DecelerateInterpolator(1.2f)

                    doOnEnd {
                        isShowingAnimation = false
                    }
                    start()
                }
            }, 100) // 100ms delay before starting the slide animation

            Log.d("VolumeOverlayService", "Overlay shown with animations")

            // Make the full-screen touch view touchable and visible
            val params = fullScreenTouchView.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(fullScreenTouchView, params)
            fullScreenTouchView.visibility = View.VISIBLE
        } else {
            Log.d("VolumeOverlayService", "Overlay already visible, skipping show animation")
        }

        // Only schedule hiding if not currently touching or interacting with buttons - respect pop-out mode timing
        if (!isTouching && !isInteractingWithButtons) {
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            val timeout = when {
                volumeDial.isInPopOutMode() -> 4000L
                volumeDial.isInQuickActionMode() -> 3000L
                else -> 2000L
            }
            hideOverlayHandler.postDelayed(hideOverlayRunnable, timeout)
            Log.d("VolumeOverlayService", "showOverlay() - scheduled hide timeout: ${timeout}ms, pop-out: ${volumeDial.isInPopOutMode()}, quick-action: ${volumeDial.isInQuickActionMode()}")
        }
    }

    private fun playSlideAndFadeInAnimation(bezierRevealView: BezierRevealView) {
        // startBezierAnimation(bezierRevealView)

        val slideInAnim = ObjectAnimator.ofFloat(overlayView, "translationX", -overlayView.width.toFloat(), 0f)
        val fadeInAnim = ObjectAnimator.ofFloat(overlayView, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(slideInAnim, fadeInAnim)
            duration = 400

            doOnEnd {
                isShowingAnimation = false
            }
            start()
        }
    }

    /* private fun startBezierAnimation(bezierRevealView: BezierRevealView) {
         bezierRevealView.visibility = View.VISIBLE
         bezierRevealView.startRevealAnimation()
     }*/


    private fun hideOverlayCompletely() {
        if (overlayView.visibility != View.GONE) {
            isShowingAnimation = true
            isOverlayVisible = false

            // Get bezier view for fade out
            //   val bezierRevealView = overlayView.findViewById<BezierRevealView>(R.id.bezierRevealView)
            //    bezierRevealView.fadeOut()

            // Slide out to the left and fade out
            val slideOutAnim = ObjectAnimator.ofFloat(overlayView, "translationX", 0f, -overlayView.width.toFloat() * 0.5f)
            val fadeOutAnim = ObjectAnimator.ofFloat(overlayView, "alpha", 1f, 0f)

            AnimatorSet().apply {
                playTogether(slideOutAnim, fadeOutAnim)
                duration = 400
                start()
                doOnEnd {
                    overlayView.visibility = View.GONE
                    overlayView.alpha = 0f
                    isShowingAnimation = false

                    // Reset to semi-circle state AFTER animation completes for smooth transition
                    resetToSemiCircleState()
                }
            }

            Log.d("VolumeOverlayService", "Overlay hidden with animations - will reset after completion")

            // Make the full-screen touch view non-interactive AND invisible
            fullScreenTouchView.visibility = View.GONE

            // Update the parameters to make sure it doesn't intercept touches
            val params = fullScreenTouchView.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(fullScreenTouchView, params)
            } catch (e: Exception) {
                Log.e("VolumeOverlayService", "Error updating full screen view params: ${e.message}")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Handle system theme changes when in system mode
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val themeValue = appPrefs.getInt("volume_theme", 0)
        if (themeValue == 2) { // System theme mode
            val theme = VolumeDialView.VolumeTheme.SYSTEM
            updateVolumeNumberColor(theme)
            Log.d("VolumeOverlayService", "Configuration changed - updated system theme colors")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::windowManager.isInitialized) {
            if (::overlayView.isInitialized) {
                try {
                    windowManager.removeView(overlayView)
                    Log.d("VolumeOverlayService", "Overlay view removed")
                } catch (e: Exception) {
                    Log.e("VolumeOverlayService", "Error removing overlay view: ${e.message}")
                }
            }

            if (::fullScreenTouchView.isInitialized) {
                try {
                    windowManager.removeView(fullScreenTouchView)
                    Log.d("VolumeOverlayService", "Full-screen touch view removed")
                } catch (e: Exception) {
                    Log.e("VolumeOverlayService", "Error removing full-screen touch view: ${e.message}")
                }
            }
        }

        hideNumberHandler.removeCallbacks(hideNumberRunnable)
        hideOverlayHandler.removeCallbacks(hideOverlayRunnable)

        showSystemVolumeBar()
    }

    private fun updateOverlayPosition(yOffset: Int) {
        val params = overlayView.layoutParams as WindowManager.LayoutParams
        params.y = yOffset

        // Save the yOffset to SharedPreferences
        prefs.edit { putInt("overlay_y_offset", yOffset) }

        try {
            windowManager.updateViewLayout(overlayView, params)
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error updating overlay position: ${e.message}")
        }
    }

    private fun showSystemVolumeBar() {
        try {
            val activeStream = volumeStreamManager.getActiveStream()
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Use safe flags - avoid FLAG_ALLOW_RINGER_MODES for non-ring streams
            val flags = if (activeStream == VolumeStreamManager.VolumeStream.RING) {
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_ALLOW_RINGER_MODES
            } else {
                AudioManager.FLAG_SHOW_UI
            }
            
            audioManager.adjustStreamVolume(activeStream.streamType, AudioManager.ADJUST_SAME, flags)
        } catch (e: SecurityException) {
            Log.e("VolumeOverlayService", "SecurityException showing system volume bar: ${e.message}")
        }
    }

    private fun initializeSettings() {
        // Load and apply saved wheel size
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedScaleFactor = appPrefs.getFloat("wheel_scale_factor", 0.975f) // Default for 75% slider position
        volumeDial.setWheelSize(savedScaleFactor)

        // Scale the volume number text size to match wheel size
        val baseTextSize = 40f
        val scaledTextSize = baseTextSize * savedScaleFactor
        volumeNumber.textSize = scaledTextSize

        // Set initial volume number position
        val layoutParams = volumeNumber.layoutParams as FrameLayout.LayoutParams
        layoutParams.marginStart = (10 * savedScaleFactor).toInt()
        volumeNumber.layoutParams = layoutParams

        // Load and apply haptic feedback setting
        val hapticEnabled = appPrefs.getBoolean("haptic_feedback_enabled", true)
        volumeDial.setHapticEnabled(hapticEnabled)

        // Load and apply haptic strength setting
        val hapticStrength = appPrefs.getInt("haptic_strength", 1)
        volumeDial.setHapticStrength(hapticStrength)

        // Load and apply volume number display setting
        val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)
        volumeNumber.visibility = if (volumeNumberDisplayEnabled) View.VISIBLE else View.GONE
        volumeDial.setVolumeNumberDisplayEnabled(volumeNumberDisplayEnabled)

        // Load and apply progress bar display setting
        val progressBarDisplayEnabled = appPrefs.getBoolean("progress_bar_display_enabled", true)
        volumeDial.setProgressBarDisplayEnabled(progressBarDisplayEnabled)


    }

    private fun updateWheelSize(scaleFactor: Float) {
        volumeDial.setWheelSize(scaleFactor)

        // Scale the volume number text size but keep position consistent
        val baseTextSize = 40f
        val scaledTextSize = baseTextSize * scaleFactor
        volumeNumber.textSize = scaledTextSize

        // Update overlay width to accommodate new wheel size
        try {
            val params = overlayView.layoutParams as WindowManager.LayoutParams
            val baseHeight = 350
            val basePadding = 40f
            val baseRadius = (Math.min(baseHeight, baseHeight) / 2f - basePadding)
            val radius = baseRadius * scaleFactor
            val progressArcRadius = radius + (60f * scaleFactor)
            val calculatedWidth = (progressArcRadius * 2.2f).toInt()
            val minWidth = 600
            val newWidth = maxOf(calculatedWidth, minWidth)
            params.width = newWidth
            windowManager.updateViewLayout(overlayView, params)
            Log.d("VolumeOverlayService", "Updated overlay width for wheel size: $newWidth")
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error updating overlay width for wheel size: ${e.message}")
        }

        // Keep the volume number position consistent regardless of wheel size
        val layoutParams = volumeNumber.layoutParams as FrameLayout.LayoutParams
        layoutParams.marginStart = (10 * scaleFactor).toInt()
        volumeNumber.layoutParams = layoutParams

        Log.d("VolumeOverlayService", "Wheel size updated to scale factor: $scaleFactor, Text size: $scaledTextSize")
    }

    private fun updateHapticFeedback(enabled: Boolean) {
        volumeDial.setHapticEnabled(enabled)
        Log.d("VolumeOverlayService", "Haptic feedback updated - Enabled: $enabled")
    }

    private fun updateHapticStrength(strength: Int) {
        volumeDial.setHapticStrength(strength)
        Log.d("VolumeOverlayService", "Haptic strength updated - Strength: $strength")
    }

    private fun updateVolumeNumberDisplay(enabled: Boolean) {
        volumeNumber.visibility = if (enabled) View.VISIBLE else View.GONE
        volumeDial.setVolumeNumberDisplayEnabled(enabled)
        Log.d("VolumeOverlayService", "Volume number display updated - Enabled: $enabled")
    }

    private fun updateProgressBarDisplay(enabled: Boolean) {
        volumeDial.setProgressBarDisplayEnabled(enabled)
        Log.d("VolumeOverlayService", "Progress bar display updated - Enabled: $enabled")
    }
    
    private fun initializeTheme() {
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val themeValue = appPrefs.getInt("volume_theme", 0) // 0=Light, 1=Dark, 2=System
        val theme = when (themeValue) {
            0 -> VolumeDialView.VolumeTheme.LIGHT
            1 -> VolumeDialView.VolumeTheme.DARK
            2 -> VolumeDialView.VolumeTheme.SYSTEM
            else -> VolumeDialView.VolumeTheme.LIGHT
        }
        
        volumeDial.setVolumeTheme(theme)
        updateVolumeNumberColor(theme)
        Log.d("VolumeOverlayService", "Theme initialized: $theme")
    }
    
    private fun updateVolumeNumberColor(theme: VolumeDialView.VolumeTheme) {
        val isDarkMode = when (theme) {
            VolumeDialView.VolumeTheme.LIGHT -> false
            VolumeDialView.VolumeTheme.DARK -> true
            VolumeDialView.VolumeTheme.SYSTEM -> isSystemInDarkMode()
        }
        
        val textColor = if (isDarkMode) {
            ContextCompat.getColor(this, R.color.volume_number_dark) // White in dark mode
        } else {
            ContextCompat.getColor(this, R.color.volume_number_light) // Black in light mode
        }
        
        volumeNumber.setTextColor(textColor)
        Log.d("VolumeOverlayService", "Updated volume number color - Theme: $theme, isDarkMode: $isDarkMode, Color: ${String.format("#%08X", textColor)}")
    }
    
    private fun isSystemInDarkMode(): Boolean {
        return when (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }
    
    fun updateVolumeTheme(theme: VolumeDialView.VolumeTheme) {
        volumeDial.setVolumeTheme(theme)
        updateVolumeNumberColor(theme)
        
        // Save theme preference
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val themeValue = when (theme) {
            VolumeDialView.VolumeTheme.LIGHT -> 0
            VolumeDialView.VolumeTheme.DARK -> 1
            VolumeDialView.VolumeTheme.SYSTEM -> 2
        }
        appPrefs.edit().putInt("volume_theme", themeValue).apply()
        
        Log.d("VolumeOverlayService", "Volume theme updated: $theme")
    }
    
    private fun handleRingerModeChange(ringerMode: Int) {
        Log.d("VolumeOverlayService", "Ringer mode changed to: ${volumeStreamManager.getRingerModeDisplayName(ringerMode)}")
        // Update UI to reflect ringer mode change if needed
        // This could trigger a brief overlay showing the new ringer mode
        if (ringerMode != -1) {
            // Show overlay briefly to indicate ringer mode change
            showOverlay()
        }
    }
    
    private fun handleDndModeChange() {
        val dndMode = volumeStreamManager.getDndMode()
        Log.d("VolumeOverlayService", "DND mode changed to: ${volumeStreamManager.getDndModeDisplayName(dndMode)}")
        // Update UI to reflect DND mode change if needed
        // This could trigger a brief overlay showing the new DND mode
        showOverlay()
    }

    private fun handlePopOutModeChange(isInPopOutMode: Boolean) {
        if (isInPopOutMode) {
            // Entering pop-out mode - extend overlay visibility
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            // Set a longer timeout for pop-out mode
            hideOverlayHandler.postDelayed(hideOverlayRunnable, 4000)
            Log.d("VolumeOverlayService", "Entered pop-out mode - extended overlay visibility")
        } else {
            // Exiting pop-out mode - return to normal timing
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            hideOverlayHandler.postDelayed(hideOverlayRunnable, 500) //semi mode snap back
            Log.d("VolumeOverlayService", "Exited pop-out mode - normal overlay timing")
        }
    }

    private fun updateOverlayForPopOut(progress: Float) {
        try {
            // Calculate the base radius for the dial (this should match the calculation in VolumeDialView)
            val baseHeight = 350
            val basePadding = 40f
            val baseRadius = (Math.min(baseHeight, baseHeight) / 2f - basePadding)
            val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val scaleFactor = appPrefs.getFloat("wheel_scale_factor", 0.975f)
            val radius = baseRadius * scaleFactor

            // Apply smooth fade animation to volume number during pop-out transition
            val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)

            if (volumeNumberDisplayEnabled) {
                // Calculate fade alpha with slower fade curve for more visual appeal
                val fadeAlpha = (1f - progress * progress).coerceIn(0f, 1f) // Quadratic fade for smoother effect

                // Apply fade effect
                volumeNumber.alpha = fadeAlpha

                // Keep volume number visible during transition for smooth fade
                if (progress < 1f) {
                    volumeNumber.visibility = View.VISIBLE

                    // Update volume number position to move with the center of the dial during transition
                    val volumeNumberParams = volumeNumber.layoutParams as FrameLayout.LayoutParams
                    val baseMargin = (10 * scaleFactor).toInt()
                    val popOutOffset = (radius * progress * 0.2f).toInt() // Even more subtle movement during fade
                    volumeNumberParams.marginStart = baseMargin + popOutOffset
                    volumeNumber.layoutParams = volumeNumberParams
                } else {
                    // Fully in pop-out mode - hide completely
                    volumeNumber.visibility = View.GONE
                    volumeNumber.alpha = 0f
                }
            } else {
                // Volume number display is disabled - keep it hidden
                volumeNumber.visibility = View.GONE
                volumeNumber.alpha = 0f
            }

            Log.d("VolumeOverlayService", "Pop-out progress: $progress, Volume number alpha: ${volumeNumber.alpha}, Number visible: ${volumeNumber.visibility}")

        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error updating overlay for pop-out: ${e.message}")
        }
    }

    private fun resetToSemiCircleState() {
        try {
            // Force the dial to exit pop-out mode and return to semi-circle
            if (volumeDial.isInPopOutMode()) {
                volumeDial.forceReturnToSemiCircle()

                // Cancel all timers related to pop-out mode
                hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
                hideNumberHandler.removeCallbacks(hideNumberRunnable)

                // Clear centered stream state when returning to semi-circle
                clearCenteredStreamState()

                // Reset volume number visibility and alpha to user preference
                val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)
                volumeNumber.visibility = if (volumeNumberDisplayEnabled) View.VISIBLE else View.GONE
                volumeNumber.alpha = 1f // Restore full opacity

                Log.d("VolumeOverlayService", "Reset to semi-circle state - volume number visibility: ${volumeNumber.visibility}, alpha: ${volumeNumber.alpha}")
            }
            
            // Also reset quick action state when overlay is hidden
            if (volumeDial.isInQuickActionMode()) {
                volumeDial.forceExitQuickActionMode()
                Log.d("VolumeOverlayService", "Quick action state reset during overlay hide")
            }
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error resetting to semi-circle state: ${e.message}")
        }
    }

    private fun hideOverlayWithSmoothReset() {
        if (overlayView.visibility != View.GONE) {
            isShowingAnimation = true
            isOverlayVisible = false

            // Start smooth pop-in animation simultaneously with overlay fade for seamless transition
            if (volumeDial.isInPopOutMode()) {
                volumeDial.smoothReturnToSemiCircle()
            }

            // Slide out to the left and fade out
            val slideOutAnim = ObjectAnimator.ofFloat(overlayView, "translationX", 0f, -overlayView.width.toFloat() * 0.5f)
            val fadeOutAnim = ObjectAnimator.ofFloat(overlayView, "alpha", 1f, 0f)

            AnimatorSet().apply {
                playTogether(slideOutAnim, fadeOutAnim)
                duration = 400
                start()
                doOnEnd {
                    overlayView.visibility = View.GONE
                    overlayView.alpha = 0f
                    isShowingAnimation = false

                    // Clean up any remaining state after animation
                    cleanupAfterHide()
                }
            }

            Log.d("VolumeOverlayService", "Overlay hiding with smooth reset animation")

            // Make the full-screen touch view non-interactive AND invisible
            fullScreenTouchView.visibility = View.GONE

            // Update the parameters to make sure it doesn't intercept touches
            val params = fullScreenTouchView.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(fullScreenTouchView, params)
            } catch (e: Exception) {
                Log.e("VolumeOverlayService", "Error updating full screen view params: ${e.message}")
            }
        }
    }

    private fun cleanupAfterHide() {
        try {
            // Cancel all timers
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            hideNumberHandler.removeCallbacks(hideNumberRunnable)

            // Clear centered stream state when overlay is hidden
            clearCenteredStreamState()

            // Reset volume number visibility and alpha to user preference
            val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)
            volumeNumber.visibility = if (volumeNumberDisplayEnabled) View.VISIBLE else View.GONE
            volumeNumber.alpha = 1f // Restore full opacity

            // Reset volume number position to base margin
            val volumeNumberParams = volumeNumber.layoutParams as FrameLayout.LayoutParams
            val scaleFactor = appPrefs.getFloat("wheel_scale_factor", 0.975f)
            volumeNumberParams.marginStart = (10 * scaleFactor).toInt()
            volumeNumber.layoutParams = volumeNumberParams

            Log.d("VolumeOverlayService", "Cleanup after hide completed - volume number alpha restored to: ${volumeNumber.alpha}")
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error during cleanup after hide: ${e.message}")
        }
    }

    private fun clearCenteredStreamState() {
        try {
            val prefs = getSharedPreferences("VolumeOverlayState", Context.MODE_PRIVATE)
            prefs.edit()
                .remove("centered_stream")
                .remove("selected_stream")  // CRITICAL FIX: Also clear selected stream
                .apply()
            Log.d("VolumeOverlayService", "Cleared centered and selected stream state")
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error clearing stream state: ${e.message}")
        }
    }
}
