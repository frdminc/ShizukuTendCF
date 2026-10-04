package af.shizuku.manager.receiver

import af.shizuku.manager.ShizukuSettings.LaunchMethod
import java.io.File

/**
 * The decisions [ServerUpgradeWorker] makes, kept free of Android calls so they can be unit
 * tested; the worker gathers the inputs.
 *
 * The native starter kills every running server before it launches the new one, so replacing a
 * server that still runs the previous build is allowed only when a start is certain to be
 * possible right now and nothing else is starting one. Anything else leaves the old server
 * serving: an old server that works is better than none.
 */
object ServerUpgradePolicy {
    /** Why a replacement does not run. Each is logged as one line; none retries. */
    enum class Blocker(
        val reason: String,
    ) {
        AUTH_WAIT_HELD("a start is holding adbd's authorisation dialog open"),
        AUTH_UNANSWERED("an adbd authorisation dialog went unanswered; only an explicit start may raise another"),
        START_IN_FLIGHT("another start is in flight and brings the installed build if it succeeds"),
        NO_ADB_CONNECTION("adbd does not accept this app's key on loopback without a prompt, or is not listening"),
        NO_ROOT("a root shell did not answer"),
        NO_BACKGROUND_START("the server was not started by this app as root or over ADB"),
        SECONDARY_USER("only the primary user starts the server"),
    }

    data class Preconditions(
        val primaryUser: Boolean,
        val launchMode: Int,
        val authWaitHeld: Boolean,
        /** Unreadable storage counts as set. Only read in ADB mode. */
        val unansweredMarker: Boolean,
        /** Evidence only: start work running in this process, or the unique start work RUNNING or ENQUEUED. */
        val startInFlight: Boolean,
        /** An AdbClient with offerKey = false has connected. Only read in ADB mode. */
        val adbConnectedWithoutKeyOffer: Boolean,
        /** A root shell ran a command and answered as uid 0. Only read in root mode. */
        val rootAnswered: Boolean,
    )

    /**
     * Null when the starter may run now, otherwise the first precondition that fails. Read
     * immediately before the starter.
     */
    fun blocker(p: Preconditions): Blocker? {
        if (!p.primaryUser) return Blocker.SECONDARY_USER
        if (p.launchMode != LaunchMethod.ROOT && p.launchMode != LaunchMethod.ADB) return Blocker.NO_BACKGROUND_START
        if (p.authWaitHeld) return Blocker.AUTH_WAIT_HELD
        if (p.launchMode == LaunchMethod.ADB && p.unansweredMarker) return Blocker.AUTH_UNANSWERED
        if (p.startInFlight) return Blocker.START_IN_FLIGHT
        return when (p.launchMode) {
            LaunchMethod.ROOT -> if (p.rootAnswered) null else Blocker.NO_ROOT
            else -> if (p.adbConnectedWithoutKeyOffer) null else Blocker.NO_ADB_CONNECTION
        }
    }

    /**
     * True only when both paths are an APK in a `<package>-<random>` install directory of this
     * package and the directories differ, i.e. the server was loaded from an earlier install.
     * False when it runs the installed APK, null when that cannot be told.
     */
    fun isStale(
        serverApkPath: String?,
        installedApkPath: String,
        packageName: String,
    ): Boolean? {
        val server = serverApkPath?.trim().orEmpty()
        if (server.isEmpty() || installedApkPath.isEmpty()) return null
        if (server == installedApkPath) return false
        val serverFile = File(server)
        val installedFile = File(installedApkPath)
        // A path naming no install of this package is the other flavor (Plus vs Drop-In) running
        // the shared server from its own APK, or a server started by hand: not this upgrade's.
        fun isInstallOfThisPackage(f: File) = f.parentFile?.name?.startsWith("$packageName-") == true
        if (!isInstallOfThisPackage(serverFile) || !isInstallOfThisPackage(installedFile)) return null
        if (serverFile.name != installedFile.name) return null
        return serverFile.parent != installedFile.parent
    }
}
