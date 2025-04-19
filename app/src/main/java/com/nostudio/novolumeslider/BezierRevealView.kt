package com.nostudio.novolumeslider

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.addListener

class BezierRevealView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.parseColor("#000000") // Match the dial background color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val path = Path()

    // Animation properties
    private var animationProgress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900 // Animation duration in milliseconds
        interpolator = DecelerateInterpolator() // Increased deceleration for smoother end
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
    }

    // View dimensions
    private var viewWidth = 0f
    private var viewHeight = 0f

    // Control point for bezier curve
    private var maxControlPointX = 0f

    init {
        // Make view transparent initially
        visibility = View.INVISIBLE
        alpha = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()

        // Set maximum control point X based on view dimensions
        maxControlPointX = Math.min(w, h) / 2f - 80f
    }

    override fun onDraw(canvas: Canvas) { // Calculate control point based on animation progress
        super.onDraw(canvas)

        // Calculate control point based on animation progress
        val controlPointX = maxControlPointX * animationProgress

        path.reset()

        // Start from top-left corner
        path.moveTo(0f, 0f)

        // Draw top edge with bezier curve
        path.cubicTo(
            controlPointX * 0.5f, 0f,                   // First control point
            controlPointX, viewHeight * 0.3f,           // Second control point
            controlPointX, viewHeight * 0.5f            // End point
        )

        // Draw bottom edge with bezier curve
        path.cubicTo(
            controlPointX, viewHeight * 0.7f,           // First control point
            controlPointX * 0.5f, viewHeight,           // Second control point
            0f, viewHeight                              // End point (bottom-left)
        )

        // Close the path
        path.close()

        // Draw the path
        canvas.drawPath(path, paint)
    }

    private val fadeOutAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 300 // Fade out duration
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            alpha = it.animatedValue as Float
        }
        addListener(onEnd = {
            visibility = View.INVISIBLE
            alpha = 1f // Reset alpha for next time
        })
    }

    fun startRevealAnimation() {
        resetAnimation() // Cancel any previous animations and set clean state

        // Make the view visible
        visibility = View.VISIBLE
        alpha = 1f

        // Start the animation
        animator.start()
    }

    fun fadeOut() {
        fadeOutAnimator.start()
    }

    fun resetAnimation() {
        animator.cancel()
        fadeOutAnimator.cancel()
        animationProgress = 0f
        alpha = 1f
        invalidate()
    }
}