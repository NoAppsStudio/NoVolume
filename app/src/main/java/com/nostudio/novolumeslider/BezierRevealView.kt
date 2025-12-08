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
        color = Color.parseColor("#000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val path = Path()
    private var animationProgress = 0f
    private var viewWidth = 0f
    private var viewHeight = 0f
    private var maxControlPointX = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private val fadeOutAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 300
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            alpha = it.animatedValue as Float
        }
        addListener(onEnd = {
            visibility = View.INVISIBLE
            alpha = 1f
        })
    }

    init {
        visibility = View.INVISIBLE
        alpha = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()
        maxControlPointX = (minOf(w, h) / 2f) - 80f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val controlPointX = maxControlPointX * animationProgress

        path.reset()
        path.moveTo(0f, 0f)
        
        path.cubicTo(
            controlPointX * 0.5f, 0f,
            controlPointX, viewHeight * 0.3f,
            controlPointX, viewHeight * 0.5f
        )
        
        path.cubicTo(
            controlPointX, viewHeight * 0.7f,
            controlPointX * 0.5f, viewHeight,
            0f, viewHeight
        )
        
        path.close()
        canvas.drawPath(path, paint)
    }

    fun startRevealAnimation() {
        resetAnimation()
        visibility = View.VISIBLE
        alpha = 1f
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