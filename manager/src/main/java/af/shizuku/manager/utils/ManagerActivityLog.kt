package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.database.ActivityLogManager
import android.content.Context

/** Activity-log entries about the manager's own service, under the name the rest of the UI uses. */
object ManagerActivityLog {
    fun log(
        context: Context,
        action: String,
    ) = ActivityLogManager.log(context.getString(R.string.activity_log_self_name), context.packageName, action)
}
