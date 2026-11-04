package af.shizuku.manager.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Result of running a saved snippet through the privileged shell channel. */
data class ScriptRunResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Manager for saved script snippets (Scripting & Snippets, #11) — persistence plus running a
 * snippet through the same privileged shell channel the rest of the app uses, via the shared
 * [ShizukuProcessUtils.runPrivilegedCapture].
 */
object ScriptSnippetManager {

    @Volatile
    private var dao: ScriptSnippetDao? = null

    // The app-level RUNNING collector and the state machine's RUNNING handler both call
    // runAutoRunSnippets for the same transition, on different IO threads: claim the slot atomically.
    private val lastAutoRunAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private const val AUTO_RUN_DEBOUNCE_MS = 3_000L

    fun initialize(context: Context) {
        if (dao != null) return
        dao = ScriptSnippetDatabase.getInstance(context.applicationContext).scriptSnippetDao()
    }

    fun getAll(): Flow<List<ScriptSnippetRoom>> =
        requireNotNull(dao) { "ScriptSnippetManager not initialized" }.getAll()

    suspend fun save(id: Long?, title: String, script: String, autoRun: Boolean = false): Long = withContext(Dispatchers.IO) {
        val d = requireNotNull(dao) { "ScriptSnippetManager not initialized" }
        val now = System.currentTimeMillis()
        if (id == null) {
            d.insert(ScriptSnippetRoom(title = title, script = script, autoRun = autoRun, createdAt = now, updatedAt = now))
        } else {
            val existing = d.getById(id)
            d.update(
                ScriptSnippetRoom(
                    id = id,
                    title = title,
                    script = script,
                    autoRun = autoRun,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
            id
        }
    }

    suspend fun runAutoRunSnippets(trigger: String = "service start") = withContext(Dispatchers.IO) {
        val d = dao ?: return@withContext
        val now = System.currentTimeMillis()
        val last = lastAutoRunAtMs.get()
        if (now - last in 0L until AUTO_RUN_DEBOUNCE_MS) return@withContext
        if (!lastAutoRunAtMs.compareAndSet(last, now)) return@withContext
        val snippets = d.getAutoRun()
        if (snippets.isEmpty()) return@withContext
        for (snippet in snippets) {
            try {
                val result = run(snippet.script)
                ActivityLogManager.log(
                    "Shizuku",
                    "af.shizuku.plus.api",
                    "Scripting: auto-ran '${snippet.title}' on $trigger (exit ${result.exitCode})"
                )
            } catch (e: Exception) {
                Timber.e(e, "Auto-run snippet '${snippet.title}' failed")
                ActivityLogManager.log(
                    "Shizuku",
                    "af.shizuku.plus.api",
                    "Scripting: auto-run '${snippet.title}' failed on $trigger (${e.message ?: e.javaClass.simpleName})"
                )
            }
        }
    }

    suspend fun delete(snippet: ScriptSnippetRoom) = withContext(Dispatchers.IO) {
        requireNotNull(dao) { "ScriptSnippetManager not initialized" }.delete(snippet)
    }

    /**
     * Runs [script] via `sh -c` through Shizuku's privileged process API and captures stdout,
     * stderr, and the exit code, via the shared [ShizukuProcessUtils.runPrivilegedCapture].
     */
    suspend fun run(script: String): ScriptRunResult = withContext(Dispatchers.IO) {
        val result = ShizukuProcessUtils.runPrivilegedCapture(arrayOf("sh", "-c", script), joinTimeoutMs = 5000)
        ScriptRunResult(result.exitCode, result.stdout, result.stderr)
    }
}
