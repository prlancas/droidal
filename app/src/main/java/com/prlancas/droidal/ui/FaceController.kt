package com.prlancas.droidal.ui

import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Look
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

@OptIn(DelicateCoroutinesApi::class)
class FaceController(
    val mainActivity: MainActivity,
    val faceCanvas: FaceCanvas
) {

    private val scope = MainScope()
    init {
        scope.launch(newSingleThreadContext("MyOwnThread")) {
            EventBus.subscribe<Look> {
                mainActivity.runOnUiThread { faceCanvas.setLookingDirection(it.x, it.y) }
            }
        }
    }

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

                mainActivity.runOnUiThread { faceCanvas.setLookingDirection(x, y) }
            }
        }.start()
    }
}