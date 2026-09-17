package com.prlancas.droidal.status

object GlobalStatus {
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

    /**
     * True while Droidal is actively speaking via Text-to-Speech.
     * Speech recognition (both wake-word and conversation listens)
     * should stand down while this is true to avoid feedback loops
     * where Droidal hears and responds to its own voice.
     */
    @Volatile
    var isSpeaking: Boolean = false
}
