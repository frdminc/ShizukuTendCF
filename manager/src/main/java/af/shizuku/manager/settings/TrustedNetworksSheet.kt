package af.shizuku.manager.settings

import af.shizuku.core.ui.compose.controlContainer
import af.shizuku.core.ui.compose.onControlContainer
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.automation.AutomationService
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.NetworkTrust
import af.shizuku.manager.utils.TcpNetworkGuard
import af.shizuku.manager.utils.WifiNetworks
import af.shizuku.manager.widget.AppSwitch
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Picks trusted Wi-Fi networks from the connected one and the ones saved on the device, instead of
 * typing names. Typing stays available for networks that aren't saved here. Serves both lists:
 * Wireless ADB's network protection and the App connection firewall automation.
 */
class TrustedNetworksSheet : BottomSheetDialogFragment() {
    /** Where a list of trusted networks lives, and what has to react when it changes. */
    enum class Target(
        val titleRes: Int,
        val subtitleRes: Int,
    ) {
        TCP(R.string.settings_tcp_trusted_networks_label, R.string.trusted_networks_tcp_subtitle) {
            override fun read() = NetworkTrust.trustedSsids()

            override fun write(
                context: Context,
                ssids: Set<String>,
            ) {
                NetworkTrust.setTrustedSsids(ssids)
                TcpNetworkGuard.enforce(context)
            }
        },
        AUTOMATION(R.string.settings_automation_trusted_networks, R.string.settings_automation_trusted_networks_desc) {
            override fun read() = ShizukuSettings.getTrustedNetworksSet()

            override fun write(
                context: Context,
                ssids: Set<String>,
            ) {
                ShizukuSettings.setTrustedNetworks(ssids.filter(::accepts).joinToString(","))
                if (ShizukuSettings.hasAnyAutomationRulesConfigured()) {
                    AutomationService.startIfNeeded(context)
                } else {
                    AutomationService.stopIfRunning(context)
                }
            }

            // Stored comma-separated, so a name containing a comma would be split in two.
            override fun accepts(ssid: String) = ',' !in ssid
        }, ;

        abstract fun read(): Set<String>

        /** Whether [ssid] can be stored in this list. */
        open fun accepts(ssid: String) = true

        abstract fun write(
            context: Context,
            ssids: Set<String>,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val target = Target.valueOf(requireArguments().getString(ARG_TARGET) ?: Target.TCP.name)
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
                        SheetHeader(stringResource(target.titleRes), stringResource(target.subtitleRes))
                        Content(target)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun Content(target: Target) {
        val context = LocalContext.current
        var snapshot by remember { mutableStateOf<WifiNetworks.Snapshot?>(null) }
        LaunchedEffect(Unit) { snapshot = WifiNetworks.load(context.applicationContext) }
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = snapshot,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
            contentKey = { it != null },
            label = "trustedNetworksLoad",
        ) { loaded ->
            if (loaded == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else {
                NetworkList(target, loaded)
            }
        }
    }

    @Composable
    private fun NetworkList(
        target: Target,
        loaded: WifiNetworks.Snapshot,
    ) {
        val context = LocalContext.current
        val view = LocalView.current
        var trusted by remember { mutableStateOf(target.read()) }
        // Every name shown this session stays listed, so a typed name switched off can be switched
        // back on instead of vanishing under the finger.
        val shown = remember { mutableStateListOf<String>().apply { addAll(trusted.sortedWith(String.CASE_INSENSITIVE_ORDER)) } }

        fun save(next: Set<String>) {
            trusted = next
            target.write(context.applicationContext, next)
        }

        Column(modifier = Modifier.padding(horizontal = 8.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            loaded.current?.let { current ->
                NetworkRow(
                    ssid = current,
                    usable = target.accepts(current),
                    subtitle = stringResource(R.string.trusted_networks_connected),
                    highlighted = true,
                    checked = current in trusted,
                    onCheckedChange = { on ->
                        if (on) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                        save(if (on) trusted + current else trusted - current)
                    },
                )
            }
            val others = (loaded.saved + shown).distinct() - setOfNotNull(loaded.current)
            if (others.isNotEmpty()) SheetSectionLabel(stringResource(R.string.trusted_networks_saved_label))
            others.forEach { ssid ->
                NetworkRow(
                    ssid = ssid,
                    usable = target.accepts(ssid),
                    subtitle = if (ssid in loaded.saved) null else stringResource(R.string.trusted_networks_not_saved),
                    highlighted = false,
                    checked = ssid in trusted,
                    onCheckedChange = { on ->
                        if (on) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                        save(if (on) trusted + ssid else trusted - ssid)
                    },
                )
            }
            if (!loaded.viaShell) {
                Text(
                    text = stringResource(R.string.trusted_networks_needs_service),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            AddByName(onAdd = { name ->
                HapticUtils.success(view)
                if (name !in shown) shown.add(name)
                save(trusted + name)
            })
        }
    }

    @Composable
    private fun NetworkRow(
        ssid: String,
        usable: Boolean,
        subtitle: String?,
        highlighted: Boolean,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        SheetRow(
            iconRes = R.drawable.ic_wifi_24,
            iconContainer = if (highlighted) colors.primaryContainer else colors.secondaryContainer,
            iconTint = if (highlighted) colors.onPrimaryContainer else colors.onSecondaryContainer,
            title = ssid,
            subtitle = if (usable) subtitle else stringResource(R.string.trusted_networks_comma),
            modifier = Modifier.toggleable(value = checked, enabled = usable, role = Role.Switch, onValueChange = onCheckedChange),
        ) {
            AppSwitch(checked = checked, onCheckedChange = null, enabled = usable)
        }
    }

    @Composable
    private fun AddByName(onAdd: (String) -> Unit) {
        val colors = MaterialTheme.colorScheme
        val view = LocalView.current
        var open by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        // Stored comma-separated for the firewall list, so commas can't be part of a name.
        val clean = name.trim().replace(",", "")
        val submit = {
            if (clean.isNotEmpty()) {
                onAdd(clean)
                name = ""
                open = false
            }
        }
        val chevronTurn by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chevron")
        SheetRow(
            iconRes = R.drawable.ic_edit_24,
            iconContainer = colors.controlContainer,
            iconTint = colors.onControlContainer,
            title = stringResource(R.string.trusted_networks_type_name),
            subtitle = null,
            modifier =
                Modifier.clickable(role = Role.Button) {
                    HapticUtils.tap(view)
                    open = !open
                },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_outline_expand_more_24),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.rotate(chevronTurn),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_tcp_ssid_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(enabled = clean.isNotEmpty(), onClick = submit) {
                    Text(stringResource(R.string.trusted_networks_add))
                }
            }
        }
    }

    companion object {
        private const val ARG_TARGET = "target"
        private const val TAG = "trusted_networks"

        fun show(
            fm: FragmentManager,
            target: Target,
        ) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            TrustedNetworksSheet()
                .apply { arguments = Bundle().apply { putString(ARG_TARGET, target.name) } }
                .show(fm, TAG)
        }
    }
}
