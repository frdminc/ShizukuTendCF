package af.shizuku.manager.utils

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The start path's own record, kept where a release build can still be read: every line also
 * goes to logcat under [TAG] (release builds plant no Timber tree), and the file is returned by the
 * HEADLESS_LOG broadcast, since `adb shell` cannot read the app's private storage on a
 * non-debuggable build. Bounded: past [MAX_SIZE] the file is moved to `headless.log.1` (replacing
 * the previous one), so at most about twice that is kept.
 */
object HeadlessLogger {
    private const val TAG = "ShizukuHeadless"
    private const val LOG_FILE = "headless.log"
    private const val MAX_SIZE = 256 * 1024

    /** Lines HEADLESS_LOG returns when the caller does not say. */
    const val DEFAULT_TAIL_LINES = 200

    /** The most lines HEADLESS_LOG returns, whatever the caller asks for. */
    const val MAX_TAIL_LINES = 2000

    /**
     * The most characters HEADLESS_LOG returns. Result data crosses binder as UTF-16, so this is
     * about 64 KB there, far under the 1 MB transaction limit.
     */
    const val MAX_TAIL_CHARS = 32 * 1024

    /** First line of a tail that was cut to fit [MAX_TAIL_CHARS]. */
    const val TRUNCATED_MARK = "[earlier lines omitted to fit the result size]"

    @Volatile private var logDir: File? = null

    @Volatile private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    enum class Level { INFO, WARN, ERROR }

    // Logging must never break the path it records, so nothing here throws.
    @Synchronized
    fun init(context: Context) {
        if (logDir != null) return
        try {
            // Below API 30 the app's external files dir is readable by any app holding
            // READ_EXTERNAL_STORAGE, and the log names the adb port/state; keep it private there.
            val dir =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    context.getExternalFilesDir(null) ?: context.filesDir
                } else {
                    context.filesDir
                }
            dir.mkdirs()
            // Not usable (yet): stay unset, so a later init tries again.
            if (!dir.isDirectory) return
            logFile = File(dir, LOG_FILE)
            logDir = dir
        } catch (e: Exception) {
            logcat(Level.WARN, "Cannot open log dir: ${e.message}")
        }
    }

    fun i(
        component: String,
        message: String,
    ) = log(Level.INFO, component, message)

    fun w(
        component: String,
        message: String,
    ) = log(Level.WARN, component, message)

    fun e(
        component: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val msg =
            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                "$message\n$sw"
            } else {
                message
            }
        log(Level.ERROR, component, msg)
    }

    /** "Type: message" for [t] and up to two causes, one line, short enough to log on every retry. */
    fun brief(t: Throwable): String =
        generateSequence(t) { it.cause?.takeIf { cause -> cause !== it } }
            .take(3)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.take(160)}" }
            .replace('\n', ' ')

    @Synchronized
    private fun log(
        level: Level,
        component: String,
        message: String,
    ) {
        val ts = dateFormat.format(Date())
        val line = "$ts ${level.name.padEnd(5)} $component: $message"

        logcat(level, "$component: $message")

        val file = logFile ?: return
        try {
            if (file.length() > MAX_SIZE) {
                file.renameTo(File(file.parent, "$LOG_FILE.1"))
            }
            FileWriter(file, true).use { it.appendLine(line) }
        } catch (e: Exception) {
            logcat(Level.WARN, "Cannot write log file: ${e.message}")
        }
    }

    // Release builds plant no Timber tree, so this is their logcat record of the start path.
    // (A plain JVM test, with android.jar's stubs, throws here; that must not reach the caller.)
    private fun logcat(
        level: Level,
        message: String,
    ) {
        try {
            when (level) {
                Level.INFO -> Log.i(TAG, message)
                Level.WARN -> Log.w(TAG, message)
                Level.ERROR -> Log.e(TAG, message)
            }
        } catch (_: RuntimeException) {
        }
    }

    fun getLogPath(): String? = logFile?.absolutePath

    /**
     * The newest [requested] lines of the log (the rotated file first when the current one is
     * short), newest last; null when there is no log to read. See [tail] for the caps.
     */
    @Synchronized
    fun readTail(requested: Int): Tail? {
        val file = logFile ?: return null
        val rotated = File(file.parent, "$LOG_FILE.1")
        val lines = ArrayList<String>()
        try {
            if (rotated.isFile) lines += rotated.readLines()
            if (file.isFile) lines += file.readLines()
        } catch (e: Exception) {
            logcat(Level.WARN, "Cannot read log file: ${e.message}")
            return null
        }
        if (lines.isEmpty()) return null
        return tail(lines, requested)
    }

    data class Tail(
        val text: String,
        val lines: Int,
        val truncated: Boolean,
    )

    /**
     * The last lines of [lines], newest last: [requested] of them (non-positive means
     * [DEFAULT_TAIL_LINES], at most [MAX_TAIL_LINES]), then as many of those as fit in [maxChars],
     * dropping the oldest. A tail cut for size starts with [TRUNCATED_MARK]; a newest line too
     * long on its own keeps its start.
     */
    @VisibleForTesting
    internal fun tail(
        lines: List<String>,
        requested: Int,
        maxChars: Int = MAX_TAIL_CHARS,
    ): Tail {
        val n = (if (requested <= 0) DEFAULT_TAIL_LINES else requested).coerceAtMost(MAX_TAIL_LINES)
        val wanted = lines.takeLast(n)
        val kept = ArrayDeque<String>()
        var size = 0
        var truncated = false
        for (line in wanted.asReversed()) {
            val cost = line.length + if (kept.isEmpty()) 0 else 1
            if (size + cost > maxChars - TRUNCATED_MARK.length - 1) {
                truncated = true
                if (kept.isEmpty()) kept.addFirst(line.take(maxChars - TRUNCATED_MARK.length - 1))
                break
            }
            kept.addFirst(line)
            size += cost
        }
        if (truncated) kept.addFirst(TRUNCATED_MARK)
        return Tail(kept.joinToString("\n"), kept.size - if (truncated) 1 else 0, truncated)
    }

    /** Forgets the log location, so the next [init] picks it up from a new context. */
    @VisibleForTesting
    @Synchronized
    fun resetForTesting() {
        logDir = null
        logFile = null
    }
}
