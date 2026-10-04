package af.shizuku.manager.fleet

import af.shizuku.manager.BuildConfig
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Records the outcome of every fleet-profile apply in `<external files dir>/fleet/last-apply.json`
 * plus one logcat line. `am start` exits 0 whatever the apply did and the toast is suppressed
 * when silent, so this file is the only way the Mac-side deploy and fleet-health probe (both
 * read it over `adb shell`) can tell a failed apply from a good one. The schema is a contract
 * with stayturgid's `shizuku_start` module and `fleet_health.HEALTH_GATHER`: change both sides
 * together. It must never carry profile contents or secrets.
 */
object FleetApplyReport {

    private const val TAG = "FleetProfile"
    const val SCHEMA = 1
    const val SOURCE_PATH = "path"
    const val SOURCE_URI = "uri"

    data class Snapshot(val ts: Long, val success: Boolean, val message: String)

    fun resultFile(context: Context): File? =
        context.getExternalFilesDir(null)?.resolve("fleet")?.resolve("last-apply.json")

    @JvmStatic
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @JvmStatic
    fun toJson(
        result: FleetProfileApplier.Result,
        source: String,
        tsSeconds: Long,
        appVersion: String,
    ): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("ts", tsSeconds)
        put("success", result.success)
        put("applied", result.appliedCount)
        put("skipped", result.skippedCount)
        put("errors", JSONArray(result.errors))
        put("message", result.message)
        // put(name, null) would drop the key; the contract wants an explicit null.
        put("profile_sha256", result.profileSha256 ?: JSONObject.NULL)
        put("source", source)
        put("app_version", appVersion)
    }

    /** Never throws: a report that cannot be written must not change the apply's outcome. */
    @JvmStatic
    fun record(context: Context, result: FleetProfileApplier.Result, source: String) {
        // android.util.Log, not Timber: release builds plant no tree that reaches logcat.
        val line = "apply success=${result.success} applied=${result.appliedCount} " +
            "skipped=${result.skippedCount} errors=${result.errors.size} " +
            "sha256=${result.profileSha256?.take(12) ?: "null"}"
        if (result.success) Log.i(TAG, line) else Log.w(TAG, line)

        try {
            val target = resultFile(context) ?: throw IllegalStateException("external files dir unavailable")
            val json = toJson(result, source, System.currentTimeMillis() / 1000, BuildConfig.VERSION_NAME)
            writeAtomically(target, json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not write apply result: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // Readers poll this file while the app may be rewriting it, so they must only ever see a
    // whole old or whole new object: write a sibling temp file, sync it, then rename over.
    private fun writeAtomically(target: File, text: String) {
        val dir = target.parentFile ?: throw IllegalStateException("no parent dir")
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("cannot create ${dir.path}")
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("rename to ${target.name} failed")
        }
    }

    @JvmStatic
    fun read(context: Context): Snapshot? = try {
        val file = resultFile(context)?.takeIf { it.isFile }
        file?.let {
            val json = JSONObject(it.readText(Charsets.UTF_8))
            Snapshot(json.getLong("ts"), json.getBoolean("success"), json.optString("message"))
        }
    } catch (e: Exception) {
        null
    }
}
