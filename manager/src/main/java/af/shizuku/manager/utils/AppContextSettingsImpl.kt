package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.AppContextSettings

class AppContextSettingsImpl(
    private val context: android.content.Context,
) : AppContextSettings {
    override fun getBundledDbJson(): String? =
        runCatching {
            context.assets
                .open("app_context_db.json")
                .bufferedReader()
                .use { it.readText() }
        }.onFailure { timber.log.Timber.e(it, "bundled app database missing") }
            .getOrNull()

    override fun getRemoteDbJson(): String? = ShizukuSettings.getRemoteDbJson()

    override fun setRemoteDbJson(json: String) {
        ShizukuSettings.setRemoteDbJson(json)
    }

    override fun setLastDbUpdate(time: Long) {
        ShizukuSettings.setLastDbUpdate(time)
    }
}
