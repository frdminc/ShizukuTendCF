package af.shizuku.manager.update

import android.content.Context
import android.os.Environment
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File

object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    const val AUTO_BACKUP_FILENAME = "shizuku_plus_auto_backup.json"

    /** Reinstall copy kept by [af.shizuku.manager.utils.SettingsBackupManager.saveAutoBackup] (shell-owned, survives uninstall). */
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
     * The former "force update" path (uninstall this package, then install whatever APK it was
     * given) is removed in this fork: it bypassed Android's same-signer rule for updates, which is
     * what the updater's authenticity rests on (see [UpdateVerifier]), and HomeActivity's exported
     * migrate action could hand it any file. Kept as a refusing stub so callers still compile;
     * a signature mismatch is resolved by the user reinstalling by hand.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forceUpdateWithShizuku(
        context: Context,
        apkFile: File,
    ): Boolean {
        Timber.tag(TAG).w("Force update (uninstall and reinstall) is disabled; reinstall manually.")
        return false
    }
}
