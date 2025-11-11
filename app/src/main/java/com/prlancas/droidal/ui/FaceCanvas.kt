package com.prlancas.droidal.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.withClip
import com.prlancas.droidal.event.events.Expression
import com.prlancas.droidal.status.GlobalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

@OptIn(DelicateCoroutinesApi::class)
class FaceCanvas @JvmOverloads constructor(context: Context,
                                           attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : View(context, attrs, defStyleAttr) {

    var centerYPercent = 0.5F
    var eyeHeightPercent = 0.5F
    var eyeWidthToHeightRatio = 1F
    var distanceBetweenEyesPercentOfWidth = 1F

    var lookingX = 0F
    var lookingY = 0F
    var currentExpression = Expression.SLEEP
    private var targetExpression = Expression.SLEEP
    private var fromExpression = Expression.SLEEP
    private var expressionTransitionProgress = 1F
    
    var isBlinking = false
    var blinkProgress = 0F
    
    private val animationScope = CoroutineScope(Dispatchers.Main)
    private var blinkJob: Job? = null
    private var autoBlinkJob: Job? = null
    private var expressionAnimator: ValueAnimator? = null
    private var wakeUpAnimator: ValueAnimator? = null
    
    private val random = Random()
    
    companion object {
        private const val EXPRESSION_TRANSITION_DURATION = 400L // ms
        private const val BLINK_DURATION = 300L // ms
        private const val WAKE_UP_DURATION = 600L // ms
        private const val AUTO_BLINK_INTERVAL_MIN = 2000L // ms
        private const val AUTO_BLINK_INTERVAL_MAX = 5000L // ms
    }

    fun setLookingDirection(x: Float, y: Float) {
        lookingX = x
        lookingY = y
        invalidate()
    }

    fun setExpression(expression: Expression) {
        if (expression == currentExpression && expressionTransitionProgress >= 1F) {
            return // No change needed
        }
        
        val wasSleeping = currentExpression == Expression.SLEEP
        val isWakingUp = wasSleeping && expression != Expression.SLEEP
        
        if (isWakingUp) {
            GlobalStatus.isAwake = true
            Log.d("FACE", "Waking up from sleep")
            animateWakeUp(expression)
        } else if (expression == Expression.BLINK) {
            // Trigger a blink animation
            triggerBlink()
        } else {
            // Animate transition to new expression
            animateExpressionTransition(expression)
        }
        
        // Start auto-blink if transitioning to NORMAL
        if (expression == Expression.NORMAL) {
            startAutoBlink()
        } else {
            stopAutoBlink()
        }
    }
    
    private fun animateWakeUp(newExpression: Expression) {
        wakeUpAnimator?.cancel()
        this.targetExpression = newExpression
        this.fromExpression = currentExpression
        
        wakeUpAnimator = ValueAnimator.ofFloat(0F, 1F).apply {
            duration = WAKE_UP_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                // Wake-up animation: eyes open gradually
                expressionTransitionProgress = progress
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationCancel(animation: android.animation.Animator) {}
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentExpression = newExpression
                    expressionTransitionProgress = 1F
                }
            })
            start()
        }
    }
    
    private fun animateExpressionTransition(newExpression: Expression) {
        expressionAnimator?.cancel()
        
        // If currently transitioning, start from current progress
        val startProgress = if (expressionTransitionProgress < 1F) expressionTransitionProgress else 0F
        targetExpression = newExpression
        fromExpression = currentExpression
        
        expressionAnimator = ValueAnimator.ofFloat(startProgress, 1F).apply {
            duration = EXPRESSION_TRANSITION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                expressionTransitionProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationCancel(animation: android.animation.Animator) {}
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentExpression = newExpression
                    expressionTransitionProgress = 1F
                }
            })
            start()
        }
    }
    
    private fun triggerBlink() {
        // Cancel any existing blink
        blinkJob?.cancel()
        
        blinkJob = animationScope.launch {
            isBlinking = true
            
            // Closing phase
            val closeDuration = BLINK_DURATION / 2
            val frameDuration = 16L // ~60 FPS
            val closeFrames = (closeDuration / frameDuration).toInt()
            
            for (frame in 0..closeFrames) {
                if (!isActive) break
                blinkProgress = frame.toFloat() / closeFrames
                postInvalidate()
                delay(frameDuration)
            }
            
            // Opening phase
            val openFrames = closeFrames
            for (frame in 0..openFrames) {
                if (!isActive) break
                blinkProgress = 1F - (frame.toFloat() / openFrames)
                postInvalidate()
                delay(frameDuration)
            }
            
            isBlinking = false
            blinkProgress = 0F
            postInvalidate()
        }
    }
    
    private fun startAutoBlink() {
        stopAutoBlink()
        
        autoBlinkJob = animationScope.launch {
            while (isActive && currentExpression == Expression.NORMAL && !isBlinking) {
                val delayTime = (AUTO_BLINK_INTERVAL_MIN + 
                    random.nextFloat() * (AUTO_BLINK_INTERVAL_MAX - AUTO_BLINK_INTERVAL_MIN)).toLong()
                delay(delayTime)
                
                if (isActive && currentExpression == Expression.NORMAL && !isBlinking) {
                    triggerBlink()
                }
            }
        }
    }
    
    private fun stopAutoBlink() {
        autoBlinkJob?.cancel()
        autoBlinkJob = null
    }

//    fun blink() {
//        // Cancel any existing blink animation
//        blinkJob?.cancel()
//
//        isBlinking = true
//        blinkProgress = 0F
//        blinkStartTime = System.currentTimeMillis()
//        startBlinkAnimation()
//    }
//
//    private fun startBlinkAnimation() {
//        blinkJob = animationScope.launch {
//            val totalDuration = 500L // 500ms total blink duration
//            val frameDuration = 16L // ~60 FPS
//            val totalFrames = totalDuration / frameDuration
//            val halfFrames = totalFrames / 2
//
//            for (frame in 0..totalFrames) {
//
//                // Calculate progress (0 to 1 and back to 0)
//                if (frame < halfFrames) {
//                    // Closing phase (0 to 1)
//                    blinkProgress = frame.toFloat() / halfFrames
//                } else {
//                    // Opening phase (1 to 0)
//                    blinkProgress = 1F - ((frame - halfFrames).toFloat() / halfFrames)
//                }
//
//                // Force redraw
//                postInvalidate()
//                // Debug output to verify animation
//                println("Blink animation: frame=$frame, progress=$blinkProgress")
//
//                delay(frameDuration)
//            }
//
//            // Animation complete
//            isBlinking = false
//            blinkProgress = 0F
//            postInvalidate()
//        }
//    }


    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Cancel any running animations when view is detached
        blinkJob?.cancel()
        autoBlinkJob?.cancel()
        expressionAnimator?.cancel()
        wakeUpAnimator?.cancel()
    }

    // Called when the view should render its content.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        canvas.drawARGB(255, 128, 108, 81)

        val eyeHeight = canvas.height * eyeHeightPercent
        val eyeWidth = eyeHeight * eyeWidthToHeightRatio
        var x = (canvas.width / 2) - (((distanceBetweenEyesPercentOfWidth + 0.5F) * eyeWidth) / 2)
        val y = canvas.height * centerYPercent

        drawEye(canvas, x, y, eyeWidth, eyeHeight)

        x = (canvas.width / 2) + (((distanceBetweenEyesPercentOfWidth + 0.5F) * eyeWidth) / 2)

        drawEye(canvas, x, y, eyeWidth, eyeHeight)
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Handle blink overlay on any expression
        if (isBlinking && blinkProgress > 0F) {
            drawBlinkingEye(canvas, x, y, width, height)
            return
        }
        
        // Handle expression transitions
        if (expressionTransitionProgress < 1F && currentExpression != targetExpression) {
            drawExpressionTransition(canvas, x, y, width, height)
            return
        }
        
        // Draw current expression
        when (currentExpression) {
            Expression.SLEEP -> drawSleepingEye(canvas, x, y, width, height)
            Expression.BLINK -> drawBlinkingEye(canvas, x, y, width, height)
            Expression.THINKING -> drawThinkingEye(canvas, x, y, width, height)
            Expression.SLEEPY -> drawSleepyEye(canvas, x, y, width, height)
            Expression.CUTE -> drawCuteEye(canvas, x, y, width, height)
            Expression.BLOODSHOT -> drawBloodshotEye(canvas, x, y, width, height)
            else -> drawNormalEye(canvas, x, y, width, height)
        }
    }
    
    private fun drawExpressionTransition(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        val progress = expressionTransitionProgress
        val fromExpr = fromExpression
        val toExpr = targetExpression
        
        // Interpolate between expressions
        when {
            // Transition from SLEEP to any other expression
            fromExpr == Expression.SLEEP -> {
                val eyeOpenProgress = progress.coerceIn(0F, 1F)
                val eyeHeight = height * eyeOpenProgress
                
                if (eyeOpenProgress < 0.1F) {
                    // Still mostly closed
                    drawSleepingEye(canvas, x, y, width, height)
                } else {
                    // Opening - draw target expression with interpolated height
                    drawExpressionWithHeight(canvas, x, y, width, height, toExpr, eyeHeight)
                }
            }
            
            // Transition between open-eye expressions
            else -> {
                // Cross-fade between expressions
                val alpha = progress
                val fromAlpha = 1F - alpha
                val toAlpha = alpha
                
                // Draw both expressions with alpha blending
                canvas.saveLayerAlpha(x - width, y - height, x + width, y + height, 
                    (fromAlpha * 255).toInt(), Canvas.ALL_SAVE_FLAG)
                drawExpressionDirect(canvas, x, y, width, height, fromExpr)
                canvas.restore()
                
                canvas.saveLayerAlpha(x - width, y - height, x + width, y + height, 
                    (toAlpha * 255).toInt(), Canvas.ALL_SAVE_FLAG)
                drawExpressionDirect(canvas, x, y, width, height, toExpr)
                canvas.restore()
            }
        }
    }
    
    private fun drawExpressionWithHeight(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, 
                                         expression: Expression, eyeHeight: Float) {
        when (expression) {
            Expression.NORMAL -> drawNormalEyeWithHeight(canvas, x, y, width, eyeHeight)
            Expression.THINKING -> drawThinkingEyeWithHeight(canvas, x, y, width, height, eyeHeight)
            Expression.SLEEPY -> drawSleepyEyeWithHeight(canvas, x, y, width, height, eyeHeight)
            Expression.CUTE -> drawCuteEyeWithHeight(canvas, x, y, width, height, eyeHeight)
            Expression.BLOODSHOT -> drawBloodshotEyeWithHeight(canvas, x, y, width, height, eyeHeight)
            else -> drawNormalEyeWithHeight(canvas, x, y, width, eyeHeight)
        }
    }
    
    private fun drawExpressionDirect(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, 
                                     expression: Expression) {
        when (expression) {
            Expression.SLEEP -> drawSleepingEye(canvas, x, y, width, height)
            Expression.BLINK -> drawBlinkingEye(canvas, x, y, width, height)
            Expression.THINKING -> drawThinkingEye(canvas, x, y, width, height)
            Expression.SLEEPY -> drawSleepyEye(canvas, x, y, width, height)
            Expression.CUTE -> drawCuteEye(canvas, x, y, width, height)
            Expression.BLOODSHOT -> drawBloodshotEye(canvas, x, y, width, height)
            else -> drawNormalEye(canvas, x, y, width, height)
        }
    }
    
    private fun drawNormalEyeWithHeight(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        val centreX = x + (lookingX * height) / 4
        val centreY = y + (lookingY * height) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX, centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)
        canvas.drawArc(x - (width / 1.75F), y - (height / 1.35F), x + (width / 1.75F), y - (height / 3), 
            180F, 180F, false, paint)

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }
    
    private fun drawThinkingEyeWithHeight(canvas: Canvas, x: Float, y: Float, width: Float, 
                                          baseHeight: Float, eyeHeight: Float) {
        val centreX = x + (lookingX * eyeHeight) / 4
        val centreY = y + (lookingY * eyeHeight) / 4 - (eyeHeight * 0.1F)

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX, centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        canvas.drawOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), paint)
        canvas.drawArc(x - (width / 1.75F), y - (baseHeight / 1.35F), x + (width / 1.75F), y - (baseHeight / 3), 
            180F, 180F, false, paint)

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }
    
    private fun drawSleepyEyeWithHeight(canvas: Canvas, x: Float, y: Float, width: Float, 
                                       baseHeight: Float, eyeHeight: Float) {
        val centreX = x + (lookingX * eyeHeight) / 4
        val centreY = y + (lookingY * eyeHeight) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX, centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        val sleepyHeight = eyeHeight * 0.6F
        canvas.drawOval(x - (width / 2), y - (sleepyHeight / 2), x + (width / 2), y + (sleepyHeight / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (sleepyHeight / 2), x + (width / 2), y + (sleepyHeight / 2), paint)
        canvas.drawArc(x - (width / 1.75F), y - (baseHeight / 1.35F), x + (width / 1.75F), y - (baseHeight / 3), 
            180F, 180F, false, paint)

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (sleepyHeight / 2), x + (width / 2), y + (sleepyHeight / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }
    
    private fun drawCuteEyeWithHeight(canvas: Canvas, x: Float, y: Float, width: Float, 
                                      baseHeight: Float, eyeHeight: Float) {
        val centreX = x + (lookingX * eyeHeight) / 4
        val centreY = y + (lookingY * eyeHeight) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX, centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        
        val cuteWidth = width * 1.2F
        val cuteHeight = eyeHeight * 1.1F
        canvas.drawOval(x - (cuteWidth / 2), y - (cuteHeight / 2), x + (cuteWidth / 2), y + (cuteHeight / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 8F
        }

        canvas.drawOval(x - (cuteWidth / 2), y - (cuteHeight / 2), x + (cuteWidth / 2), y + (cuteHeight / 2), paint)
        canvas.drawArc(x - (cuteWidth / 1.5F), y - (cuteHeight / 1.2F), x + (cuteWidth / 1.5F), y - (cuteHeight / 4), 
            180F, 180F, false, paint)

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (cuteWidth / 2), y - (cuteHeight / 2), x + (cuteWidth / 2), y + (cuteHeight / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 4, pupilPaint)
        }
    }
    
    private fun drawBloodshotEyeWithHeight(canvas: Canvas, x: Float, y: Float, width: Float, 
                                          baseHeight: Float, eyeHeight: Float) {
        val centreX = x + (lookingX * eyeHeight) / 4
        val centreY = y + (lookingY * eyeHeight) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX, centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        canvas.drawOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), paint)
        canvas.drawArc(x - (width / 1.75F), y - (baseHeight / 1.35F), x + (width / 1.75F), y - (baseHeight / 3), 
            180F, 180F, false, paint)

        val veinPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.RED
            strokeWidth = 3F
        }

        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), Path.Direction.CW)
        }) {
            canvas.drawLine(x - (width / 3), y - (eyeHeight / 4), x + (width / 3), y - (eyeHeight / 4), veinPaint)
            canvas.drawLine(x - (width / 3), y, x + (width / 3), y, veinPaint)
            canvas.drawLine(x - (width / 3), y + (eyeHeight / 4), x + (width / 3), y + (eyeHeight / 4), veinPaint)
            canvas.drawLine(x - (width / 4), y - (eyeHeight / 3), x + (width / 4), y + (eyeHeight / 3), veinPaint)
            canvas.drawLine(x - (width / 4), y + (eyeHeight / 3), x + (width / 4), y - (eyeHeight / 3), veinPaint)
            canvas.drawLine(x - (width / 6), y - (eyeHeight / 2), x - (width / 6), y + (eyeHeight / 2), veinPaint)
            canvas.drawLine(x + (width / 6), y - (eyeHeight / 2), x + (width / 6), y + (eyeHeight / 2), veinPaint)
        }

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }

    private fun drawNormalEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        val centreX = x + (lookingX * height) / 4
        val centreY = y + (lookingY * height) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX , centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)

        canvas.drawArc(x - (width / 1.75F), y - (height / 1.35F) , x + (width / 1.75F), y - (height / 3), 180F, 180F, false, paint )

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }

    private fun drawSleepingEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Draw closed eye as a horizontal line
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 8F
        }
        canvas.drawLine(x - (width / 2), y, x + (width / 2), y, paint)
    }

    private fun drawBlinkingEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Animate blink by gradually closing the eye
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 8F
        }
        
        // Calculate how much the eye should be closed (0 = fully open, 1 = fully closed)
        val eyeHeight = height * (1F - blinkProgress)
        
        if (eyeHeight > height * 0.1F) {
            // Draw the eye as it closes with some eye color
            val colours = IntArray(3)
            colours[0] = Color.BLUE
            colours[1] = Color.WHITE
            colours[2] = Color.GRAY

            val stops = FloatArray(3)
            stops[0] = 0F
            stops[1] = 0.35F
            stops[2] = 0.75F

            val fillPaint = Paint().apply {
                style = Paint.Style.FILL
                shader = RadialGradient(x, y, width, colours, stops, Shader.TileMode.CLAMP)
            }
            
            canvas.drawOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), fillPaint)
            canvas.drawOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), paint)
        } else {
            // Eye is fully closed, draw as a horizontal line
            canvas.drawLine(x - (width / 2), y, x + (width / 2), y, paint)
        }
    }

    private fun drawThinkingEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Draw normal eye but with a slight upward tilt to show thinking
        val centreX = x + (lookingX * height) / 4
        val centreY = y + (lookingY * height) / 4 - (height * 0.1F) // Slight upward tilt

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX , centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)

        // Draw thinking eyebrow
        canvas.drawArc(x - (width / 1.75F), y - (height / 1.35F) , x + (width / 1.75F), y - (height / 3), 180F, 180F, false, paint )

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }

    private fun drawSleepyEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Draw half-closed eye
        val centreX = x + (lookingX * height) / 4
        val centreY = y + (lookingY * height) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX , centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        // Draw smaller eye (half-closed)
        val sleepyHeight = height * 0.6F
        canvas.drawOval(x - (width / 2), y - (sleepyHeight / 2), x + (width / 2), y + (sleepyHeight / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (sleepyHeight / 2), x + (width / 2), y + (sleepyHeight / 2), paint)

        // Draw sleepy eyebrow (lower)
        canvas.drawArc(x - (width / 1.75F), y - (height / 1.35F) , x + (width / 1.75F), y - (height / 3), 180F, 180F, false, paint )

        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (sleepyHeight / 2), x + (width / 2), y + (sleepyHeight / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }

    private fun drawCuteEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Draw larger, more rounded eyes for cute expression
        val centreX = x + (lookingX * height) / 4
        val centreY = y + (lookingY * height) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX , centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        
        // Draw larger, more rounded eye for cute effect
        val cuteWidth = width * 1.2F
        val cuteHeight = height * 1.1F
        canvas.drawOval(x - (cuteWidth / 2), y - (cuteHeight / 2), x + (cuteWidth / 2), y + (cuteHeight / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 8F
        }

        canvas.drawOval(x - (cuteWidth / 2), y - (cuteHeight / 2), x + (cuteWidth / 2), y + (cuteHeight / 2), paint)

        // Draw cute eyebrow (higher and more curved)
        canvas.drawArc(x - (cuteWidth / 1.5F), y - (cuteHeight / 1.2F) , x + (cuteWidth / 1.5F), y - (cuteHeight / 4), 180F, 180F, false, paint )

        // Draw larger pupil for cute effect
        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (cuteWidth / 2), y - (cuteHeight / 2), x + (cuteWidth / 2), y + (cuteHeight / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 4, pupilPaint) // Larger pupil
        }
    }

    private fun drawBloodshotEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Draw normal eye first
        val centreX = x + (lookingX * height) / 4
        val centreY = y + (lookingY * height) / 4

        val colours = IntArray(3)
        colours[0] = Color.BLUE
        colours[1] = Color.WHITE
        colours[2] = Color.GRAY

        val stops = FloatArray(3)
        stops[0] = 0F
        stops[1] = 0.35F
        stops[2] = 0.75F

        var paint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(centreX , centreY, width, colours, stops, Shader.TileMode.CLAMP)
        }
        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)

        paint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = 10F
        }

        canvas.drawOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), paint)

        canvas.drawArc(x - (width / 1.75F), y - (height / 1.35F) , x + (width / 1.75F), y - (height / 3), 180F, 180F, false, paint )

        // Draw bloodshot veins
        val veinPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.RED
            strokeWidth = 3F
        }

        // Draw multiple red veins across the eye
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), Path.Direction.CW)
        }) {
            // Horizontal veins
            canvas.drawLine(x - (width / 3), y - (height / 4), x + (width / 3), y - (height / 4), veinPaint)
            canvas.drawLine(x - (width / 3), y, x + (width / 3), y, veinPaint)
            canvas.drawLine(x - (width / 3), y + (height / 4), x + (width / 3), y + (height / 4), veinPaint)
            
            // Diagonal veins
            canvas.drawLine(x - (width / 4), y - (height / 3), x + (width / 4), y + (height / 3), veinPaint)
            canvas.drawLine(x - (width / 4), y + (height / 3), x + (width / 4), y - (height / 3), veinPaint)
            
            // Vertical veins
            canvas.drawLine(x - (width / 6), y - (height / 2), x - (width / 6), y + (height / 2), veinPaint)
            canvas.drawLine(x + (width / 6), y - (height / 2), x + (width / 6), y + (height / 2), veinPaint)
        }

        // Draw pupil
        val pupilPaint = Paint().apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.BLACK
        }
        canvas.withClip(Path().apply {
            addOval(x - (width / 2), y - (height / 2), x + (width / 2), y + (height / 2), Path.Direction.CW)
        }) {
            canvas.drawCircle(centreX, centreY, width / 5, pupilPaint)
        }
    }
}