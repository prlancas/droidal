package com.prlancas.droidal.status

object GlobalStatus {
    @Volatile
    var isListeningForWakeWord: Boolean = false

    @Volatile
    var isAwake: Boolean = false

    /**
     * Wall-clock timestamp (ms since epoch) of the last frame in which
     * the camera processor saw at least one face, or `0L` when no face
     * has been seen yet this run. Used by the patient listen policy as
     * a second "the user is still here" signal alongside the RMS-based
     * voice timestamp — if Droidal can see your face, you're not gone,
     * even if you've stopped speaking for a moment.
     */
    @Volatile
    var lastFaceSeenAtMs: Long = 0L
}
