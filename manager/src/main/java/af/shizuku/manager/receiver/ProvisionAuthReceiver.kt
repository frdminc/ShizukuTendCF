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
 * script (`adb shell am broadcast -p <pkg> -a <pkg>.PROVISION_AUTH --es auth_token ...`; the
 * explicit package is required since API 26 or the broadcast is dropped). Caller
 * identity is enforced by the manifest permission (INTERACT_ACROSS_USERS_FULL: shell, root,
 * system); Binder.getCallingUid() is not meaningful inside onReceive, so it is not used.
 */
class ProvisionAuthReceiver : BroadcastReceiver() {
    private companion object {
        val TOKEN_FORMAT = Regex("^[A-Za-z0-9_-]{24,128}$")
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
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
        // This token is the only thing protecting the unpermissioned START/STOP receivers, so a
        // provisioned value must carry at least the entropy of a generated one (24 alphanumerics)
        // and must be in the exact form AuthenticatedReceiver compares against.
        if (!TOKEN_FORMAT.matches(token)) {
            HeadlessLogger.w("ProvisionAuth", "Rejected token (len=${token.length}): must match ${TOKEN_FORMAT.pattern}")
            Toast.makeText(context, R.string.notification_auth_invalid_title, Toast.LENGTH_SHORT).show()
            setResult(3, "WEAK_OR_INVALID_TOKEN", null)
            return
        }

        ShizukuSettings.setAuthToken(token)
        HeadlessLogger.i("ProvisionAuth", "Auth token set (len=${token.length})")
        Toast.makeText(context, context.getString(R.string.home_automation_regenerate_token), Toast.LENGTH_SHORT).show()
        setResult(0, "OK", null)
    }
}
