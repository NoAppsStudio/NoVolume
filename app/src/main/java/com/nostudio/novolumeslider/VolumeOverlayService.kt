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
import kotlin.math.pow
import kotlin.math.sqrt
import android.widget.FrameLayout

class VolumeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var fullScreenTouchView: View // Full screen touch view
    private lateinit var audioManager: AudioManager
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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Inflate the overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.custom_volume_overlay, null)

        // Get references to our views
        volumeDial = overlayView.findViewById(R.id.volumeDial)
        volumeNumber = overlayView.findViewById(R.id.volumeNumber)

        // Set up the touch volume change listener
        volumeDial.setOnVolumeChangeListener(object : VolumeDialView.OnVolumeChangeListener {
            override fun onVolumeChanged(volume: Int) {
                // Update system volume
                updateSystemVolume(volume)

                // Update UI - but check mode first
                val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)

                // Never show volume number in pop-out mode, regardless of touch interaction
                if (volumeNumberDisplayEnabled && !volumeDial.isInPopOutMode()) {
                    volumeNumber.text = volume.toString()
                    volumeNumber.visibility = View.VISIBLE

                    // Don't hide number while touching
                    hideNumberHandler.removeCallbacks(hideNumberRunnable)
                } else {
                    volumeNumber.visibility = View.GONE
                    // Cancel number hiding timer when hiding the number
                    hideNumberHandler.removeCallbacks(hideNumberRunnable)
                }

                // Handle overlay timer extension for touch interaction (same as volume buttons)
                if (volumeDial.isInPopOutMode()) {
                    // In pop-out mode, extend overlay visibility timer
                    hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
                    hideOverlayHandler.postDelayed(hideOverlayRunnable, 4000)
                }

                Log.d("VolumeOverlayService", "Volume changed via touch - In pop-out: ${volumeDial.isInPopOutMode()}, Number visible: ${volumeNumber.visibility}")
            }

            override fun onTouchStart() {
                // User started touching the dial
                isTouching = true

                // Cancel any pending hide operations
                hideNumberHandler.removeCallbacks(hideNumberRunnable)
                hideOverlayHandler.removeCallbacks(hideOverlayRunnable)

                Log.d("VolumeOverlayService", "Touch started on dial, pop-out mode: ${volumeDial.isInPopOutMode()}")
            }

            override fun onTouchEnd() {
                // User stopped touching the dial
                isTouching = false

                // Schedule hiding the number after 2 seconds (only if not in pop-out mode)
                if (!volumeDial.isInPopOutMode()) {
                    hideNumberHandler.postDelayed(hideNumberRunnable, 2000)
                }

                // Schedule hiding the overlay - use different timing based on pop-out mode
                if (volumeDial.isInPopOutMode()) {
                    // In pop-out mode, extend the timer to match the 5-second auto-return + buffer
                    hideOverlayHandler.postDelayed(hideOverlayRunnable, 4000)
                } else {
                    // Normal mode, use standard timing
                    hideOverlayHandler.postDelayed(hideOverlayRunnable, 2000)
                }

                Log.d("VolumeOverlayService", "Touch ended on dial, pop-out mode: ${volumeDial.isInPopOutMode()}")
            }

            override fun onDismiss() {
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
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
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
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.TRANSLUCENT
            width = initialWidth // Use calculated width instead of WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START // Align to the top-left corner
            windowAnimations = android.R.style.Animation_Activity
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
            // Get volume from intent
            val currentVolume = intent.getIntExtra("CURRENT_VOLUME", -1)
            val maxVolume = intent.getIntExtra("MAX_VOLUME", -1)

            if (currentVolume != -1 && maxVolume != -1) {
                Log.d("VolumeOverlayService", "Received volume: $currentVolume/$maxVolume")

                // Convert to percentage (0-100)
                val volumePercentage = (currentVolume * 100) / maxVolume
                updateVolumeUI(volumePercentage)
            } else {
                // If no volume data, use current system volume
                val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumePercentage = (volume * 100) / max
                updateVolumeUI(volumePercentage)
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

        if (intent?.action != "HIDE_OVERLAY") {
            showOverlay()
        }


        return START_NOT_STICKY
    }

    private fun updateVolumeUI(volumePercentage: Int) {
        // Update the stored precise volume level
        preciseVolumeLevel = volumePercentage

        // Update the dial view
        volumeDial.volume = volumePercentage

        // Notify dial of external volume change to reset pop-out timer
        volumeDial.onExternalVolumeChange()

        // Handle overlay timer extension for volume button interaction
        if (volumeDial.isInPopOutMode()) {
            // In pop-out mode, extend overlay visibility timer
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            hideOverlayHandler.postDelayed(hideOverlayRunnable, 4000)
            Log.d("VolumeOverlayService", "Volume button pressed in pop-out mode - extended overlay timer")
        } else {
            // In normal mode, reset overlay timer if not currently touching
            if (!isTouching) {
                hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
                hideOverlayHandler.postDelayed(hideOverlayRunnable, 2000)
            }
        }

        // Update the number only if display is enabled AND not in pop-out mode
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)

        if (volumeNumberDisplayEnabled && !volumeDial.isInPopOutMode()) {
            volumeNumber.text = volumePercentage.toString()
            volumeNumber.visibility = View.VISIBLE

            // Cancel any pending number hiding
            hideNumberHandler.removeCallbacks(hideNumberRunnable)

            // Only schedule hiding if not currently touching
            if (!isTouching) {
                hideNumberHandler.postDelayed(hideNumberRunnable, 2000)
            }
        } else {
            volumeNumber.visibility = View.GONE
            // Also cancel number hiding timer when hiding the number
            hideNumberHandler.removeCallbacks(hideNumberRunnable)
        }

        Log.d("VolumeOverlayService", "Volume UI updated - External change, In pop-out: ${volumeDial.isInPopOutMode()}, Number visible: ${volumeNumber.visibility}")
    }

    private fun updateSystemVolume(volumePercentage: Int) {
        preciseVolumeLevel = volumePercentage

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (volumePercentage * maxVolume) / 100  // Convert 100 steps to system range

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9+ (Pie) - Use hidden API for fine control
            try {
                val audioSystemClass = Class.forName("android.media.AudioSystem")
                val setVolumeIndexMethod = audioSystemClass.getMethod(
                    "setStreamVolumeIndex",
                    Int::class.java,
                    Int::class.java,
                    Int::class.java
                )

                val DEVICE_OUT_SPEAKER = 2 // Speaker output device
                val preciseIndex = (volumePercentage * 1000) / 100  // Scale for precision

                setVolumeIndexMethod.invoke(null, AudioManager.STREAM_MUSIC, preciseIndex, DEVICE_OUT_SPEAKER)

                // Also update system UI to match
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                Log.d("VolumeOverlayService", "Precise volume set using AudioSystem API")
            } catch (e: Exception) {
                Log.e("VolumeOverlayService", "Error setting precise volume: ${e.message}")
                // Fallback to standard volume setting
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            }
        } else {
            // Android 7-8.1 (API 24-27) - Use standard AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        }

        Log.d("VolumeOverlayService", "System volume set to $volumePercentage%")
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

        // Only schedule hiding if not currently touching - respect pop-out mode timing
        if (!isTouching) {
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            val timeout = if (volumeDial.isInPopOutMode()) 4000L else 2000L
            hideOverlayHandler.postDelayed(hideOverlayRunnable, timeout)
            Log.d("VolumeOverlayService", "showOverlay() - scheduled hide timeout: ${timeout}ms, pop-out mode: ${volumeDial.isInPopOutMode()}")
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
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
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

        Log.d("VolumeOverlayService", "Settings initialized - Scale: $savedScaleFactor, Text: $scaledTextSize, Haptic: $hapticEnabled, Strength: $hapticStrength, VolumeDisplay: $volumeNumberDisplayEnabled")
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

    private fun handlePopOutModeChange(isInPopOutMode: Boolean) {
        if (isInPopOutMode) {
            // Entering pop-out mode - extend overlay visibility
            hideOverlayHandler.removeCallbacks(hideOverlayRunnable)
            // Set a longer timeout for pop-out mode (7 seconds to accommodate 5s + 2s buffer)
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

            // Hide volume number during pop-out
            if (volumeDial.isInPopOutMode()) {
                volumeNumber.visibility = View.GONE
            } else {
                // Only show if volume number display is enabled in settings and not in pop-out mode
                val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)
                volumeNumber.visibility = if (volumeNumberDisplayEnabled) View.VISIBLE else View.GONE

                // Update volume number position to move with the center of the dial
                val volumeNumberParams = volumeNumber.layoutParams as FrameLayout.LayoutParams
                val baseMargin = (10 * scaleFactor).toInt()
                val popOutOffset = (radius * progress).toInt()
                volumeNumberParams.marginStart = baseMargin + popOutOffset
                volumeNumber.layoutParams = volumeNumberParams
            }

            Log.d("VolumeOverlayService", "Pop-out progress: $progress, In pop-out mode: ${volumeDial.isInPopOutMode()}, Volume number visibility: ${volumeNumber.visibility}")

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

                // Reset volume number visibility to user preference
                val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)
                volumeNumber.visibility = if (volumeNumberDisplayEnabled) View.VISIBLE else View.GONE

                Log.d("VolumeOverlayService", "Reset to semi-circle state - volume number visibility: ${volumeNumber.visibility}")
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

            // Reset volume number visibility to user preference
            val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val volumeNumberDisplayEnabled = appPrefs.getBoolean("volume_number_display_enabled", true)
            volumeNumber.visibility = if (volumeNumberDisplayEnabled) View.VISIBLE else View.GONE

            // Reset volume number position to base margin
            val volumeNumberParams = volumeNumber.layoutParams as FrameLayout.LayoutParams
            val scaleFactor = appPrefs.getFloat("wheel_scale_factor", 0.975f)
            volumeNumberParams.marginStart = (10 * scaleFactor).toInt()
            volumeNumber.layoutParams = volumeNumberParams

            Log.d("VolumeOverlayService", "Cleanup after hide completed")
        } catch (e: Exception) {
            Log.e("VolumeOverlayService", "Error during cleanup after hide: ${e.message}")
        }
    }
}
