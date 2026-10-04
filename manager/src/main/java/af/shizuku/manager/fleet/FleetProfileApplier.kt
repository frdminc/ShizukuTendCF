package af.shizuku.manager.fleet

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Applies a JSON fleet profile to Shizuku's preferences. Keys map 1:1 onto
 * [ShizukuSettings.Keys]; the few that have side effects (start-on-boot, watchdog, TCP mode,
 * update settings) go through their setters so component state stays consistent. Application
 * is best effort per key, not transactional: a bad value skips that key and is reported.
 */
object FleetProfileApplier {

    private val knownKeys = setOf(
        "mode", "start_on_boot", "watchdog", "tcp_mode", "tcp_port",
        "auto_disable_usb_debugging", "legacy_pairing", "update_mode",
    )

    data class Result(
        val success: Boolean,
        val appliedCount: Int,
        val skippedCount: Int,
        val errors: List<String>,
        val message: String,
        /** Hex SHA-256 of the profile bytes as read; null when they were never read. */
        val profileSha256: String? = null,
    )

    @JvmStatic
    fun applyJson(context: Context, json: String): Result {
        return try {
            val profile = JSONObject(json)
            applyProfile(context, profile)
        } catch (e: Exception) {
            // org.json appends the whole input to its syntax errors; never echo file contents.
            Result(false, 0, 0, listOf("Invalid JSON"), "Profile parse failed: invalid JSON")
        }
    }

    /**
     * Directories a profile file may be read from. The app's own external files dir is writable
     * by the app and by shell/root (`adb push`) but, on Android 11+, not by other apps, which
     * closes the swap-the-file-between-push-and-apply window shared storage would leave open.
     *
     * Known limitation on API 24-29 (Android 7-10): there the external files dir IS writable by
     * any app holding WRITE_EXTERNAL_STORAGE, so a hostile app can swap the file between the
     * `adb push` and the apply. On such devices push the profile to the internal
     * `files/fleet/` dir instead (`run-as` or root), which no other app can write. The fleet is
     * Android 11+ throughout, so this is documented rather than coded around.
     */
    private fun allowedDirs(context: Context): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir.resolve("fleet"))
            .map { it.canonicalFile }

    private fun isAllowedPath(context: Context, file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return allowedDirs(context).any { dir ->
            canonical.path == dir.path || canonical.path.startsWith(dir.path + File.separator)
        }
    }

    @JvmStatic
    fun applyFromPath(context: Context, path: String): Result {
        val file = File(path)
        if (!isAllowedPath(context, file)) {
            val where = allowedDirs(context).joinToString(" or ") { it.path }
            return Result(false, 0, 0, listOf("Path not allowed"), "Profile must be under $where")
        }
        return try {
            applyBytes(context, file.readBytes())
        } catch (e: Exception) {
            Result(false, 0, 0, listOf(e.javaClass.simpleName), "Failed to read profile: ${e.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun applyFromUri(context: Context, uri: Uri): Result {
        when (uri.scheme) {
            "file" -> return applyFromPath(context, uri.path ?: "")
            "content" -> {
                // Never let a caller read the manager's own providers back through this activity.
                // Resolve the authority (minus any "<userId>@" prefix) to its owning package rather
                // than string-matching, since this app also owns authorities under other names.
                val authority = (uri.authority ?: "").replace(Regex("^\\d+@"), "")
                val owner = runCatching {
                    context.packageManager.resolveContentProvider(authority, 0)?.packageName
                }.getOrNull()
                if (authority.isEmpty() || owner == null || owner == context.packageName) {
                    return Result(false, 0, 0, listOf("URI not allowed"), "Profile URI must point at another app's provider")
                }
            }
            else -> return Result(false, 0, 0, listOf("Unsupported URI scheme"), "Unsupported URI scheme")
        }
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result(false, 0, 0, listOf("Cannot open URI"), "Cannot open profile URI")
            applyBytes(context, stream.use { it.readBytes() })
        } catch (e: Exception) {
            Result(false, 0, 0, listOf(e.javaClass.simpleName), "Failed to read profile URI: ${e.javaClass.simpleName}")
        }
    }

    private fun applyBytes(context: Context, bytes: ByteArray): Result =
        applyJson(context, String(bytes, Charsets.UTF_8))
            .copy(profileSha256 = FleetApplyReport.sha256Hex(bytes))

    private fun applyProfile(context: Context, profile: JSONObject): Result {
        val errors = mutableListOf<String>()
        val prefs = ShizukuSettings.getPreferences()
        val clearExisting = profile.optJSONObject("_meta")?.optBoolean("clear_existing", false) ?: false

        var applied = 0
        var skipped = 0

        // clear_existing resets only the keys a profile can set, never the auth token or the
        // operator's other hardening toggles, and is committed before the setters below run so
        // their own apply()s are not wiped by a later batch clear.
        if (clearExisting) {
            val reset = prefs.edit()
            knownKeys.forEach { reset.remove(it) }
            // update_mode is a profile-level alias for these two stored keys.
            reset.remove(ShizukuSettings.Keys.KEY_AUTO_UPDATE_ENABLED)
            reset.remove(ShizukuSettings.Keys.KEY_UPDATE_CHANNEL)
            reset.commit()
        }
        val editor = prefs.edit()

        val keys = profile.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("_")) continue
            if (key !in knownKeys) {
                skipped++
                errors.add("Unknown key: $key")
                continue
            }

            try {
                when (key) {
                    "mode" -> editor.putInt(key, parseLaunchMode(profile.get(key)))
                    "tcp_port" -> editor.putString(key, parseTcpPort(profile.get(key)))
                    "tcp_mode" -> ShizukuSettings.setTcpMode(profile.getBoolean(key))
                    "start_on_boot" -> ShizukuSettings.setStartOnBoot(context, profile.getBoolean(key))
                    "watchdog" -> ShizukuSettings.setWatchdog(context, profile.getBoolean(key))
                    "update_mode" -> applyUpdateMode(profile.get(key))
                    else -> putValue(editor, key, profile.get(key))
                }
                applied++
            } catch (e: Exception) {
                skipped++
                errors.add("$key: ${e.message}")
            }
        }

        editor.apply()

        val message = "Applied $applied preferences, skipped $skipped" +
            if (errors.isEmpty()) "" else " (${errors.size} errors)"

        return Result(errors.isEmpty(), applied, skipped, errors, message)
    }

    private fun parseLaunchMode(value: Any): Int = when (value) {
        is Int -> value
        is String -> when (value.lowercase()) {
            "unknown", "none" -> ShizukuSettings.LaunchMethod.UNKNOWN
            "root" -> ShizukuSettings.LaunchMethod.ROOT
            "adb" -> ShizukuSettings.LaunchMethod.ADB
            else -> throw IllegalArgumentException("Unknown launch mode: $value")
        }
        else -> throw IllegalArgumentException("Unsupported type for mode: ${value.javaClass.simpleName}")
    }

    /**
     * The old fork stored a single update_mode int (0 off / 1 stable / 2 beta); this base keeps an
     * enable flag plus a channel ("stable" or "dev"). Accept both spellings.
     */
    private fun applyUpdateMode(value: Any) {
        val mode = when (value) {
            is Int -> when (value) {
                0 -> "off"
                1 -> "stable"
                2 -> "beta"
                else -> throw IllegalArgumentException("Unknown update mode: $value")
            }
            is String -> value.lowercase()
            else -> throw IllegalArgumentException("Unsupported type for update_mode: ${value.javaClass.simpleName}")
        }
        when (mode) {
            "off" -> ShizukuSettings.setAutoUpdateEnabled(false)
            "stable" -> {
                ShizukuSettings.setAutoUpdateEnabled(true)
                ShizukuSettings.setUpdateChannel("stable")
            }
            "beta", "dev" -> {
                ShizukuSettings.setAutoUpdateEnabled(true)
                ShizukuSettings.setUpdateChannel("dev")
            }
            else -> throw IllegalArgumentException("Unknown update mode: $value")
        }
    }

    private fun parseTcpPort(value: Any): String {
        val port = when (value) {
            is Int -> value
            is String -> value.trim().toIntOrNull()
                ?: throw IllegalArgumentException("tcp_port is not a number: $value")
            else -> throw IllegalArgumentException("Unsupported type for tcp_port: ${value.javaClass.simpleName}")
        }
        require(port in 1..65535) { "tcp_port out of range: $port" }
        return port.toString()
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Double -> editor.putFloat(key, value.toFloat())
            is Float -> editor.putFloat(key, value)
            is JSONArray -> {
                val set = LinkedHashSet<String>()
                for (i in 0 until value.length()) {
                    set.add(value.getString(i))
                }
                editor.putStringSet(key, set)
            }
            else -> throw IllegalArgumentException("Unsupported type: ${value?.javaClass?.simpleName}")
        }
    }
}
