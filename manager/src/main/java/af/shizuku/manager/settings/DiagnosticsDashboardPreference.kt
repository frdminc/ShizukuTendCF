package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.automation.AutomationService
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class DiagnosticsDashboardPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        init {
            layoutResource = R.layout.layout_diagnostics_dashboard
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)

            val container = holder.itemView as? LinearLayout ?: return
            val listContainer = holder.findViewById(R.id.diagnostics_list) as? LinearLayout ?: return
            val btnDisable = holder.findViewById(R.id.btn_disable_diagnostics)

            val sp = preferenceManager.sharedPreferences
            val isDisabledGlobal = sp?.getBoolean("diagnostics_enabled", true) == false

            if (isDisabledGlobal) {
                container.visibility = View.GONE
                return
            }

            val dismissed = sp?.getStringSet("diagnostics_dismissed", emptySet()) ?: emptySet()
            listContainer.removeAllViews()

            val activeWarnings = mutableListOf<WarningItem>()

            // Diagnostic 1: Dhizuku Mode enabled but Device Owner not active
            if (ShizukuSettings.isDhizukuModeEnabled() && !isDeviceOwnerActive(context)) {
                activeWarnings.add(
                    WarningItem(
                        id = "dhizuku_not_owner",
                        message = context.getString(R.string.diagnostics_warning_dhizuku_not_owner),
                    ),
                )
            }

            // Diagnostic 2: Shadow Binder enabled but no hidden packages
            if (ShizukuSettings.isShadowBinderEnabled() && ShizukuSettings.getShadowBinderHiddenPackages().isNullOrBlank()) {
                activeWarnings.add(
                    WarningItem(
                        id = "shadow_binder_no_apps",
                        message = context.getString(R.string.diagnostics_warning_shadow_binder_no_apps),
                    ),
                )
            }

            // Diagnostic 3: Battery Optimization scan
            if (!isIgnoringBatteryOptimizations(context)) {
                activeWarnings.add(
                    WarningItem(
                        id = "battery_optimization",
                        message = context.getString(R.string.diagnostics_warning_battery_optimization),
                    ),
                )
            }

            // Diagnostic 4: Automation rules configured but service not running
            if (ShizukuSettings.hasAnyAutomationRulesConfigured() && !isAutomationServiceRunning(context)) {
                activeWarnings.add(
                    WarningItem(
                        id = "automation_service_stopped",
                        message = context.getString(R.string.diagnostics_warning_automation_stopped),
                    ),
                )
            }

            // Diagnostic 5: Watchdog disabled
            if (!ShizukuSettings.getWatchdog()) {
                activeWarnings.add(
                    WarningItem(
                        id = "watchdog_disabled",
                        message = context.getString(R.string.diagnostics_warning_watchdog_disabled),
                    ),
                )
            }

            // Diagnostic 6: Auto-reconnect disabled
            if (!ShizukuSettings.isAutoReconnectMdnsEnabled()) {
                activeWarnings.add(
                    WarningItem(
                        id = "auto_reconnect_disabled",
                        message = context.getString(R.string.diagnostics_warning_auto_reconnect_disabled),
                    ),
                )
            }

            // Diagnostic 7: Start on Boot disabled
            if (!ShizukuSettings.getStartOnBoot(context)) {
                activeWarnings.add(
                    WarningItem(
                        id = "start_on_boot_disabled",
                        message = context.getString(R.string.diagnostics_warning_start_on_boot_disabled),
                    ),
                )
            }

            // Diagnostic 8: TCP Mode disabled while using Wireless ADB
            if (ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ROOT &&
                (
                    af.shizuku.manager.utils.EnvironmentUtils
                        .isTlsSupported() ||
                        af.shizuku.manager.utils.EnvironmentUtils
                            .isTelevision()
                ) &&
                !ShizukuSettings.getTcpMode()
            ) {
                activeWarnings.add(
                    WarningItem(
                        id = "tcp_mode_disabled",
                        message = context.getString(R.string.diagnostics_warning_tcp_mode_disabled),
                    ),
                )
            }

            // Diagnostic 9: Device Hardening disabled
            if (!ShizukuSettings.isDeviceHardeningEnabled()) {
                activeWarnings.add(
                    WarningItem(
                        id = "device_hardening_disabled",
                        message = context.getString(R.string.diagnostics_warning_device_hardening_disabled),
                    ),
                )
            }

            val visibleWarnings = activeWarnings.filter { it.id !in dismissed }

            if (visibleWarnings.isEmpty()) {
                container.visibility = View.GONE
                return
            }

            container.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(context)
            for (warning in visibleWarnings) {
                val itemView = inflater.inflate(R.layout.layout_diagnostic_item, listContainer, false)
                itemView.findViewById<TextView>(R.id.diagnostic_text).text = warning.message

                // Set action handling on tapping the warning card itself
                itemView.setOnClickListener {
                    when (warning.id) {
                        "battery_optimization" -> {
                            try {
                                // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS shows a direct
                                // system Allow/Deny dialog for THIS app specifically - no
                                // searching a 300+ app alphabetical list to find it (that's
                                // what ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS did here
                                // before, despite the warning saying "Tap to exempt").
                                af.shizuku.manager.utils.SettingsHelper
                                    .requestIgnoreBatteryOptimizations(context)
                            } catch (_: Exception) {
                                try {
                                    val intent = Intent(Settings.ACTION_SETTINGS)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, R.string.diagnostics_battery_settings_open_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        "shadow_binder_no_apps" -> {
                            // The app list lives on the Root integration screen; open it there.
                            context.startActivity(
                                Intent(context, SettingsActivity::class.java)
                                    .putExtra("highlight_key", "shadow_binder_hidden_packages")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        "dhizuku_not_owner" -> {
                            // applicationId differs from namespace (af.shizuku.manager), so the full class name
                            // must be explicit rather than dot shorthand notation (issue #560).
                            val cmd =
                                "adb shell dpm set-device-owner " +
                                    "${context.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"
                            MaterialAlertDialogBuilder(context)
                                .setTitle(R.string.diagnostics_device_owner_setup_title)
                                .setMessage(context.getString(R.string.diagnostics_device_owner_setup_message, cmd))
                                .setPositiveButton(R.string.diagnostics_copy_command) { _, _ ->
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("dpm command", cmd))
                                    Toast.makeText(context, R.string.diagnostics_command_copied, Toast.LENGTH_SHORT).show()
                                }.setNegativeButton(R.string.diagnostics_dismiss, null)
                                .show()
                        }
                        "automation_service_stopped" -> {
                            try {
                                context.startService(Intent(context, AutomationService::class.java))
                                notifyChanged()
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.diagnostics_automation_start_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                        "watchdog_disabled" -> {
                            ShizukuSettings.setWatchdog(context, true)
                            Toast.makeText(context, R.string.diagnostics_watchdog_enabled_toast, Toast.LENGTH_SHORT).show()
                            notifyChanged()
                        }
                        "auto_reconnect_disabled" -> {
                            ShizukuSettings.setAutoReconnectMdnsEnabled(true)
                            Toast.makeText(context, R.string.diagnostics_auto_reconnect_enabled_toast, Toast.LENGTH_SHORT).show()
                            notifyChanged()
                        }
                        "start_on_boot_disabled" -> {
                            ShizukuSettings.setStartOnBoot(context, true)
                            Toast.makeText(context, R.string.diagnostics_start_on_boot_enabled_toast, Toast.LENGTH_SHORT).show()
                            notifyChanged()
                        }
                        "tcp_mode_disabled" -> {
                            ShizukuSettings.setTcpMode(true)
                            ShizukuSettings.setForceStartWadbEnabled(true)
                            Toast.makeText(context, R.string.diagnostics_tcp_mode_enabled_toast, Toast.LENGTH_SHORT).show()
                            notifyChanged()
                        }
                        "device_hardening_disabled" -> {
                            // Same as turning it on in Behavior: the setting alone doesn't harden anything.
                            ShizukuSettings.setDeviceHardeningEnabled(true)
                            notifyChanged()
                            val appContext = context.applicationContext
                            kotlinx.coroutines.MainScope().launch {
                                val applied =
                                    af.shizuku.manager.utils.DeviceOptimizer
                                        .applyFixes(appContext)
                                if (applied) Toast.makeText(appContext, R.string.device_hardening_applied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                itemView.findViewById<Button>(R.id.btn_dismiss_warning).setOnClickListener {
                    val newDismissed = dismissed.toMutableSet().apply { add(warning.id) }
                    sp?.edit()?.putStringSet("diagnostics_dismissed", newDismissed)?.apply()
                    notifyChanged()
                }
                listContainer.addView(itemView)
            }

            btnDisable?.setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.diagnostics_disable_title)
                    .setMessage(R.string.diagnostics_disable_message)
                    .setPositiveButton(R.string.diagnostics_disable_confirm) { _, _ ->
                        sp?.edit()?.putBoolean("diagnostics_enabled", false)?.apply()
                        notifyChanged()
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        private fun isDeviceOwnerActive(ctx: Context): Boolean =
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isDeviceOwnerApp(ctx.packageName) || dpm.isProfileOwnerApp(ctx.packageName)
            } catch (_: Exception) {
                false
            }

        private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } catch (_: Exception) {
                true // default to true to not raise warnings if system query fails
            }

        // getRunningServices() is deprecated in API 26+ for third-party apps but remains
        // functional for querying the caller's OWN services — exactly what we need here.
        @Suppress("DEPRECATION")
        private fun isAutomationServiceRunning(ctx: Context): Boolean =
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.getRunningServices(100)?.any {
                    it.service.packageName == ctx.packageName &&
                        it.service.className.endsWith("AutomationService")
                } == true
            } catch (e: Exception) {
                true // default to "running" to suppress false warnings
            }

        private data class WarningItem(
            val id: String,
            val message: String,
        )
    }
