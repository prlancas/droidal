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

class FaceCanvas @JvmOverloads constructor(context: Context,
                                           attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : View(context, attrs, defStyleAttr) {

    var centerYPercent = 0.5F
    var eyeHeightPercent = 0.5F
    var eyeWidthToHeightRatio = 1F
    var distanceBetweenEyesPercentOfWidth = 1F

    var lookingX = 0F
    var lookingY = 0F

    fun setLookingDirection(x: Float, y: Float) {
        lookingX = x
        lookingY = y
        invalidate()
//        println("$lookingX:$lookingY")
    }

    // Called when the view should render its content.
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.let {

            canvas.drawARGB(255, 128, 108, 81)

            var eyeHeight = canvas.height * eyeHeightPercent
            var eyeWidth = eyeHeight * eyeWidthToHeightRatio
            var x = (canvas.width / 2) - (((distanceBetweenEyesPercentOfWidth + 0.5F) * eyeWidth) / 2)
            var y = canvas.height * centerYPercent



            drawEye(canvas, x, y, eyeWidth, eyeHeight)

            x = (canvas.width / 2) + (((distanceBetweenEyesPercentOfWidth + 0.5F) * eyeWidth) / 2)

            drawEye(canvas, x, y, eyeWidth, eyeHeight)
        }

    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
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
}