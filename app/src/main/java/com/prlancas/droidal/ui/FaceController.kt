package com.prlancas.droidal.ui

import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Look
import com.prlancas.droidal.event.events.SetExpression
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Bridges face-related events on the [EventBus] onto the [FaceCanvas]:
 *
 *  - [Look] steers the gaze (and optionally the expression) so the eyes
 *    track whatever the camera processor is publishing.
 *  - [SetExpression] changes only the expression, leaving the gaze alone —
 *    used by `Agent` to open the eyes ([Expression.NORMAL]) while a
 *    conversation is in flight and close them ([Expression.SLEEP]) when it
 *    ends.
 *
 * The dedicated single-thread context guarantees ordered delivery — back-
 * to-back events don't race for the UI thread.
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
        MainScope().launch(newSingleThreadContext("ExpressionThread")) {
            EventBus.subscribe<SetExpression> {
                mainActivity.runOnUiThread {
                    faceCanvas.setExpression(it.expression)
                }
            }
        }
    }
}
