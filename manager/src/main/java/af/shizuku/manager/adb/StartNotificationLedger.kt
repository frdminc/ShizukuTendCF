package af.shizuku.manager.adb

/**
 * The one ongoing notification background ADB starts share (ShizukuReceiverStarter.NOTIFICATION_ID),
 * kept as a function of state. Pure logic so it can be tested without Android; [AdbAuthWait] holds
 * the process-wide instance and every writer of that notification goes through it.
 *
 * Each background start is an attempt numbered by [schedule], which also submits its worker. What
 * the notification could say lives in three independent slots, each owned by one attempt:
 * 1. prompt: adbd's authorisation dialog is up for an attempt ([postPrompt], until [waitEnded]), or
 *    a notice that must reach the user, such as the dialog going unanswered ([finish], [notice]);
 * 2. running: an attempt's worker is running and has progress to show ([post]);
 * 3. pending: an attempt's request is queued and not running, confirmed by WorkManager
 *    ([enqueued]) or left in backoff by a run that will be retried ([retrying]).
 *
 * An event sets or clears only its own attempt's slots, and a clear for an attempt that no longer
 * owns a slot changes nothing there. Ownership only moves forward: a newer request's pending
 * status or a newer run's progress displaces an older one, never the reverse, and a cancel ends
 * every attempt issued so far. After every change [render], under the same lock, shows the prompt
 * if there is one, else the progress, else the pending status, else nothing; nothing else posts or
 * removes the notification. What ends up shown therefore depends only on which events happened,
 * not on the order in which a confirmation, a replaced worker's exit, a prompt and the end of its
 * wait reach this object from their different threads.
 *
 * Numbers outlive the process. [schedule] issues a number only once [recordIssued] has stored it,
 * so every number a persisted worker carries is at most [lastIssued], and every number issued
 * after a restart outranks it. Numbering, submitting and cancelling share this lock and
 * WorkManager applies unique-work operations in submission order, so a larger number is always
 * the later REPLACE, i.e. the request WorkManager actually keeps.
 */
internal class StartNotificationLedger(
    private val lastIssued: () -> Long,
    private val recordIssued: (Long) -> Boolean,
) {
    private class Slot(
        val attempt: Long,
        val show: () -> Unit,
        val hide: () -> Unit,
        val isNotice: Boolean = false,
    )

    // The newest attempt whose worker has run, and whether that worker is running now.
    private var current = NO_ATTEMPT
    private var currentLive = false

    // Every attempt up to this one was cancelled by the user and may never show anything again.
    private var cancelledThrough = NO_ATTEMPT

    // The newest attempt whose enqueue WorkManager confirmed. Its REPLACE removed every older
    // request, so no older attempt's confirmation, worker or retry may take a slot any more.
    private var newestEnqueued = NO_ATTEMPT

    // The newest attempt that has shown progress or a prompt; an older attempt's notice is no
    // longer news once a newer run is visibly under way.
    private var newestShown = NO_ATTEMPT

    // The newest attempt that started the server; no older attempt's notice is true any more.
    private var newestStarted = NO_ATTEMPT

    // Every attempt up to this one has ended for good, so a confirmation that arrives late (the
    // enqueue listener runs on another thread than the worker) must not leave a pending status
    // that nothing would ever remove.
    private var endedThrough = NO_ATTEMPT

    private var prompt: Slot? = null
    private var running: Slot? = null
    private var pending: Slot? = null

    // What this process last put in the notification, or null once that was removed. Null while
    // something shows means it was posted by a process that has since died.
    private var displayed: Slot? = null

    private fun isLive(attempt: Long) =
        attempt != NO_ATTEMPT && attempt == current && currentLive && attempt > cancelledThrough &&
            attempt >= newestEnqueued && attempt > endedThrough

    // Storage that cannot be read proves nothing about which numbers are taken.
    private fun recorded(): Long? = runCatching { lastIssued() }.getOrNull()

    /**
     * The only code that posts or removes the notification. [leftover] removes it when nothing
     * this process posted is showing, for the events after which a dead process's leftover can
     * only be wrong.
     */
    private fun render(leftover: (() -> Unit)? = null) {
        val top = prompt ?: running ?: pending
        if (top != null) {
            if (top !== displayed) {
                displayed = top
                top.show()
            }
            return
        }
        val gone = displayed
        displayed = null
        (gone?.hide ?: leftover)?.invoke()
    }

    // A run of [attempt] is over: its progress and its pending status go, whoever else is newer.
    // That it ran at all proves REPLACE kept its request, so an older request's pending status,
    // which has no worker left to remove it, goes too.
    private fun endRun(attempt: Long) {
        if (attempt == current) currentLive = false
        if (running?.attempt == attempt) running = null
        pending?.let { if (it.attempt <= attempt) pending = null }
    }

    /**
     * Numbers an attempt and runs [submit] with it (enqueueing its worker) as one step against
     * every other call here. Returns [NO_ATTEMPT], without running [submit], when the number could
     * not be recorded. No slot is set for the attempt until [enqueued] or its worker runs, so if
     * [submit] throws or the enqueue fails later, only an unused number is spent.
     */
    @Synchronized
    fun schedule(submit: (attempt: Long) -> Unit): Long {
        val last = recorded() ?: return NO_ATTEMPT
        val next = maxOf(current, last) + 1
        if (!runCatching { recordIssued(next) }.getOrDefault(false)) return NO_ATTEMPT
        submit(next)
        return next
    }

    /**
     * WorkManager confirmed [attempt]'s enqueue, so its request is the one queued and every older
     * one is gone: [show] becomes the pending status. Refused once [attempt]'s worker has run (it
     * shows its own state), a newer enqueue was confirmed, a cancel ended it, or its worker has
     * already ended for good without becoming the owner (it stood down).
     */
    @Synchronized
    fun enqueued(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ): Boolean {
        if (attempt <= cancelledThrough || attempt < newestEnqueued) return false
        // True even when the status itself is refused below: older requests are gone.
        newestEnqueued = attempt
        pending?.let { if (it.attempt < attempt) pending = null }
        val accepted = attempt > current && attempt > endedThrough
        if (accepted) pending = Slot(attempt, show, hide)
        render()
        return accepted
    }

    /**
     * [attempt]'s worker started running and means to go on (a worker that stands down never
     * calls this, so it cannot displace the slots of the start it stood down for). Returns
     * [attempt]; whether it may post is decided by [post]. It is the running owner unless a newer attempt has run or been confirmed, a
     * cancel ended it, or its number was never recorded (a larger recorded number than this
     * process has seen was issued by a process that has since died). A retried run of the owner
     * owns again. Its running proves REPLACE kept its request, so older attempts' progress and
     * pending statuses go; its own pending status stays until its first post. A worker without a
     * number (scheduled by an older build) never gets one: numbering it here, outside [schedule],
     * could outrank the request REPLACE is about to keep.
     */
    @Synchronized
    fun adopt(attempt: Long): Long {
        if (attempt <= NO_ATTEMPT) return NO_ATTEMPT
        val last = recorded() ?: return attempt
        if (attempt > cancelledThrough && attempt >= current && attempt >= newestEnqueued && attempt <= last) {
            if (attempt > current) {
                current = attempt
                running?.let { if (it.attempt < attempt) running = null }
                pending?.let { if (it.attempt < attempt) pending = null }
                render()
            }
            currentLive = true
        }
        return attempt
    }

    /** [attempt] stays live but its progress is shown elsewhere for now (a foreground notification). */
    @Synchronized
    fun hideProgress(attempt: Long) {
        if (running?.attempt != attempt) return
        running = null
        render()
    }

    /**
     * Progress, refused unless [attempt]'s worker is the running owner. It replaces the attempt's
     * pending status and makes an older attempt's notice stale; a prompt still shows over it.
     */
    @Synchronized
    fun post(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ): Boolean {
        if (!isLive(attempt)) return false
        running = Slot(attempt, show, hide)
        pending?.let { if (it.attempt <= attempt) pending = null }
        newestShown = maxOf(newestShown, attempt)
        prompt?.let { if (it.isNotice && it.attempt < attempt) prompt = null }
        render()
        return true
    }

    /** The key has been offered and adbd's dialog is up, so this always shows until [waitEnded]. */
    @Synchronized
    fun postPrompt(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ) {
        prompt = Slot(attempt, show, hide)
        newestShown = maxOf(newestShown, attempt)
        render()
    }

    /**
     * The one wait ended, so its prompt goes and what lies beneath shows: the attempt's own
     * progress, or a newer request's pending status. A notice is not a prompt and stays.
     */
    @Synchronized
    fun waitEnded() {
        if (prompt?.isNotice != false) return
        prompt = null
        render()
    }

    /**
     * [attempt]'s run is over and leaves a notice (the dialog went unanswered). Posted even if a
     * newer request has been confirmed meanwhile, since it is a fact about the device the user
     * needs; refused only when it is no longer true or no longer news: a cancel ended the attempt,
     * a newer attempt has shown progress or a prompt, or one has started the server.
     */
    @Synchronized
    fun finish(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ): Boolean {
        endRun(attempt)
        endedThrough = maxOf(endedThrough, attempt)
        val accepted =
            attempt != NO_ATTEMPT &&
                attempt > cancelledThrough &&
                attempt >= newestShown &&
                attempt > newestStarted &&
                prompt?.isNotice != false
        if (accepted) prompt = Slot(attempt, show, hide, isNotice = true)
        render()
        return accepted
    }

    /**
     * [attempt]'s run ended but WorkManager will run it again (backoff, or the system stopped it),
     * so it stays queued and [show] becomes its pending status, with the controls. Its progress
     * goes either way; the pending status is refused unless it was still the running owner, since
     * a cancel or a newer request has otherwise already ended it.
     */
    @Synchronized
    fun retrying(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ): Boolean {
        val accepted = isLive(attempt)
        endRun(attempt)
        if (accepted) pending = Slot(attempt, show, hide)
        render()
        return accepted
    }

    /**
     * [attempt] started the server, so its own and every older attempt's progress, pending status
     * and notice are no longer true; a newer attempt's, which is still under way or queued, and
     * any prompt (its dialog is up) stay.
     */
    @Synchronized
    fun clear(
        attempt: Long,
        hide: () -> Unit,
    ) {
        endRun(attempt)
        endedThrough = maxOf(endedThrough, attempt)
        newestStarted = maxOf(newestStarted, attempt)
        running?.let { if (it.attempt <= attempt) running = null }
        pending?.let { if (it.attempt <= attempt) pending = null }
        prompt?.let { if (it.isNotice && it.attempt <= attempt) prompt = null }
        render(leftover = hide)
    }

    /**
     * [attempt]'s worker stopped running for good (stood down, failed, cancelled): only its own
     * progress and pending status go. With nothing shown by this process, a leftover can only be
     * a dead process's, so it goes too.
     */
    @Synchronized
    fun withdraw(
        attempt: Long,
        hide: () -> Unit,
    ) {
        endRun(attempt)
        endedThrough = maxOf(endedThrough, attempt)
        render(leftover = hide)
    }

    /**
     * The user cancelled background starts: every attempt issued so far, in this process or a
     * dead one, is over and nothing stays. [cancelWork] then cancels the worker under the same
     * lock, so a start being scheduled is either cancelled with its notification or not at all.
     */
    @Synchronized
    fun cancel(
        hide: () -> Unit,
        cancelWork: () -> Unit,
    ) {
        cancelledThrough = maxOf(cancelledThrough, current, newestEnqueued, recorded() ?: NO_ATTEMPT)
        currentLive = false
        prompt = null
        running = null
        pending = null
        displayed = null
        hide()
        cancelWork()
    }

    /** A notice that belongs to no attempt (e.g. a missing permission); refused while a prompt is up. */
    @Synchronized
    fun notice(
        show: () -> Unit,
        hide: () -> Unit,
    ): Boolean {
        if (prompt?.isNotice == false) return false
        prompt = Slot(NO_ATTEMPT, show, hide, isNotice = true)
        render()
        return true
    }

    /**
     * The user swiped the notification away. A notice has been read and goes; whatever else the
     * slots still hold is put back.
     */
    @Synchronized
    fun restore() {
        if (prompt?.isNotice == true) prompt = null
        displayed = null
        render()
    }

    companion object {
        const val NO_ATTEMPT = 0L
    }
}
