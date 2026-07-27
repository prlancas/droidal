package com.prlancas.droidal.event.events

/**
 * Sets Droidal's facial [Expression] without touching where the eyes are
 * looking.
 *
 * [Look] carries both a gaze direction (x, y) and an optional expression,
 * so publishing it purely to change the expression would also yank the
 * pupils to whatever x/y the caller passed. The camera face-tracker fires
 * [Look] every frame to steer the gaze, so the two concerns are kept
 * separate: the tracker owns the gaze via [Look], and lifecycle code
 * (e.g. `Agent` waking the face at the start of a conversation and putting
 * it back to sleep at the end) owns the expression via [SetExpression].
 */
data class SetExpression(val expression: Expression)
