package af.shizuku.manager.utils

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
<<<<<<< HEAD
import java.util.concurrent.atomic.AtomicInteger
=======
>>>>>>> upstream/master
import java.util.concurrent.atomic.AtomicReference

object ShizukuStateMachine {
    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    // Seeded from the last persisted settled state (see the persistence hook in transition()),
    // not a hardcoded STOPPED - a freshly cold-started process (e.g. WatchdogAlarmReceiver reviving
    // the app after an OEM freezer killed it, #417) otherwise has no way to tell "the server was
    // RUNNING when this process died" apart from "the user deliberately stopped it": both would
    // read as the in-memory default, and update() below would silently report STOPPED for an actual
    // crash, so the watchdog's CRASHED-triggered restart would never fire for exactly the case it
    // exists to handle.
    private var state = AtomicReference<State>(loadPersistedSettledState())
    private val listeners = CopyOnWriteArrayList<Registration>()

    /**
     * A state listener. Transitions happen on whatever thread makes them (AdbStarter, the start
     * worker, binder callbacks), so a listener that touches views is called on the main thread
     * ([onMainThread]); only thread-safe ones (asFlow's trySend) are called inline.
     */
    private class Registration(
        val listener: (State) -> Unit,
        val onMainThread: Boolean,
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Main-thread deliveries posted but not yet run. While any are queued, a transition made on the
    // main thread queues behind them instead of running inline, so listeners see states in order.
    private val queuedMainDeliveries = AtomicInteger(0)
    private val startingTimestamp =
        java.util.concurrent.atomic
            .AtomicLong(0L)
    private const val STARTING_TIMEOUT_MS = 90_000L
    private val stoppingTimestamp =
        java.util.concurrent.atomic
            .AtomicLong(0L)

    // A stop whose binder never reported death (no binder held by this process, exit() that
    // threw) used to stay STOPPING for good.
    private const val STOPPING_TIMEOUT_MS = 15_000L
    private const val COLD_START_GRACE_MS = 2_500L

    @Volatile
    private var processStartElapsedMs = android.os.SystemClock.elapsedRealtime()
    private val binderReceivedThisProcess = AtomicBoolean(false)
    private val coldStartRecheckScheduled = AtomicBoolean(false)

    // One exemption check runs at a time in this process: two RUNNING transitions close together
    // (a crash right after the first start, then a quick restart) each launch a coroutine that could
    // read the pending flag before the other clears it. Claiming here keeps the post to one.
    private val exemptionCheckRunning = AtomicBoolean(false)

    /**
     * Tests only: signalled when the post-RUNNING coroutine (Device Hardening, privilege grant,
     * and the new-install battery-exemption check) has finished, so a scenario can wait for it.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var postRunningLatchForTesting: CountDownLatch? = null

    /** Tests only: what a new process would start from (the persisted settled state). */
    internal fun resetForTesting() {
        state.set(loadPersistedSettledState())
        startingTimestamp.set(0L)
        processStartElapsedMs = -COLD_START_GRACE_MS * 2
        binderReceivedThisProcess.set(false)
        coldStartRecheckScheduled.set(false)
        postRunningLatchForTesting = null
        exemptionCheckRunning.set(false)
        BatteryOptimizationGuard.resetForTesting()
        VendorBatteryRestrictions.resetForTesting()
    }

    /** A start of ours that has not yet produced a new server; [binder] is the one present when it began. */
    private class PendingStart(
        val binder: IBinder?,
    )

    private val pendingStart = AtomicReference<PendingStart?>(null)

    // One exemption check runs at a time in this process: two RUNNING transitions close together
    // (a crash right after the first start, then a quick restart) each launch a coroutine that could
    // read the pending flag before the other clears it. Claiming here keeps the post to one.
    private val exemptionCheckRunning = AtomicBoolean(false)

    /**
     * Tests only: signalled when the post-RUNNING coroutine (Device Hardening, privilege grant,
     * and the new-install battery-exemption check) has finished, so a scenario can wait for it.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var postRunningLatchForTesting: CountDownLatch? = null

    /** Tests only: what a new process would start from (the persisted settled state). */
    internal fun resetForTesting() {
        state.set(loadPersistedSettledState())
        startingTimestamp.set(0L)
        pendingStart.set(null)
        postRunningLatchForTesting = null
        exemptionCheckRunning.set(false)
        BatteryOptimizationGuard.resetForTesting()
        VendorBatteryRestrictions.resetForTesting()
    }

    private fun loadPersistedSettledState(): State =
        try {
            when (ShizukuSettings.getLastSettledState()) {
                State.RUNNING.name -> State.RUNNING
                State.CRASHED.name -> State.CRASHED
                else -> State.STOPPED
            }
        } catch (_: Exception) {
            State.STOPPED
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                Sentry.addBreadcrumb(
                    Breadcrumb("Binder received - service is now RUNNING").apply {
                        category = "shizuku.service"
                    },
                )
<<<<<<< HEAD
                // A live binder does not say this manager's ADB key was accepted (the server may
                // have been started as root, from a computer, or before the key was revoked), so
                // the unanswered marker stays: it is cleared where adbd accepts the key
                // (AdbClient) and by explicit starts. Its notice is hidden while a server runs;
                // the start notification follows this state machine's flow, so it comes back
                // when the server stops.
                set(State.RUNNING)
                recordIfNewServer()
=======
                binderReceivedThisProcess.set(true)
                set(State.RUNNING)
                // Push the user's Plus feature flags as soon as the binder arrives - in the
                // background too (watchdog, boot), not only when Home is open - so the server never
                // runs with defaults the user changed.
                af.shizuku.manager.ShizukuSettings
                    .syncAllPlusFeaturesToServer()
>>>>>>> upstream/master
            },
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener {
                Sentry.addBreadcrumb(
                    Breadcrumb("Binder dead - service connection lost").apply {
                        category = "shizuku.service"
                        level = io.sentry.SentryLevel.WARNING
                    },
                )
                setDead()
            },
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        transitionAndGet(transform)
    }

    /** [transition], returning the state it produced. */
    private fun transitionAndGet(transform: (State) -> State): State {
        // Capture the value our own CAS produced: a separate state.get() after getAndUpdate can
        // observe a concurrent transition's later write, making oldState == newState and silently
        // skipping listener/broadcast side effects for a transition that did occur.
        var computed: State? = null
        val oldState = state.getAndUpdate { current -> transform(current).also { computed = it } }
        val newState = computed ?: error("getAndUpdate lambda must always execute synchronously")
        if (oldState != newState) {
<<<<<<< HEAD
=======
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                listeners.forEach { it(newState) }
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    listeners.forEach { it(newState) }
                }
            }
>>>>>>> upstream/master
            Timber.tag("ShizukuStateMachine").d(newState.toString())
            logConnectionTransition(oldState, newState)

<<<<<<< HEAD
            // Which app build started the running server is recorded when a start succeeds, not
            // when it begins. All deliberate start paths (AdbStarter, ShizukuReceiverStarter, tile,
            // StarterActivity) funnel through STARTING, which notes the server binder present then;
            // the build is recorded once RUNNING is reached with a different binder, that is, a new
            // server. Recording at STARTING marked a server left over from a pre-update build as
            // current when the restart failed and update() settled back onto it, so
            // isServerVersionSkewed() never fired in exactly the case it exists for.
=======
            // Record which app build is starting this server instance. All deliberate start paths
            // (AdbStarter, ShizukuReceiverStarter, tile, StarterActivity) funnel through STARTING,
            // so this captures every fresh start centrally and lets isServerVersionSkewed() later
            // detect a running server left behind by a pre-update build.
            if (newState == State.STOPPING) stoppingTimestamp.set(System.currentTimeMillis())
            // Running again, however it was started: a stop the user made earlier no longer
            // stands (root and other non-ADB starts never cleared it, so Watchdog and reconnect
            // would have kept treating Shizuku+ as stopped on purpose).
            if (newState == State.RUNNING) {
                runCatching { UserStopFlag.clear() }
                runCatching {
                    af.shizuku.manager.doctor.StartFailureLog
                        .clear()
                }
            }
>>>>>>> upstream/master
            if (newState == State.STARTING) {
                startingTimestamp.set(System.currentTimeMillis())
                pendingStart.set(PendingStart(currentBinder()))
            }
            if (newState == State.RUNNING) {
                recordIfNewServer()
                applyWatchdogDefault()
            }

            // After the bookkeeping above: a RUNNING listener (HomeActivity's version-skew check)
            // must see the build this start recorded, or it offers a "restart" that stops the new
            // server. Each listener is isolated: one that throws must not abort the side effects
            // below (the persisted state, the STATE_CHANGED broadcast) or the caller's start.
            deliver(newState)

            if (newState == State.RUNNING || newState == State.STOPPED || newState == State.CRASHED) {
                try {
                    val context = ShizukuApplication.appContext
                    af.shizuku.manager.automation.AutomationEngine.dispatchEvent(
                        af.shizuku.manager.automation
                            .ShizukuStateEvent(newState == State.RUNNING),
                        context,
                    )
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to dispatch automation event")
                }

                if (newState == State.RUNNING) {
                    applyWatchdogDefault()
                    try {
                        val context = ShizukuApplication.appContext
                        af.shizuku.manager.service.WatchdogService
                            .dismissCrashNotification(context)
                        af.shizuku.manager.worker.AdbStartWorker
                            .dismissNotifications(context)
                        ShizukuIssues.clearStartProblems(context)
                        val nm = context.getSystemService(android.app.NotificationManager::class.java)
                        nm?.cancel(af.shizuku.manager.receiver.ShizukuReceiverStarter.NOTIFICATION_ID)
                        nm?.cancel(af.shizuku.manager.receiver.ShizukuReceiverStarter.FOREGROUND_NOTIFICATION_ID)
                        nm?.cancel(BackgroundNudge.NOTIFICATION_ID)
                        nm?.cancel(af.shizuku.manager.adb.AdbPairingService.NOTIFICATION_ID)
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to cancel lingering notifications on RUNNING")
                    }
                    try {
                        val context = ShizukuApplication.appContext
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                try {
                                    if (ShizukuSettings.isDeviceHardeningEnabled()) {
                                        DeviceOptimizer.applyFixes(context)
                                    }
                                    SettingsHelper.autoGrantPrivileges(context)
<<<<<<< HEAD
=======
                                    val trigger =
                                        if (oldState == State.CRASHED ||
                                            af.shizuku.manager.service.WatchdogService
                                                .isRunning()
                                        ) {
                                            "Watchdog / service start"
                                        } else {
                                            "service start"
                                        }
                                    af.shizuku.manager.database.ScriptSnippetManager
                                        .runAutoRunSnippets(trigger)
>>>>>>> upstream/master
                                } catch (e: Exception) {
                                    Timber.tag("ShizukuStateMachine").e(e, "Failed to apply optimizations and privileges on RUNNING")
                                }
                                if (ShizukuSettings.isWatchdogExemptionPending() && exemptionCheckRunning.compareAndSet(false, true)) {
                                    try {
                                        if (!ShizukuSettings.getWatchdog()) {
                                            // The user turned the Watchdog off after the default; nothing to exempt.
                                            ShizukuSettings.clearWatchdogExemptionPending()
                                        } else {
                                            // The check runs on the main thread (not this IO dispatcher): the
<<<<<<< HEAD
                                            // scenario harness serves the system services it reads reliably
=======
                                            // Robolectric unit tests serve the system services it reads reliably
>>>>>>> upstream/master
                                            // only from the main looper. It stays after the Device Hardening
                                            // pass above, which is what makes it observe the pass's effect.
                                            val settled =
                                                withContext(Dispatchers.Main) {
                                                    BatteryOptimizationGuard.ensureExemption(context)
                                                }
                                            if (settled) ShizukuSettings.clearWatchdogExemptionPending()
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag("ShizukuStateMachine").e(e, "Failed to check the battery exemption for the new-install Watchdog")
                                    } finally {
                                        exemptionCheckRunning.set(false)
                                    }
                                }
                                if (ShizukuSettings.getWatchdog()) {
                                    try {
                                        // Vendor battery restrictions (Samsung, Xiaomi) are re-applied by the
<<<<<<< HEAD
                                        // vendor at any time, so unlike the one-shot #36 exemption check above this
=======
                                        // vendor at any time, so unlike the one-shot exemption check above this
>>>>>>> upstream/master
                                        // runs at every start while the Watchdog is on. The shell work stays on this
                                        // IO dispatcher; the system-service reads inside switch to Main.
                                        VendorBatteryRestrictions.check(context)
                                    } catch (e: Exception) {
                                        Timber.tag("ShizukuStateMachine").e(e, "Failed to check vendor battery restrictions")
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag("ShizukuStateMachine").e(e, "Failed to run the post-RUNNING work")
                            } finally {
                                postRunningLatchForTesting?.countDown()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to apply optimizations and privileges on RUNNING")
                    }
                }

                if (newState == State.CRASHED && oldState == State.RUNNING) {
                    runCatching { ShizukuIssues.onServerLost(ShizukuApplication.appContext) }
                }

                if ((newState == State.STOPPED || newState == State.CRASHED) && (oldState == State.RUNNING || oldState == State.STARTING)) {
                    try {
                        val context = ShizukuApplication.appContext
                        af.shizuku.manager.doctor.ServiceDoctor
                            .triggerAdaptiveAutoHealAsync(context)
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to trigger adaptive auto-heal")
                    }
                }

                val shouldAutoClose =
                    ShizukuSettings.isAutoCloseTcpPortEnabled() &&
                        (
                            newState == State.STOPPED ||
                                (newState == State.CRASHED && !ShizukuSettings.isWatchdogAutoRestartEnabled())
                        )
                if (shouldAutoClose) {
                    try {
                        val context = ShizukuApplication.appContext
                        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                            // Close the ADB TCP listening port by disabling wireless debugging.
                            // This runs asynchronously so it doesn't block the state transition.
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    af.shizuku.manager.adb.AdbServerPin.putSetting(
                                        context,
                                        "adb_wifi_enabled",
                                        0,
                                    )
                                    Timber.tag("ShizukuStateMachine").i("auto-closed TCP port (disabled wireless debugging)")
                                } catch (e: Exception) {
                                    Timber.tag("ShizukuStateMachine").w(e, "Failed to auto-close TCP port")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to initiate TCP auto-close on $newState")
                    }
                }

                // Persist so a future cold-started process (see loadPersistedSettledState() above)
                // can tell a crash from a deliberate stop instead of defaulting to "assume stopped".
                try {
                    ShizukuSettings.setLastSettledState(newState.name)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to persist settled state")
                }
            }

            // Broadcast state change for widgets and other receivers
            try {
                val context = ShizukuApplication.appContext
                val intent =
                    android.content.Intent("af.shizuku.manager.action.STATE_CHANGED").apply {
                        setPackage(context.packageName)
                    }
                context.sendBroadcast(intent)
            } catch (_: UninitializedPropertyAccessException) {
                Timber.tag("ShizukuStateMachine").w("Skipping broadcast: appContext not initialized yet")
            }
        }
        return newState
    }

    /**
     * A new install's Watchdog default turns on with its first server, so that until a server has
     * run the watchdog starts nothing and shows nothing (see ShizukuSettings.decideWatchdogDefault).
     */
    private fun applyWatchdogDefault() {
        try {
            val context = ShizukuApplication.appContext
            if (ShizukuSettings.applyWatchdogDefaultOnFirstRun(context)) {
                ManagerActivityLog.log(context, "Watchdog: turned on with the first server start (the default for a new install)")
            }
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "Failed to apply the new-install Watchdog default")
        }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() =
        transition {
            when (it) {
                State.RUNNING -> State.CRASHED
                State.STOPPING -> {
                    try {
                        val context = ShizukuApplication.appContext
                        val permissionGranted = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                        val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                        if (shouldDisableUsbDebugging) {
                            af.shizuku.manager.adb.AdbServerPin
                                .putSetting(context, Settings.Global.ADB_ENABLED, 0)
                        }
                    } catch (_: UninitializedPropertyAccessException) {
                        Timber.tag("ShizukuStateMachine").w("Skipping USB debugging disable: appContext not initialized yet")
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to disable USB debugging")
                    }
                    State.STOPPED
                }
                else -> it
            }
        }

    fun update(): State {
        val span = Sentry.getSpan()?.startChild("ipc.shizuku", "pingBinder")
        val isAlive =
            try {
                Shizuku.pingBinder()
            } catch (_: Exception) {
                false
            } finally {
                span?.finish()
            }
        // Re-verify before calling a RUNNING server crashed, in case it attached between the
        // first ping and now. Done here, not in the transition below, which may run again.
        val verifiedAlive =
            !isAlive &&
                get() == State.RUNNING &&
                try {
                    Shizuku.pingBinder()
                } catch (_: Exception) {
                    false
                }
        val sinceProcessStart = android.os.SystemClock.elapsedRealtime() - processStartElapsedMs
        val inColdStartGrace = !binderReceivedThisProcess.get() && sinceProcessStart in 0L until COLD_START_GRACE_MS
        var needsColdStartRecheck = false

<<<<<<< HEAD
        val currentState = get()
        val state =
            when {
                isAlive -> State.RUNNING
                currentState == State.STARTING -> {
                    // Break out of STARTING after 90 s so a failed start (server process died,
                    // ADB connection refused, etc.) never leaves the UI permanently locked.
                    val elapsed = System.currentTimeMillis() - startingTimestamp.get()
                    // A start waiting on adbd's authorisation dialog is still in progress; calling
                    // it STOPPED would invite a second start, and with it a second dialog.
                    if (elapsed > STARTING_TIMEOUT_MS &&
                        !af.shizuku.manager.adb.AdbAuthWait
                            .isWaiting()
                    ) {
                        State.STOPPED
                    } else {
                        State.STARTING
                    }
=======
        // Decided against the state as it is at the moment of the change, not as it was read
        // before the ping: deciding first and then setting unconditionally let a stale read win
        // (a STOPPING the user just made went back to RUNNING, then CRASHED, and Watchdog
        // restarted the server they had stopped; or RUNNING went back to STARTING).
        val result =
            transitionAndGet { current ->
                needsColdStartRecheck = false
                when {
                    // A stop is under way and the server hasn't gone yet: stay stopping.
                    isAlive && current == State.STOPPING -> State.STOPPING
                    isAlive -> State.RUNNING
                    current == State.STARTING -> {
                        // Break out of STARTING after 90 s so a failed start (server process died,
                        // ADB connection refused, etc.) never leaves the UI permanently locked.
                        val elapsed = System.currentTimeMillis() - startingTimestamp.get()
                        // A start waiting on adbd's authorisation dialog is still in progress;
                        // calling it STOPPED would invite a second start, and a second dialog.
                        if (elapsed > STARTING_TIMEOUT_MS &&
                            !af.shizuku.manager.adb.AdbAuthWait
                                .isWaiting()
                        ) {
                            State.STOPPED
                        } else {
                            State.STARTING
                        }
                    }
                    current == State.STOPPING -> {
                        val elapsed = System.currentTimeMillis() - stoppingTimestamp.get()
                        if (elapsed > STOPPING_TIMEOUT_MS) State.STOPPED else State.STOPPING
                    }
                    current == State.CRASHED -> State.CRASHED
                    // Was RUNNING (or, thanks to loadPersistedSettledState(), a freshly cold-started
                    // process that persisted RUNNING before it died) and the binder isn't
                    // answering: that's a crash, not a stop.
                    current == State.RUNNING ->
                        when {
                            verifiedAlive -> State.RUNNING
                            inColdStartGrace -> {
                                needsColdStartRecheck = true
                                State.RUNNING
                            }
                            else -> State.CRASHED
                        }
                    else -> State.STOPPED
>>>>>>> upstream/master
                }
            }
        if (verifiedAlive) binderReceivedThisProcess.set(true)
        if (needsColdStartRecheck) scheduleColdStartRecheck(COLD_START_GRACE_MS - sinceProcessStart + 50L)
        return result
    }

    private fun scheduleColdStartRecheck(delayMs: Long) {
        if (coldStartRecheckScheduled.compareAndSet(false, true)) {
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    coldStartRecheckScheduled.set(false)
                    update()
                }, delayMs)
            }.onFailure {
                coldStartRecheckScheduled.set(false)
            }
        }
    }

    /** When a drop was last observed, so the next RUNNING can report how long the gap was. */
    private val lastDropAtMs =
        java.util.concurrent.atomic
            .AtomicLong(0L)

    /**
     * Records connection-level transitions in the activity log (kept longer than ordinary entries):
     * a running server dying or being stopped, a start that never completed, and recovery. Fired
     * once per real state change, so repeated update() polling can't spam it.
     */
    private fun logConnectionTransition(
        old: State,
        new: State,
    ) {
        val message =
            when {
                old == State.RUNNING && new == State.CRASHED -> {
                    lastDropAtMs.set(System.currentTimeMillis())
                    DeactivationAdvisor.recordUnexpectedDrop()
                    "Connection: Shizuku service dropped unexpectedly (process or binder died)"
                }
                old == State.RUNNING && new == State.STOPPING -> {
                    lastDropAtMs.set(System.currentTimeMillis())
                    if (!UserStopFlag.isSet()) DeactivationAdvisor.recordUnexpectedDrop()
                    if (UserStopFlag.isSet()) "Connection: Shizuku service stopped by you" else "Connection: Shizuku service stopping"
                }
                old == State.RUNNING && new == State.STOPPED -> {
                    lastDropAtMs.set(System.currentTimeMillis())
                    if (!UserStopFlag.isSet()) DeactivationAdvisor.recordUnexpectedDrop()
                    "Connection: Shizuku service stopped"
                }
                old == State.STARTING && (new == State.STOPPED || new == State.CRASHED) ->
                    "Connection: start did not complete (${new.name.lowercase()})"
                new == State.RUNNING && lastDropAtMs.get() > 0L -> {
                    val seconds = (System.currentTimeMillis() - lastDropAtMs.getAndSet(0L)) / 1000
                    "Connection: restored after ${seconds}s"
                }
                else -> null
            } ?: return
        runCatching {
            af.shizuku.manager.database.ActivityLogManager
                .log("Shizuku", ShizukuApplication.appContext.packageName, message)
        }
    }

    fun isRunning(): Boolean = get() == State.RUNNING

    /**
     * True when the running privileged server was started by an app build older than the one now
     * installed — i.e. the app was updated but the server (a separate long-lived process) is still
     * running the old code. The binder wire protocol can differ across versions, so this can
     * silently break connections for third-party apps until the service is restarted. Returns false
     * when the starting build is unknown (0, e.g. server predates this tracking) or already current.
     */
    fun isServerVersionSkewed(): Boolean {
        if (!isRunning()) return false
        val startedBuild = ShizukuSettings.getServerStartedBuild()
        return startedBuild in 1 until BuildConfig.VERSION_CODE
    }

<<<<<<< HEAD
    private fun currentBinder(): IBinder? = runCatching { Shizuku.getBinder() }.getOrNull()

    /**
     * Records this build as the running server's starter when a start of ours is pending and the
     * server now answering is a new one (its binder differs from the one present when the start
     * began). A restart that failed over a live older server leaves the binder unchanged, so the
     * older build stays recorded and the skew stays visible. The pending start is kept until a new
     * server arrives, so a start that timed out to STOPPED is still recorded when it comes up late.
     */
    private fun recordIfNewServer() {
        val start = pendingStart.get() ?: return
        val binder = currentBinder() ?: return
        if (binder === start.binder) return
        if (!pendingStart.compareAndSet(start, null)) return
        try {
            ShizukuSettings.setServerStartedBuild(BuildConfig.VERSION_CODE)
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "Failed to record server start build")
=======
    /**
     * A new install's Watchdog default turns on with its first server, so that until a server has
     * run the watchdog starts nothing and shows nothing (see ShizukuSettings.decideWatchdogDefault).
     */
    private fun applyWatchdogDefault() {
        try {
            val context = ShizukuApplication.appContext
            if (ShizukuSettings.applyWatchdogDefaultOnFirstRun(context)) {
                ActivityLogManager.log("Shizuku", context.packageName, "Watchdog: turned on with the first server start (the default for a new install)")
            }
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "Failed to apply the new-install Watchdog default")
>>>>>>> upstream/master
        }
    }

    fun isDead(): Boolean = (get() == State.STOPPED || get() == State.CRASHED)

    /**
     * Calls [listener] with the current state and on every transition, always on the main thread,
     * so it may touch views. Use [asFlow] to follow the state from another thread.
     */
    fun addListener(listener: (State) -> Unit) = addListener(listener, onMainThread = true)

    private fun addListener(
        listener: (State) -> Unit,
        onMainThread: Boolean,
    ) {
        listeners.add(Registration(listener, onMainThread))
        if (onMainThread) {
            runOnMainThread { if (listeners.any { it.listener === listener }) notify(listener, state.get()) }
        } else {
            notify(listener, state.get())
        }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.removeAll { it.listener === listener }
    }

    // The flow's listener only hands the state to a channel, which is thread-safe, so it is called
    // inline: the watchdog and start-notification collectors do not wait on the main thread.
    fun asFlow(): Flow<State> =
        callbackFlow {
            val listener: (State) -> Unit = { trySend(it).isSuccess }
            addListener(listener, onMainThread = false)
            awaitClose { removeListener(listener) }
        }

    private fun deliver(newState: State) {
        listeners.forEach { if (!it.onMainThread) notify(it.listener, newState) }
        if (listeners.any { it.onMainThread }) {
            // Read the list when the delivery runs: a listener removed meanwhile (a destroyed
            // activity's) is not called, one added meanwhile already had the current state.
            runOnMainThread { listeners.forEach { if (it.onMainThread) notify(it.listener, newState) } }
        }
    }

    private fun notify(
        listener: (State) -> Unit,
        newState: State,
    ) {
        try {
            listener(newState)
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "State listener failed on $newState")
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        val main = runCatching { Looper.getMainLooper() }.getOrNull()
        if (main == null || (Looper.myLooper() == main && queuedMainDeliveries.get() == 0)) {
            block()
            return
        }
        queuedMainDeliveries.incrementAndGet()
        val posted =
            mainHandler.post {
                try {
                    block()
                } finally {
                    queuedMainDeliveries.decrementAndGet()
                }
            }
        if (!posted) queuedMainDeliveries.decrementAndGet()
    }
}
