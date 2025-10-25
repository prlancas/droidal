package com.prlancas.droidal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withClip
import com.prlancas.droidal.event.events.Expression

class FaceCanvas @JvmOverloads constructor(context: Context,
                                           attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : View(context, attrs, defStyleAttr) {

    var centerYPercent = 0.5F
    var eyeHeightPercent = 0.5F
    var eyeWidthToHeightRatio = 1F
    var distanceBetweenEyesPercentOfWidth = 1F

    var lookingX = 0F
    var lookingY = 0F
    var currentExpression = Expression.NORMAL
    var isBlinking = false
    var blinkProgress = 0F
    var blinkStartTime = 0L

    fun setLookingDirection(x: Float, y: Float) {
        lookingX = x
        lookingY = y
        invalidate()
//        println("$lookingX:$lookingY")
    }

    fun setExpression(expression: Expression) {
        currentExpression = expression
        invalidate()
    }

    fun goToSleep() {
        currentExpression = Expression.SLEEP
        invalidate()
    }

    fun blink() {
        isBlinking = true
        blinkProgress = 0F
        blinkStartTime = System.currentTimeMillis()
        startBlinkAnimation()
    }

    private fun startBlinkAnimation() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (isBlinking) {
                    val currentTime = System.currentTimeMillis()
                    val elapsed = currentTime - blinkStartTime
                    val totalDuration = 500L // 500ms total blink duration
                    
                    if (elapsed < totalDuration) {
                        // Calculate progress (0 to 1 and back to 0)
                        val halfDuration = totalDuration / 2
                        if (elapsed < halfDuration) {
                            // Closing phase (0 to 1)
                            blinkProgress = elapsed.toFloat() / halfDuration
                        } else {
                            // Opening phase (1 to 0)
                            blinkProgress = 1F - ((elapsed - halfDuration).toFloat() / halfDuration)
                        }
                        
                        invalidate()
                        handler.postDelayed(this, 16) // ~60 FPS
                    } else {
                        // Animation complete
                        isBlinking = false
                        blinkProgress = 0F
                        invalidate()
                    }
                }
            }
        }
        handler.post(runnable)
    }

    fun thinkingExpression() {
        currentExpression = Expression.THINKING
        invalidate()
    }

    fun sleepyExpression() {
        currentExpression = Expression.SLEEPY
        invalidate()
    }

    fun cuteExpression() {
        currentExpression = Expression.CUTE
        invalidate()
    }

    fun bloodshotExpression() {
        currentExpression = Expression.BLOODSHOT
        invalidate()
    }

    // Called when the view should render its content.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        canvas.drawARGB(255, 128, 108, 81)

        var eyeHeight = canvas.height * eyeHeightPercent
        var eyeWidth = eyeHeight * eyeWidthToHeightRatio
        var x = (canvas.width / 2) - (((distanceBetweenEyesPercentOfWidth + 0.5F) * eyeWidth) / 2)
        var y = canvas.height * centerYPercent

        drawEye(canvas, x, y, eyeWidth, eyeHeight)

        x = (canvas.width / 2) + (((distanceBetweenEyesPercentOfWidth + 0.5F) * eyeWidth) / 2)

        drawEye(canvas, x, y, eyeWidth, eyeHeight)
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
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

    private fun drawNormalEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        var centreX = x + (lookingX * height) / 4
        var centreY = y + (lookingY * height) / 4

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

        var pupilPaint = Paint().apply {
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
        if (isBlinking && blinkProgress > 0F) {
            // Animate blink by gradually closing the eye
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                color = android.graphics.Color.BLACK
                strokeWidth = 8F
            }
            
            // Calculate how much the eye should be closed (0 = fully open, 1 = fully closed)
            val eyeHeight = height * (1F - blinkProgress)
            
            if (eyeHeight > 0F) {
                // Draw the eye as it closes
                canvas.drawOval(x - (width / 2), y - (eyeHeight / 2), x + (width / 2), y + (eyeHeight / 2), paint)
            } else {
                // Eye is fully closed, draw as a horizontal line
                canvas.drawLine(x - (width / 2), y, x + (width / 2), y, paint)
            }
        } else {
            drawNormalEye(canvas, x, y, width, height)
        }
    }

    private fun drawThinkingEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        // Draw normal eye but with a slight upward tilt to show thinking
        var centreX = x + (lookingX * height) / 4
        var centreY = y + (lookingY * height) / 4 - (height * 0.1F) // Slight upward tilt

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

        var pupilPaint = Paint().apply {
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
        var centreX = x + (lookingX * height) / 4
        var centreY = y + (lookingY * height) / 4

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

        var pupilPaint = Paint().apply {
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
        var centreX = x + (lookingX * height) / 4
        var centreY = y + (lookingY * height) / 4

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
        var pupilPaint = Paint().apply {
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
        var centreX = x + (lookingX * height) / 4
        var centreY = y + (lookingY * height) / 4

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
        var pupilPaint = Paint().apply {
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