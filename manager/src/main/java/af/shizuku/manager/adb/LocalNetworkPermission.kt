package af.shizuku.manager.adb

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher

/**
 * Android 16+ "Local Network Protection" gates access to the local network — including the mDNS
 * (NsdManager) discovery of the wireless-debugging service and the loopback socket that ADB
 * pairing/connect rely on. On Android 17 this is enforced, so an app that hasn't been granted
 * local-network access can't find or reach the adb service and Shizuku silently fails to
 * start/connect (no authorized apps, privileged actions fail — see #317, mirrors thedjchi's fork).
 *
 * Tiering matches thedjchi's proven fork: Android 17 (SDK 37) gates on ACCESS_LOCAL_NETWORK,
 * Android 16 (SDK 36) on NEARBY_WIFI_DEVICES. ACCESS_LOCAL_NETWORK is an API 36 permission not in
 * the SDK 35 constants, so it's referenced as a string literal.
 *
 * Android 17 also shows a "Choose a device to connect" picker for a discovery made without the
 * permission unless the request carries [android.net.nsd.DiscoveryRequest.FLAG_NO_PICKER] (#25).
 * [AdbMdns] sets that flag on every discovery, so a start that runs where nobody can answer a picker
 * (boot, the watchdog, HEADLESS_START) never blocks on one; without the permission such a discovery
 * fails instead, and [WirelessDebugging] reports that rather than waiting for a port that cannot
 * come. The permission itself is requested on every interactive path that discovers: the start
 * dialog, both pairing flows.
 */
object LocalNetworkPermission {
    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    const val REQUEST_CODE = 0x10CA1 // "LOCAl" network

    /** [Build.VERSION.SDK_INT], a field so the harness can stand in Android 16 or 17. */
    @Volatile
    internal var sdkInt: Int = Build.VERSION.SDK_INT

    /** The single runtime permission that gates local-network / mDNS access on this OS, or null. */
    fun required(): String? =
        when {
            sdkInt >= 37 -> ACCESS_LOCAL_NETWORK
            sdkInt >= 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
            else -> null
        }

    /**
     * Whether a discovery without the permission fails outright on this OS. Android 17 refuses a
     * no-picker discovery made without ACCESS_LOCAL_NETWORK; Android 16's NEARBY_WIFI_DEVICES tier
     * is best effort, so a discovery there is still attempted.
     */
    fun enforced(): Boolean = sdkInt >= 37

    fun granted(context: Context): Boolean {
        val permission = required() ?: return true
        return try {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Best-effort request for flows without a result callback (e.g. the ADB-start screen). No-op
     * when the permission is already granted or not applicable to this OS version. Never throws,
     * so it can't break the ADB flow it's trying to enable.
     */
    fun request(
        activity: Activity,
        requestCode: Int = REQUEST_CODE,
    ) {
        try {
            val permission = required() ?: return
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(permission), requestCode)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Asks through [launcher] when this OS gates discovery and the permission is missing. Returns
     * true when a request was launched (the caller continues from the launcher's result), false
     * when nothing stands in the way (the caller continues at once). Never throws.
     */
    fun requestIfNeeded(
        context: Context,
        launcher: ActivityResultLauncher<String>,
    ): Boolean {
        val permission = required() ?: return false
        if (granted(context)) return false
        return try {
            launcher.launch(permission)
            true
        } catch (_: Throwable) {
            false
        }
    }

    internal fun resetForTesting() {
        sdkInt = Build.VERSION.SDK_INT
    }
}

/** A discovery or start this OS refuses until local network access is granted (#25). */
class LocalNetworkPermissionException(
    message: String,
) : Exception(message)
