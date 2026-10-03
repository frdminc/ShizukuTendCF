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
    )

    @JvmStatic
    fun applyJson(context: Context, json: String): Result {
        return try {
            val profile = JSONObject(json)
            applyProfile(context, profile)
        } catch (e: Exception) {
            Result(false, 0, 0, listOf(e.message ?: "Invalid JSON"), "Profile parse failed: ${e.message}")
        }
    }

    @JvmStatic
    fun applyFromPath(context: Context, path: String): Result {
        return try {
            val json = File(path).readText(Charsets.UTF_8)
            applyJson(context, json)
        } catch (e: Exception) {
            Result(false, 0, 0, listOf(e.message ?: "Read error"), "Failed to read $path: ${e.message}")
        }
    }

    @JvmStatic
    fun applyFromUri(context: Context, uri: Uri): Result {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result(false, 0, 0, listOf("Cannot open URI"), "Cannot open URI: $uri")
            val json = stream.use { it.reader(Charsets.UTF_8).readText() }
            applyJson(context, json)
        } catch (e: Exception) {
            Result(false, 0, 0, listOf(e.message ?: "URI error"), "Failed to read URI $uri: ${e.message}")
        }
    }

    private fun applyProfile(context: Context, profile: JSONObject): Result {
        val errors = mutableListOf<String>()
        val prefs = ShizukuSettings.getPreferences()
        val clearExisting = profile.optJSONObject("_meta")?.optBoolean("clear_existing", false) ?: false

        var applied = 0
        var skipped = 0

        val editor = prefs.edit()
        if (clearExisting) {
            editor.clear()
        }

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
