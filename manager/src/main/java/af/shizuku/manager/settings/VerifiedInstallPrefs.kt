package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Verified installation (local signatures, F-Droid index, VirusTotal, Pithus) and Package governor.
 * Lives under Startup & behavior > Security; kept here so the rows' wiring moves with them.
 */
object VerifiedInstallPrefs {
    const val VIRUSTOTAL_KEY_URL = "https://www.virustotal.com/gui/my-apikey"
    const val PITHUS_KEY_URL = "https://beta.pithus.org"

    fun attach(fragment: BaseSettingsFragment) {
        // Package governor is a Plus feature: sync once the switch has saved its new value.
        fragment
            .findPreference<TwoStatePreference>("package_governor_plus_enabled")
            ?.setOnPreferenceChangeListener { _, _ ->
                fragment.view?.post { ShizukuSettings.syncAllPlusFeaturesToServer() }
                true
            }

        // Legacy individual rows wiring preserved if present
        fragment.findPreference<TwoStatePreference>("verify_apk_virustotal")?.setOnPreferenceChangeListener { pref, newValue ->
            val enabling = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            if (enabling && ShizukuSettings.getVirusTotalApiKey().isBlank()) {
                promptVirusTotalKey(
                    fragment.requireContext(),
                    onSaved = { (pref as TwoStatePreference).isChecked = true },
                    onCancel = { (pref as TwoStatePreference).isChecked = false },
                )
                false
            } else {
                true
            }
        }

        updateApiKeyManageSummary(fragment, "virustotal_api_key_manage", ShizukuSettings.getVirusTotalApiKey())
        fragment.findPreference<Preference>("virustotal_api_key_manage")?.setOnPreferenceClickListener {
            promptVirusTotalKey(fragment.requireContext(), onSaved = { key ->
                updateApiKeyManageSummary(fragment, "virustotal_api_key_manage", key)
            })
            true
        }

        updateApiKeyManageSummary(fragment, "pithus_api_key_manage", ShizukuSettings.getPithusApiKey())
        fragment.findPreference<Preference>("pithus_api_key_manage")?.setOnPreferenceClickListener {
            promptPithusKey(fragment.requireContext(), onSaved = { key ->
                updateApiKeyManageSummary(fragment, "pithus_api_key_manage", key)
            })
            true
        }
    }

    fun promptVirusTotalKey(
        context: Context,
        onSaved: (String) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        showApiKeyDialog(
            context = context,
            title = context.getString(R.string.verify_virustotal_key_title),
            hint = context.getString(R.string.verify_api_key_hint),
            currentKey = ShizukuSettings.getVirusTotalApiKey(),
            getKeyUrl = VIRUSTOTAL_KEY_URL,
            onSave = { key ->
                if (key.isBlank()) {
                    Toast.makeText(context, R.string.verify_virustotal_key_required, Toast.LENGTH_SHORT).show()
                    onCancel()
                } else {
                    ShizukuSettings.setVirusTotalApiKey(key)
                    onSaved(key)
                }
            },
            onCancel = onCancel,
        )
    }

    fun promptPithusKey(
        context: Context,
        onSaved: (String) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        showApiKeyDialog(
            context = context,
            title = context.getString(R.string.verify_pithus_key_title),
            hint = context.getString(R.string.verify_api_key_hint),
            currentKey = ShizukuSettings.getPithusApiKey(),
            getKeyUrl = PITHUS_KEY_URL,
            onSave = { key ->
                ShizukuSettings.setPithusApiKey(key)
                onSaved(key)
            },
            onCancel = onCancel,
        )
    }

    private fun updateApiKeyManageSummary(
        fragment: BaseSettingsFragment,
        key: String,
        apiKey: String,
    ) {
        val summary =
            if (apiKey.isNotBlank()) {
                fragment.getString(R.string.verify_api_key_configured)
            } else {
                fragment.getString(R.string.verify_api_key_hint)
            }
        fragment.findPreference<Preference>(key)?.setCustomSummary(summary)
    }

    fun showApiKeyDialog(
        context: Context,
        title: String,
        hint: String,
        currentKey: String,
        getKeyUrl: String? = null,
        onSave: (String) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val dp16 = (16 * context.resources.displayMetrics.density).toInt()
        val input =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                this.hint = hint
                if (currentKey.isNotBlank()) setText(currentKey)
            }
        val container =
            FrameLayout(context).apply {
                setPadding(dp16, dp16 / 2, dp16, 0)
                addView(input)
            }
        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ -> onSave(input.text.toString().trim()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
        if (getKeyUrl != null) {
            builder.setNeutralButton(R.string.verify_get_api_key) { _, _ ->
                runCatching {
                    context.startActivity(
                        android.content
                            .Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(getKeyUrl))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        builder.show()
    }
}
