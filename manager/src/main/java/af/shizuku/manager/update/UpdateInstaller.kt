package af.shizuku.manager.update

import android.content.Context
import android.os.Environment
import java.io.File

object UpdateInstaller {
    const val AUTO_BACKUP_FILENAME = "shizuku_plus_auto_backup.json"

    /**
     * Settings backup written by the former "force update" path, which uninstalled the app and
     * reinstalled whatever APK it was given. That path was removed because it bypassed Android's
     * same-signer rule for updates; MainActivity still restores a backup an older build left behind.
     */
    fun getBackupFile(context: Context): File? {
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir ?: return null
        return File(extDir, AUTO_BACKUP_FILENAME)
    }
}
