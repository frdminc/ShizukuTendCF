package af.shizuku.manager.settings

import af.shizuku.manager.R
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Multi-select preference rendered as a wrapping row of M3 filter chips. Stores a Set<String>,
 * exactly like MultiSelectListPreference.
 *
 * XML:
 * ```
 * <af.shizuku.manager.settings.FilterChipPreference
 *     android:key="..."
 *     android:title="..."
 *     android:entries="@array/..."
 *     android:entryValues="@array/..."
 *     android:defaultValue="@array/..." />
 * ```
 * Listen for changes with setOnPreferenceChangeListener (newValue is a Set<String>); return false to veto.
 */
class FilterChipPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        var entries: Array<CharSequence> = emptyArray()
        var entryValues: Array<CharSequence> = emptyArray()

        private val selected = mutableStateOf<Set<String>>(emptySet())

        var values: Set<String>
            get() = selected.value
            set(newValues) {
                val changed = selected.value != newValues
                selected.value = newValues
                if (changed) {
                    persistStringSet(newValues)
                    notifyDependencyChange(shouldDisableDependents())
                }
            }

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            context.obtainStyledAttributes(attrs, R.styleable.FilterChipPreference).apply {
                getTextArray(R.styleable.FilterChipPreference_android_entries)?.let { entries = it }
                getTextArray(R.styleable.FilterChipPreference_android_entryValues)?.let { entryValues = it }
                recycle()
            }
        }

        override fun onGetDefaultValue(
            a: TypedArray,
            index: Int,
        ): Any? = a.getTextArray(index)?.map { it.toString() }?.toSet() ?: emptySet<String>()

        override fun onSetInitialValue(defaultValue: Any?) {
            @Suppress("UNCHECKED_CAST")
            values = getPersistedStringSet(defaultValue as? Set<String> ?: emptySet()) ?: emptySet()
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                (holder.itemView as? ComposeView)
                    ?: (holder.findViewById(R.id.preference_compose_view) as? ComposeView)
            if (cv == null) {
                timber.log.Timber.w("FilterChipPreference: ComposeView not found in holder (itemView is %s)", holder.itemView.javaClass.name)
                return
            }
            val labels = entries.map { it.toString() }
            val keys = entryValues.map { it.toString() }
            val enabled = isEnabled
            val prefIcon = icon
            cv.setPreferenceContent {
                ComposePreferenceRow(
                    title = title,
                    summary = summary,
                    enabled = enabled,
                    icon = prefIcon,
                ) {
                    FilterChipGroup(
                        labels = labels,
                        selected = keys.indices.filter { keys[it] in selected.value }.toSet(),
                        enabled = enabled,
                        onToggle = { index ->
                            val key = keys.getOrNull(index) ?: return@FilterChipGroup
                            val newValues = if (key in selected.value) selected.value - key else selected.value + key
                            if (callChangeListener(newValues)) values = newValues
                        },
                    )
                }
            }
        }
    }

/** Reusable wrapping multi-select chip group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChipGroup(
    labels: List<String>,
    selected: Set<Int>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(index) },
                enabled = enabled,
                label = { Text(label) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_switch_check_16),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}
