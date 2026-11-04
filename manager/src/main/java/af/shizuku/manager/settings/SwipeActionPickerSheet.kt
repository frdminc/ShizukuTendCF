package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.management.SwipeActionStyle
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.widget.AppSwitch
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Picker for one swipe direction (a radio list with a still preview of the choice), or the
 * on/off list for the long-press menu. Writes straight to preferences; the rows that show the
 * current choice listen for the change.
 */
class SwipeActionPickerSheet : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val mode = requireArguments().getString(ARG_MODE) ?: MODE_RIGHT
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PreferenceComposeTheme {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp),
                    ) {
                        if (mode == MODE_LONG_PRESS) LongPressContent() else DirectionContent(mode == MODE_RIGHT)
                    }
                }
            }
        }
    }

    @Composable
    private fun DirectionContent(isRight: Boolean) {
        val view = LocalView.current
        val key = if (isRight) ShizukuSettings.Keys.KEY_SWIPE_RIGHT_ACTION else ShizukuSettings.Keys.KEY_SWIPE_LEFT_ACTION
        val selected =
            remember {
                mutableStateOf(if (isRight) ShizukuSettings.getSwipeRightAction() else ShizukuSettings.getSwipeLeftAction())
            }
        SheetHeader(
            title = stringResource(if (isRight) R.string.swipe_actions_right_title else R.string.swipe_actions_left_title),
            subtitle = stringResource(if (isRight) R.string.swipe_picker_right_subtitle else R.string.swipe_picker_left_subtitle),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val widthPx = constraints.maxWidth.toFloat()
            val sign = if (isRight) 1f else -1f
            val shown = selected.value
            val dx = if (shown == SwipeActionStyle.NONE) 0f else sign * widthPx * 0.5f
            SwipeDemoRow(
                dx = { dx },
                widthPx = widthPx,
                right = if (isRight) shown else SwipeActionStyle.NONE,
                left = if (isRight) SwipeActionStyle.NONE else shown,
            )
        }
        Column(modifier = Modifier.selectableGroup().padding(horizontal = 8.dp)) {
            SwipeActionStyle.all.forEach { spec ->
                val isSelected = spec.value == selected.value
                SheetRow(
                    iconRes = spec.iconRes,
                    iconContainer = spec.role.container(MaterialTheme.colorScheme),
                    iconTint = spec.role.onContainer(MaterialTheme.colorScheme),
                    title = stringResource(spec.labelRes),
                    subtitle = stringResource(spec.descriptionRes),
                    selected = isSelected,
                    modifier =
                        Modifier.selectable(selected = isSelected, role = Role.RadioButton) {
                            if (!isSelected) {
                                HapticUtils.segmentTick(view)
                                selected.value = spec.value
                                ShizukuSettings
                                    .getPreferences()
                                    ?.edit()
                                    ?.putString(key, spec.value)
                                    ?.apply()
                            }
                        },
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                }
            }
        }
    }

    @Composable
    private fun LongPressContent() {
        val view = LocalView.current
        val prefs = ShizukuSettings.getPreferences()
        val states =
            remember {
                mutableStateMapOf<String, Boolean>().apply {
                    LONG_PRESS_ITEMS.forEach { put(it.key, prefs?.getBoolean(it.key, it.defaultOn) ?: it.defaultOn) }
                }
            }
        SheetHeader(
            title = stringResource(R.string.swipe_actions_long_press_title),
            subtitle = stringResource(R.string.settings_lp_category_summary),
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            LONG_PRESS_ITEMS.forEach { item ->
                val on = states[item.key] ?: item.defaultOn
                SheetRow(
                    iconRes = item.iconRes,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    title = stringResource(item.titleRes),
                    subtitle = null,
                    modifier =
                        Modifier.toggleable(value = on, role = Role.Switch) { checked ->
                            if (checked) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                            states[item.key] = checked
                            prefs?.edit()?.putBoolean(item.key, checked)?.apply()
                        },
                ) {
                    AppSwitch(checked = on, onCheckedChange = null)
                }
            }
        }
    }

    data class LongPressItem(
        val key: String,
        val titleRes: Int,
        val iconRes: Int,
        val defaultOn: Boolean,
    )

    companion object {
        const val MODE_RIGHT = "right"
        const val MODE_LEFT = "left"
        const val MODE_LONG_PRESS = "long_press"
        private const val ARG_MODE = "mode"
        private const val TAG = "swipe_action_picker"

        /** Mirrors the defaults of the `ShizukuSettings.isLp*` getters the app list reads. */
        val LONG_PRESS_ITEMS =
            listOf(
                LongPressItem(ShizukuSettings.Keys.KEY_LP_OPEN_APP, R.string.settings_lp_open_app, R.drawable.ic_outline_play_arrow_24, true),
                LongPressItem(ShizukuSettings.Keys.KEY_LP_APP_INFO, R.string.settings_lp_app_info, R.drawable.ic_outline_info_24, true),
                LongPressItem(ShizukuSettings.Keys.KEY_LP_TOGGLE_PERMISSION, R.string.settings_lp_toggle_permission, R.drawable.ic_shield_24, true),
                LongPressItem(ShizukuSettings.Keys.KEY_LP_HIDE_FROM_LIST, R.string.settings_lp_hide_from_list, R.drawable.ic_visibility_off_24, false),
                LongPressItem(ShizukuSettings.Keys.KEY_LP_API_SCOPES, R.string.settings_lp_api_scopes, R.drawable.ic_admin_panel_settings_24, true),
                LongPressItem(ShizukuSettings.Keys.KEY_LP_SELECT_MULTIPLE, R.string.settings_lp_select_multiple, R.drawable.ic_select_all_24, true),
                LongPressItem(ShizukuSettings.Keys.KEY_LP_FREEZE_APP, R.string.settings_lp_freeze_app, R.drawable.ic_ac_unit_24, true),
                LongPressItem(ShizukuSettings.Keys.KEY_LP_FREEFORM_RESIZE, R.string.settings_lp_freeform_resize, R.drawable.ic_window_24, false),
            )

        fun show(
            fm: FragmentManager,
            mode: String,
        ) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            SwipeActionPickerSheet()
                .apply { arguments = Bundle().apply { putString(ARG_MODE, mode) } }
                .show(fm, TAG)
        }
    }
}
