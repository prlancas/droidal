package com.prlancas.droidal.ui

import com.prlancas.droidal.MainActivity

class FaceController(
    val mainActivity: MainActivity,
    val faceCanvas: FaceCanvas
) {

    private fun lookAbout() {
        val downSpeed = 0.05F
        val rightSpeed = 0.02F

        var x = 0F
        var y = 0F
        var right = true
        var down = true
        Thread {
            while (true) {
                Thread.sleep(100)
                if (right) {
                    if (x < 1F)
                        x += rightSpeed
                    else
                        right = false
                } else {
                    if (x > -1F)
                        x -= rightSpeed
                    else
                        right = true
                }

                if (down) {
                    if (y < 1F)
                        y += downSpeed
                    else
                        down = false
                } else {
                    if (y > -1F)
                        y -= downSpeed
                    else
                        down = true
                }

                mainActivity.runOnUiThread { faceCanvas.setLookingDirection(x, y) };
            }
        }.start()
    }
}