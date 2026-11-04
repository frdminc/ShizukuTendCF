package af.shizuku.core.ui.compose

import android.content.Context
import android.util.TypedValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.AbsoluteCornerSize
import com.google.android.material.shape.CutCornerTreatment
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.R as MaterialR

/**
 * Reads the ColorScheme from the hosting Activity's own theme instead of having Compose decide
 * dynamic-color/custom-accent/default independently. By the time this runs, AppActivity's
 * onApplyUserThemeResource (see ThemeDelegateImpl) has already applied the correct ThemeOverlay -
 * system dynamic color, a custom accent, the default blue, or black night theme - to the hosting
 * Activity's theme. Reading those resolved attributes keeps Compose screens visually identical to
 * the rest of the (XML-themed) app instead of falling back to Compose's own generic baseline
 * scheme whenever dynamic color isn't available or a custom accent is set.
 */
private fun androidColorScheme(context: Context, darkTheme: Boolean): ColorScheme {
    val fallback = if (darkTheme) darkColorScheme() else lightColorScheme()
    fun color(attr: Int, fallbackColor: Color) =
        Color(MaterialColors.getColor(context, attr, fallbackColor.toArgb()))

    // colorPrimary and colorError are declared by appcompat, not material - with non-transitive
    // R classes they only exist in appcompat's R.
    val primary = color(androidx.appcompat.R.attr.colorPrimary, fallback.primary)
    val onPrimary = color(MaterialR.attr.colorOnPrimary, fallback.onPrimary)
    val primaryContainer = color(MaterialR.attr.colorPrimaryContainer, fallback.primaryContainer)
    val onPrimaryContainer = color(MaterialR.attr.colorOnPrimaryContainer, fallback.onPrimaryContainer)
    val secondary = color(MaterialR.attr.colorSecondary, fallback.secondary)
    val onSecondary = color(MaterialR.attr.colorOnSecondary, fallback.onSecondary)
    val secondaryContainer = color(MaterialR.attr.colorSecondaryContainer, fallback.secondaryContainer)
    val onSecondaryContainer = color(MaterialR.attr.colorOnSecondaryContainer, fallback.onSecondaryContainer)
    val tertiary = color(MaterialR.attr.colorTertiary, fallback.tertiary)
    val onTertiary = color(MaterialR.attr.colorOnTertiary, fallback.onTertiary)
    val tertiaryContainer = color(MaterialR.attr.colorTertiaryContainer, fallback.tertiaryContainer)
    val onTertiaryContainer = color(MaterialR.attr.colorOnTertiaryContainer, fallback.onTertiaryContainer)
    val error = color(androidx.appcompat.R.attr.colorError, fallback.error)
    val onError = color(MaterialR.attr.colorOnError, fallback.onError)
    val errorContainer = color(MaterialR.attr.colorErrorContainer, fallback.errorContainer)
    val onErrorContainer = color(MaterialR.attr.colorOnErrorContainer, fallback.onErrorContainer)
    val background = color(android.R.attr.colorBackground, fallback.background)
    val onBackground = color(MaterialR.attr.colorOnBackground, fallback.onBackground)
    val surface = color(MaterialR.attr.colorSurface, fallback.surface)
    val onSurface = color(MaterialR.attr.colorOnSurface, fallback.onSurface)
    val surfaceVariant = color(MaterialR.attr.colorSurfaceVariant, fallback.surfaceVariant)
    val onSurfaceVariant = color(MaterialR.attr.colorOnSurfaceVariant, fallback.onSurfaceVariant)
    val outline = color(MaterialR.attr.colorOutline, fallback.outline)
    val outlineVariant = color(MaterialR.attr.colorOutlineVariant, fallback.outlineVariant)
    val scrim = color(MaterialR.attr.scrimBackground, fallback.scrim)
    val surfaceBright = color(MaterialR.attr.colorSurfaceBright, fallback.surfaceBright)
    val surfaceDim = color(MaterialR.attr.colorSurfaceDim, fallback.surfaceDim)
    val surfaceContainer = color(MaterialR.attr.colorSurfaceContainer, fallback.surfaceContainer)
    val surfaceContainerHigh = color(MaterialR.attr.colorSurfaceContainerHigh, fallback.surfaceContainerHigh)
    val surfaceContainerHighest = color(MaterialR.attr.colorSurfaceContainerHighest, fallback.surfaceContainerHighest)
    val surfaceContainerLow = color(MaterialR.attr.colorSurfaceContainerLow, fallback.surfaceContainerLow)
    val surfaceContainerLowest = color(MaterialR.attr.colorSurfaceContainerLowest, fallback.surfaceContainerLowest)
    val inversePrimary = color(MaterialR.attr.colorPrimaryInverse, fallback.inversePrimary)
    val inverseSurface = color(MaterialR.attr.colorSurfaceInverse, fallback.inverseSurface)
    val inverseOnSurface = color(MaterialR.attr.colorOnSurfaceInverse, fallback.inverseOnSurface)

    // darkColorScheme()/lightColorScheme() are plain functions (not a shared type with a common
    // named-argument call), so a stored function reference can't be invoked with named args here -
    // hence the duplicated call rather than picking one reference to invoke once.
    val scheme =
        if (darkTheme) {
            darkColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
                secondary = secondary, onSecondary = onSecondary,
                secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary, onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
                error = error, onError = onError,
                errorContainer = errorContainer, onErrorContainer = onErrorContainer,
                background = background, onBackground = onBackground,
                surface = surface, onSurface = onSurface,
                surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                scrim = scrim,
                surfaceBright = surfaceBright,
                surfaceDim = surfaceDim,
                surfaceContainer = surfaceContainer,
                surfaceContainerHigh = surfaceContainerHigh,
                surfaceContainerHighest = surfaceContainerHighest,
                surfaceContainerLow = surfaceContainerLow,
                surfaceContainerLowest = surfaceContainerLowest,
                inversePrimary = inversePrimary,
                inverseSurface = inverseSurface,
                inverseOnSurface = inverseOnSurface,
                surfaceTint = primary,
            )
        } else {
            lightColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
                secondary = secondary, onSecondary = onSecondary,
                secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary, onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
                error = error, onError = onError,
                errorContainer = errorContainer, onErrorContainer = onErrorContainer,
                background = background, onBackground = onBackground,
                surface = surface, onSurface = onSurface,
                surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                scrim = scrim,
                surfaceBright = surfaceBright,
                surfaceDim = surfaceDim,
                surfaceContainer = surfaceContainer,
                surfaceContainerHigh = surfaceContainerHigh,
                surfaceContainerHighest = surfaceContainerHighest,
                surfaceContainerLow = surfaceContainerLow,
                surfaceContainerLowest = surfaceContainerLowest,
                inversePrimary = inversePrimary,
                inverseSurface = inverseSurface,
                inverseOnSurface = inverseOnSurface,
                surfaceTint = primary,
            )
        }
    return scheme.copy(
        primaryFixed = color(MaterialR.attr.colorPrimaryFixed, scheme.primaryFixed),
        primaryFixedDim = color(MaterialR.attr.colorPrimaryFixedDim, scheme.primaryFixedDim),
        onPrimaryFixed = color(MaterialR.attr.colorOnPrimaryFixed, scheme.onPrimaryFixed),
        onPrimaryFixedVariant = color(MaterialR.attr.colorOnPrimaryFixedVariant, scheme.onPrimaryFixedVariant),
        secondaryFixed = color(MaterialR.attr.colorSecondaryFixed, scheme.secondaryFixed),
        secondaryFixedDim = color(MaterialR.attr.colorSecondaryFixedDim, scheme.secondaryFixedDim),
        onSecondaryFixed = color(MaterialR.attr.colorOnSecondaryFixed, scheme.onSecondaryFixed),
        onSecondaryFixedVariant = color(MaterialR.attr.colorOnSecondaryFixedVariant, scheme.onSecondaryFixedVariant),
        tertiaryFixed = color(MaterialR.attr.colorTertiaryFixed, scheme.tertiaryFixed),
        tertiaryFixedDim = color(MaterialR.attr.colorTertiaryFixedDim, scheme.tertiaryFixedDim),
        onTertiaryFixed = color(MaterialR.attr.colorOnTertiaryFixed, scheme.onTertiaryFixed),
        onTertiaryFixedVariant = color(MaterialR.attr.colorOnTertiaryFixedVariant, scheme.onTertiaryFixedVariant),
    )
}

/**
 * Reads the corner scale from the hosting Activity's theme, like [androidColorScheme] does for
 * colour. ThemeDelegateImpl has already applied the Shape Style, One UI, corner rounding and
 * Rounded edges overlays, so XML and Compose components get the same corners. A token that
 * doesn't resolve, or isn't an absolute size, keeps the Compose default for that slot.
 */
private fun androidShapes(context: Context): Shapes {
    val defaults = Shapes()
    val rect = android.graphics.RectF()
    fun shape(attr: Int, fallback: CornerBasedShape): CornerBasedShape {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true) || tv.resourceId == 0) return fallback
        val model =
            runCatching { ShapeAppearanceModel.builder(context, tv.resourceId, 0).build() }
                .getOrNull() ?: return fallback
        val size = model.topLeftCornerSize as? AbsoluteCornerSize ?: return fallback
        val px = size.getCornerSize(rect)
        return if (model.topLeftCorner is CutCornerTreatment) CutCornerShape(px) else RoundedCornerShape(px)
    }
    return Shapes(
        extraSmall = shape(MaterialR.attr.shapeAppearanceCornerExtraSmall, defaults.extraSmall),
        small = shape(MaterialR.attr.shapeAppearanceCornerSmall, defaults.small),
        medium = shape(MaterialR.attr.shapeAppearanceCornerMedium, defaults.medium),
        large = shape(MaterialR.attr.shapeAppearanceCornerLarge, defaults.large),
        extraLarge = shape(MaterialR.attr.shapeAppearanceCornerExtraLarge, defaults.extraLarge),
        largeIncreased = shape(MaterialR.attr.shapeAppearanceCornerLargeIncreased, defaults.largeIncreased),
        extraLargeIncreased =
            shape(MaterialR.attr.shapeAppearanceCornerExtraLargeIncreased, defaults.extraLargeIncreased),
        extraExtraLarge = shape(MaterialR.attr.shapeAppearanceCornerExtraExtraLarge, defaults.extraExtraLarge),
    )
}

/**
 * "Bold typography": display, headline, title and label roles take their M3 Expressive Emphasized
 * style (heavier weight, same size); body stays regular. Mirrors ThemeOverlay.Typography.Bold.
 */
private val BoldTypography =
    Typography().run {
        Typography(
            displayLarge = displayLargeEmphasized,
            displayMedium = displayMediumEmphasized,
            displaySmall = displaySmallEmphasized,
            headlineLarge = headlineLargeEmphasized,
            headlineMedium = headlineMediumEmphasized,
            headlineSmall = headlineSmallEmphasized,
            titleLarge = titleLargeEmphasized,
            titleMedium = titleMediumEmphasized,
            titleSmall = titleSmallEmphasized,
            labelLarge = labelLargeEmphasized,
            labelMedium = labelMediumEmphasized,
            labelSmall = labelSmallEmphasized,
        )
    }

/**
 * Mirrors TextAppearance.OneUI.{HeadlineLarge,TitleLarge,TitleMedium} in themes_overlay.xml on
 * top of [base] (default or bold), like ThemeOverlay.OneUI over ThemeOverlay.Typography.Bold.
 */
private fun oneUiTypography(base: Typography) =
    base.copy(
        headlineLarge =
            base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.01).em),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
    )

private val SharpShapes =
    RoundedCornerShape(0.dp).let { sharp ->
        Shapes(
            extraSmall = sharp,
            small = sharp,
            medium = sharp,
            large = sharp,
            extraLarge = sharp,
            largeIncreased = sharp,
            extraLargeIncreased = sharp,
            extraExtraLarge = sharp,
        )
    }

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isBlackNightTheme: Boolean = false,
    isAmoledPlus: Boolean = false,
    isOneUi: Boolean = false,
    isBoldTypography: Boolean = false,
    isHighContrast: Boolean = false,
    isRoundedEdges: Boolean = true,
    themeVersion: Int = 0,
    isExpressiveMotion: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var colorScheme = remember(context, darkTheme, themeVersion) { androidColorScheme(context, darkTheme) }

    // Belt-and-suspenders alongside ThemeOverlay.Black / ThemeOverlay.Black.Plus applied via
    // onApplyUserThemeResource. AMOLED mode forces background + base surfaces to black with
    // subtle near-black elevated containers. AMOLED+ additionally collapses all containers to
    // pure black so cards blend completely with the panel.
    val isNight = darkTheme || ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    if (isNight && (isBlackNightTheme || isAmoledPlus)) {
        colorScheme = colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceBright = if (isAmoledPlus) Color.Black else Color(0xFF1A1A1A),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = if (isAmoledPlus) Color.Black else Color(0xFF0B0B0B),
            surfaceContainerHigh = if (isAmoledPlus) Color.Black else Color(0xFF121212),
            surfaceContainerHighest = if (isAmoledPlus) Color.Black else Color(0xFF181818),
            surfaceVariant = if (isAmoledPlus) Color(0xFF121212) else Color(0xFF1E1E1E),
            scrim = Color.Black,
        )
    }

    val shapes =
        if (!isRoundedEdges) SharpShapes else remember(context, themeVersion) { androidShapes(context) }
    val baseTypography = if (isBoldTypography) BoldTypography else Typography()
    val typography = if (isOneUi) oneUiTypography(baseTypography) else baseTypography

    // M3 Expressive motion: springy, physics-based specs for every M3 component (switch thumbs,
    // sliders, tabs, segmented buttons, sheets). Standard scheme when the user turned it off.
    @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
    MaterialTheme(
        colorScheme = colorScheme,
        motionScheme =
            if (isExpressiveMotion) {
                androidx.compose.material3.MotionScheme.expressive()
            } else {
                androidx.compose.material3.MotionScheme.standard()
            },
        shapes = shapes,
        typography = typography,
        content = content
    )
}

/**
 * Container for unselected controls (segments, shape tiles). On a pure-black (AMOLED) surface the
 * whole surface-container ramp is black or near it, so those controls vanish; the tonal secondary
 * container stays visible there.
 */
val ColorScheme.controlContainer: Color
    get() = if (surface == Color.Black) secondaryContainer else surfaceContainerHighest

/** Content colour for [controlContainer]. */
val ColorScheme.onControlContainer: Color
    get() = if (surface == Color.Black) onSecondaryContainer else onSurface
