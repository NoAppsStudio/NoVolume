package com.nostudio.novolumeslider

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.os.Handler
import kotlin.math.cos
import kotlin.math.sin
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.core.content.res.ResourcesCompat
import android.view.MotionEvent
import kotlin.math.atan2
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import android.view.HapticFeedbackConstants
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.view.Gravity
import android.os.VibratorManager
import android.os.Looper
import android.util.Log
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import android.graphics.drawable.Drawable
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuff
import android.media.AudioManager

class VolumeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Theme management
    enum class VolumeTheme {
        LIGHT,
        DARK,
        SYSTEM
    }
    
    private var currentTheme = VolumeTheme.LIGHT
    private var isDarkMode = false

    // Theme-aware color getters
    private fun getBackgroundColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_background_dark)
        else -> ContextCompat.getColor(context, R.color.volume_background_light)
    }
    
    private fun getProgressColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_progress_dark)
        else -> ContextCompat.getColor(context, R.color.volume_progress_light)
    }
    
    private fun getTextColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_text_dark)
        else -> ContextCompat.getColor(context, R.color.volume_text_light)
    }
    
    private fun getMajorMarkerColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_marker_major_dark)
        else -> ContextCompat.getColor(context, R.color.volume_marker_major_light)
    }
    
    private fun getMinorMarkerColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_marker_minor_dark)
        else -> ContextCompat.getColor(context, R.color.volume_marker_minor_light)
    }
    
    private fun getIndicatorColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_indicator_dark)
        else -> ContextCompat.getColor(context, R.color.volume_indicator_light)
    }
    
    private fun getButtonColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_button_dark)
        else -> ContextCompat.getColor(context, R.color.volume_button_light)
    }
    
    private fun getButtonTextColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_button_text_dark)
        else -> ContextCompat.getColor(context, R.color.volume_button_text_light)
    }
    
    private fun getNumberColor(): Int = when {
        isDarkMode -> ContextCompat.getColor(context, R.color.volume_number_dark)
        else -> ContextCompat.getColor(context, R.color.volume_number_light)
    }

    // Interface for volume change callbacks
    interface OnVolumeChangeListener {
        fun onVolumeChanged(volume: Int)
        fun onTouchStart()
        fun onTouchEnd()
        fun onDismiss()
        fun onSlideRight()
        fun onStreamChanged(stream: VolumeStreamManager.VolumeStream)
    }

    interface InteractionListener {
        fun onInteractionStart()
    }

    interface PopOutAnimationListener {
        fun onPopOutProgressChanged(progress: Float)
        fun onPopOutModeChanged(isInPopOutMode: Boolean)
    }

    private var interactionListener: InteractionListener? = null
    private var popOutAnimationListener: PopOutAnimationListener? = null

    fun setInteractionListener(listener: InteractionListener) {
        interactionListener = listener
    }

    fun setPopOutAnimationListener(listener: PopOutAnimationListener) {
        popOutAnimationListener = listener
    }

    // Listener variable
    public var volumeChangeListener: OnVolumeChangeListener? = null

    // Function to set the listener
    fun setOnVolumeChangeListener(listener: OnVolumeChangeListener) {
        volumeChangeListener = listener
    }

    private var isAnimating = false

    fun showWithSlideInFromLeft() {
        if (isAnimating) return
        isAnimating = true

        post {
            this.translationX = -width.toFloat()
            this.alpha = 0f

            this.animate()
                .translationX(0f)
                .alpha(1f)
                .setInterpolator(DecelerateInterpolator())
                .setDuration(600)
                .withEndAction {
                    isAnimating = false
                }
                .start()
        }
    }

    fun setAnimationDuration(duration: Int) {
        animationDuration = duration
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupClickThroughBehavior() // Set up click-through behavior when attached
        showWithSlideInFromLeft() // Apply the animation when the view is attached

        // Add a global layout listener to detect orientation changes
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // Check if the layout has changed (e.g., due to orientation change)
                if (width != measuredWidth || height != measuredHeight) {
                    onSizeChanged(measuredWidth, measuredHeight, 0, 0)
                }
            }        })
    }
    
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        // Update theme if in system mode when configuration changes (e.g., dark mode toggle)
        if (currentTheme == VolumeTheme.SYSTEM) {
            updateTheme()
            invalidate()
        }
    }

    private fun setupClickThroughBehavior() {
        // Get the parent FrameLayout
        val parentLayout = parent as? FrameLayout

        parentLayout?.setOnTouchListener { _, event ->
            // Convert parent coordinates to this view's coordinates
            val localX = event.x - left
            val localY = event.y - top

            // Calculate distance from center of dial
            val distance = Math.sqrt(
                ((localX - centerX) * (localX - centerX) +
                        (localY - centerY) * (localY - centerY)).toDouble()
            ).toFloat()

            // If touch is outside the dial radius
            if (distance > radius) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Notify listener to dismiss the overlay
                        volumeChangeListener?.onDismiss()
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Clean up any stuck touch state if user releases outside
                        if (isCurrentlyTouching) {
                            Log.d("VolumeDialView", "Touch released outside dial - cleaning up stuck state")
                            cleanupTouch()
                            volumeChangeListener?.onTouchEnd()
                            if (isPopOutMode) {
                                schedulePopOutReturn()
                            }
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false // Let other touches pass through
        }
    }

    private val customTypeface: Typeface? = ResourcesCompat.getFont(context, R.font.ntype)

    // Track touch state
    private var isTouching = false

    private val backgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val progressPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND // Rounded ends for the arc
    }

    private val markerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f //slightly bolder
        isAntiAlias = true
    }

    private val indicatorPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }

    // Paint for the multi-stream buttons in pop-out mode
    private val multiStreamButtonPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Paint for background during click animation
    private val whiteBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Paint for the labels on the multi-stream buttons
    private val buttonLabelPaint = Paint().apply {
        textSize = 18f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = customTypeface
        isFakeBoldText = true
    }

    private val numbersPaint = Paint().apply {
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = customTypeface
    }

    private val oval = RectF()
    private var radius = 0f
    private var progressArcRadius = 0f
    private var centerX = 0f
    private var centerY = 0f

    var volume: Int = 64
        set(value) {
            val newValue = value.coerceIn(0, 100)
            if (field != newValue) {
                // Trigger haptic feedback
                if (lastVolumeForHaptic != newValue && lastVolumeForHaptic != -1) {
                    performHapticFeedback()
                }
                lastVolumeForHaptic = newValue

                // Only animate if not currently touching the dial
                if (!isTouching) {
                    ObjectAnimator.ofInt(this, "animatedVolume", field, newValue).apply {
                        duration = 90
                        start()
                    }
                } else {
                    animatedVolume = newValue
                }
            }
            field = newValue
        }

    // Internal variable for animation
    private var animatedVolume: Int = volume
        set(value) {
            field = value
            invalidate() // Redraw for animation effect
        }

    private var animationDuration: Int = 600

    // Wheel size scaling factor (0.6 to 1.1)
    private var scaleFactor: Float = 1.0f

    // Volume number display state -
    private var isVolumeNumberDisplayEnabled: Boolean = true

    // Progress bar display state
    private var isProgressBarDisplayEnabled: Boolean = true

    // Haptic feedback properties
    private var isHapticEnabled: Boolean = true
    private var vibrator: Vibrator? = null
    private var lastVolumeForHaptic: Int = -1
    private var hapticStrength: Int = 1 // 0=Low, 1=Medium, 2=High

    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ - Use VibratorManager
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            // API 24-30
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        
        // Initialize theme and colors
        updateTheme()
    }
    
    /**
     * Update all paint colors based on current theme
     */
    private fun updatePaintColors() {
        backgroundPaint.color = getBackgroundColor()
        progressPaint.color = getProgressColor()
        markerPaint.color = getMajorMarkerColor() // Major markers (10, 20, 30, etc.)
        indicatorPaint.color = getIndicatorColor()
        multiStreamButtonPaint.color = getButtonColor()
        whiteBackgroundPaint.color = getBackgroundColor() // Use background color for click animation
        buttonLabelPaint.color = getButtonTextColor()
        numbersPaint.color = getTextColor()
    }
    
    /**
     * Update theme based on current setting and system state
     */
    private fun updateTheme() {
        isDarkMode = when (currentTheme) {
            VolumeTheme.LIGHT -> false
            VolumeTheme.DARK -> true
            VolumeTheme.SYSTEM -> isSystemInDarkMode()
        }
        updatePaintColors()
    }
    
    /**
     * Check if system is in dark mode
     */
    private fun isSystemInDarkMode(): Boolean {
        return when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }
    
    /**
     * Set the volume theme
     */
    fun setVolumeTheme(theme: VolumeTheme) {
        if (currentTheme != theme) {
            currentTheme = theme
            updateTheme()
            invalidate() // Redraw with new colors
        }
    }
    
    /**
     * Get the current volume theme
     */
    fun getVolumeTheme(): VolumeTheme = currentTheme

    fun setHapticEnabled(enabled: Boolean) {
        isHapticEnabled = enabled
    }

    fun setHapticStrength(strength: Int) {
        hapticStrength = strength.coerceIn(0, 2)
    }

    private fun performHapticFeedback() {
        if (!isHapticEnabled) return

        // Haptic Strength values
        val duration = when (hapticStrength) {
            0 -> 25L  // Low - High duration
            1 -> 15L  // Medium
            2 -> 5L   // High -Low duration
            else -> 15L
        }

        val amplitude = when (hapticStrength) {
            0 -> VibrationEffect.DEFAULT_AMPLITUDE     // Low - High amplitude
            1 -> VibrationEffect.DEFAULT_AMPLITUDE / 2 // Medium
            2 -> VibrationEffect.DEFAULT_AMPLITUDE / 4 // High - Low amplitude
            else -> VibrationEffect.DEFAULT_AMPLITUDE / 2
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                // For API 24-25 suppress deprecation
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration)
            }
        } catch (e: Exception) {
            // Fallback to view haptic feedback
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun setWheelSize(scaleFactor: Float) {
        this.scaleFactor = scaleFactor.coerceIn(0.6f, 1.1f)
        // Update text size with new scale factor
        updateDialTextSize()
        // Trigger size recalculation
        onSizeChanged(width, height, 0, 0)
        invalidate()
    }

    fun pxToDp(context: Context, px: Int): Int {
        val density = context.resources.displayMetrics.density
        return (px / density).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Don't constrain parent width - let the overlay service handle container sizing

        // Align the view
        val layoutParams = layoutParams as? FrameLayout.LayoutParams
        layoutParams?.leftMargin = 0
        layoutParams?.rightMargin = 0
        layoutParams?.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        requestLayout()

        // Base dimensions for semi-circle
        val basePadding = 40f // Reduced padding for closer to edge
        val baseRadius = Math.min(w, h) / 2f - basePadding

        //  Scaling apply
        radius = baseRadius * scaleFactor
        centerY = h / 2f

        // Scale progress arc radius proportionally
        progressArcRadius = radius + (60f * scaleFactor)

        // Update paint stroke widths to scale with size
        progressPaint.strokeWidth = 24f * scaleFactor
        markerPaint.strokeWidth = 4f * scaleFactor
        indicatorPaint.strokeWidth = 7f * scaleFactor

        // Scale text size for numbers on the dial
        updateDialTextSize()

        updateCenterXAndOval()
    }

    private fun updateCenterXAndOval() {
        // Calculate centerX based on pop-out animation progress
        // In semi-stae mode: centerX = 0 (at screen edge)
        // In full-state mode: centerX = radius (fully visible circle)
        centerX = popOutAnimationProgress * radius

        oval.set(centerX - progressArcRadius, centerY - progressArcRadius, centerX + progressArcRadius, centerY + progressArcRadius)
    }

    var startAngle: Float = 75f  // Default starting angle
    var endAngle: Float = 224f  // Ending angle

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Update centerX and oval based on current animation progress
        updateCenterXAndOval()

        // Save the canvas state before transformations
        canvas.save()

        // Dynamic clipping based on pop-out animation
        if (isPopOutMode) {
            // In pop-out mode, progressively show more of the circle from right semi to full circle
            // Start from right edge (centerX) and expand leftward as animation progresses
            val clipLeft = centerX - (radius * popOutAnimationProgress)
            canvas.clipRect(clipLeft, 0f, width.toFloat(), height.toFloat())
        } else {
            // In semi-circle mode, clip to only show the right half
            canvas.clipRect(centerX, 0f, width.toFloat(), height.toFloat())
        }

        // Draw the full background circle
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // Create a paint object for the background arc
        val backgroundArcPaint = Paint().apply {
            color = if (isDarkMode) {
                ContextCompat.getColor(context, R.color.volume_accent_gray)
            } else {
                Color.LTGRAY
            }
            style = Paint.Style.STROKE
            strokeWidth = 24f * scaleFactor
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            alpha = 100 // Set transparency for the background arc
        }

        // Draw the full background arc (always at 100% volume level) - only if progress bar is enabled
        if (isProgressBarDisplayEnabled) {
            canvas.drawArc(oval, startAngle, -(endAngle - startAngle), false, backgroundArcPaint)

            // Now draw the animated volume progress arc
            // Apply visual constraint for alarm - never show below 5%
            val displayVolume = if (currentActiveStream == VolumeStreamManager.VolumeStream.ALARM) {
                // For alarm, ensure minimum 5% display but map 0-100% input to 5-100% display
                if (animatedVolume == 0) 5 else animatedVolume.coerceIn(5, 100)
            } else {
                animatedVolume
            }
            val sweepAngle = -(displayVolume * (endAngle - startAngle)) / 100f
            canvas.drawArc(oval, startAngle, sweepAngle, false, progressPaint)
        }

        // Rotate the canvas to align with the animated volume progress
        // Apply the same constraint logic for alarm dial rotation
        val dialRotationVolume = if (currentActiveStream == VolumeStreamManager.VolumeStream.ALARM) {
            // For alarm, ensure minimum 5% for dial rotation as well
            if (animatedVolume == 0) 5 else animatedVolume.coerceIn(5, 100)
        } else {
            animatedVolume
        }
        val rotationAngle = (135f - (dialRotationVolume * (280f - 10f) / 100f))
        canvas.rotate(rotationAngle, centerX, centerY)

        // Draw all markers and numbers in the rotated context
        drawMarkersAndNumbers(canvas, centerX, centerY)

        // Restore the canvas to its original state
        canvas.restore()

        // Draw the four multi-stream buttons with beautiful animation (in unrotated context)
        if (isPopOutMode) {
            drawMultiStreamButtons(canvas, centerX, centerY)
        }

        // Draw quick action buttons if in quick action mode
        if (isQuickActionMode) {
            drawQuickActionButtons(canvas, centerX, centerY)
        }

        // Draw the fixed indicator at the rightmost point (0 degrees) - scaled
        val indicatorLength = 30f * scaleFactor
        val indicatorOffset = 15f * scaleFactor
        val indicatorStartX = centerX + (radius - indicatorLength) + indicatorOffset
        val indicatorStartY = centerY
        val indicatorEndX = centerX + radius + indicatorOffset
        val indicatorEndY = centerY

        // Indicator line remains in its original position in the restored canvas
        canvas.drawLine(indicatorStartX, indicatorStartY, indicatorEndX, indicatorEndY, indicatorPaint)
    }

    private fun drawMarkersAndNumbers(canvas: Canvas, centerX: Float, centerY: Float) {
        // Scale gaps and distances with the wheel size
        val gap = 30f * scaleFactor
        val minorMarkerPaint = Paint().apply {
            color = getMinorMarkerColor() // Use theme-aware minor marker color
            style = Paint.Style.STROKE
            strokeWidth = 2f * scaleFactor
            isAntiAlias = true
        }

        // Use a consistent distance for all numbers that scales
        val numberGap = 60f * scaleFactor
        val numbersRadius = radius - gap - numberGap

        for (i in 0..10) {
            val angle = -135f + (i * 270f / 10f)
            val angleRadians = Math.toRadians(angle.toDouble())

            // Keep dial lines static - no animation
            val markerLength = 15f * scaleFactor
            val startX = centerX + (radius - markerLength - gap) * cos(angleRadians).toFloat()
            val startY = centerY + (radius - markerLength - gap) * sin(angleRadians).toFloat()
            val endX = centerX + (radius - gap) * cos(angleRadians).toFloat()
            val endY = centerY + (radius - gap) * sin(angleRadians).toFloat()

            canvas.drawLine(startX, startY, endX, endY, markerPaint)

            // Draw numbers with fade animation during pop-out transition or quick action mode
            // Skip dial number animation during timeout transition from center mode
            if ((popOutAnimationProgress < 1f && !(isInCenterMode && popOutAnimationProgress > 0f)) || isQuickActionMode) {
                // Calculate fade alpha based on mode
                val fadeAlpha = if (isQuickActionMode) {
                    // In quick action mode, fade out numbers as they spread
                    (1f - quickActionAnimationProgress * quickActionAnimationProgress).coerceIn(0f, 1f)
                } else {
                    // Normal pop-out mode fade
                    (1f - popOutAnimationProgress * popOutAnimationProgress).coerceIn(0f, 1f)
                }

                // Animate numbers spreading outward and fading with gentler movement
                val baseNumbersRadius = radius - gap - numberGap
                val extendedNumbersRadius = radius - gap + (numberGap * 0.3f) // Reduced spread for subtler effect

                val animationProgress = if (isQuickActionMode) quickActionAnimationProgress else popOutAnimationProgress
                val animatedNumbersRadius = baseNumbersRadius + (extendedNumbersRadius - baseNumbersRadius) * animationProgress

                val textX = centerX + animatedNumbersRadius * cos(angleRadians).toFloat()
                val textY = centerY + animatedNumbersRadius * sin(angleRadians).toFloat()

                // Save canvas state before rotating text
                canvas.save()

                // Move canvas to the text position
                canvas.translate(textX, textY)

                // Ensure numbers are always upright
                var textRotationAngle = angle - 90
                if (textRotationAngle > 180) {
                    textRotationAngle -= 180
                }

                canvas.rotate(textRotationAngle)

                // Apply fade alpha to the paint
                val originalAlpha = numbersPaint.alpha
                numbersPaint.alpha = (originalAlpha * fadeAlpha).toInt()

                // Calculate numbers in reverse order: 100, 90, 80, ... (for inverted volume)
                val numberText = (i * 10).toString()
                canvas.drawText(numberText, 0f, numbersPaint.textSize / 3, numbersPaint)

                // Restore original alpha
                numbersPaint.alpha = originalAlpha

                // Restore canvas
                canvas.restore()
            }
        }

        // Draw the minor markers with scaled dimensions - no animation
        for (i in 0..9) {
            val startAngle = -135f + (i * 270f / 10f)
            val endAngle = -135f + ((i + 1) * 270f / 10f)

            for (j in 1..9) {
                val angle = startAngle + (j * (endAngle - startAngle) / 10f)
                val angleRadians = Math.toRadians(angle.toDouble())

                // Keep minor markers static - no animation
                val markerLength = 10f * scaleFactor
                val markerStartX = centerX + (radius - markerLength - gap) * cos(angleRadians).toFloat()
                val markerStartY = centerY + (radius - markerLength - gap) * sin(angleRadians).toFloat()
                val markerEndX = centerX + (radius - gap) * cos(angleRadians).toFloat()
                val markerEndY = centerY + (radius - gap) * sin(angleRadians).toFloat()

                canvas.drawLine(markerStartX, markerStartY, markerEndX, markerEndY, minorMarkerPaint)
            }
        }
    }

    private fun drawMultiStreamButtons(canvas: Canvas, centerX: Float, centerY: Float) {
        // Only draw if in pop-out mode
        if (!isPopOutMode) return

        // Calculate the radius for the circles
        val circleRadius = 90f * scaleFactor

        // Calculate the distance from center where circles should be positioned
        val distanceFromCenter = (radius * 0.58f)

        // Array of angles for positioning (forming a square)
        val angles = arrayOf(45.0, 135.0, 225.0, 315.0)

        // Handle click animation first
        if (isClickAnimating) {
            drawClickAnimation(canvas, centerX, centerY, circleRadius, distanceFromCenter, angles)
            return
        }

        // Draw central spinning circle during early animation phase
        if (buttonAnimationPhase < 0.4f) {
            val spinnerProgress = (buttonAnimationPhase / 0.4f).coerceIn(0f, 1f)
            
            // Apply exit fade during pop-out exit animation
            val isExiting = popOutAnimationProgress < 1f && buttonAnimationPhase > 0f
            val adjustedSpinnerProgress = if (isExiting) {
                spinnerProgress * popOutAnimationProgress
            } else {
                spinnerProgress
            }
            
            if (adjustedSpinnerProgress > 0f) {
                val spinnerRadius = circleRadius * centralSpinnerScale * adjustedSpinnerProgress

                // Save canvas state for spinner
                canvas.save()
                canvas.rotate(centralSpinnerRotation, centerX, centerY)

                // Draw spinning central circle with pulsing effect
                val pulseScale = 1f + 0.2f * sin(centralSpinnerRotation * Math.PI / 180f).toFloat()
                
                // Apply fade during exit
                val originalAlpha = multiStreamButtonPaint.alpha
                multiStreamButtonPaint.alpha = (originalAlpha * adjustedSpinnerProgress).toInt()
                
                canvas.drawCircle(centerX, centerY, spinnerRadius * pulseScale, multiStreamButtonPaint)
                
                // Restore alpha
                multiStreamButtonPaint.alpha = originalAlpha

                // Restore canvas
                canvas.restore()
            }
        }

        // Draw individual buttons with staggered spawn animation
        for (i in 0..3) {
            val angle = angles[i]

            // Calculate spawn progress for this button (staggered timing)
            val buttonDelay = i * 0.1f // 100ms delay between each button
            val buttonStartTime = 0.3f + buttonDelay // Start after spinner begins
            val rawButtonProgress = ((buttonAnimationPhase - buttonStartTime) / 0.4f).coerceIn(0f, 1f)
            
            // Apply reverse animation when popOutAnimationProgress is decreasing (exit animation)
            val isExiting = popOutAnimationProgress < 1f && buttonAnimationPhase > 0f
            val buttonProgress = if (isExiting) {
                // During exit, reverse the button animation based on popOutAnimationProgress
                rawButtonProgress * popOutAnimationProgress
            } else {
                rawButtonProgress
            }

            if (buttonProgress > 0f) {
                // Animate position from center outward with overshoot effect
                val overshootProgress = if (buttonProgress < 0.8f) {
                    // Ease out with overshoot
                    val t = buttonProgress / 0.8f
                    1f - (1f - t) * (1f - t) * (1f - t)
                } else {
                    // Settle back to final position
                    val t = (buttonProgress - 0.8f) / 0.2f
                    1f - 0.1f * (1f - t) * (1f - t)
                }

                val animatedDistance = distanceFromCenter * overshootProgress
                val x = centerX + (animatedDistance * cos(Math.toRadians(angle))).toFloat()
                val y = centerY + (animatedDistance * sin(Math.toRadians(angle))).toFloat()

                // Store position for hit detection
                buttonPositions[i] = Pair(x, y)

                // Calculate scale with bounce effect
                val scaleProgress = buttonProgress
                val bounceScale = if (scaleProgress < 0.7f) {
                    // Scale up with overshoot
                    val t = scaleProgress / 0.7f
                    t * t * (3f - 2f * t) * 1.2f // Smooth step with overshoot
                } else {
                    // Settle to final size
                    val t = (scaleProgress - 0.7f) / 0.3f
                    1.2f - 0.2f * t * t * (3f - 2f * t)
                }

                val finalScale = bounceScale * buttonAnimationScales[i]

                // Calculate rotation for spinning effect during spawn/exit
                val spinRotation = if (isExiting) {
                    // During exit, spin in reverse direction
                    buttonProgress * 360f * 2f // Reverse spin back to center
                } else {
                    // During spawn, normal reverse spin
                    (1f - buttonProgress) * 360f * 2f // Two full spins while spawning
                }

                // Calculate alpha for fade-in/out effect
                val alpha = (buttonProgress * 2f).coerceIn(0f, 1f)

                // Draw button with icon
                drawButtonWithIcon(canvas, x, y, circleRadius, finalScale, spinRotation, alpha, i)
            }
        }
    }

    private fun drawButtonWithIcon(canvas: Canvas, x: Float, y: Float, radius: Float, scale: Float, rotation: Float, alpha: Float, buttonIndex: Int) {
        // Save canvas state for individual button transformation
        canvas.save()

        // Apply transformations
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scale, scale)

        // Apply alpha to the paint
        val originalAlpha = multiStreamButtonPaint.alpha
        multiStreamButtonPaint.alpha = (originalAlpha * alpha).toInt()

        // Draw the multi-stream button at origin (already translated)
        canvas.drawCircle(0f, 0f, radius, multiStreamButtonPaint)

        // Draw the icon
        if (alpha > 0.1f) { // Only draw icon if reasonably visible
            val iconDrawable = ContextCompat.getDrawable(context, getStreamIcon(buttonIndex))
            iconDrawable?.let { drawable ->
                val iconSize = (radius * 0.6f).toInt() // Icon is 60% of button size
                val iconHalf = iconSize / 2

                drawable.setBounds(-iconHalf, -iconHalf, iconHalf, iconHalf)

                // Use SRC_ATOP to preserve alpha channels while applying color
                drawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                drawable.alpha = (255 * alpha).toInt()
                drawable.draw(canvas)
            }
        }

        // Restore original alpha
        multiStreamButtonPaint.alpha = originalAlpha

        // Restore canvas
        canvas.restore()
    }

    private fun drawClickAnimation(canvas: Canvas, centerX: Float, centerY: Float, circleRadius: Float, distanceFromCenter: Float, angles: Array<Double>) {
        if (clickedButtonIndex < 0 || clickedButtonIndex >= 4) return

        val progress = clickAnimationProgress

        // Draw spreading white background - constrained to white circle area
        if (progress > 0.1f) {
            val maxBackgroundRadius = radius * 0.95f // Stay within the white circle boundary
            val backgroundRadius = maxBackgroundRadius * progress
            val baseBackgroundAlpha = (1f - progress * 0.7f).coerceIn(0.3f, 1f) // Keep more visible

            // Apply timeout fade to background
            val timeoutFade = if (popOutAnimationProgress < 1f && isInCenterMode) {
                popOutAnimationProgress // Fade with timeout transition
            } else {
                1f
            }
            val backgroundAlpha = baseBackgroundAlpha * timeoutFade

            val originalAlpha = whiteBackgroundPaint.alpha
            whiteBackgroundPaint.alpha = (originalAlpha * backgroundAlpha).toInt()
            canvas.drawCircle(centerX, centerY, backgroundRadius, whiteBackgroundPaint)
            whiteBackgroundPaint.alpha = originalAlpha
        }

        // Draw other buttons with constrained spreading - stay within white circle
        for (i in 0..3) {
            if (i == clickedButtonIndex) continue // Skip the clicked button

            val angle = angles[i]
            // Constrain spread to stay within white circle boundary
            val maxSpreadDistance = radius * 0.8f // Maximum spread within white circle
            val spreadOffset = (maxSpreadDistance - distanceFromCenter) * progress * 0.6f
            val spreadDistance = distanceFromCenter + spreadOffset
            val x = centerX + (spreadDistance * cos(Math.toRadians(angle))).toFloat()
            val y = centerY + (spreadDistance * sin(Math.toRadians(angle))).toFloat()

            // Fade out as they spread
            val fadeAlpha = (1f - progress * progress).coerceIn(0f, 1f)
            val scale = 1f - progress * 0.3f // Slightly shrink as they spread

            drawButtonWithIcon(canvas, x, y, circleRadius, scale, 0f, fadeAlpha, i)
        }

        // Draw clicked button moving to center
        val clickedAngle = angles[clickedButtonIndex]
        val centerProgress = progress * progress // Ease in to center
        val currentDistance = distanceFromCenter * (1f - centerProgress)
        val x = centerX + (currentDistance * cos(Math.toRadians(clickedAngle))).toFloat()
        val y = centerY + (currentDistance * sin(Math.toRadians(clickedAngle))).toFloat()

        // Scale up the clicked button
        // During timeout transition, scale down the center button smoothly
        val baseScale = 1f + progress * 0.5f
        val scale = if (popOutAnimationProgress < 1f && isInCenterMode) {
            baseScale * popOutAnimationProgress // Shrink with the timeout transition
        } else {
            baseScale
        }

        // Save canvas state for clicked button
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)

        // Draw button background with smooth transition
        val backgroundTransition = (progress * 2f).coerceIn(0f, 1f)

        // Apply timeout fade to all background elements
        val timeoutFade = if (popOutAnimationProgress < 1f && isInCenterMode) {
            popOutAnimationProgress // Fade with timeout transition
        } else {
            1f
        }

        if (backgroundTransition < 1f) {
            // Blend red to white
            val redAlpha = (1f - backgroundTransition) * timeoutFade
            val whiteAlpha = backgroundTransition * timeoutFade

            // Draw red background first
            val originalRedAlpha = multiStreamButtonPaint.alpha
            multiStreamButtonPaint.alpha = (originalRedAlpha * redAlpha).toInt()
            canvas.drawCircle(0f, 0f, circleRadius, multiStreamButtonPaint)
            multiStreamButtonPaint.alpha = originalRedAlpha

            // Draw white background on top
            val originalWhiteAlpha = whiteBackgroundPaint.alpha
            whiteBackgroundPaint.alpha = (originalWhiteAlpha * whiteAlpha).toInt()
            canvas.drawCircle(0f, 0f, circleRadius, whiteBackgroundPaint)
            whiteBackgroundPaint.alpha = originalWhiteAlpha
        } else {
            // Fully white with timeout fade
            val originalWhiteAlpha = whiteBackgroundPaint.alpha
            whiteBackgroundPaint.alpha = (originalWhiteAlpha * timeoutFade).toInt()
            canvas.drawCircle(0f, 0f, circleRadius, whiteBackgroundPaint)
            whiteBackgroundPaint.alpha = originalWhiteAlpha
        }

        // Draw icon with proper color transition and transparency preservation
        val iconDrawable = ContextCompat.getDrawable(context, getStreamIcon(clickedButtonIndex))
        iconDrawable?.let { drawable ->
            val iconSize = (circleRadius * 0.6f).toInt()
            val iconHalf = iconSize / 2

            drawable.setBounds(-iconHalf, -iconHalf, iconHalf, iconHalf)

            // Transition from white to black as background becomes white
            val colorTransition = (progress * 2f - 0.5f).coerceIn(0f, 1f)
            val iconColor = if (colorTransition > 0f) {
                // Interpolate from white to black
                val grayValue = (255 * (1f - colorTransition)).toInt()
                Color.rgb(grayValue, grayValue, grayValue)
            } else {
                Color.WHITE
            }

            // Use SRC_ATOP to preserve alpha channels while applying color
            drawable.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_ATOP)

            // Apply timeout fade to icon alpha
            val iconAlpha = (255 * timeoutFade).toInt()
            drawable.alpha = iconAlpha

            drawable.draw(canvas)
        }

        canvas.restore()

        // Draw dial numbers when button is transitioning to center (after 50% progress)
        // During timeout transition, preserve existing dial numbers and fade them with the transition
        if (progress > 0.5f) {
            val numbersProgress = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
            // During timeout transition, adjust alpha based on pop-out animation progress
            val finalAlpha = if (popOutAnimationProgress < 1f && isInCenterMode) {
                numbersProgress * popOutAnimationProgress // Fade out with the timeout transition
            } else {
                numbersProgress
            }
            drawDialNumbersInCenterMode(canvas, centerX, centerY, finalAlpha)
        }
    }

    private fun drawDialNumbersInCenterMode(canvas: Canvas, centerX: Float, centerY: Float, progress: Float) {
        // Use the same rotation logic as the main dial - rotate canvas to align with volume
        val rotationAngle = (135f - (animatedVolume * (280f - 10f) / 100f))
        canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)

        // Now draw the dial numbers using the same logic as the main dial
        drawMarkersAndNumbersForCenterMode(canvas, centerX, centerY, progress)

        canvas.restore()
    }

    private fun drawMarkersAndNumbersForCenterMode(canvas: Canvas, centerX: Float, centerY: Float, fadeAlpha: Float) {
        // Use the same positioning logic as the original drawMarkersAndNumbers
        val gap = 30f * scaleFactor
        val numberGap = 60f * scaleFactor

        // Use the same spread animation logic as the main dial
        val baseNumbersRadius = radius - gap - numberGap
        val extendedNumbersRadius = radius - gap + (numberGap * 0.3f) // Same as main dial

        // Reverse the spread animation: start spread out, animate to normal position
        val spreadProgress = 1f - fadeAlpha // Reverse the fade alpha for spread-in effect
        val animatedNumbersRadius = baseNumbersRadius + (extendedNumbersRadius - baseNumbersRadius) * spreadProgress

        for (i in 0..10) {
            val angle = -135f + (i * 270f / 10f)
            val angleRadians = Math.toRadians(angle.toDouble())

            // Draw numbers with spread animation
            val textX = centerX + animatedNumbersRadius * cos(angleRadians).toFloat()
            val textY = centerY + animatedNumbersRadius * sin(angleRadians).toFloat()

            // Save canvas state before rotating text
            canvas.save()

            // Move canvas to the text position
            canvas.translate(textX, textY)

            // Ensure numbers are always upright
            var textRotationAngle = angle - 90
            if (textRotationAngle > 180) {
                textRotationAngle -= 180
            }

            canvas.rotate(textRotationAngle)

            // Apply fade alpha to the paint
            val originalAlpha = numbersPaint.alpha
            numbersPaint.alpha = (originalAlpha * fadeAlpha).toInt()

            // Calculate numbers in reverse order: 100, 90, 80, ... (for inverted volume)
            val numberText = (i * 10).toString()
            canvas.drawText(numberText, 0f, numbersPaint.textSize / 3, numbersPaint)

            // Restore original alpha
            numbersPaint.alpha = originalAlpha

            // Restore canvas
            canvas.restore()
        }
    }

    // Variables to track touch movement and sliding
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasMoved = false
    private val touchThreshold = 5f // Better balance between responsiveness and stability
    private var lastTouchY = 0f // Track last Y position for vertical sliding
    private var accumulatedDelta = 0f // Accumulate small movements for fine control

    // Touch area management
    private var isCurrentlyTouching = false
    private var hasExitedTouchArea = false

    // Pop-out animation variables
    private var isPopOutMode = false
    private var popOutAnimationProgress = 0f
    private var longPressDetected = false

    // Multi-stream button variables
    // Button order: Media (M), Ring (R), Notification (N), Alarm (A)
    private val multiStreamLabels = arrayOf("M", "R", "N", "A") // Media, Ring, Notification, Alarm
    private val multiStreamNames = arrayOf("Media", "Ring", "Notification", "Alarm") // For debugging

    // Reference to VolumeStreamManager for dynamic icon selection
    private var volumeStreamManager: VolumeStreamManager? = null

    // Current active stream
    private var currentActiveStream: VolumeStreamManager.VolumeStream = VolumeStreamManager.VolumeStream.MEDIA
    private val buttonAnimationScales = floatArrayOf(1f, 1f, 1f, 1f) // Scale for animation
    private val buttonPositions = Array(4) { Pair(0f, 0f) } // Store positions for hit detection

    // Button animation variables for beautiful entrance/exit effects
    private val buttonSpawnProgress = floatArrayOf(0f, 0f, 0f, 0f) // Individual spawn progress for each button
    private var centralSpinnerRotation = 0f // Rotation angle for central spinner
    private var centralSpinnerScale = 0f // Scale for central spinner
    private var buttonAnimationPhase = 0f // Overall animation phase (0.0 to 1.0)

    // Click animation variables
    private var isClickAnimating = false
    private var clickedButtonIndex = -1
    private var clickAnimationProgress = 0f
    private var isClickAnimationReversing = false
    private var isInCenterMode = false // When a button is fully centered

    // Dial numbers animation during center mode
    private var dialNumbersProgress = 0f

    // Add tracking for multistream button interactions
    private var isInteractingWithButton = false
    private var interactingButtonIndex = -1

    // Quick action mode variables (swipe right in semi-circle mode)
    private var isQuickActionMode = false
    private var quickActionAnimationProgress = 0f
    private var quickActionReturnHandler = Handler(Looper.getMainLooper())
    private var quickActionReturnRunnable = Runnable {
        if (isQuickActionMode && !isTouching) {
            animateQuickActionOut()
        }
    }
    private val quickActionReturnDelay = 2500L // 2.5 seconds - starts animation before overlay fades

    // Quick action button positions and states
    private val quickActionButtons = Array(3) { Pair(0f, 0f) } // Ring, Media, Live Caption
    private var isInteractingWithQuickAction = false
    private var interactingQuickActionIndex = -1

    // Swipe detection for quick actions
    private var initialSwipeX = 0f
    private var initialSwipeY = 0f
    private var isHorizontalSwipe = false
    private var isVerticalSwipe = false
    private var swipeDetected = false
    private val swipeThreshold = 50f // Minimum distance for swipe detection
    private val swipeAngleThreshold = 30f // Maximum angle deviation for horizontal swipe

    // Volume state management for quick toggles
    private var savedMediaVolume = 50 // Store volume before muting
    private var savedRingVolume = 50 // Store ring volume before silence/vibrate
    private var savedNotificationVolume = 50 // Store notification volume

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!hasMoved && isTouching) {
            longPressDetected = true
            animatePopOut()
            // Provide stronger haptic feedback for pop-out
            performLongPressHapticFeedback()
        }
    }
    private val longPressDelay = 500L // 500ms for long press detection

    // Auto-return timer for pop-out mode
    private val popOutReturnHandler = Handler(Looper.getMainLooper())
    private val popOutReturnRunnable = Runnable {
        if (isPopOutMode && !isTouching) {
            // If in center mode, use smooth timeout transition
            if (isInCenterMode || isClickAnimating) {
                animateTimeoutTransitionToSemi()
            } else {
                // Normal case: directly animate back to semi-circle
                animatePopIn()
            }
        }
    }
    private val popOutReturnDelay = 3000L // 3 second

    private fun performLongPressHapticFeedback() {
        if (!isHapticEnabled) return // Respect the haptic toggle

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use haptic strength settings for pop-out activation
                val duration = when (hapticStrength) {
                    0 -> 30L  // Low - shorter duration
                    1 -> 50L  // Medium - standard duration
                    2 -> 70L  // High - longer duration
                    else -> 50L
                }

                val amplitude = when (hapticStrength) {
                    0 -> VibrationEffect.DEFAULT_AMPLITUDE / 2  // Low
                    1 -> VibrationEffect.DEFAULT_AMPLITUDE      // Medium
                    2 -> VibrationEffect.DEFAULT_AMPLITUDE      // High (same as medium for long press)
                    else -> VibrationEffect.DEFAULT_AMPLITUDE
                }

                vibrator?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val duration = when (hapticStrength) {
                    0 -> 30L  // Low
                    1 -> 50L  // Medium
                    2 -> 70L  // High
                    else -> 50L
                }
                vibrator?.vibrate(duration)
            }
        } catch (e: Exception) {
            if (isHapticEnabled) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    private fun animatePopOut() {
        if (isPopOutMode) return

        // If quick action mode is active, animate it out at the same speed as dial numbers
        if (isQuickActionMode) {
            animateQuickActionOut()
        }

        val animator = ObjectAnimator.ofFloat(this, "popOutProgress", 0f, 1f).apply {
            duration = 600 // Increased from 300ms to 600ms for slower, more appealing animation
            interpolator = DecelerateInterpolator(2.0f) // Increased deceleration for smoother effect
            addUpdateListener {
                popOutAnimationProgress = it.animatedValue as Float
                invalidate()
                // Update the parent layout to accommodate the new positioning
                updatePopOutLayout()
            }
            doOnEnd {
                // Start the auto-return timer when pop-out animation completes
                schedulePopOutReturn()
            }
        }
        isPopOutMode = true
        // Notify listener when in pop-out mode
        popOutAnimationListener?.onPopOutModeChanged(true)
        animator.start()
    }

    private fun animateTimeoutTransitionToSemi() {
        if (!isPopOutMode) return

        // Cancel any pending auto-return
        cancelPopOutReturn()

        // Create a smooth transition that:
        // 1. Keeps everything as is (no jarring resets)
        // 2. Just shrinks and fades the center dial
        // 3. Skips dial number spreading since we already have dial numbers from center mode

        val animator = ObjectAnimator.ofFloat(this, "popOutProgress", 1f, 0f).apply {
            duration = 600 // Smooth transition duration
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                popOutAnimationProgress = it.animatedValue as Float

                // During timeout transition, preserve center mode state until the end
                // This prevents multiple animations from conflicting

                invalidate()
                updatePopOutLayout()
            }
            doOnEnd {
                isPopOutMode = false
                // Clean up center mode state only at the end of transition
                isClickAnimating = false
                isClickAnimationReversing = false
                isInCenterMode = false
                clickedButtonIndex = -1
                clickAnimationProgress = 0f
                dialNumbersProgress = 0f
                // Clear the centered stream state
                clearCenteredStreamState()

                // Notify listener when pop-out mode exited
                popOutAnimationListener?.onPopOutModeChanged(false)
                Log.d("VolumeDialView", "Smooth timeout transition completed - cleaned up center state")
            }
        }

        Log.d("VolumeDialView", "Starting smooth timeout transition from center mode to semi-circle")
        animator.start()
    }

    private fun animatePopIn() {
        if (!isPopOutMode) return

        // Cancel any pending auto-return
        cancelPopOutReturn()

        val animator = ObjectAnimator.ofFloat(this, "popOutProgress", 1f, 0f).apply {
            duration = 500 // Increased from 250ms to 500ms for slower return animation
            interpolator = DecelerateInterpolator(1.8f) // Increased deceleration for smoother return
            addUpdateListener {
                popOutAnimationProgress = it.animatedValue as Float
                invalidate()
                updatePopOutLayout()
            }
            doOnEnd {
                isPopOutMode = false
                // Clean up any center mode or click animation state when returning to semi-circle
                if (isInCenterMode || isClickAnimating) {
                    isClickAnimating = false
                    isClickAnimationReversing = false
                    isInCenterMode = false
                    clickedButtonIndex = -1
                    clickAnimationProgress = 0f
                    dialNumbersProgress = 0f
                    // Clear the centered stream state
                    clearCenteredStreamState()
                    Log.d("VolumeDialView", "Pop-in animation completed - cleaned up center/animation state")
                }
                // Notify listener when pop-out mode exited
                popOutAnimationListener?.onPopOutModeChanged(false)
            }
        }
        animator.start()
    }

    private fun animateQuickActionIn() {
        if (isQuickActionMode) return

        val animator = ObjectAnimator.ofFloat(this, "quickActionProgress", 0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                quickActionAnimationProgress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                // Start the auto-return timer when quick action animation completes
                scheduleQuickActionReturn()
            }
        }
        isQuickActionMode = true
        animator.start()
        Log.d("VolumeDialView", "Quick action mode activated")
    }

    private fun animateQuickActionOut() {
        if (!isQuickActionMode) return

        // Cancel any pending auto-return
        cancelQuickActionReturn()

        val animator = ObjectAnimator.ofFloat(this, "quickActionProgress", quickActionAnimationProgress, 0f).apply {
            duration = 500 // Slightly longer for smoother reverse animation
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                quickActionAnimationProgress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                isQuickActionMode = false
                quickActionAnimationProgress = 0f
                invalidate()
                Log.d("VolumeDialView", "Quick action mode deactivated with reverse spin animation")
            }
        }
        animator.start()
        Log.d("VolumeDialView", "Started quick action reverse spin animation")
    }

    // Property for ObjectAnimator (quick action animation)
    fun setQuickActionProgress(progress: Float) {
        quickActionAnimationProgress = progress
        invalidate()
    }

    fun getQuickActionProgress(): Float {
        return quickActionAnimationProgress
    }

    private fun scheduleQuickActionReturn() {
        cancelQuickActionReturn()
        if (!isTouching && !isInteractingWithQuickAction) {
            quickActionReturnHandler.postDelayed(quickActionReturnRunnable, quickActionReturnDelay)
        }
    }

    private fun cancelQuickActionReturn() {
        quickActionReturnHandler.removeCallbacks(quickActionReturnRunnable)
    }

    // Property for ObjectAnimator
    fun setPopOutProgress(progress: Float) {
        popOutAnimationProgress = progress

        // Update button animation phase - buttons animate after dial numbers fade (starts at 0.5)
        buttonAnimationPhase = if (progress > 0.5f) {
            ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Update central spinner animation
        if (buttonAnimationPhase > 0f) {
            centralSpinnerScale = (buttonAnimationPhase * 2f).coerceIn(0f, 1f)
            centralSpinnerRotation = buttonAnimationPhase * 720f // Two full rotations during animation
        } else {
            centralSpinnerScale = 0f
            centralSpinnerRotation = 0f
        }

        invalidate()
        updatePopOutLayout()
        // Notify listener of progress change
        popOutAnimationListener?.onPopOutProgressChanged(progress)
    }

    fun getPopOutProgress(): Float {
        return popOutAnimationProgress
    }

    private fun updatePopOutLayout() {
        // Notify parent layout to update positioning
        val parentLayout = parent as? FrameLayout
        parentLayout?.requestLayout()
    }

    private fun schedulePopOutReturn() {
        // Cancel any existing timer
        cancelPopOutReturn()
        // Schedule new timer only if not currently touching dial or interacting with buttons
        if (!isTouching && !isInteractingWithButton) {
            popOutReturnHandler.postDelayed(popOutReturnRunnable, popOutReturnDelay)
        }
    }

    private fun cancelPopOutReturn() {
        popOutReturnHandler.removeCallbacks(popOutReturnRunnable)
    }

    // Call this when volume changes from external sources (volume buttons)
    fun onExternalVolumeChange() {
        if (isPopOutMode) {
            // Reset the timer when volume changes externally
            schedulePopOutReturn()
        }

        // Trigger a redraw to update icons with new volume levels
        invalidate()
    }

    /**
     * Force update of all stream icons (called when any stream volume changes)
     */
    fun updateStreamIcons() {
        invalidate()
    }

    // Getter for pop-out mode state
    fun isInPopOutMode(): Boolean {
        return isPopOutMode
    }

    // Getter for quick action mode state
    fun isInQuickActionMode(): Boolean {
        return isQuickActionMode
    }

    // Force return to semi-circle state (used when overlay is dismissed)
    fun forceReturnToSemiCircle() {
        if (isPopOutMode) {
            // Cancel all timers and animations
            cancelPopOutReturn()
            longPressHandler.removeCallbacks(longPressRunnable)

            // Immediately set state to semi-circle without animation
            isPopOutMode = false
            popOutAnimationProgress = 0f
            longPressDetected = false

            // Clean up any center mode or click animation state
            isClickAnimating = false
            isClickAnimationReversing = false
            isInCenterMode = false
            clickedButtonIndex = -1
            clickAnimationProgress = 0f
            dialNumbersProgress = 0f

            // Update the visual state
            updateCenterXAndOval()
            invalidate()
            updatePopOutLayout()

            // Notify listener when pop-out mode exited
            popOutAnimationListener?.onPopOutModeChanged(false)

            Log.d("VolumeDialView", "Forced return to semi-circle state with state cleanup")
        }

        // Also handle quick action mode
        if (isQuickActionMode) {
            forceExitQuickActionMode()
        }
    }

    // Smooth return to semi-circle state with animation (used during overlay fade)
    fun smoothReturnToSemiCircle() {
        if (isPopOutMode) {
            // Use the existing smooth pop-in animation
            animatePopIn()
            Log.d("VolumeDialView", "Smooth return to semi-circle state with animation")
        }

        if (isQuickActionMode) {
            // Use the existing smooth quick action out animation
            animateQuickActionOut()
            Log.d("VolumeDialView", "Smooth exit from quick action mode with animation")
        }
    }

    // Force immediate exit from quick action mode (for overlay dismissal)
    fun forceExitQuickActionMode() {
        cancelQuickActionReturn()
        isQuickActionMode = false
        quickActionAnimationProgress = 0f
        isInteractingWithQuickAction = false
        interactingQuickActionIndex = -1
        invalidate()
        Log.d("VolumeDialView", "Force exit from quick action mode - state reset")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Call interaction listener at the beginning
        interactionListener?.onInteractionStart()

        // Check if interaction is allowed
        if (!isInteractionAllowed()) {
            return false // Ignore the touch event if not allowed
        }
        val x = event.x
        val y = event.y

        val distance = Math.sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toDouble()).toFloat()

        // Define touch radius (generous area for interaction)
        val touchRadius = if (isPopOutMode) radius + 200f else radius + 200f
        val isInsideTouchArea = distance <= touchRadius

        // Handle touches that start outside the dial
        if (event.action == MotionEvent.ACTION_DOWN && !isInsideTouchArea) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check for quick action button clicks first when in quick action mode
                if (isQuickActionMode) {
                    val clickedQuickAction = getClickedQuickActionIndex(x, y)
                    if (clickedQuickAction != -1) {
                        isInteractingWithQuickAction = true
                        interactingQuickActionIndex = clickedQuickAction
                        handleQuickActionButtonClick(clickedQuickAction)
                        performButtonClickHaptic()

                        // Cancel auto-return timer when interacting with quick action
                        cancelQuickActionReturn()

                        // Notify overlay service about interaction start
                        volumeChangeListener?.onTouchStart()

                        Log.d("VolumeDialView", "Started interaction with quick action button $clickedQuickAction")
                        return true
                    }
                }

                // Check for multi-stream button clicks first when in pop-out mode
                if (isPopOutMode) {
                    // Check for center tap during click animation or center mode
                    if ((isClickAnimating && !isClickAnimationReversing) || isInCenterMode) {
                        val centerDistance = Math.sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toDouble()).toFloat()
                        if (centerDistance <= radius * 0.4f) { // Slightly larger center tap area
                            if (isInCenterMode) {
                                // In center mode, reverse the animation back to 4-button state
                                startClickAnimationReverse()
                                performButtonClickHaptic()
                                Log.d("VolumeDialView", "Center tapped in center mode - reversing to 4-button state")
                                return true
                            } else {
                                // During animation, reverse the click animation
                                startClickAnimationReverse()
                                performButtonClickHaptic()
                                Log.d("VolumeDialView", "Center tapped during click animation - reversing")
                                return true
                            }
                        }
                        // If in center mode but not clicking center, allow volume control to proceed
                        if (isInCenterMode) {
                            // Allow volume control in center mode - don't return, let it fall through
                            Log.d("VolumeDialView", "Touch in center mode outside center area - allowing volume control")
                        } else {
                            // If clicking during animation but not center, ignore the touch
                            return true
                        }
                    }

                    // Only check for button clicks if not in center mode
                    if (!isInCenterMode) {
                        val clickedButton = getClickedButtonIndex(x, y)
                        if (clickedButton != -1) {
                            // Handle multi-stream button interaction start
                            isInteractingWithButton = true
                            interactingButtonIndex = clickedButton
                            animateButtonClick(clickedButton)
                            performButtonClickHaptic()

                            // Cancel auto-return timer when interacting with button (like dial touch)
                            cancelPopOutReturn()

                            // Notify overlay service about button interaction start
                            volumeChangeListener?.onTouchStart()

                            Log.d("VolumeDialView", "Started interaction with ${multiStreamNames[clickedButton]} button")
                            return true
                        }
                    }
                }

                // Only proceed if touch started inside the area
                if (!isInsideTouchArea) return false

                isTouching = true
                isCurrentlyTouching = true
                hasExitedTouchArea = false
                initialTouchX = x
                initialTouchY = y
                lastTouchY = y
                hasMoved = false
                longPressDetected = false
                accumulatedDelta = 0f

                // Initialize swipe detection
                initialSwipeX = x
                initialSwipeY = y
                isHorizontalSwipe = false
                isVerticalSwipe = false
                swipeDetected = false

                // Cancel auto-return timer when user starts touching
                if (isPopOutMode) {
                    cancelPopOutReturn()
                }

                // Start long press detection
                longPressHandler.postDelayed(longPressRunnable, longPressDelay)

                volumeChangeListener?.onTouchStart()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Check if touch moved outside in the touch area
                if (!isInsideTouchArea && !hasExitedTouchArea) {
                    hasExitedTouchArea = true
                    Log.d("VolumeDialView", "Touch moved outside dial area - preparing for cleanup")
                }

                // Continue processing if started inside wheel
                if (!isCurrentlyTouching) return false

                val dx = Math.abs(x - initialTouchX)
                val dy = Math.abs(y - initialTouchY)

                // Detect swipe direction when in semi-circle mode (not pop-out)
                if (!isPopOutMode && !isQuickActionMode && !swipeDetected && (dx > swipeThreshold || dy > swipeThreshold)) {
                    val swipeAngle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

                    // Check if it's a horizontal right swipe
                    if (dx > dy && (x - initialSwipeX) > swipeThreshold && swipeAngle < swipeAngleThreshold) {
                        // Right swipe detected - trigger quick action mode
                        swipeDetected = true
                        isHorizontalSwipe = true
                        hasMoved = true
                        longPressHandler.removeCallbacks(longPressRunnable)

                        animateQuickActionIn()
                        performButtonClickHaptic()

                        Log.d("VolumeDialView", "Right swipe detected - activating quick action mode")
                        return true
                    } else if (dy > dx) {
                        // Vertical swipe - proceed with volume control
                        isVerticalSwipe = true
                        swipeDetected = true
                    }
                }

                // If movement exceeds threshold, cancel long press and process as a slide
                if (dy > touchThreshold || dx > touchThreshold) {
                    hasMoved = true
                    longPressHandler.removeCallbacks(longPressRunnable)

                    // Only process volume changes if still in touch area
                    if (isInsideTouchArea && !longPressDetected && dy > touchThreshold) {
                        // Calculate delta and accumulate it
                        val deltaY = lastTouchY - y // Invert direction
                        accumulatedDelta += deltaY

                        // Use fixed sensitivity instead of user-configurable
                        val sensitivity = 0.3f // Fixed moderate sensitivity

                        // Only change volume when accumulated delta is enough
                        if (Math.abs(accumulatedDelta) >= 2f / sensitivity) {
                            val volumeDelta = (accumulatedDelta * sensitivity).roundToInt()

                            // Calculate new volume based on relative movement
                            var newVolume = (volume + volumeDelta).coerceIn(0, 100)
                            
                            // Apply constraint for alarm - never allow below 5% for touch/slide
                            if (currentActiveStream == VolumeStreamManager.VolumeStream.ALARM && newVolume < 5) {
                                newVolume = 5
                            }

                            // Update volume if it changed
                            if (volume != newVolume) {
                                volume = newVolume
                                volumeChangeListener?.onVolumeChanged(volume)

                                // Provide haptic feedback for user interaction
                                performHapticFeedback()

                                // Reset accumulated delta after volume change
                                accumulatedDelta = 0f
                            }
                        }

                        // Save current position for next comparison
                        lastTouchY = y
                    }
                }

                // Handle horizontal slide for classic volume bar (only if inside area)
                if (isInsideTouchArea) {
                    val horizontalDelta = x - initialTouchX
                    if (horizontalDelta > 50 && dx > dy && !hasMoved && !longPressDetected) {
                        // Significant slide to the right, trigger action
                        hasMoved = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        cleanupTouch()
                        volumeChangeListener?.onSlideRight()
                        return true
                    }
                }

                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Handle quick action button interaction end
                if (isInteractingWithQuickAction) {
                    isInteractingWithQuickAction = false
                    interactingQuickActionIndex = -1

                    // Notify overlay service about interaction end
                    volumeChangeListener?.onTouchEnd()

                    // Restart the auto-return timer
                    if (isQuickActionMode) {
                        scheduleQuickActionReturn()
                    }

                    Log.d("VolumeDialView", "Ended interaction with quick action button")
                    return true
                }

                // Handle multistream button interaction end
                if (isInteractingWithButton) {
                    val buttonName = if (interactingButtonIndex >= 0 && interactingButtonIndex < multiStreamNames.size) {
                        multiStreamNames[interactingButtonIndex]
                    } else {
                        "Unknown"
                    }

                    isInteractingWithButton = false
                    interactingButtonIndex = -1

                    // Notify overlay service about button interaction end
                    volumeChangeListener?.onTouchEnd()

                    // If in pop-out mode, restart the auto-return timer with same timing as other interactions
                    if (isPopOutMode) {
                        schedulePopOutReturn()
                    }

                    Log.d("VolumeDialView", "Ended interaction with $buttonName button")
                    return true
                }

                cleanupTouch()

                // Always call onTouchEnd to ensure proper state cleanup
                volumeChangeListener?.onTouchEnd()

                // If in pop-out mode, restart the auto-return timer
                if (isPopOutMode) {
                    schedulePopOutReturn()
                }

                Log.d("VolumeDialView", "Touch ended - hasExitedTouchArea: $hasExitedTouchArea, final position: ($x, $y)")
                return true
            }
        }

        return false
    }

    fun isInteractionAllowed(): Boolean {
        return true // Interaction is allowed by default
    }

    private fun cleanupTouch() {
        isTouching = false
        isCurrentlyTouching = false
        hasExitedTouchArea = false
        longPressHandler.removeCallbacks(longPressRunnable)

        // Don't reset button interaction state here as it's handled separately
        Log.d("VolumeDialView", "Touch cleanup completed")
    }

    private fun updateVolumeFromTouch(x: Float, y: Float) {
        // Calculate the angle of touch relative to the actual center
        val touchX = x - centerX
        val touchY = y - centerY

        // Calculate angle in radians, then convert to degrees
        var angle = Math.toDegrees(atan2(touchY.toDouble(), touchX.toDouble())).toFloat()

        // Normalize to 0-360 range
        if (angle < 0) angle += 360

        // Define the volume control range (shifted slightly to the right)
        val minAngle = -70f  // Instead of -90 (bottom)
        val maxAngle = 70f   // Instead of 90 (top)

        // Map the adjusted semicircle to volume values
        val normalizedAngle = when {
            // If in top right quadrant (0-90)
            angle <= 90 -> angle
            // If in bottom right quadrant (270-360), normalize to be contiguous with top right
            angle >= 270 -> angle - 360  // This makes 270° become -90°
            // For angles outside range (left semicircle)
            else -> return
        }

        // Restrict to angle range
        if (normalizedAngle < minAngle || normalizedAngle > maxAngle) return

        // **INVERTED:** Change the direction of volume control.
        val mappedVolume = ((maxAngle - normalizedAngle) / (maxAngle - minAngle)) * 100
        var volumePercent = mappedVolume.toInt().coerceIn(0, 100)

        // If the volume is 99, set it to 100
        if (volumePercent == 99) {
            volumePercent = 100
        }

        // Apply constraint for alarm - never allow below 5% for actual volume setting
        if (currentActiveStream == VolumeStreamManager.VolumeStream.ALARM && volumePercent < 5) {
            volumePercent = 5
        }

        // Set the volume and notify listener if it changed
        if (volume != volumePercent) {
            volume = volumePercent
            volumeChangeListener?.onVolumeChanged(volume)
        }
    }

    fun setVolumeNumberDisplayEnabled(enabled: Boolean) {
        isVolumeNumberDisplayEnabled = enabled
        // Update text size
        updateDialTextSize()
        invalidate()
    }

    fun setProgressBarDisplayEnabled(enabled: Boolean) {
        isProgressBarDisplayEnabled = enabled
        invalidate()
    }

    private fun updateDialTextSize() {
        val baseTextSize = 48f
        val volumeNumberBoost = if (isVolumeNumberDisplayEnabled) 1.0f else 1.0f
        val finalTextSize = baseTextSize * scaleFactor * volumeNumberBoost
        numbersPaint.textSize = finalTextSize
    }

    // Multi-stream button helper methods
    private fun getClickedButtonIndex(touchX: Float, touchY: Float): Int {
        if (!isPopOutMode) return -1

        // Don't allow clicking other buttons during click animation
        if (isClickAnimating) return -1

        val buttonRadius = 90f * scaleFactor // Updated to match current button size
        val touchRadius = buttonRadius + 20f // Add extra touch area for easier clicking

        for (i in 0..3) {
            val (buttonX, buttonY) = buttonPositions[i]
            val distance = Math.sqrt(
                ((touchX - buttonX) * (touchX - buttonX) +
                        (touchY - buttonY) * (touchY - buttonY)).toDouble()
            ).toFloat()

            if (distance <= touchRadius) {
                Log.d("VolumeDialView", "Clicked ${multiStreamNames[i]} button (index: $i)")
                return i
            }
        }
        return -1
    }

    // Quick action button helper methods
    private fun getClickedQuickActionIndex(touchX: Float, touchY: Float): Int {
        if (!isQuickActionMode) return -1

        val buttonRadius = radius * 0.22f * scaleFactor // Updated to match new size
        val touchRadius = buttonRadius + 20f // Add extra touch area

        for (i in 0..2) {
            val (buttonX, buttonY) = quickActionButtons[i]
            val distance = Math.sqrt(
                ((touchX - buttonX) * (touchX - buttonX) +
                        (touchY - buttonY) * (touchY - buttonY)).toDouble()
            ).toFloat()

            if (distance <= touchRadius) {
                Log.d("VolumeDialView", "Clicked quick action button $i")
                return i
            }
        }
        return -1
    }

    private fun animateButtonClick(buttonIndex: Int) {
        // Handle stream switching first
        handleMultiStreamButtonClick(buttonIndex)

        // Check if we should start click animation or reverse it
        if (isClickAnimating && clickedButtonIndex == buttonIndex) {
            // Clicking the same button - reverse the animation
            startClickAnimationReverse()
        } else if (!isClickAnimating) {
            // Start new click animation
            startClickAnimation(buttonIndex)
        }
        // If different button clicked during animation, ignore for now

        Log.d("VolumeDialView", "${multiStreamNames[buttonIndex]} button clicked - Animation state: isAnimating=$isClickAnimating, clickedIndex=$clickedButtonIndex")
    }

    private fun startClickAnimation(buttonIndex: Int) {
        isClickAnimating = true
        clickedButtonIndex = buttonIndex
        clickAnimationProgress = 0f
        isClickAnimationReversing = false

        val animator = ObjectAnimator.ofFloat(this, "clickAnimationProgress", 0f, 1f).apply {
            duration = 800 // Slower animation for dramatic effect
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener {
                clickAnimationProgress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                // Animation complete - enter center mode
                isInCenterMode = true
                // Save the centered stream state for volume key handling
                saveCenteredStreamState(currentActiveStream)
                Log.d("VolumeDialView", "Click animation completed for ${multiStreamNames[buttonIndex]} button - entered center mode")
            }
        }
        animator.start()
    }

    private fun startClickAnimationReverse() {
        if (!isClickAnimating) return // Don't reverse if not animating

        isClickAnimationReversing = true
        val currentProgress = clickAnimationProgress

        val animator = ObjectAnimator.ofFloat(this, "clickAnimationProgress", currentProgress, 0f).apply {
            duration = (600 * currentProgress).toLong() // Proportional duration based on current progress
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                clickAnimationProgress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                // Reset animation state completely
                isClickAnimating = false
                clickedButtonIndex = -1
                clickAnimationProgress = 0f
                isClickAnimationReversing = false
                isInCenterMode = false
                dialNumbersProgress = 0f
                // Clear the centered stream state
                clearCenteredStreamState()
                Log.d("VolumeDialView", "Click animation reversed and reset - ready for new interactions")
            }
        }
        animator.start()
    }

    // Property for ObjectAnimator
    fun setClickAnimationProgress(progress: Float) {
        clickAnimationProgress = progress
        invalidate()
    }

    fun getClickAnimationProgress(): Float {
        return clickAnimationProgress
    }



    private fun performButtonClickHaptic() {
        if (!isHapticEnabled) return

        // Use the same haptic settings as the dial
        val duration = when (hapticStrength) {
            0 -> 20L  // Low
            1 -> 12L  // Medium
            2 -> 8L   // High
            else -> 12L
        }

        val amplitude = when (hapticStrength) {
            0 -> VibrationEffect.DEFAULT_AMPLITUDE / 2
            1 -> VibrationEffect.DEFAULT_AMPLITUDE / 3
            2 -> VibrationEffect.DEFAULT_AMPLITUDE / 4
            else -> VibrationEffect.DEFAULT_AMPLITUDE / 3
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration)
            }
        } catch (e: Exception) {
            // Fallback to view haptic feedback
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    // Helper method to get button name by index for external reference
    fun getButtonName(index: Int): String {
        return if (index >= 0 && index < multiStreamNames.size) {
            multiStreamNames[index]
        } else {
            "Unknown"
        }
    }

    private fun saveCenteredStreamState(stream: VolumeStreamManager.VolumeStream) {
        try {
            val prefs = context.getSharedPreferences("VolumeOverlayState", Context.MODE_PRIVATE)
            prefs.edit().putString("centered_stream", stream.name).apply()
            Log.d("VolumeDialView", "Saved centered stream state: ${stream.name}")
        } catch (e: Exception) {
            Log.e("VolumeDialView", "Error saving centered stream state: ${e.message}")
        }
    }

    private fun clearCenteredStreamState() {
        try {
            val prefs = context.getSharedPreferences("VolumeOverlayState", Context.MODE_PRIVATE)
            prefs.edit().remove("centered_stream").apply()
            Log.d("VolumeDialView", "Cleared centered stream state")
        } catch (e: Exception) {
            Log.e("VolumeDialView", "Error clearing centered stream state: ${e.message}")
        }
    }

    // Helper method to get button label by index for external reference
    fun getButtonLabel(index: Int): String {
        return if (index >= 0 && index < multiStreamLabels.size) {
            multiStreamLabels[index]
        } else {
            "?"
        }
    }

    // Reset to 4-button state (called when overlay is first shown)
    fun resetToFourButtonState() {
        if (isClickAnimating || isInCenterMode) {
            isClickAnimating = false
            isClickAnimationReversing = false
            isInCenterMode = false
            clickedButtonIndex = -1
            clickAnimationProgress = 0f
            dialNumbersProgress = 0f
            invalidate()
            Log.d("VolumeDialView", "Reset to 4-button state for new overlay session")
        }
    }

    /**
     * Set the active stream for volume control
     */
    fun setActiveStream(stream: VolumeStreamManager.VolumeStream) {
        currentActiveStream = stream
        Log.d("VolumeDialView", "Active stream changed to: ${stream.displayName}")
        invalidate() // Redraw to reflect the active stream
    }

    /**
     * Get the current active stream
     */
    fun getActiveStream(): VolumeStreamManager.VolumeStream {
        return currentActiveStream
    }

    /**
     * Set the VolumeStreamManager reference for dynamic icon selection
     */
    fun setVolumeStreamManager(manager: VolumeStreamManager) {
        volumeStreamManager = manager
        // Set up volume restoration callbacks
        setupVolumeRestorationCallbacks(manager)
    }

    /**
     * Setup callbacks for volume restoration
     */
    private fun setupVolumeRestorationCallbacks(manager: VolumeStreamManager) {
        // This creates a connection for the manager to get saved volumes
        // We'll implement this through a companion object or interface
    }

    /**
     * Get saved media volume for restoration
     */
    fun getSavedMediaVolume(): Int = savedMediaVolume

    /**
     * Get saved ring volume for restoration
     */
    fun getSavedRingVolume(): Int = savedRingVolume

    /**
     * Get saved notification volume for restoration
     */
    fun getSavedNotificationVolume(): Int = savedNotificationVolume

    /**
     * Get the appropriate icon for a stream button based on current volume level and device state
     */
    private fun getStreamIcon(buttonIndex: Int): Int {
        val streamManager = volumeStreamManager ?: return getDefaultIcon(buttonIndex)

        return when (buttonIndex) {
            0 -> getMediaIcon(streamManager) // Media
            1 -> getRingIcon(streamManager)  // Ring
            2 -> getNotificationIcon(streamManager) // Notification
            3 -> getAlarmIcon(streamManager) // Alarm
            else -> getDefaultIcon(buttonIndex)
        }
    }

    /**
     * Get media icon based on volume level and Bluetooth state
     */
    private fun getMediaIcon(streamManager: VolumeStreamManager): Int {
        val volumeLevel = streamManager.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.MEDIA)
        val isBluetoothConnected = streamManager.isBluetoothAudioConnected()

        return if (isBluetoothConnected) {
            if (volumeLevel == 0) {
                R.drawable.volume_media_bt_off
            } else {
                R.drawable.volume_media_bt
            }
        } else {
            when {
                volumeLevel == 0 -> R.drawable.volume_media_0
                volumeLevel in 1..49 -> R.drawable.volume_media_1
                else -> R.drawable.volume_media_2 // 50-100
            }
        }
    }

    /**
     * Get ring icon based on volume level and ringer mode
     */
    private fun getRingIcon(streamManager: VolumeStreamManager): Int {
        val volumeLevel = streamManager.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.RING)
        val ringerMode = streamManager.getRingerMode()

        return when {
            volumeLevel == 0 || ringerMode == AudioManager.RINGER_MODE_VIBRATE -> R.drawable.volume_ring_vibrate
            volumeLevel in 1..49 -> R.drawable.volume_ring_1
            else -> R.drawable.volume_ring_2 // 50-100
        }
    }

    /**
     * Get notification icon based on volume level
     */
    private fun getNotificationIcon(streamManager: VolumeStreamManager): Int {
        val volumeLevel = streamManager.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.NOTIFICATION)

        return if (volumeLevel == 0) {
            R.drawable.volume_ringer_mute
        } else {
            R.drawable.volume_ringer_ring
        }
    }

    /**
     * Get alarm icon based on volume level
     */
    private fun getAlarmIcon(streamManager: VolumeStreamManager): Int {
        val volumeLevel = streamManager.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.ALARM)

        return if (volumeLevel == 0) {
            R.drawable.volume_alarm_off
        } else {
            R.drawable.volume_alarm
        }
    }

    /**
     * Get default icon when VolumeStreamManager is not available
     */
    private fun getDefaultIcon(buttonIndex: Int): Int {
        return when (buttonIndex) {
            0 -> R.drawable.volume_media_0    // Media
            1 -> R.drawable.volume_ring_1     // Ring
            2 -> R.drawable.volume_ringer_ring // Notification
            3 -> R.drawable.volume_alarm      // Alarm
            else -> R.drawable.volume_media_0
        }
    }

    /**
     * Handle multi-stream button click and switch active stream
     */
    private fun handleMultiStreamButtonClick(buttonIndex: Int) {
        val newStream = when (buttonIndex) {
            0 -> VolumeStreamManager.VolumeStream.MEDIA
            1 -> VolumeStreamManager.VolumeStream.RING
            2 -> VolumeStreamManager.VolumeStream.NOTIFICATION
            3 -> VolumeStreamManager.VolumeStream.ALARM
            else -> VolumeStreamManager.VolumeStream.MEDIA
        }

        // Switch to the new stream
        currentActiveStream = newStream

        // Notify the volume change listener about the stream switch
        volumeChangeListener?.onStreamChanged(newStream)

        Log.d("VolumeDialView", "Switched to ${newStream.displayName} stream via button click")
    }

    /**
     * Draw quick action buttons in arc formation
     */
    private fun drawQuickActionButtons(canvas: Canvas, centerX: Float, centerY: Float) {
        val buttonRadius = radius * 0.25f * scaleFactor // 10% larger
        val arcRadius = radius * 0.6f // Distance from center

        // Button angles in the semi-circle (spread across the arc)
        val angles = arrayOf(-60.0, 0.0, 60.0) // Ring, Media, Live Caption

        for (i in 0..2) {
            val angle = Math.toRadians(angles[i])

            // Animate buttons spinning in/out with reverse direction for exit
            val spinProgress = quickActionAnimationProgress
            // When animating in (progress 0->1): spin from left side (PI to 0)
            // When animating out (progress 1->0): spin back to left side (0 to PI)
            val spinAngle = angle + (Math.PI * (1f - spinProgress)) // Spin from/to left side

            val buttonX = centerX + (arcRadius * cos(spinAngle)).toFloat()
            val buttonY = centerY + (arcRadius * sin(spinAngle)).toFloat()

            // Store final position for hit detection
            if (spinProgress > 0.9f) {
                quickActionButtons[i] = Pair(
                    centerX + (arcRadius * cos(angle)).toFloat(),
                    centerY + (arcRadius * sin(angle)).toFloat()
                )
            }

            // Animate button appearance with smooth scaling and rotation
            val buttonAlpha = (spinProgress * 1.2f).coerceIn(0f, 1f)
            val buttonScale = spinProgress
            // Enhanced rotation: spin more dramatically during entry/exit
            val buttonRotation = (1f - spinProgress) * 360f // Full rotation for more dramatic effect

            if (buttonAlpha > 0.1f) {
                canvas.save()
                canvas.translate(buttonX, buttonY)
                canvas.scale(buttonScale, buttonScale)
                canvas.rotate(buttonRotation)

                // Draw button background
                val originalAlpha = multiStreamButtonPaint.alpha
                multiStreamButtonPaint.alpha = (originalAlpha * buttonAlpha).toInt()
                canvas.drawCircle(0f, 0f, buttonRadius, multiStreamButtonPaint)
                multiStreamButtonPaint.alpha = originalAlpha

                // Draw icon
                val iconDrawable = ContextCompat.getDrawable(context, getQuickActionIcon(i))
                iconDrawable?.let { drawable ->
                    val iconSize = (buttonRadius * 0.8f).toInt() // 20% larger icon
                    val iconHalf = iconSize / 2

                    drawable.setBounds(-iconHalf, -iconHalf, iconHalf, iconHalf)
                    drawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                    drawable.alpha = (255 * buttonAlpha).toInt()
                    drawable.draw(canvas)
                }

                canvas.restore()
            }
        }
    }

    /**
     * Get icon for quick action button
     */
    private fun getQuickActionIcon(buttonIndex: Int): Int {
        val streamManager = volumeStreamManager

        return when (buttonIndex) {
            0 -> { // Ring mode toggle
                when (streamManager?.getRingerMode()) {
                    AudioManager.RINGER_MODE_NORMAL -> R.drawable.volume_ring_2
                    AudioManager.RINGER_MODE_VIBRATE -> R.drawable.volume_ring_vibrate
                    AudioManager.RINGER_MODE_SILENT -> R.drawable.volume_ring_mute
                    else -> R.drawable.volume_ringer_ring
                }
            }
            1 -> { // Media mute toggle
                val mediaVolume = streamManager?.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.MEDIA) ?: 0
                val isBluetoothConnected = streamManager?.isBluetoothAudioConnected() ?: false

                if (isBluetoothConnected) {
                    if (mediaVolume == 0) R.drawable.volume_media_bt_off else R.drawable.volume_media_bt
                } else {
                    when {
                        mediaVolume == 0 -> R.drawable.volume_media_0
                        mediaVolume in 1..49 -> R.drawable.volume_media_1
                        else -> R.drawable.volume_media_2
                    }
                }
            }
            2 -> { // Live Caption toggle
                if (isLiveCaptionEnabled()) {
                    R.drawable.ic_odi_captions
                } else {
                    R.drawable.ic_odi_captions_disabled
                }
            }
            else -> R.drawable.volume_media_0
        }
    }

    /**
     * Check if Live Caption is enabled (placeholder - will need proper implementation)
     */
    private fun isLiveCaptionEnabled(): Boolean {
        // TODO: Implement actual Live Caption state check
        return false // Placeholder
    }

    /**
     * Handle quick action button click
     */
    private fun handleQuickActionButtonClick(buttonIndex: Int) {
        when (buttonIndex) {
            0 -> toggleRingerMode() // Ring mode toggle
            1 -> toggleMediaMute()  // Media mute toggle
            2 -> toggleLiveCaption() // Live Caption toggle
        }

        // Reset timer and update icons
        scheduleQuickActionReturn()
        invalidate()

        Log.d("VolumeDialView", "Quick action button $buttonIndex clicked")
    }

    /**
     * Toggle ringer mode (Normal -> Vibrate -> Silent -> Normal)
     */
    private fun toggleRingerMode() {
        val streamManager = volumeStreamManager ?: return
        val currentRingerMode = streamManager.getRingerMode()
        val currentRingVolume = streamManager.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.RING)
        val currentNotificationVolume = streamManager.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.NOTIFICATION)

        Log.d("VolumeDialView", "Current ringer mode: $currentRingerMode, ring vol: $currentRingVolume, notif vol: $currentNotificationVolume")

        when (currentRingerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                // Save current volumes before going to vibrate (only if they're > 0)
                if (currentRingVolume > 0) savedRingVolume = currentRingVolume
                if (currentNotificationVolume > 0) savedNotificationVolume = currentNotificationVolume

                // Switch to vibrate mode
                streamManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE)
                Log.d("VolumeDialView", "Normal -> Vibrate, saved ring: $savedRingVolume, notification: $savedNotificationVolume")
            }
            AudioManager.RINGER_MODE_VIBRATE -> {
                // Switch to silent mode
                streamManager.setRingerMode(AudioManager.RINGER_MODE_SILENT)
                Log.d("VolumeDialView", "Vibrate -> Silent")
            }
            AudioManager.RINGER_MODE_SILENT -> {
                // Switch back to normal and restore volumes
                streamManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL)

                // Small delay to ensure ringer mode is set before setting volumes
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    streamManager.setStreamVolumePercentage(VolumeStreamManager.VolumeStream.RING, savedRingVolume)
                    streamManager.setStreamVolumePercentage(VolumeStreamManager.VolumeStream.NOTIFICATION, savedNotificationVolume)

                    // Update the dial if currently showing ring or notification stream
                    when (currentActiveStream) {
                        VolumeStreamManager.VolumeStream.RING -> {
                            volume = savedRingVolume
                            volumeChangeListener?.onVolumeChanged(savedRingVolume)
                        }
                        VolumeStreamManager.VolumeStream.NOTIFICATION -> {
                            volume = savedNotificationVolume
                            volumeChangeListener?.onVolumeChanged(savedNotificationVolume)
                        }
                        else -> {}
                    }

                    Log.d("VolumeDialView", "Silent -> Normal, restored ring: $savedRingVolume, notification: $savedNotificationVolume")
                }, 100)
            }
        }
    }

    /**
     * Toggle media mute/unmute
     */
    private fun toggleMediaMute() {
        val streamManager = volumeStreamManager ?: return
        val currentMediaVolume = streamManager.getStreamVolumePercentage(VolumeStreamManager.VolumeStream.MEDIA)

        if (currentMediaVolume == 0) {
            // Unmute - restore saved volume
            streamManager.setStreamVolumePercentage(VolumeStreamManager.VolumeStream.MEDIA, savedMediaVolume)
            
            // Clear quick action mute status since we're unmuting
            streamManager.clearQuickActionMuteStatus(VolumeStreamManager.VolumeStream.MEDIA)

            // Update the dial if currently showing media stream
            if (currentActiveStream == VolumeStreamManager.VolumeStream.MEDIA) {
                volume = savedMediaVolume
                volumeChangeListener?.onVolumeChanged(savedMediaVolume)
            }

            Log.d("VolumeDialView", "Media unmuted to $savedMediaVolume%")
        } else {
            // Mute - save current volume and set to 0
            savedMediaVolume = currentMediaVolume
            streamManager.setStreamVolumePercentage(VolumeStreamManager.VolumeStream.MEDIA, 0)
            
            // Mark as quick action muted so volume keys will restore to saved volume
            streamManager.markStreamAsQuickActionMuted(VolumeStreamManager.VolumeStream.MEDIA)

            // Update the dial if currently showing media stream
            if (currentActiveStream == VolumeStreamManager.VolumeStream.MEDIA) {
                volume = 0
                volumeChangeListener?.onVolumeChanged(0)
            }

            Log.d("VolumeDialView", "Media muted via quick action, saved volume: $savedMediaVolume%")
        }
    }

    /**
     * Toggle Live Caption on/off
     */
    private fun toggleLiveCaption() {
        // Live Caption doesn't have a simple programmatic API in current Android versions
        // The setting is typically controlled through System Settings -> Accessibility -> Live Caption
        // For now, this opens the Live Caption settings page
        try {
            val intent = Intent("android.settings.CAPTIONING_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            Log.d("VolumeDialView", "Opened Live Caption settings")
        } catch (e: Exception) {
            // Fallback to general accessibility settings
            try {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Log.d("VolumeDialView", "Opened Accessibility settings as fallback")
            } catch (e2: Exception) {
                Log.e("VolumeDialView", "Could not open settings: ${e2.message}")
            }
        }
    }

}
