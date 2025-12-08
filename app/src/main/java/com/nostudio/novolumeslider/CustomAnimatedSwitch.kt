package com.nostudio.novolumeslider

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat

class CustomAnimatedSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Switch properties
    private var isChecked = false
    private var animationProgress = 0f
    private var isAnimating = false
    private var thumbScaleProgress = 0f
    private var activeTrackColor = Color.parseColor("#FF0000")
    private var inactiveTrackColor = Color.parseColor("#66FFFFFF")
    private var activeThumbColor = Color.WHITE
    private var inactiveThumbColor = Color.parseColor("#FAFAFA")
    private var outlineColor = Color.parseColor("#33000000")

    // Paint objects
    private val trackPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val outlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    
    private val thumbPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val thumbShadowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#40000000")
    }

    // Dimensions
    private val trackWidth = 120f
    private val trackHeight = 60f
    private val thumbSize = 44f
    private val thumbMaxSize = 52f
    private val padding = 8f

    // Animation
    private var positionAnimator: ValueAnimator? = null
    private var scaleAnimator: ValueAnimator? = null

    // Listener
    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    init {
        // Minimum size
        minimumWidth = (trackWidth + padding * 2).toInt()
        minimumHeight = (trackHeight + padding * 2).toInt()
        
        // Make view clickable
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (trackWidth + padding * 2).toInt()
        val desiredHeight = (trackHeight + padding * 2).toInt()
        
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        
        // Track bounds
        val trackLeft = centerX - trackWidth / 2f
        val trackTop = centerY - trackHeight / 2f
        val trackRight = centerX + trackWidth / 2f
        val trackBottom = centerY + trackHeight / 2f
        val trackRadius = trackHeight / 2f
        
        // Draw track with animated color transition
        val currentTrackColor = if (isAnimating) {
            interpolateColor(inactiveTrackColor, activeTrackColor, animationProgress)
        } else {
            if (isChecked) activeTrackColor else inactiveTrackColor
        }
        
        trackPaint.color = currentTrackColor
        val trackRect = RectF(trackLeft, trackTop, trackRight, trackBottom)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)
        
        // Draw track outline
        outlinePaint.color = outlineColor
        outlinePaint.alpha = 60
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, outlinePaint)
        
        // Thumb position and size
        val baseThumbRadius = thumbSize / 2f
        val maxThumbRadius = thumbMaxSize / 2f
        
        // Apply scale effect
        val scaleMultiplier = if (isAnimating) {
            1f + (thumbScaleProgress * 0.20f) // 20% scale increase
        } else {
            1f
        }
        
        val currentThumbRadius = baseThumbRadius * scaleMultiplier
        val thumbCenterY = centerY
        val thumbStartX = trackLeft + baseThumbRadius + 5.0f
        val thumbEndX = trackRight - baseThumbRadius - 5.0f
        
        val thumbCenterX = if (isAnimating) {
            thumbStartX + (thumbEndX - thumbStartX) * animationProgress
        } else {
            if (isChecked) thumbEndX else thumbStartX
        }
        
        // Draw thumb shadow with offset
        val shadowRadius = currentThumbRadius * 0.9f
        canvas.drawCircle(thumbCenterX + 1.5f, thumbCenterY + 1.5f, shadowRadius, thumbShadowPaint)
        
        // Draw thumb with animated color and size
        val currentThumbColor = if (isAnimating) {
            interpolateColor(inactiveThumbColor, activeThumbColor, animationProgress)
        } else {
            if (isChecked) activeThumbColor else inactiveThumbColor
        }
        
        thumbPaint.color = currentThumbColor
        canvas.drawCircle(thumbCenterX, thumbCenterY, currentThumbRadius, thumbPaint)
        
        // Draw thumb highlight/outline with scaled radius
        outlinePaint.color = outlineColor
        outlinePaint.alpha = 25
        outlinePaint.strokeWidth = 1.0f
        canvas.drawCircle(thumbCenterX, thumbCenterY, currentThumbRadius, outlinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height) {
                    toggle()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setChecked(checked: Boolean, animate: Boolean = true) {
        if (isChecked == checked) return
        
        isChecked = checked
        
        if (animate && !isAnimating) {
            animateToState()
        } else {
            animationProgress = if (checked) 1f else 0f
            invalidate()
        }
    }

    fun isChecked(): Boolean = isChecked

    fun toggle() {
        setChecked(!isChecked, true)
        onCheckedChangeListener?.invoke(isChecked)
    }

    private fun animateToState() {
        positionAnimator?.cancel()
        scaleAnimator?.cancel()
        
        val startProgress = animationProgress
        val endProgress = if (isChecked) 1f else 0f
        
        // Position animation with smooth easing
        positionAnimator = ValueAnimator.ofFloat(startProgress, endProgress).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            
            addUpdateListener { valueAnimator ->
                animationProgress = valueAnimator.animatedValue as Float
                invalidate()
            }
            
            doOnStart {
                isAnimating = true
            }
            
            doOnEnd {
                isAnimating = false
                animationProgress = endProgress
                invalidate()
            }
            
            start()
        }
        
        // Scale animation with bounce effect
        // Creates a bounce from 0 -> 1.2 -> 0 for a fluid effect
        scaleAnimator = ValueAnimator.ofFloat(0f, 1.5f, 0f).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.8f)
            
            addUpdateListener { valueAnimator ->
                thumbScaleProgress = valueAnimator.animatedValue as Float
                invalidate()
            }
            
            start()
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, progress: Float): Int {
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)
        
        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)
        
        val a = (startA + (endA - startA) * progress).toInt()
        val r = (startR + (endR - startR) * progress).toInt()
        val g = (startG + (endG - startG) * progress).toInt()
        val b = (startB + (endB - startB) * progress).toInt()
        
        return Color.argb(a, r, g, b)
    }

    // Setter methods for customization
    fun setActiveTrackColor(color: Int) {
        activeTrackColor = color
        invalidate()
    }

    fun setInactiveTrackColor(color: Int) {
        inactiveTrackColor = color
        invalidate()
    }

    fun setActiveThumbColor(color: Int) {
        activeThumbColor = color
        invalidate()
    }

    fun setInactiveThumbColor(color: Int) {
        inactiveThumbColor = color
        invalidate()
    }

    fun setOutlineColor(color: Int) {
        outlineColor = color
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        positionAnimator?.cancel()
        scaleAnimator?.cancel()
    }
}

// Extension function for ValueAnimator callbacks
private fun ValueAnimator.doOnStart(action: (animator: ValueAnimator) -> Unit) {
    addListener(object : android.animation.Animator.AnimatorListener {
        override fun onAnimationStart(animation: android.animation.Animator) {
            action(this@doOnStart)
        }
        override fun onAnimationEnd(animation: android.animation.Animator) {}
        override fun onAnimationCancel(animation: android.animation.Animator) {}
        override fun onAnimationRepeat(animation: android.animation.Animator) {}
    })
}

private fun ValueAnimator.doOnEnd(action: (animator: ValueAnimator) -> Unit) {
    addListener(object : android.animation.Animator.AnimatorListener {
        override fun onAnimationStart(animation: android.animation.Animator) {}
        override fun onAnimationEnd(animation: android.animation.Animator) {
            action(this@doOnEnd)
        }
        override fun onAnimationCancel(animation: android.animation.Animator) {}
        override fun onAnimationRepeat(animation: android.animation.Animator) {}
    })
} 