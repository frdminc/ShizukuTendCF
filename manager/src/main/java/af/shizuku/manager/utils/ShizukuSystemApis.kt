package af.shizuku.manager.utils

import af.shizuku.common.compat.InstalledPackagesCompat
import af.shizuku.common.util.UserHandleCompat
import af.shizuku.common.util.UserInfoCompat
import af.shizuku.manager.utils.Logger.LOGGER
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.RemoteException
import rikka.hidden.compat.PermissionManagerApis
import rikka.shizuku.server.UserListCompat
import rikka.hidden.compat.util.SystemServiceBinder
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuSystemApis {
    init {
        SystemServiceBinder.setOnGetBinderListener {
            return@setOnGetBinderListener ShizukuBinderWrapper(it)
        }
    }

    private val users = arrayListOf<UserInfoCompat>()

    private fun getUsers(): List<UserInfoCompat> {
        return if (!ShizukuStateMachine.isRunning()) {
            arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner", 0))
        } else {
            try {
                val list = UserListCompat.getUsers(true, true, true)
                val users: MutableList<UserInfoCompat> = ArrayList<UserInfoCompat>()
                for (ui in list) {
                    users.add(UserInfoCompat(ui.id, ui.name, 0))
                }
                return users
            } catch (_: Throwable) {
                arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner", 0))
            }
        }
    }

    fun getUsers(useCache: Boolean = true): List<UserInfoCompat> {
        synchronized(users) {
            if (!useCache || users.isEmpty()) {
                users.clear()
                users.addAll(getUsers())
            }
            // Copy while still holding the lock: returning the live list lets callers iterate it
            // outside synchronization while a refresh clears/refills it on another thread (CME).
            return ArrayList(users)
        }
    }

    fun getUserInfo(userId: Int): UserInfoCompat =
        getUsers(useCache = true).firstOrNull { it.id == userId } ?: UserInfoCompat(
            UserHandleCompat.myUserId(),
            "Unknown",
            0,
        )

    fun getInstalledPackages(
        flags: Long,
        userId: Int,
    ): List<PackageInfo> =
        if (!ShizukuStateMachine.isRunning()) {
            ArrayList()
        } else {
            try {
                // Android 17 changed IPackageManager#getInstalledPackages' return type; the shim
                // resolves the list via the Shizuku-wrapped binder (privileged) or the context
                // PackageManager, so the authorized-apps list still populates on A17. See
                // af.shizuku.common.compat.InstalledPackagesCompat.
                InstalledPackagesCompat.getInstalledPackages(flags, userId)
            } catch (tr: Throwable) {
                throw RuntimeException(tr.message, tr)
            }
        }

    fun checkPermission(
        permName: String,
        pkgName: String,
        userId: Int,
    ): Int =
        if (!ShizukuStateMachine.isRunning()) {
            PackageManager.PERMISSION_DENIED
        } else {
            try {
                PermissionManagerApis.checkPermission(permName, pkgName, userId)
            } catch (tr: RemoteException) {
                throw RuntimeException(tr.message, tr)
            } catch (_: Throwable) {
                PackageManager.PERMISSION_DENIED
            }
        }

    fun grantRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ) {
        if (!ShizukuStateMachine.isRunning()) {
            return
        }
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
        } catch (tr: Throwable) {
            LOGGER.w("grantRuntimePermission failed for $permissionName on $packageName: ${tr.message}")
        }
    }

    fun revokeRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ) {
        if (!ShizukuStateMachine.isRunning()) {
            return
        }
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
        } catch (tr: Throwable) {
            LOGGER.w("revokeRuntimePermission failed for $permissionName on $packageName: ${tr.message}")
        }
    }
}
