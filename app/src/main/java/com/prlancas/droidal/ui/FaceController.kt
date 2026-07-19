package com.prlancas.droidal.ui

import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Look
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Subscribes to [Look] events on the [EventBus] and forwards them onto
 * the [FaceCanvas] so the eyes track whatever the camera processor is
 * publishing. The dedicated single-thread context guarantees ordered
 * delivery — back-to-back Look events from the analyzer don't race for
 * the UI thread.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class FaceController(
    private val mainActivity: MainActivity,
    private val faceCanvas: FaceCanvas,
) {

    init {
        MainScope().launch(newSingleThreadContext("LookThread")) {
            EventBus.subscribe<Look> {
                mainActivity.runOnUiThread {
                    faceCanvas.setLookingDirection(it.x, it.y)
                    it.expression?.let { expression -> faceCanvas.setExpression(expression) }
                }
            }
        }
    }
}
