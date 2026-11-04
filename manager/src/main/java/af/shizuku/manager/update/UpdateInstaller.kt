package af.shizuku.manager.update

import android.content.Context
import android.os.Environment
import java.io.File

object UpdateInstaller {
    const val AUTO_BACKUP_FILENAME = "shizuku_plus_auto_backup.json"

<<<<<<< HEAD
    /**
     * Settings backup written by the former "force update" path, which uninstalled the app and
     * reinstalled whatever APK it was given. That path was removed because it bypassed Android's
     * same-signer rule for updates; MainActivity still restores a backup an older build left behind.
     */
    fun getBackupFile(context: Context): File? {
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir ?: return null
        return File(extDir, AUTO_BACKUP_FILENAME)
    }
=======
    /** Reinstall copy kept by [SettingsBackupManager.saveAutoBackup] (shell-owned, survives uninstall). */
    const val REINSTALL_BACKUP_DIR = "/data/local/tmp/shizukuplus"

    /** Per flavor: Plus and Drop-In each keep their own copy instead of overwriting each other. */
    fun reinstallBackupFile(context: Context): File = File(REINSTALL_BACKUP_DIR, "settings_backup_${context.packageName}.json")

    /**
     * Where the force-update script hands the settings over across its uninstall/reinstall. Only
     * locations other apps can't write: /data/local/tmp (shell/root only) and this app's own
     * external dir. Restored with privileged settings applied as stored.
     */
    fun handoffBackupCandidates(context: Context): List<File> =
        listOfNotNull(
            File("/data/local/tmp", AUTO_BACKUP_FILENAME),
            File("/data/local/tmp", "shizukuplus_settings.json"),
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, AUTO_BACKUP_FILENAME) },
        )

    fun trustedBackupCandidates(context: Context): List<File> = handoffBackupCandidates(context) + reinstallBackupFile(context)

    /**
     * Public Downloads copies (older builds wrote one there). Any app with storage access can
     * create or replace these, so they are never restored automatically and never with
     * privileged settings trusted.
     */
    fun publicBackupCandidates(): List<File> =
        listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), AUTO_BACKUP_FILENAME),
            File("/sdcard/Download", AUTO_BACKUP_FILENAME),
            File("/storage/emulated/0/Download", AUTO_BACKUP_FILENAME),
        )

    private fun File.usable(): Boolean = runCatching { isFile && length() > 0 }.getOrDefault(false)

    const val KEY_RESTORED_BACKUP_FINGERPRINT = "restored_backup_fingerprint"

    // The force-update script relaunches the app right after reinstalling it.
    private const val AUTO_RESTORE_MAX_AGE_MS = 10 * 60 * 1000L

    private fun fingerprint(file: File): String = "${file.length()}:${file.lastModified()}"

    /**
     * True once this exact file (size + mtime) was restored. Deleting it isn't enough: the script
     * writes the Android/data copy as shell, so the app often can't delete it, and the same old
     * backup was restored again on every launch, reverting the user's later changes.
     */
    fun wasRestored(
        context: Context,
        file: File,
    ): Boolean =
        runCatching {
            af.shizuku.manager.ShizukuSettings
                .getPreferences()
                .getString(KEY_RESTORED_BACKUP_FINGERPRINT, null)
                ?.split('|')
                ?.contains(fingerprint(file)) == true
        }.getOrDefault(false)

    /** Record [file] as restored, then remove every copy that can be removed. */
    fun consumeBackup(
        context: Context,
        file: File,
    ) {
        runCatching {
            val prefs =
                af.shizuku.manager.ShizukuSettings
                    .getPreferences()
            val seen = prefs.getString(KEY_RESTORED_BACKUP_FINGERPRINT, null)?.split('|').orEmpty()
            val updated = (seen + fingerprint(file)).distinct().takeLast(8).joinToString("|")
            prefs.edit().putString(KEY_RESTORED_BACKUP_FINGERPRINT, updated).commit()
        }
        deleteAllBackupCopies(context)
    }

    /**
     * Whether a restore from [file] may apply privileged settings as stored: only a force-update
     * handoff written minutes ago by this app. The reinstall copy can be written by any app
     * authorized for Shizuku (shell), and public copies by any app, so those ask first.
     */
    fun isTrustedBackupLocation(
        context: Context,
        file: File,
    ): Boolean {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val isHandoff = handoffBackupCandidates(context).any { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) == path }
        return isHandoff && System.currentTimeMillis() - file.lastModified() in 0L..AUTO_RESTORE_MAX_AGE_MS
    }

    /**
     * After a restore, remove every copy: a leftover one was restored again on the next launch
     * (reverting changes made since), and copies hold settings such as scanner API keys.
     */
    fun deleteAllBackupCopies(context: Context) {
        // Not the reinstall copy: saveAutoBackup keeps rewriting it on purpose.
        val leftovers = handoffBackupCandidates(context) + publicBackupCandidates() + File(context.filesDir, AUTO_BACKUP_FILENAME)
        leftovers.forEach { f -> runCatching { if (f.exists()) f.delete() } }
        // The /data/local/tmp handoff copies are shell-owned (and hold API keys): remove them as
        // shell when the service is up.
        val shellOwned = leftovers.filter { it.absolutePath.startsWith("/data/local/tmp/") && runCatching { it.exists() }.getOrDefault(false) }
        if (shellOwned.isNotEmpty() && runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            runCatching {
                Shizuku.newProcess(arrayOf("rm", "-f") + shellOwned.map { it.absolutePath }, null, null).waitFor()
            }
        }
    }

    /**
     * Performs a seamless background update when standard in-place installation is
     * blocked (e.g. signature mismatch between Debug and Release builds, or corrupted install).
     *
     * Automatically backs up all preferences and app authorization state to a persistent location,
     * detaches a shell process via Shizuku (UID 2000) or Root, removes the conflicting package,
     * installs the new APK, auto-grants permissions, and relaunches the app so the backup is
     * immediately imported on startup.
     */
    fun forceUpdateWithShizuku(
        context: Context,
        apkFile: File,
    ): Boolean {
        val hasShizuku = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val isRoot = !hasShizuku && runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!hasShizuku && !isRoot) {
            Timber.tag(TAG).w("No root or Shizuku available for force update.")
            return false
        }
        if (!isNotOlderThanInstalled(context, apkFile)) {
            Timber.tag(TAG).w("Force update refused: the APK is older than the installed version")
            return false
        }

        return try {
            // The handoff file is world-readable (the reinstalled app runs as a new user), so it
            // gets the same secret-free copy as the reinstall backup.
            val backupJson = SettingsBackupManager.withoutSecrets(SettingsBackupManager.export(context))
            val files =
                mapOf(
                    "$TMP/update.apk" to apkFile.readBytes(),
                    "$TMP/shizukuplus_settings.json" to backupJson.toByteArray(Charsets.UTF_8),
                    "$TMP/force_update.sh" to forceUpdateScript(context.packageName).toByteArray(Charsets.UTF_8),
                )
            val staged = if (isRoot) stageWithRoot(context, files) else stageWithShizuku(files)
            if (!staged) {
                Timber.tag(TAG).e("Force update: staging files failed")
                return false
            }
            val launch = "chmod 755 $TMP/force_update.sh && nohup /system/bin/sh $TMP/force_update.sh >/dev/null 2>&1 &"
            val started =
                if (isRoot) {
                    Shell.cmd(launch).exec().isSuccess
                } else {
                    @Suppress("DEPRECATION")
                    Shizuku.newProcess(arrayOf("sh", "-c", launch), null, null)?.waitFor() == 0
                }
            Timber.tag(TAG).i("Detached force update started via %s: %s", if (isRoot) "root" else "Shizuku", started)
            started
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to force update with Shizuku/Root")
            false
        }
    }

    private const val TMP = "/data/local/tmp"

    /** Same version is allowed: switching between differently signed builds of one release. */
    private fun isNotOlderThanInstalled(
        context: Context,
        apk: File,
    ): Boolean {
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        if (candidate.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, 0)
        return androidx.core.content.pm.PackageInfoCompat
            .getLongVersionCode(candidate) >=
            androidx.core.content.pm.PackageInfoCompat
                .getLongVersionCode(installed)
    }

    /** Shell (uid 2000) can't read the app's files, so each one is piped in over stdin. */
    private fun stageWithShizuku(files: Map<String, ByteArray>): Boolean =
        files.all { (path, bytes) ->
            @Suppress("DEPRECATION")
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", "cat > $path && chmod 644 $path"), null, null) ?: return false
            proc.outputStream.use { it.write(bytes) }
            proc.waitFor() == 0
        }

    /** The app can't write to /data/local/tmp itself; root copies the files in from its cache. */
    private fun stageWithRoot(
        context: Context,
        files: Map<String, ByteArray>,
    ): Boolean {
        val dir = File(context.cacheDir, "force_update").apply { mkdirs() }
        return try {
            val commands =
                files.map { (path, bytes) ->
                    val local = File(dir, File(path).name).apply { writeBytes(bytes) }
                    "cp '${local.absolutePath}' $path && chmod 644 $path"
                }
            Shell.cmd(*commands.toTypedArray()).exec().isSuccess
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Runs detached as shell or root. Tries an in-place install first; only when that fails
     * (signature mismatch) does it uninstall, and if the new APK then won't install either it
     * puts the previous version back rather than leaving no app at all.
     */
    private fun forceUpdateScript(packageName: String): String =
        """
        #!/system/bin/sh
        sleep 1
        OLD_APK=${'$'}(pm path $packageName | head -n 1 | cut -d: -f2)
        [ -n "${'$'}OLD_APK" ] && cp "${'$'}OLD_APK" $TMP/previous.apk 2>/dev/null
        INSTALLED=1
        if ! pm install -r $TMP/update.apk; then
            pm uninstall $packageName
            if ! pm install $TMP/update.apk; then
                INSTALLED=0
                [ -f $TMP/previous.apk ] && pm install $TMP/previous.apk
            fi
        fi
        if [ ${'$'}INSTALLED -eq 1 ]; then
            pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null
            pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
            appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null
            appops set $packageName GET_USAGE_STATS allow 2>/dev/null
            appops set $packageName MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
            cp $TMP/shizukuplus_settings.json "$TMP/$AUTO_BACKUP_FILENAME" 2>/dev/null
            chmod 644 "$TMP/$AUTO_BACKUP_FILENAME" 2>/dev/null
            am start -n $packageName/af.shizuku.manager.MainActivity
        fi
        rm -f $TMP/shizukuplus_settings.json $TMP/update.apk $TMP/previous.apk $TMP/force_update.sh
        """.trimIndent()
>>>>>>> upstream/master
}
