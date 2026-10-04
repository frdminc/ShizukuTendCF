package af.shizuku.manager.receiver

import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.AdbClient
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.adb.AdbKeyNotAcceptedException
import af.shizuku.manager.adb.AdbPortProber
import af.shizuku.manager.adb.PreferenceAdbKeyStore
import af.shizuku.manager.receiver.ServerUpgradePolicy.Blocker
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import af.shizuku.manager.worker.AdbStartWorker
import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit

/**
 * After an in-place upgrade, replaces a server still running the previous build, once. The
 * server is a separate long-lived process, so `adb install -r` leaves it serving the old code.
 *
 * The native starter kills the old server before it launches the new one, so it runs only when
 * every [ServerUpgradePolicy.blocker] precondition holds immediately before it: in ADB mode over
 * a connection adbd already accepts without the key being offered (it can neither raise the
 * dialog nor touch the unanswered marker), in root mode after a root shell answered, and with no
 * other start in flight. Otherwise the old server keeps running.
 *
 * It does nothing else. No retry, no record, no hand-off: if no new server answers, the state
 * becomes CRASHED and the watchdog owns it as it owns any missing server. No server at upgrade
 * time is an ordinary server-down, which the watchdog and the boot start already own.
 */
class ServerUpgradeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            HeadlessLogger.init(applicationContext)
            // A periodic retry an unreleased build of this worker armed; nothing arms it any more.
            runCatching { WorkManager.getInstance(applicationContext).cancelUniqueWork(LEGACY_SLOW_WORK_NAME) }
            try {
                replaceIfStale()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HeadlessLogger.e(LOG_COMPONENT, "Upgrade check failed", e)
            }
            Result.success()
        }

    private suspend fun replaceIfStale() {
        val context = applicationContext
        // The process the install started gets the running server's binder once the server
        // notices it, which takes a moment.
        val binderAlive =
            withTimeoutOrNull(BINDER_WAIT_MS) {
                while (!pingBinder()) delay(250)
                true
            } ?: false
        if (!binderAlive) {
            HeadlessLogger.i(LOG_COMPONENT, "No server running after the upgrade; nothing to replace")
            return
        }

        val installedApk = context.applicationInfo.sourceDir
        val serverApk = readServerApkPath()
        when (ServerUpgradePolicy.isStale(serverApk, installedApk, context.packageName)) {
            false -> {
                HeadlessLogger.i(LOG_COMPONENT, "Server already runs the installed build r${BuildConfig.VERSION_CODE}")
                return
            }
            null -> {
                HeadlessLogger.i(LOG_COMPONENT, "Server left as it is: cannot tell which build it runs (${serverApk ?: "path unreadable"})")
                return
            }
            true -> Unit
        }
        val builds = "server $serverApk, installed r${BuildConfig.VERSION_CODE} $installedApk"
        val launchMode = ShizukuSettings.getLastLaunchMode()

        // Everything but the route first, so a blocked replacement opens no connection or root shell.
        blocker(launchMode, routeReady = true)?.let { return standDown(it, builds) }

        if (launchMode == LaunchMethod.ROOT) {
            blocker(launchMode, routeReady = rootShellAnswers())?.let { return standDown(it, builds) }
            HeadlessLogger.i(LOG_COMPONENT, "Replacing the old server as root: $builds")
            replace(installedApk) { runStarterAsRoot() }
            return
        }

        val port = reachableAdbPort() ?: return standDown(Blocker.NO_ADB_CONNECTION, builds)
        val key =
            try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
            } catch (e: Exception) {
                HeadlessLogger.w(LOG_COMPONENT, "Cannot load the ADB key: ${e.message}")
                return standDown(Blocker.NO_ADB_CONNECTION, builds)
            }
        val client = AdbClient("127.0.0.1", port, key, offerKey = false)
        try {
            val connected =
                try {
                    client.connect()
                    true
                } catch (e: AdbKeyNotAcceptedException) {
                    false
                } catch (e: Exception) {
                    HeadlessLogger.w(LOG_COMPONENT, "Cannot connect to adbd on port $port: ${e.message}")
                    false
                }
            blocker(launchMode, routeReady = connected)?.let { return standDown(it, builds) }
            HeadlessLogger.i(LOG_COMPONENT, "Replacing the old server over adbd port $port: $builds")
            replace(installedApk) {
                val output = StringBuilder()
                try {
                    client.command("shell:${Starter.internalCommand}") { output.append(String(it)) }
                } finally {
                    client.close()
                    logStarterFatals(output.lines())
                }
            }
        } finally {
            client.close()
        }
    }

    /** Read just before the starter; [routeReady] is the no-key connection (ADB) or the root shell's answer (root). */
    private fun blocker(
        launchMode: Int,
        routeReady: Boolean,
    ): Blocker? =
        ServerUpgradePolicy.blocker(
            ServerUpgradePolicy.Preconditions(
                primaryUser = UserHandleCompat.myUserId() == 0,
                launchMode = launchMode,
                authWaitHeld = AdbAuthWait.isWaiting(),
                // Storage that cannot be read counts as a marker, as it does for AdbStartWorker.
                unansweredMarker = AdbAuthWait.unansweredStamp() != 0L,
                startInFlight = AdbAuthWait.starts.state.value.running > 0 || isStartWorkRunningOrQueued(),
                adbConnectedWithoutKeyOffer = routeReady,
                rootAnswered = routeReady,
            ),
        )

    private fun standDown(
        blocker: Blocker,
        builds: String,
    ) {
        HeadlessLogger.i(LOG_COMPONENT, "Old server left running: ${blocker.reason} ($builds)")
    }

    /**
     * Runs the starter, waits for a server from the installed APK, and settles the state without
     * cancellation: once the starter has run the old server may be gone, and a stop at a later
     * suspend point must not skip naming a missing server CRASHED.
     */
    private suspend fun replace(
        installedApk: String,
        runStarter: () -> Unit,
    ) = withContext(NonCancellable) {
        // Counted as start work, so update() does not settle the STARTING below and nothing else
        // reads this replacement as no start at all.
        val replaced =
            AdbAuthWait.starts.track {
                // STARTING keeps setDead() from reading the old server's death as a crash the
                // watchdog would answer with a second start while this one runs.
                ShizukuStateMachine.set(State.STARTING)
                runCatching(runStarter).onFailure { HeadlessLogger.w(LOG_COMPONENT, "Starter did not complete: ${it.message}") }
                awaitNewServer(installedApk)
            }
        when {
            replaced -> {
                ShizukuStateMachine.update()
                HeadlessLogger.i(LOG_COMPONENT, "Server now runs the installed build r${BuildConfig.VERSION_CODE} ($installedApk)")
            }
            pingBinder() -> {
                ShizukuStateMachine.update()
                HeadlessLogger.w(LOG_COMPONENT, "A server answers but is not confirmed to run the installed build; left as it is")
            }
            AdbAuthWait.starts.state.value.running > 0 ->
                HeadlessLogger.w(LOG_COMPONENT, "No server answered after the starter; another start is in flight and settles the state")
            else -> {
                ShizukuStateMachine.set(State.CRASHED)
                HeadlessLogger.w(LOG_COMPONENT, "No server answered after the starter; marked CRASHED for the watchdog")
            }
        }
    }

    // The exit code is logged, never trusted: the starter exits 0 once its child forked, before
    // exec. Bounded, so a hung shell cannot hold the run.
    private fun runStarterAsRoot() {
        val result = Shell.cmd(Starter.internalCommand).enqueue().get(ROOT_STARTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!result.isSuccess) HeadlessLogger.w(LOG_COMPONENT, "Root starter exited with code ${result.code}")
        logStarterFatals(result.out + result.err)
    }

    private fun logStarterFatals(lines: List<String>) {
        lines.map { it.trim() }.filter { it.startsWith("fatal:") }.forEach {
            HeadlessLogger.w(LOG_COMPONENT, "Starter: $it")
        }
    }

    /**
     * Waits for a server whose APK is the installed one and that is still up [CONFIRM_MS] later.
     * A binder alone may be the old server still answering between the SIGKILL and its death
     * notice, and a new server that dies during its own start-up can answer once.
     */
    private suspend fun awaitNewServer(installedApk: String): Boolean {
        val packageName = applicationContext.packageName
        fun isCurrent() = pingBinder() && ServerUpgradePolicy.isStale(readServerApkPath(), installedApk, packageName) == false
        return withTimeoutOrNull(NEW_SERVER_WAIT_MS) {
            while (true) {
                if (isCurrent()) {
                    delay(CONFIRM_MS)
                    if (isCurrent()) break
                } else {
                    delay(500)
                }
            }
            true
        } ?: false
    }

    /**
     * adbd's loopback TCP port, without changing any adbd setting. adbd's own reported port comes
     * first: a fixed port could be held by another app relaying adbd's challenge. The configured
     * port is the same fallback the normal start's TCP fast path uses.
     */
    private fun reachableAdbPort(): Int? {
        val reported = EnvironmentUtils.getAdbTcpPort()
        if (reported in 1..65535) return reported.takeIf { AdbPortProber.isPortOpen(it, PORT_PROBE_MS) }
        if (!ShizukuSettings.getTcpMode()) return null
        return ShizukuSettings.getTcpPort().takeIf { it in 1..65535 && AdbPortProber.isPortOpen(it, PORT_PROBE_MS) }
    }

    // Bounded: libsu runs one command at a time on its shell, so a hung starter would block this too.
    private fun rootShellAnswers(): Boolean =
        runCatching {
            Shell.getShell().isRoot &&
                Shell.cmd("id -u").enqueue().get(ROOT_PROBE_MS, TimeUnit.MILLISECONDS).let { result ->
                    result.isSuccess && result.out.firstOrNull()?.trim() == "0"
                }
        }.getOrDefault(false)

    private fun pingBinder(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // Unreadable counts as in flight: a second starter could kill the first one's server.
    private fun isStartWorkRunningOrQueued(): Boolean =
        runCatching {
            WorkManager
                .getInstance(applicationContext)
                .getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
                .get(2, TimeUnit.SECONDS)
                .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }.getOrDefault(true)

    /**
     * The APK the running server was loaded from, or null if it cannot be read. The starter
     * exports CLASSPATH=<apk> before exec'ing the server, and a child spawned without an explicit
     * environment inherits it; this works against any older server, with no new binder call.
     */
    private fun readServerApkPath(): String? {
        var process: ShizukuRemoteProcess? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", "echo \"\$CLASSPATH\""), null, null) ?: return null
            if (!process.waitForTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            process.inputStream
                .bufferedReader()
                .use { it.readLine() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            HeadlessLogger.w(LOG_COMPONENT, "Cannot read the server's APK path: ${e.message}")
            null
        } finally {
            runCatching { process?.destroy() }
        }
    }

    companion object {
        private const val LOG_COMPONENT = "Upgrade"
        private const val WORK_NAME = "server_upgrade_restart"
        private const val LEGACY_SLOW_WORK_NAME = "server_upgrade_slow_retry"
        private const val BINDER_WAIT_MS = 20_000L
        private const val NEW_SERVER_WAIT_MS = 45_000L
        private const val CONFIRM_MS = 5_000L
        private const val PROBE_TIMEOUT_MS = 3_000L
        private const val ROOT_PROBE_MS = 5_000L
        private const val ROOT_STARTER_TIMEOUT_MS = 30_000L
        private const val PORT_PROBE_MS = 600

        // WorkManager's storage is credential-encrypted; until unlock the old server keeps running.
        private fun isUserLocked(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            val um = context.getSystemService(UserManager::class.java) ?: return false
            return !um.isUserUnlocked
        }

        /** From MY_PACKAGE_REPLACED only. REPLACE: each install is checked once, by its own process. */
        fun enqueue(context: Context) {
            if (isUserLocked(context)) {
                HeadlessLogger.w(LOG_COMPONENT, "User locked; not checking the server after the upgrade")
                return
            }
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ServerUpgradeWorker>().build())
        }
    }
}
