package af.shizuku.manager.widget

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * #569 guard for collapsing top app bars: the bar updates heightOffsetLimit in its layout pass,
 * one frame after a font/display-size change, so a stale heightOffset below the new limit made
 * Scaffold report negative padding ("Padding must be non-negative"). Re-coerce through the setter
 * whenever the limit moves - the same fix as SettingsScreen / HomeScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBarScrollBehavior.GuardHeightOffset() {
    LaunchedEffect(state.heightOffsetLimit) { state.heightOffset = state.heightOffset }
}

/** Scaffold's content padding with no negative side (belt-and-braces for the same #569 window). */
@Composable
fun PaddingValues.nonNegative(): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(dir).coerceAtLeast(0.dp),
        top = calculateTopPadding().coerceAtLeast(0.dp),
        end = calculateEndPadding(dir).coerceAtLeast(0.dp),
        bottom = calculateBottomPadding().coerceAtLeast(0.dp),
    )
}
