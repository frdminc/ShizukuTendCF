package af.shizuku.manager.settings

import af.shizuku.core.ui.compose.controlContainer
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors

/**
 * M3E 2.6: categorical choices (accent, shape style) as a row of tiles with radio semantics,
 * instead of a slider that implied an order. Stores a String like ListPreference.
 */
abstract class ChoiceTilePreference(
    context: Context,
    attrs: AttributeSet?,
) : Preference(context, attrs) {
    protected var entries: Array<CharSequence> = emptyArray()
    protected var entryValues: Array<CharSequence> = emptyArray()
    private val selected = mutableStateOf<String?>(null)

    init {
        layoutResource = R.layout.preference_compose
        isSelectable = false
        context.obtainStyledAttributes(attrs, R.styleable.SliderPreference).apply {
            getTextArray(R.styleable.SliderPreference_android_entries)?.let { entries = it }
            getTextArray(R.styleable.SliderPreference_android_entryValues)?.let { entryValues = it }
            recycle()
        }
    }

    override fun onGetDefaultValue(
        a: TypedArray,
        index: Int,
    ): Any? = a.getString(index)

    override fun onSetInitialValue(defaultValue: Any?) {
        selected.value = getPersistedString(defaultValue as? String)
    }

    /** Draws one tile's preview (40dp canvas); [fill] is the selected/unselected colour. */
    protected abstract fun DrawScope.drawTile(
        value: String,
        index: Int,
        fill: Color,
    )

    /** Colour of a tile when not selected (accents show their own colour either way). */
    protected open fun tileColor(
        index: Int,
        isSelected: Boolean,
        selectedColor: Color,
        unselectedColor: Color,
    ): Color = if (isSelected) selectedColor else unselectedColor

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val cv = holder.findViewById(R.id.preference_compose_view) as? ComposeView ?: holder.itemView as? ComposeView ?: return
        val enabled = isEnabled
        val prefIcon = icon
        cv.setPreferenceContent {
            ComposePreferenceRow(title = title, summary = summary, enabled = enabled, icon = prefIcon) {
                ChoiceTiles(enabled) { value ->
                    if (value != selected.value && callChangeListener(value)) {
                        selected.value = value
                        persistString(value)
                    }
                }
            }
        }
    }

    @Composable
    private fun ChoiceTiles(
        enabled: Boolean,
        onSelect: (String) -> Unit,
    ) {
        val view = LocalView.current
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp)
                    .selectableGroup(),
        ) {
            entryValues.forEachIndexed { index, raw ->
                val value = raw.toString()
                val isSelected = value == selected.value
                val fill by animateColorAsState(
                    tileColor(index, isSelected, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.controlContainer),
                    label = "tileFill",
                )
                val ring = MaterialTheme.colorScheme.primary
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .width(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
                                HapticUtils.segmentTick(view)
                                onSelect(value)
                            }.padding(6.dp),
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(48.dp)) {
                            // Selection ring, so a tile that keeps its own colour (accents) still
                            // shows which one is chosen.
                            if (isSelected) {
                                drawRoundRect(ring, style = Stroke(width = 2.dp.toPx()), cornerRadius = CornerRadius(14.dp.toPx()))
                            }
                            inset(4.dp.toPx()) { drawTile(value, index, fill) }
                        }
                    }
                    Text(
                        entries.getOrNull(index)?.toString() ?: value,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Shape style tiles: each draws the container corner its style gives icons. */
class ShapeStylePickerPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ChoiceTilePreference(context, attrs) {
        override fun DrawScope.drawTile(
            value: String,
            index: Int,
            fill: Color,
        ) {
            val w = size.width
            when (value) {
                "classic" -> drawRoundRect(fill, cornerRadius = CornerRadius(w * 0.06f))
                "squircle" -> drawRoundRect(fill, cornerRadius = CornerRadius(w * 0.36f))
                "cut" -> {
                    val c = w * 0.28f
                    val p =
                        Path().apply {
                            moveTo(c, 0f)
                            lineTo(w - c, 0f)
                            lineTo(w, c)
                            lineTo(w, w - c)
                            lineTo(w - c, w)
                            lineTo(c, w)
                            lineTo(0f, w - c)
                            lineTo(0f, c)
                            close()
                        }
                    drawPath(p, fill)
                }
                "zen" -> {
                    // Leaf: two opposite corners full, the other two square.
                    val r = w / 2
                    val p =
                        Path().apply {
                            moveTo(r, 0f)
                            lineTo(w, 0f)
                            lineTo(w, r)
                            quadraticTo(w, w, r, w)
                            lineTo(0f, w)
                            lineTo(0f, r)
                            quadraticTo(0f, 0f, r, 0f)
                            close()
                        }
                    drawPath(p, fill)
                }
                else -> drawRoundRect(fill, cornerRadius = CornerRadius(w * 0.3f)) // modern
            }
        }
    }

/**
 * Accent swatches in each accent's real primary - read through the same overlay ThemeDelegateImpl
 * would apply (released or generated schemes, light or dark), so the swatch matches the result.
 */
class AccentPickerPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ChoiceTilePreference(context, attrs) {
        private val swatches: List<Color> by lazy { entryValues.map { Color(primaryFor(it.toString())) } }

        override fun tileColor(
            index: Int,
            isSelected: Boolean,
            selectedColor: Color,
            unselectedColor: Color,
        ): Color = swatches.getOrElse(index) { unselectedColor }

        override fun DrawScope.drawTile(
            value: String,
            index: Int,
            fill: Color,
        ) {
            drawCircle(fill, radius = size.minDimension / 2, center = Offset(size.width / 2, size.height / 2))
        }

        private fun primaryFor(accent: String): Int {
            val base = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme)
            val dark =
                (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val generated = ShizukuSettings.isExperimentalColorSchemesEnabled()
            val array =
                when (accent) {
                    "VIOLET" -> if (generated) R.array.theme_overlay_accent_violet else R.array.theme_overlay_accent_classic_violet
                    "GREEN" -> if (generated) R.array.theme_overlay_accent_green else R.array.theme_overlay_accent_classic_green
                    "CRIMSON" -> if (generated) R.array.theme_overlay_accent_crimson else R.array.theme_overlay_accent_classic_crimson
                    "OCEAN" -> if (generated) R.array.theme_overlay_accent_ocean else R.array.theme_overlay_accent_classic_ocean
                    "AMBER" -> if (generated) R.array.theme_overlay_accent_amber else R.array.theme_overlay_accent_classic_amber
                    "ROSE" -> if (generated) R.array.theme_overlay_accent_rose else R.array.theme_overlay_accent_classic_rose
                    "SLATE" -> if (generated) R.array.theme_overlay_accent_slate else R.array.theme_overlay_accent_classic_slate
                    "CYAN" -> if (generated) R.array.theme_overlay_accent_cyan else R.array.theme_overlay_accent_classic_cyan
                    "NEON" -> if (generated) R.array.theme_overlay_accent_neon else R.array.theme_overlay_accent_classic_neon
                    else -> 0
                }
            if (array != 0) {
                // Generated arrays: light std/med/high then dark; classic arrays: light, dark.
                val index = if (dark) (if (generated) 3 else 1) else 0
                val styles = context.resources.obtainTypedArray(array)
                val style = styles.getResourceId(index, 0)
                styles.recycle()
                if (style != 0) base.theme.applyStyle(style, true)
            }
            return MaterialColors.getColor(base, androidx.appcompat.R.attr.colorPrimary, 0xFF4355B9.toInt())
        }
    }
