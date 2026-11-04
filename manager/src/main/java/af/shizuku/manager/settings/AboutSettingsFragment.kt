package af.shizuku.manager.settings

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
<<<<<<< HEAD
import af.shizuku.manager.backup.BackupKeyUnavailableException
import af.shizuku.manager.backup.BackupRestoreManager
import af.shizuku.manager.backup.CryptoUtils
import af.shizuku.manager.fleet.FleetApplyReport
=======
import af.shizuku.manager.database.AppContextManager
>>>>>>> upstream/master
import af.shizuku.manager.home.ChangelogDialogFragment
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.update.UpdateDialogHelper
import af.shizuku.manager.update.UpdateManager
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.HapticUtils
import android.content.Intent
import android.net.Uri
import android.os.Bundle
<<<<<<< HEAD
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.text.format.DateUtils
=======
>>>>>>> upstream/master
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

class AboutSettingsFragment : BaseSettingsFragment() {
    companion object {
        private const val TAG = "AboutSettingsFragment"
        private const val KEY_CHECK_FOR_UPDATE = "check_for_update"
<<<<<<< HEAD
        private const val RELEASES_URL = "https://github.com/frdminc/ShizukuTendCF/releases"
=======
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val RELEASES_URL = "https://github.com/thejaustin/ShizukuPlus/releases"
>>>>>>> upstream/master
    }

    private val updateManager: UpdateManager by inject()
    private var versionClickCount = 0
    private var isCheckingForUpdate = false
    private var checkingDatabase = false

<<<<<<< HEAD
    private fun backupErrorMessage(
        prefix: String,
        e: Exception,
    ): String =
        when (e) {
            is KeyPermanentlyInvalidatedException ->
                "$prefix: your device's screen lock or biometrics changed since this backup's " +
                    "encryption key was created, which permanently invalidates it by design. " +
                    if (prefix == "Restore failed") {
                        "This backup can no longer be decrypted."
                    } else {
                        "Please try again to generate a new key."
                    }
            is BackupKeyUnavailableException -> "$prefix: ${e.message}"
            is AEADBadTagException ->
                "$prefix: this backup could not be decrypted. It was most likely created by a different " +
                    "installation of ShizukuTendCF — backups are encrypted per-install and can't be restored " +
                    "after reinstalling or clearing the app's data."
            else -> "$prefix: ${e.message ?: e.javaClass.simpleName}"
        }

    private val createPlainBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            try {
                val payload = BackupRestoreManager.createPlainBackupPayload(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                }
                Toast.makeText(ctx, R.string.backup_plain_exported, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, ctx.getString(R.string.backup_failed_generic, e.message), Toast.LENGTH_LONG).show()
            }
        }

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            val lock = BiometricLock(requireActivity())
            val useAuth = lock.canAuthenticate(ctx)

            if (!useAuth) {
                try {
                    val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = false)
                    val payload = BackupRestoreManager.createBackupPayload(ctx, cipher)
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                    }
                    Toast.makeText(ctx, R.string.backup_exported_success, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, backupErrorMessage("Backup failed", e), Toast.LENGTH_LONG).show()
                }
                return@registerForActivityResult
            }

            try {
                val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = true)
                lock.authenticate(onSuccess = { crypto ->
                    try {
                        val payload = BackupRestoreManager.createBackupPayload(ctx, crypto?.cipher ?: cipher)
                        ctx.contentResolver.openOutputStream(uri)?.use { os ->
                            OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                        }
                        Toast.makeText(ctx, R.string.backup_exported_success, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, backupErrorMessage("Backup failed", e), Toast.LENGTH_LONG).show()
                    }
                }, onError = { errCode ->
                    Toast.makeText(ctx, ctx.getString(R.string.backup_auth_failed, errCode), Toast.LENGTH_SHORT).show()
                }, crypto = BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                Toast.makeText(ctx, ctx.getString(R.string.backup_failed_generic, e.message), Toast.LENGTH_LONG).show()
            }
        }

    private val restoreBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            val lock = BiometricLock(requireActivity())
            val useAuth = lock.canAuthenticate(ctx)

            try {
                val payload =
                    ctx.contentResolver.openInputStream(uri)?.use { `is` ->
                        InputStreamReader(`is`, Charsets.UTF_8).readText()
                    } ?: return@registerForActivityResult

                if (!BackupRestoreManager.isEncrypted(payload)) {
                    try {
                        BackupRestoreManager.restoreFromPlainPayload(ctx, payload)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, ctx.getString(R.string.restore_failed_generic, e.message), Toast.LENGTH_LONG).show()
                    }
                    return@registerForActivityResult
                }

                val iv = BackupRestoreManager.extractIv(payload)

                if (!useAuth) {
                    try {
                        val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = false)
                        BackupRestoreManager.restoreFromPayload(ctx, payload, cipher)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, backupErrorMessage("Restore failed", e), Toast.LENGTH_LONG).show()
                    }
                    return@registerForActivityResult
                }

                val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = true)
                lock.authenticate(onSuccess = { crypto ->
                    try {
                        BackupRestoreManager.restoreFromPayload(ctx, payload, crypto?.cipher ?: cipher)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, backupErrorMessage("Restore failed", e), Toast.LENGTH_LONG).show()
                    }
                }, onError = { errCode ->
                    Toast.makeText(ctx, ctx.getString(R.string.backup_auth_failed, errCode), Toast.LENGTH_SHORT).show()
                }, crypto = BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                Toast.makeText(ctx, ctx.getString(R.string.restore_failed_generic, e.message), Toast.LENGTH_LONG).show()
=======
    private fun updateDatabaseSummary(pref: Preference) {
        val last = ShizukuSettings.getLastDbUpdate()
        val count = AppContextManager.appCount()
        pref.summary =
            if (last > 0) {
                val checked =
                    android.text.format.DateUtils
                        .getRelativeTimeSpanString(last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
                getString(R.string.settings_update_app_database_status, count, checked)
            } else {
                getString(R.string.settings_update_app_database_status_never, count)
>>>>>>> upstream/master
            }
    }

    override fun getTitle(): CharSequence? = getString(R.string.settings_about)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val context = requireContext()

        val navDevOptions = findPreference<Preference>("nav_developer_options")
        navDevOptions?.isVisible = ShizukuSettings.isDevOptionsUnlocked()

        findPreference<Preference>("version")?.apply {
            setCustomSummary(BuildConfig.VERSION_NAME)
            setOnPreferenceClickListener {
                if (ShizukuSettings.isDevOptionsUnlocked()) {
                    notifyUser(R.string.settings_developer_options_revealed)
                    return@setOnPreferenceClickListener true
                }

                versionClickCount++
                if (versionClickCount >= 7) {
                    ShizukuSettings.setDevOptionsUnlocked(true)
                    SettingsSearchEngine.reset()
                    navDevOptions?.isVisible = true
                    notifyUser(R.string.settings_developer_options_revealed)
                    versionClickCount = 0
                } else if (versionClickCount > 2) {
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.settings_developer_options_click_more, 7 - versionClickCount),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                true
            }
        }

<<<<<<< HEAD
        setupCheckForUpdatePreference()
        setupAutoUpdatePreference()
        setupAutoInstallPreference()
        setupChannelPreference()
        updateLastCheckSummary()
        updateFleetProfileSummary()
=======
        setupUpdateCardPreference()
>>>>>>> upstream/master

        findPreference<Preference>("changelog")?.setOnPreferenceClickListener {
            val activity = activity as? androidx.fragment.app.FragmentActivity ?: return@setOnPreferenceClickListener true
            activity.lifecycleScope.launch {
<<<<<<< HEAD
                val currentTag = BuildConfig.VERSION_NAME.substringAfterLast(' ').trim()
=======
                val currentTag = BuildConfig.VERSION_NAME.removePrefix("Shizuku+ ").trim()
                val currentCode =
                    try {
                        activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
                    } catch (_: Exception) {
                        0
                    }
                val lastSeenCode =
                    af.shizuku.manager.ShizukuSettings
                        .getLastSeenChangelogVersion()
                val since = if (lastSeenCode > 0 && lastSeenCode < currentCode) lastSeenCode else 0
>>>>>>> upstream/master
                val releases =
                    try {
                        UpdateChecker.fetchReleasesSince(sinceVersionCode = since, maxReleases = 25)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to fetch releases for in-app changelog")
                        emptyList()
                    }
                if (isAdded && !isDetached) {
                    ChangelogDialogFragment
                        .newInstance(releases, currentTag)
                        .show(activity.supportFragmentManager, ChangelogDialogFragment.TAG)
                }
            }
            true
        }

<<<<<<< HEAD
        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/frdminc/ShizukuTendCF")
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(requireContext(), "https://github.com/frdminc/ShizukuTendCF/blob/master/OPEN_SOURCE_LICENSES.md")
=======
        findPreference<Preference>("feature_tour")?.setOnPreferenceClickListener {
            val intent =
                Intent(requireContext(), af.shizuku.manager.onboarding.OnboardingActivity::class.java).apply {
                    putExtra(af.shizuku.manager.onboarding.OnboardingActivity.EXTRA_NEW_FEATURES_ONLY, true)
                }
            startActivity(intent)
>>>>>>> upstream/master
            true
        }

        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/thejaustin/ShizukuPlus")
            true
        }

        findPreference<Preference>(ShizukuSettings.Keys.KEY_HELP)?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.help_url))
            true
        }

        findPreference<Preference>(ShizukuSettings.Keys.KEY_REPORT_BUG)?.setOnPreferenceClickListener {
            BugReportDialog().show(parentFragmentManager, "BugReportDialog")
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(
                requireContext(),
                "https://github.com/thejaustin/ShizukuPlus/blob/main/OPEN_SOURCE_LICENSES.md",
            )
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateLastCheckSummary()
<<<<<<< HEAD
        updateFleetProfileSummary()
        applyBackupCategoryVisibility()
    }

    private fun updateFleetProfileSummary() {
        val pref = findPreference<Preference>("fleet_profile_status") ?: return
        val last = FleetApplyReport.read(requireContext())
        setChildAvailable(pref, last != null)
        if (last == null) return
        val ago =
            DateUtils.getRelativeTimeSpanString(
                last.ts * 1000,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        pref.summary =
            if (last.success) {
                getString(R.string.settings_fleet_profile_applied, ago)
            } else {
                getString(R.string.settings_fleet_profile_failed, ago, last.message)
            }
    }

    private fun applyBackupCategoryVisibility() {
        val hide = ShizukuSettings.isHideBackupSettingsEnabled()
        findPreference<af.shizuku.manager.settings.CollapsiblePreferenceCategory>("category_backup")
            ?.isVisible = !hide
    }

    private fun onRestoreSuccess() {
        if (!isAdded) return
        Toast.makeText(requireContext(), R.string.backup_restored_success, Toast.LENGTH_SHORT).show()
        Snackbar
            .make(
                requireView(),
                R.string.backup_restored_restart_hint,
                Snackbar.LENGTH_LONG,
            ).setAction(R.string.backup_restored_restart_now) {
                requireActivity().recreate()
            }.show()
=======
>>>>>>> upstream/master
    }

    private fun setupUpdateCardPreference() {
        updateLastCheckSummary()

        findPreference<Preference>(KEY_CHECK_FOR_UPDATE)?.setOnPreferenceClickListener {
            if (!isCheckingForUpdate) checkForUpdate()
            true
        }

        findPreference<SegmentedControlPreference>(KEY_UPDATE_CHANNEL)?.let { channelPref ->
            channelPref.setOnPreferenceChangeListener { _, newValue ->
                if (newValue != "dev") return@setOnPreferenceChangeListener true
                // Dev builds can be unstable: only switch once the user confirms.
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.update_channel_dev_warning_title)
                    .setMessage(R.string.update_channel_dev_warning_message)
                    .setPositiveButton(R.string.update_channel_dev) { _, _ ->
                        channelPref.value = "dev"
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            }
        }

        findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_AUTO_UPDATE_ENABLED)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == false) {
                    findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_AUTO_INSTALL_ENABLED)?.isChecked = false
                }
                true
            }

        findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_AUTO_INSTALL_ENABLED)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !updateManager.canRequestPackageInstalls()) {
                    showPermissionRequiredDialog()
                    false
                } else {
                    true
                }
            }

        findPreference<Preference>("update_app_database")?.let { pref ->
            updateDatabaseSummary(pref)
            pref.setOnPreferenceClickListener {
                if (checkingDatabase) return@setOnPreferenceClickListener true
                view?.let { HapticUtils.segmentTick(it) }
                checkingDatabase = true
                pref.summary = getString(R.string.settings_update_app_database_checking)
                lifecycleScope.launch {
                    val result =
                        af.shizuku.manager.worker.RemoteDbSyncWorker
                            .sync()
                    checkingDatabase = false
                    if (!isAdded) return@launch
                    updateDatabaseSummary(pref)
                    when (result) {
                        is af.shizuku.manager.worker.RemoteDbSyncWorker.Companion.SyncResult.Updated -> {
                            view?.let { HapticUtils.success(it) }
                            notifyUser(resources.getQuantityString(R.plurals.settings_update_app_database_updated, result.apps, result.apps))
                        }
                        af.shizuku.manager.worker.RemoteDbSyncWorker.Companion.SyncResult.UpToDate -> {
                            view?.let { HapticUtils.success(it) }
                            notifyUser(R.string.settings_update_app_database_up_to_date)
                        }
                        is af.shizuku.manager.worker.RemoteDbSyncWorker.Companion.SyncResult.Failed ->
                            notifyUser(R.string.settings_update_app_database_error, long = true)
                    }
                }
                true
            }
        }
    }

    private fun updateLastCheckSummary() {
        findPreference<Preference>("update_app_database")?.let { updateDatabaseSummary(it) }
        val pref = findPreference<Preference>(KEY_CHECK_FOR_UPDATE) ?: return
        val lastCheck = ShizukuSettings.getLastUpdateCheckTime()
        // Local time: formatting it as UTC and parsing it back could land on the wrong day.
        val date =
            java.text.DateFormat
                .getDateInstance(java.text.DateFormat.MEDIUM)
                .format(java.util.Date(lastCheck))
        val status =
            when {
                ShizukuSettings.wasLastUpdateCheckFailed() && lastCheck > 0 -> {
                    "$date · ${getString(R.string.update_last_check_failed)}"
                }
                ShizukuSettings.wasLastUpdateCheckFailed() ->
                    getString(R.string.update_last_check_failed)
                lastCheck > 0 -> {
                    "${getString(R.string.settings_last_check_title)}: $date"
                }
                else ->
                    getString(R.string.settings_check_for_update_summary)
            }
        pref.setCustomSummary(status)
    }

    private fun checkForUpdate() {
        isCheckingForUpdate = true
        findPreference<Preference>(KEY_CHECK_FOR_UPDATE)?.setCustomSummary(getString(R.string.update_checking))
        val channel = ShizukuSettings.getUpdateChannel()
        lifecycleScope.launch {
            try {
                when (val result = UpdateChecker.checkForUpdate(channel)) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            isCheckingForUpdate = false
                            updateLastCheckSummary()
                            showUpdateAvailableDialog(result.info)
                        }
                    }
                    is UpdateChecker.CheckResult.UpToDate -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            isCheckingForUpdate = false
                            updateLastCheckSummary()
                            showUpToDateDialog()
                        }
                    }
                    is UpdateChecker.CheckResult.NetworkError -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckFailed(true)
                            isCheckingForUpdate = false
                            updateLastCheckSummary()
                            showErrorDialog()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Unexpected error checking for update")
                if (isAdded) {
                    ShizukuSettings.setLastUpdateCheckFailed(true)
                    isCheckingForUpdate = false
                    updateLastCheckSummary()
                    showErrorDialog()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(info: UpdateChecker.UpdateInfo) {
        val context = context ?: return
        val devBadge = if (info.isPrerelease) " ⚠ Dev" else ""
        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.update_available_title) + devBadge)
                .setNegativeButton(R.string.update_later, null)
                .setNeutralButton(R.string.update_release_notes) { _, _ ->
                    val activity = activity as? androidx.fragment.app.FragmentActivity ?: return@setNeutralButton
                    activity.lifecycleScope.launch {
                        val currentCode =
                            try {
                                activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
                            } catch (_: Exception) {
                                0
                            }
                        val releases =
                            try {
                                UpdateChecker.fetchReleasesSince(sinceVersionCode = currentCode, maxReleases = 25)
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to fetch releases for in-app changelog")
                                emptyList()
                            }
                        if (isAdded && !isDetached) {
                            ChangelogDialogFragment
                                .newInstance(releases, info.versionName)
                                .show(activity.supportFragmentManager, ChangelogDialogFragment.TAG)
                        }
                    }
                }

        if (info.requiresManualDownload) {
            builder
                .setMessage(getString(R.string.update_available_manual_message, info.versionName))
                .setPositiveButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
        } else {
            builder
                .setMessage(getString(R.string.update_available_message, info.versionName))
                .setPositiveButton(R.string.update_download) { _, _ ->
                    val activity = activity as? androidx.fragment.app.FragmentActivity ?: return@setPositiveButton
                    UpdateDialogHelper.showDownloadProgressDialog(activity, updateManager, info)
                }
        }

        builder.show()
    }

    private fun showUpToDateDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_up_to_date_title)
            .setMessage(getString(R.string.update_up_to_date_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showErrorDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_error_title)
            .setMessage(R.string.update_error_message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
            .show()
    }

    private fun showPermissionRequiredDialog() {
        val context = context ?: return
        try {
            val intent =
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
            startActivity(intent)
        } catch (_: Exception) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.update_permission_required_title)
                .setMessage(R.string.update_permission_required_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun openReleasesPage() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Timber.w(e, "No activity found to handle releases URL")
        }
    }
}
