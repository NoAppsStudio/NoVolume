package com.nostudio.novolumeslider

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.os.Handler
import kotlin.math.cos
import kotlin.math.sin
import android.animation.ObjectAnimator
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

class VolumeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Interface for volume change callbacks
    interface OnVolumeChangeListener {
        fun onVolumeChanged(volume: Int)
        fun onTouchStart()
        fun onTouchEnd()
        fun onDismiss()
        fun onSlideRight()
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
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // Notify listener to dismiss the overlay
                    volumeChangeListener?.onDismiss()
                    return@setOnTouchListener true
                }
            }
            false // Let other touches pass through
        }
    }

    private val customTypeface: Typeface? = ResourcesCompat.getFont(context, R.font.ntype)

    // Track touch state
    private var isTouching = false

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val progressPaint = Paint().apply {
        color = Color.WHITE // White progress bar
        style = Paint.Style.STROKE
        strokeWidth = 24f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND // Rounded ends for the arc
    }

    private val markerPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f //slightly bolder
        isAntiAlias = true
    }

    private val indicatorPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }

    private val numbersPaint = Paint().apply {
        color = Color.BLACK
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

                // Only animate if we're not currently touching the dial
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
    }

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
            color = Color.LTGRAY // Customize background arc color
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
            val sweepAngle = -(animatedVolume * (endAngle - startAngle)) / 100f
            canvas.drawArc(oval, startAngle, sweepAngle, false, progressPaint)
        }

        // Rotate the canvas to align with the animated volume progress
        val rotationAngle = (135f - (animatedVolume * (280f - 10f) / 100f))
        canvas.rotate(rotationAngle, centerX, centerY)

        // Draw all markers and numbers in the rotated context
        drawMarkersAndNumbers(canvas, centerX, centerY)

        // Restore the canvas to its original state
        canvas.restore()

        // Draw the fixed indicator at the rightmost point (0 degrees) - scaled
        val indicatorLength = 30f * scaleFactor
        val indicatorOffset = 15f * scaleFactor
        val indicatorStartX = centerX + (radius - indicatorLength) + indicatorOffset
        val indicatorStartY = centerY
        val indicatorEndX = centerX + radius + indicatorOffset
        val indicatorEndY = centerY

        // The indicator line remains in its original position because we are using the restored canvas
        canvas.drawLine(indicatorStartX, indicatorStartY, indicatorEndX, indicatorEndY, indicatorPaint)
    }

    private fun drawMarkersAndNumbers(canvas: Canvas, centerX: Float, centerY: Float) {
        // Scale gaps and distances with the wheel size
        val gap = 30f * scaleFactor
        val blackMarkerPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f * scaleFactor
            isAntiAlias = true
        }

        // Use a consistent distance for all numbers that scales
        val numberGap = 60f * scaleFactor
        val numbersRadius = radius - gap - numberGap

        for (i in 0..10) {
            val angle = -135f + (i * 270f / 10f)
            val markerLength = 15f * scaleFactor
            val angleRadians = Math.toRadians(angle.toDouble())
            val startX = centerX + (radius - markerLength - gap) * cos(angleRadians).toFloat()
            val startY = centerY + (radius - markerLength - gap) * sin(angleRadians).toFloat()
            val endX = centerX + (radius - gap) * cos(angleRadians).toFloat()
            val endY = centerY + (radius - gap) * sin(angleRadians).toFloat()

            canvas.drawLine(startX, startY, endX, endY, markerPaint)

            // Draw numbers that "sit" on the red markers
            val textX = centerX + numbersRadius * cos(angleRadians).toFloat()
            val textY = centerY + numbersRadius * sin(angleRadians).toFloat()

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

            // Calculate numbers in reverse order: 100, 90, 80, ... (for inverted volume)
            val numberText = (i * 10).toString()
            canvas.drawText(numberText, 0f, numbersPaint.textSize / 3, numbersPaint)

            // Restore canvas
            canvas.restore()
        }

        // Draw the minor markers with scaled dimensions
        for (i in 0..9) {
            val startAngle = -135f + (i * 270f / 10f)
            val endAngle = -135f + ((i + 1) * 270f / 10f)

            for (j in 1..9) {
                val angle = startAngle + (j * (endAngle - startAngle) / 10f)
                val angleRadians = Math.toRadians(angle.toDouble())
                val markerLength = 10f * scaleFactor
                val markerStartX = centerX + (radius - markerLength - gap) * cos(angleRadians).toFloat()
                val markerStartY = centerY + (radius - markerLength - gap) * sin(angleRadians).toFloat()
                val markerEndX = centerX + (radius - gap) * cos(angleRadians).toFloat()
                val markerEndY = centerY + (radius - gap) * sin(angleRadians).toFloat()

                canvas.drawLine(markerStartX, markerStartY, markerEndX, markerEndY, blackMarkerPaint)
            }
        }
    }

    // Variables to track touch movement and sliding
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasMoved = false
    private val touchThreshold = 5f // Better balance between responsiveness and stability
    private var lastTouchY = 0f // Track last Y position for vertical sliding
    private var accumulatedDelta = 0f // Accumulate small movements for fine control

    // Pop-out animation variables
    private var isPopOutMode = false
    private var popOutAnimationProgress = 0f
    private var longPressDetected = false
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
            animatePopIn()
        }
    }
    private val popOutReturnDelay = 5000L // 5 seconds

    private fun performLongPressHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Stronger vibration for pop-out activation
                vibrator?.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50L)
            }
        } catch (e: Exception) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun animatePopOut() {
        if (isPopOutMode) return

        val animator = ObjectAnimator.ofFloat(this, "popOutProgress", 0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator(1.5f)
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
        // Notify listener that we entered pop-out mode
        popOutAnimationListener?.onPopOutModeChanged(true)
        animator.start()
    }

    private fun animatePopIn() {
        if (!isPopOutMode) return

        // Cancel any pending auto-return
        cancelPopOutReturn()

        val animator = ObjectAnimator.ofFloat(this, "popOutProgress", 1f, 0f).apply {
            duration = 250
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                popOutAnimationProgress = it.animatedValue as Float
                invalidate()
                updatePopOutLayout()
            }
            doOnEnd {
                isPopOutMode = false
                // Notify listener that we exited pop-out mode
                popOutAnimationListener?.onPopOutModeChanged(false)
            }
        }
        animator.start()
    }

    // Property for ObjectAnimator
    fun setPopOutProgress(progress: Float) {
        popOutAnimationProgress = progress
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
        // Schedule new timer only if not currently touching
        if (!isTouching) {
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
    }

    // Getter for pop-out mode state
    fun isInPopOutMode(): Boolean {
        return isPopOutMode
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

            // Update the visual state
            updateCenterXAndOval()
            invalidate()
            updatePopOutLayout()

            // Notify listener that we exited pop-out mode
            popOutAnimationListener?.onPopOutModeChanged(false)

            Log.d("VolumeDialView", "Forced return to semi-circle state")
        }
    }

    // Smooth return to semi-circle state with animation (used during overlay fade)
    fun smoothReturnToSemiCircle() {
        if (isPopOutMode) {
            // Use the existing smooth pop-in animation
            animatePopIn()
            Log.d("VolumeDialView", "Smooth return to semi-circle state with animation")
        }
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

        // Ignore touches outside the dial (adjust for pop-out mode)
        val touchRadius = if (isPopOutMode) radius + 200f else radius + 200f
        if (distance > touchRadius) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                initialTouchX = x
                initialTouchY = y
                lastTouchY = y
                hasMoved = false
                longPressDetected = false
                accumulatedDelta = 0f

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
                val dx = Math.abs(x - initialTouchX)
                val dy = Math.abs(y - initialTouchY)

                // If movement exceeds threshold, cancel long press and process as a slide
                if (dy > touchThreshold || dx > touchThreshold) {
                    hasMoved = true
                    longPressHandler.removeCallbacks(longPressRunnable)

                    // Only process vertical sliding if not in pop-out animation phase
                    if (!longPressDetected && dy > touchThreshold) {
                        // Calculate delta and accumulate it
                        val deltaY = lastTouchY - y // Invert direction
                        accumulatedDelta += deltaY

                        // Use fixed sensitivity instead of user-configurable
                        val sensitivity = 0.3f // Fixed moderate sensitivity

                        // Only change volume when accumulated delta is enough
                        if (Math.abs(accumulatedDelta) >= 2f / sensitivity) {
                            val volumeDelta = (accumulatedDelta * sensitivity).roundToInt()

                            // Calculate new volume based on relative movement
                            val newVolume = (volume + volumeDelta).coerceIn(0, 100)

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

                // Handle horizontal slide for classic volume bar
                val horizontalDelta = x - initialTouchX
                if (horizontalDelta > 50 && dx > dy && !hasMoved && !longPressDetected) {
                    // Significant slide to the right, trigger action
                    hasMoved = true
                    longPressHandler.removeCallbacks(longPressRunnable)
                    isTouching = false
                    volumeChangeListener?.onSlideRight()
                    return true
                }

                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                longPressHandler.removeCallbacks(longPressRunnable)

                // If we're in pop-out mode, restart the auto-return timer instead of immediately returning
                if (isPopOutMode) {
                    schedulePopOutReturn()
                }

                volumeChangeListener?.onTouchEnd()
                return true
            }
        }

        return false
    }

    open fun isInteractionAllowed(): Boolean {
        return true // Interaction is allowed by default
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
            // For angles outside our range (left semicircle)
            else -> return
        }

        // Restrict to our desired angle range
        if (normalizedAngle < minAngle || normalizedAngle > maxAngle) return

        // **INVERTED:** Change the direction of volume control.
        val mappedVolume = ((maxAngle - normalizedAngle) / (maxAngle - minAngle)) * 100
        var volumePercent = mappedVolume.toInt().coerceIn(0, 100)

        // If the volume is 99, set it to 100
        if (volumePercent == 99) {
            volumePercent = 100
        }

        // Set the volume and notify listener if it changed
        if (volume != volumePercent) {
            volume = volumePercent
            volumeChangeListener?.onVolumeChanged(volume)
        }
    }

    fun setVolumeNumberDisplayEnabled(enabled: Boolean) {
        isVolumeNumberDisplayEnabled = enabled
        // Update text size immediately
        updateDialTextSize()
        invalidate()
    }

    fun setProgressBarDisplayEnabled(enabled: Boolean) {
        isProgressBarDisplayEnabled = enabled
        invalidate()
    }

    private fun updateDialTextSize() {
        // Base text size with conditional scaling based on volume number display
        val baseTextSize = 48f
        val volumeNumberBoost = if (isVolumeNumberDisplayEnabled) 1.0f else 1.0f // 20% larger when volume number is hidden
        val finalTextSize = baseTextSize * scaleFactor * volumeNumberBoost
        numbersPaint.textSize = finalTextSize
    }

}
