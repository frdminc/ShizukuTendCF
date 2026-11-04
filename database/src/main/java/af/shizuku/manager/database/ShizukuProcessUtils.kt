package af.shizuku.manager.database

import java.io.OutputStream
import rikka.shizuku.Shizuku
import timber.log.Timber

/** Result of a privileged command run through [ShizukuProcessUtils.runPrivilegedCapture]. */
data class ShizukuCaptureResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Shared helper for running commands through `Shizuku.newProcess` and capturing their output.
 * Extracted from the copy-pasted drain logic that used to live separately in
 * `RootCompatHelper` (executePrivileged/streamToPrivilegedFile/runPrivilegedCapture) and
 * `ScriptSnippetManager.run`.
 */
object ShizukuProcessUtils {

    /**
     * Runs [cmd] via Shizuku's privileged process API, draining stdout/stderr on background
     * threads so a full pipe buffer can't hang the child. If [writeStdin] is given it's invoked
     * with the process's stdin before waiting (the lambda must close it, e.g. via `.use { }`, so
     * the child sees EOF); otherwise stdin is closed immediately. [joinTimeoutMs] bounds how long
     * we wait for the drain threads once the process exits.
     */
    fun runPrivilegedCapture(
        cmd: Array<String>,
        joinTimeoutMs: Long = 1500,
        writeStdin: ((OutputStream) -> Unit)? = null
    ): ShizukuCaptureResult {
        if (!Shizuku.pingBinder()) {
            return ShizukuCaptureResult(-1, "", "Shizuku binder not available")
        }
        return try {
            val process = Shizuku.newProcess(cmd, null, null)
            // StringBuffer (not StringBuilder) on purpose: the drain threads append() while the
            // caller thread may read toString() on the join-timeout path below. StringBuffer's
            // per-method synchronization makes that concurrent access safe (a torn read of a
            // StringBuilder can throw AIOOBE / return corrupt text); worst case here is a partial
            // but well-formed snapshot, which the "may be truncated" warning already covers.
            val out = StringBuffer()
            val err = StringBuffer()
            val outT = Thread {
                try {
                    process.inputStream.bufferedReader().use { out.append(it.readText()) }
                } catch (e: Exception) {
                    Timber.v(e, "runPrivilegedCapture: stdout drain failed")
                }
            }.also { it.isDaemon = true }
            val errT = Thread {
                try {
                    process.errorStream.bufferedReader().use { err.append(it.readText()) }
                } catch (e: Exception) {
                    Timber.v(e, "runPrivilegedCapture: stderr drain failed")
                }
            }.also { it.isDaemon = true }
            outT.start(); errT.start()
            if (writeStdin != null) {
                writeStdin(process.outputStream)
            } else {
                try { process.outputStream.close() } catch (_: Exception) {}
            }
            val exitCode = process.waitFor()
            outT.join(joinTimeoutMs); errT.join(joinTimeoutMs)
            if (outT.isAlive || errT.isAlive) {
                Timber.w("runPrivilegedCapture: drain thread still running after ${joinTimeoutMs}ms join timeout; output may be truncated")
            }
            ShizukuCaptureResult(exitCode, out.toString(), err.toString())
        } catch (e: Exception) {
            Timber.w(e, "runPrivilegedCapture failed")
            ShizukuCaptureResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Spawns [command] as a detached, persistent privileged daemon (UID 2000 / 0) that outlives
     * the caller and the individual `newProcess` binder call (#531). Redirects stdin/stdout/stderr
     * to `/dev/null` inside a new session (`setsid`) so pipe drain threads never block and the
     * child is not torn down when the shell exits.
     *
     * @return the spawned child's PID (> 0), or -1 on failure.
     */
    fun runDetached(command: String): Int {
        if (!Shizuku.pingBinder() || command.isBlank()) return -1
        return try {
            val escaped = "'" + command.replace("'", "'\\''") + "'"
            val wrapper = "setsid sh -c $escaped </dev/null >/dev/null 2>&1 & echo \$!"
            val process =
                Shizuku.newProcess(
                    arrayOf("sh", "-c", wrapper),
                    // null inherits the server's environment; a non-null array replaces it
                    // entirely (no PATH, so `setsid` would not resolve).
                    null,
                    null,
                )
            try {
                process.outputStream.close()
            } catch (_: Exception) {
            }
            val pidLine =
                process.inputStream
                    .bufferedReader()
                    .use { it.readLine()?.trim() }
            process.waitFor()
            pidLine?.toIntOrNull()?.takeIf { it > 0 } ?: -1
        } catch (e: Exception) {
            Timber.w(e, "runDetached failed")
            -1
        }
    }

    /** Returns true if a privileged process with [pid] is currently alive. */
    fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0 || !Shizuku.pingBinder()) return false
        return runPrivilegedCapture(arrayOf("sh", "-c", "kill -0 $pid 2>/dev/null")).exitCode == 0
    }

    /** Sends [signal] (default `TERM`) to a privileged process with [pid]. */
    fun killProcess(
        pid: Int,
        signal: String = "TERM",
    ): Boolean {
        if (pid <= 0 || !Shizuku.pingBinder()) return false
        val safeSig = signal.filter { it.isLetterOrDigit() }.ifEmpty { "TERM" }
        return runPrivilegedCapture(arrayOf("sh", "-c", "kill -s $safeSig $pid 2>/dev/null")).exitCode == 0
    }
}
