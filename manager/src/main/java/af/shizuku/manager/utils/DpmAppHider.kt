package af.shizuku.manager.utils

import af.shizuku.manager.admin.DhizukuAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import timber.log.Timber

/**
 * Freezes/unfreezes apps through DevicePolicyManager when Shizuku+ itself is the device or
 * profile owner (#632). DPMS only accepts these calls from the app that owns the admin
 * component, so this must run in the manager; the Shizuku server (shell/root uid) can't do it.
 *
 * Suspends rather than hides: a hidden package is reported as uninstalled to every query
 * without MATCH_UNINSTALLED_PACKAGES, so it would vanish from Shizuku+'s own app list and could
 * never be unfrozen from there. A suspended app stays listed (FLAG_SUSPENDED, which the server's
 * isAppFrozen already reads). Every call fails soft so callers can fall back to the server.
 */
object DpmAppHider {
    private fun dpm(context: Context): DevicePolicyManager? = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private fun admin(context: Context) = ComponentName(context, DhizukuAdminReceiver::class.java)

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        runCatching {
            val dpm = dpm(context) ?: return false
            dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)
        }.getOrDefault(false)

    @JvmStatic
    fun isFrozen(
        context: Context,
        packageName: String,
    ): Boolean =
        runCatching {
            dpm(context)?.isPackageSuspended(admin(context), packageName) == true
        }.getOrDefault(false)

    /** Returns true only if DPMS applied the change. */
    @JvmStatic
    fun setFrozen(
        context: Context,
        packageName: String,
        frozen: Boolean,
    ): Boolean =
        try {
            // Returns the packages that could NOT be changed.
            dpm(context)?.setPackagesSuspended(admin(context), arrayOf(packageName), frozen)?.isEmpty() == true
        } catch (e: Exception) {
            Timber.w(e, "DPM setPackagesSuspended(%s, %b) failed", packageName, frozen)
            false
        }
}
