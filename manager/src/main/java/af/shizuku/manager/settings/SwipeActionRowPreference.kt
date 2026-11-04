package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.findActivity
import af.shizuku.manager.management.SwipeActionStyle
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.preference.Preference

/**
 * A plain settings row showing the current choice for one swipe direction, or how many
 * long-press actions are on. Tapping it opens [SwipeActionPickerSheet]. Which one it is comes
 * from the key: `swipe_right_row`, `swipe_left_row` or `long_press_row`.
 */
class SwipeActionRowPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        private val mode: String
            get() =
                when (key) {
                    "swipe_left_row" -> SwipeActionPickerSheet.MODE_LEFT
                    "long_press_row" -> SwipeActionPickerSheet.MODE_LONG_PRESS
                    else -> SwipeActionPickerSheet.MODE_RIGHT
                }

        init {
            isPersistent = false
        }

        override fun getSummary(): CharSequence =
            when (mode) {
                SwipeActionPickerSheet.MODE_LONG_PRESS -> {
                    val prefs = ShizukuSettings.getPreferences()
                    val items = SwipeActionPickerSheet.LONG_PRESS_ITEMS
                    val on = items.count { prefs?.getBoolean(it.key, it.defaultOn) ?: it.defaultOn }
                    context.getString(R.string.swipe_actions_long_press_summary, on, items.size)
                }
                SwipeActionPickerSheet.MODE_LEFT -> context.getString(SwipeActionStyle.of(ShizukuSettings.getSwipeLeftAction()).labelRes)
                else -> context.getString(SwipeActionStyle.of(ShizukuSettings.getSwipeRightAction()).labelRes)
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
            val relevant =
                when (mode) {
                    SwipeActionPickerSheet.MODE_LONG_PRESS -> key?.startsWith("lp_") == true
                    SwipeActionPickerSheet.MODE_LEFT -> key == ShizukuSettings.Keys.KEY_SWIPE_LEFT_ACTION
                    else -> key == ShizukuSettings.Keys.KEY_SWIPE_RIGHT_ACTION
                }
            if (relevant) notifyChanged()
        }

        override fun onClick() {
            val activity = context.findActivity<androidx.fragment.app.FragmentActivity>() ?: return
            SwipeActionPickerSheet.show(activity.supportFragmentManager, mode)
        }
    }
