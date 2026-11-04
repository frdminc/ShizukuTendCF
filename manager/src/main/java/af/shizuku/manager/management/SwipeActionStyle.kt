package af.shizuku.manager.management

import af.shizuku.core.ui.compose.controlContainer
import af.shizuku.core.ui.compose.onControlContainer
import af.shizuku.manager.R
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.R as MaterialR

/**
 * The one table of what each app-list swipe action looks like. The list's swipe drawing, the
 * settings preview and the action picker all read it, so the preview shows exactly what a real
 * swipe shows.
 */
object SwipeActionStyle {
    enum class Role(
        val containerAttr: Int,
        val onContainerAttr: Int,
    ) {
        PRIMARY(MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer),
        SECONDARY(MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer),
        TERTIARY(MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer),
        ERROR(MaterialR.attr.colorErrorContainer, MaterialR.attr.colorOnErrorContainer),
        NEUTRAL(MaterialR.attr.colorSurfaceVariant, MaterialR.attr.colorOnSurfaceVariant),
        ;

        fun container(cs: ColorScheme): Color =
            when (this) {
                PRIMARY -> cs.primaryContainer
                SECONDARY -> cs.secondaryContainer
                TERTIARY -> cs.tertiaryContainer
                ERROR -> cs.errorContainer
                // surfaceVariant is (nearly) black on AMOLED themes, like the sheet behind it.
                NEUTRAL -> cs.controlContainer
            }

        fun onContainer(cs: ColorScheme): Color =
            when (this) {
                PRIMARY -> cs.onPrimaryContainer
                SECONDARY -> cs.onSecondaryContainer
                TERTIARY -> cs.onTertiaryContainer
                ERROR -> cs.onErrorContainer
                NEUTRAL -> cs.onControlContainer
            }
    }

    data class Spec(
        val value: String,
        val labelRes: Int,
        val descriptionRes: Int,
        val iconRes: Int,
        val role: Role,
    )

    const val NONE = "none"

    /** Share of the row width a swipe must pass to trigger, used by the list and the preview. */
    const val THRESHOLD = 0.38f

    val all: List<Spec> =
        listOf(
            Spec("open_app", R.string.settings_action_open_app, R.string.swipe_action_desc_open_app, R.drawable.ic_outline_play_arrow_24, Role.PRIMARY),
            Spec("app_info", R.string.settings_action_app_info, R.string.swipe_action_desc_app_info, R.drawable.ic_outline_info_24, Role.SECONDARY),
            Spec("toggle_permission", R.string.settings_action_toggle_permission, R.string.swipe_action_desc_toggle_permission, R.drawable.ic_shield_24, Role.TERTIARY),
            Spec("hide_from_list", R.string.settings_action_hide_from_list, R.string.swipe_action_desc_hide_from_list, R.drawable.ic_visibility_off_24, Role.ERROR),
            Spec(NONE, R.string.settings_action_none, R.string.swipe_action_desc_none, R.drawable.ic_close_24, Role.NEUTRAL),
        )

    fun of(value: String?): Spec = all.firstOrNull { it.value == value } ?: all.last()
}
