package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings

/**
 * Remembers that the user deliberately stopped Shizuku, so automatic reconnect triggers
 * (network returning, etc.) don't start it again behind their back. Cleared when the service is
 * running again (any start) and at boot, where "start on boot" is the user's standing choice.
 */
object UserStopFlag {
    private const val KEY = "user_stopped"

    fun mark() {
        runCatching {
            ShizukuSettings
                .getPreferences()
                .edit()
                .putBoolean(KEY, true)
                .apply()
        }
    }

    fun clear() {
        runCatching {
            ShizukuSettings
                .getPreferences()
                .edit()
                .remove(KEY)
                .apply()
        }
    }

    fun isSet(): Boolean = runCatching { ShizukuSettings.getPreferences().getBoolean(KEY, false) }.getOrDefault(false)
}
