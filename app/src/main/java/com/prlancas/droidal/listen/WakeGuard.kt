package com.prlancas.droidal.listen

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny mutual-exclusion guard around the wake-word → listen handover.
 *
 * Porcupine runs on its own audio thread and a single utterance of the
 * keyword can produce two consecutive callbacks before [Listen.awaken]
 * has finished tearing down wake-word detection and starting STT. Without
 * a guard, that shows up as Droidal saying "yes yes" / "Pardon Pardon".
 *
 * The guard is a single-bit compare-and-set:
 *
 *   - [tryAwaken] returns `true` exactly once per "wake → listen → done"
 *     cycle; further calls return `false` until [release] runs.
 *   - [release] is called from the STT completion listener so the next
 *     wake-word trigger can fire.
 *
 * Extracted from the [Listen] singleton so it can be exercised in plain
 * JVM unit tests (no Android dependencies).
 */
internal class WakeGuard {

    private val awake = AtomicBoolean(false)

    /**
     * Try to enter the awake window. Returns `true` if the caller now
     * owns the window (and must eventually call [release]); returns
     * `false` if another wake-word callback already owns it — that
     * caller should silently drop the trigger.
     */
    fun tryAwaken(): Boolean = awake.compareAndSet(false, true)

    /** Leave the awake window. Idempotent and safe to call from any thread. */
    fun release() {
        awake.set(false)
    }

    /** True while a wake-word handover is in flight. */
    fun isAwake(): Boolean = awake.get()
}
