package com.prlancas.droidal.event.events

/**
 * Published from UI surfaces (e.g. the Learning manager's "Stop" button)
 * to interrupt any in-flight TTS playback. Subscribed by [Speak] so the
 * existing pipeline (face animation + voice) is reused — we don't want
 * a second TTS engine running alongside it.
 */
object StopSpeaking
