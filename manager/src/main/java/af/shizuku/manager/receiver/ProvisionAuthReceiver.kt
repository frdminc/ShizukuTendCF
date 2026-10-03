package af.shizuku.manager.receiver

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HeadlessLogger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Installs the automation auth token used by the START/STOP broadcasts from a provisioning
 * script (`adb shell am broadcast -a <pkg>.PROVISION_AUTH --es auth_token ...`). Caller
 * identity is enforced by the manifest permission (INTERACT_ACROSS_USERS_FULL: shell, root,
 * system); Binder.getCallingUid() is not meaningful inside onReceive, so it is not used.
 */
class ProvisionAuthReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.PROVISION_AUTH") return
        HeadlessLogger.init(context)
        HeadlessLogger.i("ProvisionAuth", "Auth token provisioning requested")

        val token = intent.getStringExtra("auth_token")
        if (token.isNullOrEmpty()) {
            HeadlessLogger.w("ProvisionAuth", "Missing auth token")
            Toast.makeText(context, R.string.notification_auth_missing_title, Toast.LENGTH_SHORT).show()
            setResult(2, "MISSING_TOKEN", null)
            return
        }

        ShizukuSettings.setAuthToken(token)
        HeadlessLogger.i("ProvisionAuth", "Auth token set (len=${token.length})")
        Toast.makeText(context, context.getString(R.string.home_automation_regenerate_token), Toast.LENGTH_SHORT).show()
        setResult(0, "OK", null)
    }
}
