package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.findActivity
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.preference.Preference

/**
 * The App connection firewall's trusted networks as a settings row: shows the names, and opens
 * [TrustedNetworksSheet] to pick them instead of typing a comma-separated list.
 */
class TrustedNetworksRowPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        init {
            isPersistent = false
        }

        override fun getSummary(): CharSequence {
            val names = ShizukuSettings.getTrustedNetworksSet().sortedWith(String.CASE_INSENSITIVE_ORDER)
            return if (names.isEmpty()) {
                context.getString(R.string.settings_automation_trusted_networks_desc)
            } else {
                names.joinToString(", ")
            }
        }

        override fun onAttached() {
            super.onAttached()
            ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDetached() {
            super.onDetached()
            ShizukuSettings.getPreferences()?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(
            prefs: SharedPreferences?,
            key: String?,
        ) {
            if (key == ShizukuSettings.Keys.KEY_AUTOMATION_TRUSTED_NETWORKS) notifyChanged()
        }

        override fun onClick() {
            val activity = context.findActivity<androidx.fragment.app.FragmentActivity>() ?: return
            TrustedNetworksSheet.show(activity.supportFragmentManager, TrustedNetworksSheet.Target.AUTOMATION)
        }
    }
