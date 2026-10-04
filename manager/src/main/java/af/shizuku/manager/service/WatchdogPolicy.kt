package af.shizuku.manager.service

import af.shizuku.manager.utils.ShizukuStateMachine.State

/**
 * Which missing server the watchdog restarts, and how fast. Pure, so it can be unit tested.
 *
 * CRASHED: a server that should be running is not, because it died or because a start attempt
 * failed with nothing left to retry it. The watchdog restarts it under [backoffMs].
 * STOPPED: a deliberate stop, or a failed attempt whose own retry is already queued. The
 * watchdog leaves it alone.
 */
object WatchdogPolicy {
    const val STARTING_TIMEOUT_MS = 90_000L

    /** How long RUNNING must last before the backoff starts again from its base. */
    const val STABLE_MS = 60_000L
    private const val BASE_COOLDOWN_MS = 5_000L
    private const val MAX_COOLDOWN_MS = 300_000L // 5 min cap

    fun backoffMs(crashes: Int): Long = minOf(BASE_COOLDOWN_MS * (1L shl crashes.coerceAtMost(10)), MAX_COOLDOWN_MS)

    fun restarts(state: State): Boolean = state == State.CRASHED

    /** How long a restart must still wait; 0 means now. A CRASHED inside the cooldown waits it out. */
    fun cooldownRemainingMs(
        nowMs: Long,
        lastRestartMs: Long,
        consecutiveCrashes: Int,
    ): Long = (backoffMs(consecutiveCrashes) - (nowMs - lastRestartMs)).coerceAtLeast(0L)

    /** An unanswered authorisation dialog stops unattended ADB starts, so the restart does nothing. */
    fun restartWithheld(
        adbMode: Boolean,
        unanswered: Boolean,
    ): Boolean = adbMode && unanswered

    /**
     * What update() makes of a STARTING whose binder does not answer. Past the timeout, with no
     * authorisation dialog held and no start work running in this process, the start failed:
     * CRASHED. While either holds, the start is still in flight and settles the state itself.
     */
    fun staleStarting(
        elapsedMs: Long,
        authWaitHeld: Boolean,
        startsRunning: Int,
    ): State = if (elapsedMs > STARTING_TIMEOUT_MS && !authWaitHeld && startsRunning <= 0) State.CRASHED else State.STARTING
}
