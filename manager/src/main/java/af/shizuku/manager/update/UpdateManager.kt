package af.shizuku.manager.update

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading and installing updates: SHA-256 verification against the release's
 * SHA256SUMS (see [UpdateVerifier]), internal cache staging to avoid Android 14+ FUSE SELinux
 * denials, and signature checking. A signature mismatch is never worked around by uninstalling
 * and reinstalling: authenticity rests on Android's same-signer rule for updates.
 */
class UpdateManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val PARTIAL_DOWNLOAD_GRACE_MS = 60 * 60 * 1000L
        private const val STAGED_PREFIX = "update-"
        private const val NOTIFICATION_CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DOWNLOAD_ID_PREF = "update_download_id"

        // Not sent by this fork (see showInstallNotification); kept for HomeActivity's handler.
        const val ACTION_MIGRATE_UPDATE = "af.shizuku.manager.action.MIGRATE_UPDATE"
        const val ACTION_SIGNATURE_MISMATCH = "af.shizuku.manager.action.SIGNATURE_MISMATCH"
        const val EXTRA_UPDATE_FILE_PATH = "af.shizuku.manager.extra.UPDATE_FILE_PATH"
        const val EXTRA_UPDATE_VERSION_NAME = "af.shizuku.manager.extra.UPDATE_VERSION_NAME"
    }

    sealed class UpdateState {
        object Idle : UpdateState()

        data class Downloading(
            val percent: Int,
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val versionName: String,
        ) : UpdateState()

        data class Installing(
            val versionName: String,
        ) : UpdateState()

        data class Ready(
            val file: File,
            val versionName: String,
        ) : UpdateState()

        data class Completed(
            val message: String,
        ) : UpdateState()

        data class Error(
            val message: String,
            val reason: Int? = null,
        ) : UpdateState()
    }

    sealed class InstallResult {
        object Success : InstallResult()

        data class NeedsSystemInstaller(
            val file: File,
        ) : InstallResult()

        /** Not produced by this fork: a mismatch is never migrated by uninstalling (see [performSmartInstall]). */
        data class SignatureMismatchCanMigrate(
            val file: File,
        ) : InstallResult()

        data class SignatureMismatchManualRequired(
            val file: File,
        ) : InstallResult()

        data class Failed(
            val message: String,
        ) : InstallResult()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val notificationManager = NotificationManagerCompat.from(context)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var downloadId: Long = -1
    private var monitorJob: Job? = null
    private var downloadJob: Job? = null
    private var isManualDownload = false

    // URL of the current download, so a damaged DownloadManager result can be fetched again
    // once over the direct HTTP path before giving up.
    private var currentDownloadUrl: String? = null
    private var retriedDirect = false

    private fun createNotificationChannel() {
        val channel =
            NotificationChannelCompat
                .Builder(
                    NOTIFICATION_CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH,
                ).setName(context.getString(R.string.update_notification_channel))
                .setDescription(context.getString(R.string.update_notification_channel_description))
                .build()
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Download update APK
     */
    @SuppressLint("Range")
    fun downloadUpdate(
        downloadUrl: String,
        versionName: String,
        manual: Boolean = false,
    ) {
        isManualDownload = manual
        currentDownloadUrl = downloadUrl
        retriedDirect = false
        createNotificationChannel()

        // Fail closed before spending the download: only a release asset URL has a SHA256SUMS
        // next to it to verify against (see UpdateVerifier).
        if (UpdateVerifier.checksumsUrlFor(downloadUrl) == null) {
            Timber.tag(TAG).w("Refusing to download update from unverifiable URL: $downloadUrl")
            _state.value = UpdateState.Error(context.getString(R.string.update_verify_failed_message))
            showDownloadErrorNotification(messageRes = R.string.update_verify_failed_message)
            return
        }

        downloadJob?.cancel()
        downloadJob =
            scope.launch(Dispatchers.IO) {
                // Plain ASCII name: '+' and other reserved characters in a file:// destination are
                // decoded inconsistently by DownloadProvider and FileProvider.
                val fileName = "ShizukuPlus-v${versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir, fileName)

                if (file.exists()) {
                    file.delete()
                }
                cleanup()

                _state.value = UpdateState.Downloading(0, 0, 0, versionName)

                if (downloadManager == null) {
                    downloadDirectly(downloadUrl, file, versionName)
                    return@launch
                }

                val request =
                    DownloadManager
                        .Request(Uri.parse(downloadUrl))
                        .setTitle(context.getString(R.string.update_downloading_title))
                        .setDescription(context.getString(R.string.update_downloading_description, versionName))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                        .setDestinationUri(Uri.fromFile(file))
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                        .setMimeType("application/vnd.android.package-archive")

                request.addRequestHeader("User-Agent", "Shizuku+/$versionName")

                try {
                    downloadId = downloadManager.enqueue(request)
                    context
                        .getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putLong(DOWNLOAD_ID_PREF, downloadId)
                        .apply()

                    Timber.tag(TAG).d("Download started via DownloadManager: $downloadUrl, ID: $downloadId")
                    monitorDownload(downloadId, file, versionName, downloadUrl)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "DownloadManager failed to enqueue; falling back to direct HTTP stream")
                    downloadDirectly(downloadUrl, file, versionName)
                }
            }
    }

    /**
     * Fallback direct HTTP download for OEM ROMs where DownloadManager is missing or restricted.
     */
    private suspend fun downloadDirectly(
        downloadUrl: String,
        file: File,
        versionName: String,
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = downloadUrl
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Shizuku+/$versionName")
                connection.connect()

                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308
                ) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = location
                        connection.disconnect()
                        redirects++
                        continue
                    }
                }
                break
            }

            val conn =
                connection ?: run {
                    _state.value = UpdateState.Error("Failed to open connection")
                    showDownloadErrorNotification()
                    return@withContext
                }

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Timber.tag(TAG).w("Direct HTTP download returned status $responseCode")
                _state.value = UpdateState.Error("Server returned code $responseCode", responseCode)
                showDownloadErrorNotification()
                return@withContext
            }

            val contentLength = conn.contentLength.toLong()
            var bytesReadTotal = 0L

            conn.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(8192)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (!isActive) {
                            file.delete()
                            return@withContext
                        }
                        output.write(buffer, 0, bytes)
                        bytesReadTotal += bytes

                        val percent = if (contentLength > 0) ((bytesReadTotal * 100) / contentLength).toInt() else 0
                        _state.value = UpdateState.Downloading(percent, bytesReadTotal, contentLength, versionName)
                        updateProgressNotification(percent, versionName)
                    }
                }
            }

            Timber.tag(TAG).d("Direct HTTP download completed: ${file.absolutePath}")
            onDownloadComplete(file, versionName, downloadUrl)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Direct HTTP download failed")
            file.delete()
            _state.value = UpdateState.Error(e.message ?: "Network error")
            showDownloadErrorNotification()
        } finally {
            connection?.disconnect()
        }
    }

    private fun monitorDownload(
        downloadId: Long,
        file: File,
        versionName: String,
        downloadUrl: String,
    ) {
        monitorJob?.cancel()
        monitorJob =
            scope.launch {
                while (isActive) {
                    try {
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor =
                            try {
                                withContext(Dispatchers.IO) { downloadManager?.query(query) }
                            } catch (e: IllegalArgumentException) {
                                Timber.tag(TAG).w(e, "DownloadManager.query rejected; retrying bare filter")
                                null
                            }

                        if (cursor != null && !cursor.moveToFirst()) {
                            cursor.close()
                        } else if (cursor != null) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val progressIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_RUNNING
                            val progress = if (progressIdx >= 0) cursor.getLong(progressIdx) else 0L
                            val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0L

                            when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    // DownloadManager may store the file under a different name
                                    // than requested; use where it actually landed.
                                    val localIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                    val localPath =
                                        if (localIdx >= 0) {
                                            cursor.getString(localIdx)?.let { runCatching { Uri.parse(it).path }.getOrNull() }
                                        } else {
                                            null
                                        }
                                    cursor.close()
                                    val actual = localPath?.let { File(it) }?.takeIf { it.exists() } ?: file
                                    Timber.tag(TAG).d("Download completed: ${actual.absolutePath}")
                                    onDownloadComplete(actual, versionName, downloadUrl)
                                    break
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                                    cursor.close()
                                    Timber.tag(TAG).w("Download failed (reason=$reason)")
                                    _state.value = UpdateState.Error("Download failed", reason)
                                    showDownloadErrorNotification(reason)
                                    break
                                }
                                DownloadManager.STATUS_RUNNING -> {
                                    val percent = if (total > 0) (progress * 100 / total).toInt() else 0
                                    _state.value = UpdateState.Downloading(percent, progress, total, versionName)
                                    updateProgressNotification(percent, versionName)
                                }
                                DownloadManager.STATUS_PAUSED -> {
                                    // Paused
                                }
                            }
                            cursor.close()
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error monitoring download")
                    }
                    delay(500)
                }
            }
    }

    private fun updateProgressNotification(
        progress: Int,
        versionName: String,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.update_downloading_title))
                .setContentText(context.getString(R.string.update_downloading_progress, versionName, progress))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, false)
                .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun onDownloadComplete(
        file: File,
        versionName: String,
        downloadUrl: String,
    ) {
        notificationManager.cancel(NOTIFICATION_ID)

        if (!isManualDownload && !ShizukuSettings.isAutoUpdateEnabled()) {
            withContext(Dispatchers.IO) { file.delete() }
            return
        }

        // Verify the SHA-256 published with the release and continue only with the app-private copy
        // that was hashed: the download dir is external storage, so the file there could be swapped
        // between a check and the install. The copy also sits on internal storage, which bypasses
        // Android 14+ FUSE SELinux restrictions. A 16 MB copy and hash: never on the main thread.
        val verified =
            withContext(Dispatchers.IO) {
                // Unique, fixed-shape name: nothing from the release reaches the path, and a second
                // verification cannot overwrite a copy that has already been verified.
                // createTempFile throws when the cache dir is full or unwritable.
                val staged =
                    runCatching {
                        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                        File.createTempFile(STAGED_PREFIX, ".apk", updatesDir)
                    }.getOrNull()
                val result = staged?.let { UpdateVerifier.verifyAndStage(file, downloadUrl, it) }
                file.delete()
                result?.setReadable(true, false)
                result
            }
        if (verified == null) {
            // Fail closed. A truncated DownloadManager result also lands here, so fetch it once
            // more over direct HTTP; the retry is verified the same way.
            if (!retriedDirect) {
                retriedDirect = true
                _state.value = UpdateState.Downloading(0, 0, 0, versionName)
                downloadDirectly(downloadUrl, file, versionName)
            } else {
                _state.value = UpdateState.Error(context.getString(R.string.update_verify_failed_message))
                showDownloadErrorNotification(messageRes = R.string.update_verify_failed_message)
            }
            return
        }
        val readyApk: File = verified

        // Never hand an unparseable file to the system installer ("There was a problem parsing
        // the package"): validate first, re-download once over direct HTTP, then fail clearly.
        val problem = withContext(Dispatchers.IO) { validateApk(readyApk) }
        if (problem != null) {
            Timber.tag(TAG).w("Downloaded update is invalid (%s): %s", problem, readyApk.absolutePath)
            withContext(Dispatchers.IO) { readyApk.delete() }
            if (!retriedDirect) {
                retriedDirect = true
                _state.value = UpdateState.Downloading(0, 0, 0, versionName)
                downloadDirectly(downloadUrl, file, versionName)
            } else {
                _state.value = UpdateState.Error(context.getString(R.string.update_download_corrupt, problem))
                showDownloadErrorNotification()
            }
            return
        }

        _state.value = UpdateState.Ready(readyApk, versionName)

        if (isManualDownload || ShizukuSettings.isAutoInstallEnabled()) {
            scope.launch {
                when (val result = performSmartInstall(readyApk)) {
                    is InstallResult.Success -> {
                        _state.value = UpdateState.Completed(context.getString(R.string.update_installed_success))
                    }
                    is InstallResult.NeedsSystemInstaller -> {
                        launchSystemInstaller(result.file)
                    }
                    is InstallResult.SignatureMismatchCanMigrate,
                    is InstallResult.SignatureMismatchManualRequired,
                    is InstallResult.Failed,
                    -> {
                        showInstallNotification(readyApk, versionName)
                    }
                }
            }
        } else {
            showInstallNotification(readyApk, versionName)
        }
    }

    /**
     * Returns null if [apk] is a complete APK of this app, else a short reason. Catches truncated
     * or HTML/JSON downloads and a wrong-flavor asset before any installer sees them.
     */
    private fun validateApk(apk: File): String? {
        if (!apk.exists()) return "file missing"
        if (apk.length() < 1024 * 1024) return "file too small (${apk.length()} bytes)"
        val magic = ByteArray(4)
        val read = runCatching { apk.inputStream().use { it.read(magic) } }.getOrDefault(-1)
        if (read != 4 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return "not an APK"
        val info =
            runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
                ?: return "package could not be parsed"
        if (info.packageName != context.packageName) return "wrong package (${info.packageName})"
        return null
    }

    /**
     * Inspects signatures and performs a privileged (root or Shizuku) or system installation.
     * A signature mismatch always needs the user to reinstall by hand: the updater never
     * uninstalls and reinstalls, since that would install an APK from any signer over this
     * package. A privileged install that fails or times out falls back to the system installer.
     */
    suspend fun performSmartInstall(file: File): InstallResult =
        withContext(Dispatchers.IO) {
            try {
                _state.value = UpdateState.Installing(file.name)
                val isRoot =
                    runCatching {
                        Shell.getShell().isRoot
                    }.getOrDefault(false)
                val hasShizuku = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val signaturesMatch = checkSignaturesMatch(file)

                if (!signaturesMatch) {
                    Timber.tag(TAG).w("Update signature mismatch detected.")
                    return@withContext InstallResult.SignatureMismatchManualRequired(file)
                }

                // 60 s covers piping a multi-MB APK over Shizuku stdin + pm install processing.
                val installed =
                    withTimeoutOrNull(60_000) {
                        when {
                            isRoot -> installViaRoot(file)
                            // Shell UID 2000 cannot read app storage on Android 11+; the bytes are
                            // piped to pm install via stdin instead.
                            hasShizuku -> installViaShizukuStdin(file)
                            else -> null
                        }
                    }
                when (installed) {
                    true -> InstallResult.Success
                    // No privileged route, or it failed or timed out: the system installer prompt.
                    else -> InstallResult.NeedsSystemInstaller(file)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "performSmartInstall failed unexpectedly")
                InstallResult.Failed(e.message ?: "Unknown installation error")
            }
        }

    /**
     * The verified copy is in app-private storage, which system_server (the process that opens a
     * path given to pm) cannot read. Copy it as root to /data/local/tmp first. The source path is
     * cacheDir plus a generated name, so nothing from the release is interpolated into the command.
     * No downgrade flag, and deliberately no uninstall-and-reinstall fallback.
     */
    private fun installViaRoot(file: File): Boolean {
        val tmp = "/data/local/tmp/update.apk"
        val result =
            Shell
                .cmd("cp '${file.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp")
                .exec()
                .also { Shell.cmd("rm -f $tmp").exec() }
        return if (result.isSuccess) {
            Timber.tag(TAG).i("Silent install via root succeeded")
            true
        } else {
            Timber.tag(TAG).w("Root install failed: ${result.out}")
            false
        }
    }

    /**
     * Launches the system PackageInstaller using an internal cacheDir FileProvider URI
     * that system_server and com.google.android.packageinstaller can safely access without FUSE denials.
     */
    fun launchSystemInstaller(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            // The cached APK was cleared (cache trim, reinstall); the installer would only
            // report a parse error for a missing file.
            _state.value = UpdateState.Error(context.getString(R.string.update_download_corrupt, "file missing"))
            return false
        }
        return try {
            val apkUri = shareableApkUri(file)
            val installIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "launchSystemInstaller failed")
            false
        }
    }

    /**
     * Compares the signing certificate of the downloaded APK with the installed app.
     */
    fun checkSignaturesMatch(apkFile: File): Boolean =
        try {
            val pm = context.packageManager
            val installedSignatures =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val info = pi.signingInfo
                    if (info != null) {
                        if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
                    } else {
                        emptyArray()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    pi.signatures ?: emptyArray()
                }

            val apkSignatures =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    val info = pi?.signingInfo
                    if (info != null) {
                        if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
                    } else {
                        emptyArray()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    pi?.signatures ?: emptyArray()
                }

            if (installedSignatures.isEmpty() || apkSignatures.isEmpty()) {
                true
            } else {
                installedSignatures.any { inst -> apkSignatures.any { apk -> inst == apk } }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Signature comparison failed; assuming match")
            true
        }

    /**
     * Show notification to install the update.
     * Routes signature mismatches to HomeActivity's manual-reinstall dialog to avoid cryptic OS errors.
     */
    fun showInstallNotification(
        file: File,
        versionName: String,
    ) {
        try {
            val signaturesMatch = checkSignaturesMatch(file)

            // A mismatch always goes to the manual-reinstall explanation, never to the migrate
            // (uninstall-and-reinstall) dialog.
            val installIntent =
                if (!signaturesMatch) {
                    Intent(context, af.shizuku.manager.home.HomeActivity::class.java).apply {
                        action = ACTION_SIGNATURE_MISMATCH
                        putExtra(EXTRA_UPDATE_FILE_PATH, file.absolutePath)
                        putExtra(EXTRA_UPDATE_VERSION_NAME, versionName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                } else {
                    val apkUri = shareableApkUri(file)
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }

            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    installIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(context.getString(R.string.update_ready_title))
                    .setContentText(context.getString(R.string.update_ready_description, versionName))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pendingIntent)
                    .addAction(
                        R.drawable.ic_notification_icon,
                        context.getString(R.string.update_install_now),
                        pendingIntent,
                    ).build()

            notificationManager.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to build install notification")
            showDownloadErrorNotification()
        }
    }

    /**
     * Resolve a content:// URI backed by an internal cacheDir copy that FileProvider can safely serve
     * without hitting Android 14+ FUSE SELinux denials.
     */
    fun shareableApkUri(file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val cached = File(updatesDir, file.name)
        if (!cached.exists() || cached.length() != file.length()) {
            try {
                file.copyTo(cached, overwrite = true)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed copying to updates cache, using direct file")
            }
        }
        cached.setReadable(true, false)
        return try {
            FileProvider.getUriForFile(context, authority, cached)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed FileProvider URI for cache; falling back to original file")
            FileProvider.getUriForFile(context, authority, file)
        }
    }

    /**
     * Show error notification. [reason] is a DownloadManager.ERROR_* code (from COLUMN_REASON)
     * when known — a few documented, OEM-independent codes get distinct, self-diagnosing text
     * (#414) instead of the generic message. [messageRes] overrides both.
     */
    private fun showDownloadErrorNotification(
        reason: Int? = null,
        @StringRes messageRes: Int? = null,
    ) {
        val text =
            messageRes ?: when (reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.update_download_failed_storage
                DownloadManager.ERROR_CANNOT_RESUME -> R.string.update_download_failed_cannot_resume
                DownloadManager.ERROR_HTTP_DATA_ERROR,
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
                -> R.string.update_download_failed_http
                else -> R.string.update_download_failed_message
            }
        val notification =
            NotificationCompat
                .Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.update_download_failed_title))
                .setContentText(context.getString(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()

        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    /**
     * Reads the APK in the app process and streams it to `pm install` via Shizuku stdin,
     * sidestepping Android 11+ scoped-storage restrictions on shell UID 2000.
     */
    private fun installViaShizukuStdin(file: File): Boolean {
        return try {
            val apkBytes = file.readBytes()
            val script =
                "cat > /data/local/tmp/update.apk && chmod 644 /data/local/tmp/update.apk" +
                    " && pm install -r /data/local/tmp/update.apk 2>&1; echo EXIT:\$?; rm -f /data/local/tmp/update.apk"
            val process =
                Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)
                    ?: run {
                        Timber.tag(TAG).w("Shizuku.newProcess returned null")
                        return false
                    }
            process.outputStream.use { it.write(apkBytes) }
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val exitCode =
                Regex("EXIT:(\\d+)")
                    .find(output)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            val success = exitCode == 0
            if (success) {
                Timber.tag(TAG).i("Shizuku stdin install succeeded")
            } else {
                Timber.tag(TAG).w("Shizuku stdin install failed (exit=$exitCode): $output")
            }
            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "installViaShizukuStdin failed")
            false
        }
    }

    fun canRequestPackageInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val packageManager = context.packageManager
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Stops the current download. Only that work is cancelled: this manager is shared, and
     * cancelling its scope's root job made every later download silently do nothing.
     */
    fun cancel() {
        monitorJob?.cancel()
        downloadJob?.cancel()
        if (downloadId != -1L) {
            runCatching { downloadManager?.remove(downloadId) }
            downloadId = -1
        }
    }

    /**
     * Deletes downloaded update APKs that are no longer needed: ones at or below the installed
     * version, and unreadable ones once they're old enough not to be a download in progress. A
     * newer one is kept, since an install notification may still point at it. Call off the main
     * thread.
     */
    fun cleanup() {
        try {
            val installed =
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                    context.packageManager.getPackageInfo(context.packageName, 0),
                )
            val dirs = listOfNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), File(context.cacheDir, "updates"))
            for (dir in dirs) {
                dir.listFiles { file -> file.name.endsWith(".apk") }?.forEach { file ->
                    if (isStaleUpdate(file, installed)) {
                        file.delete()
                        Timber.tag(TAG).d("Cleaned up old APK: ${file.name}")
                    }
                }
            }
            // Older builds staged verified copies directly in the cache dir.
            context.cacheDir?.listFiles { file -> file.name.startsWith(STAGED_PREFIX) && file.name.endsWith(".apk") }?.forEach { file ->
                file.delete()
                Timber.tag(TAG).d("Cleaned up staged APK: ${file.name}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error cleaning up")
        }
    }

    private fun isStaleUpdate(
        file: File,
        installed: Long,
    ): Boolean {
        val version =
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.let {
                androidx.core.content.pm.PackageInfoCompat
                    .getLongVersionCode(it)
            }
        if (version != null) return version <= installed
        // Unreadable: a partial download is still being written, so only remove old ones.
        return System.currentTimeMillis() - file.lastModified() > PARTIAL_DOWNLOAD_GRACE_MS
    }
}
