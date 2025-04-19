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
        fun onDismiss() // New callback for dismissal
        fun onSlideRight()
    }

    interface InteractionListener {
        fun onInteractionStart()
    }

    private var interactionListener: InteractionListener? = null

    fun setInteractionListener(listener: InteractionListener) {
        interactionListener = listener
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
        color = Color.parseColor("#F5F5F5") //make this a variable
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
        strokeCap = Paint.Cap.ROUND // Rounded ends for this too
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
                // Only animate if we're not currently touching the dial
                if (!isTouching) {
                    ObjectAnimator.ofInt(this, "animatedVolume", field, newValue).apply {
                        duration = 90 // Adjust duration for smooth animation  (adjusted for smoother by Aleks)
                        //add haptic here too (10ms or 20ms)
                        start()
                    }
                } else {
                    // If we're touching, update immediately without animation
                    animatedVolume = newValue
                }
            }
            field = newValue
        }

    // Internal variable for smooth animation
    private var animatedVolume: Int = volume
        set(value) {
            field = value
            invalidate() // Redraw at each step for animation effect
        }

    private var animationDuration: Int = 600

    fun pxToDp(context: Context, px: Int): Int {
        val density = context.resources.displayMetrics.density
        return (px / density).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Get the screen width dynamically
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels

        // Set the width of the parent FrameLayout dynamically to the screen width
        val parentLayout = parent as? FrameLayout
        parentLayout?.layoutParams?.width = screenWidth

        // Align the view to the left (adjust X position)
        val layoutParams = layoutParams as? FrameLayout.LayoutParams
        layoutParams?.leftMargin = 0 // Ensure no left margin
        layoutParams?.rightMargin = 0 // Optional: reset any right margin
        requestLayout() // Trigger a layout pass
        val padding = 80f

        // Calculate the radius
        radius = Math.min(w, h) / 2f - padding
        centerX = 0f
        centerY = h / 2f

        progressArcRadius = radius + 60f

        oval.set(centerX - progressArcRadius, centerY - progressArcRadius, centerX + progressArcRadius, centerY + progressArcRadius)
    }

    var startAngle: Float = 75f  // Default starting angle
    var endAngle: Float = 224f  // Default ending angle

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Save the canvas state before transformations
        canvas.save()

        // Draw the full background circle
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // Create a paint object for the background arc
        val backgroundArcPaint = Paint().apply {
            color = Color.LTGRAY // Customize background arc color
            style = Paint.Style.STROKE
            strokeWidth = 24f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            alpha = 100 // Set transparency for the background arc
        }

        // Draw the full background arc (always at 100% volume level)
        canvas.drawArc(oval, startAngle, -(endAngle - startAngle), false, backgroundArcPaint)

        // Now draw the animated volume progress arc
        val sweepAngle = -(animatedVolume * (endAngle - startAngle)) / 100f
        canvas.drawArc(oval, startAngle, sweepAngle, false, progressPaint)

        // Rotate the canvas to align with the animated volume progress
        val rotationAngle = (135f - (animatedVolume * (280f - 10f) / 100f))
        canvas.rotate(rotationAngle, centerX, centerY)

        // Draw all markers and numbers in the rotated context
        drawMarkersAndNumbers(canvas, centerX, centerY)

        // Restore the canvas to its original state
        canvas.restore()

        // Draw the fixed indicator at the rightmost point (0 degrees)
        val indicatorLength = 30f
        val indicatorOffset = 15f
        val indicatorStartX = centerX + (radius - indicatorLength) + indicatorOffset
        val indicatorStartY = centerY
        val indicatorEndX = centerX + radius + indicatorOffset
        val indicatorEndY = centerY

        // The indicator line remains in its original position because we are using the restored canvas
        canvas.drawLine(indicatorStartX, indicatorStartY, indicatorEndX, indicatorEndY, indicatorPaint)
    }

    private fun drawMarkersAndNumbers(canvas: Canvas, centerX: Float, centerY: Float) {
        val gap = 30f
        val blackMarkerPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // Use a consistent distance for all numbers
        val numberGap = 60f
        val numbersRadius = radius - gap - numberGap

        for (i in 0..10) {
            val angle = -135f + (i * 270f / 10f)
            val markerLength = 15f
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

        // Draw the minor markers
        for (i in 0..9) {
            val startAngle = -135f + (i * 270f / 10f)
            val endAngle = -135f + ((i + 1) * 270f / 10f)

            for (j in 1..9) {
                val angle = startAngle + (j * (endAngle - startAngle) / 10f)
                val angleRadians = Math.toRadians(angle.toDouble())
                val markerLength = 10f
                val markerStartX = centerX + (radius - markerLength - gap) * cos(angleRadians).toFloat()
                val markerStartY = centerY + (radius - markerLength - gap) * sin(angleRadians).toFloat()
                val markerEndX = centerX + (radius - gap) * cos(angleRadians).toFloat()
                val markerEndY = centerY + (radius - gap) * sin(angleRadians).toFloat()

                canvas.drawLine(markerStartX, markerStartY, markerEndX, markerEndY, blackMarkerPaint)
            }
        }
    }

    // Variables to track touch movement and sliding
    // Variables to track touch movement and sliding
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasMoved = false
    private val touchThreshold = 5f // Better balance between responsiveness and stability
    private var lastTouchY = 0f // Track last Y position for vertical sliding
    private var accumulatedDelta = 0f // Accumulate small movements for fine control

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

        // Ignore touches outside the dial
        if (distance > radius+200f) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                initialTouchX = x
                initialTouchY = y
                lastTouchY = y
                hasMoved = false
                accumulatedDelta = 0f
                volumeChangeListener?.onTouchStart()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(x - initialTouchX)
                val dy = Math.abs(y - initialTouchY)

                // If movement exceeds threshold, process as a slide
                if (dy > touchThreshold) { // we only need the y here, right?
                    hasMoved = true

                    // HAPTIC FEEDBACK HANDLER should be inserted here

                    // Calculate delta and accumulate it
                    val deltaY = lastTouchY - y // Invert direction
                    accumulatedDelta += deltaY

                    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    val sensitivity = prefs.getFloat("volume_sensitivity", 0.5f)


                    // Lower sensitivity for finer control (0.1f instead of 0.3f) // Use the updated sensitivity value

                    // Only change volume when accumulated delta is enough
                    // This allows for fine-grained control
                    if (Math.abs(accumulatedDelta) >= 2f / sensitivity) {
                        val volumeDelta = (accumulatedDelta * sensitivity).roundToInt()

                        // Calculate new volume based on relative movement
                        val newVolume = (volume + volumeDelta).coerceIn(0, 100)

                        // Update volume if it changed
                        if (volume != newVolume) {
                            volume = newVolume
                            volumeChangeListener?.onVolumeChanged(volume)

                            // Reset accumulated delta after volume change
                            accumulatedDelta = 0f
                        }
                    }

                    // Save current position for next comparison
                    lastTouchY = y
                }
                val horizontalDelta = x - initialTouchX
                if (horizontalDelta > 50 && dx >  dy && !hasMoved) {
                    // Significant slide to the right, trigger action
                    hasMoved = true
                    isTouching = false
                    volumeChangeListener?.onSlideRight()
                    return true
                }

                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
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

}
