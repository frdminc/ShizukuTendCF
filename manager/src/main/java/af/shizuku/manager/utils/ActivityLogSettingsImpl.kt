package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogSettings

class ActivityLogSettingsImpl : ActivityLogSettings {
    override fun isActivityLogEnabled(): Boolean = ShizukuSettings.isActivityLogEnabled()

    override fun getWatchdog(): Boolean = ShizukuSettings.getWatchdog()

    override fun getActivityLogRetention(): Int = ShizukuSettings.getActivityLogRetention()

    override fun setActivityLogRetention(count: Int) {
        ShizukuSettings.setActivityLogRetention(count)
    }

    override fun showNotification(
        appName: String,
        action: String,
    ) {
        if (!ShizukuSettings.isActivityLogEnabled()) return
        if (!ShizukuSettings.isLiveActivityEnabled()) return
        val context = af.shizuku.manager.ShizukuApplication.appContext
        val shown =
            af.shizuku.manager.activitylog.ActivityLogText
                .localize(context, action)
        val text =
            when {
                appName.isNotBlank() && shown.isNotBlank() -> "$appName: $shown"
                appName.isNotBlank() -> appName
                else -> shown
            }.removePrefix(":").trim()
        LiveActivityNotificationManager.show(context, text)
    }

    override fun showWarningNotification(
        title: String,
        message: String,
    ) {
        LiveActivityNotificationManager.showWarning(af.shizuku.manager.ShizukuApplication.appContext, title, message)
    }
}
