import com.google.android.material.color.utilities.DynamicColor;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.MaterialDynamicColors;
import com.google.android.material.color.utilities.SchemeContent;
import com.google.android.material.color.utilities.SchemeFidelity;
import com.google.android.material.color.utilities.SchemeTonalSpot;
import com.google.android.material.color.utilities.SchemeVibrant;
import com.google.android.material.color.utilities.TonalPalette;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Writes themes_accents.xml to stdout. Run through scripts/dev/gen-accent-palettes.sh, which puts
 * MDC's classes.jar on the classpath. Seeds are each accent's previous light primary; Default is
 * the base theme's primary (md_theme_shizuku_light_primary) and only gets the contrast variants,
 * since its standard scheme comes from the base theme.
 */
public final class GenerateAccents {
    /** Accent key suffix (ThemeOverlay.Accent.<Name>) to seed colour. */
    private static final Map<String, Integer> SEEDS = new LinkedHashMap<>();

    static {
        SEEDS.put("Violet", 0xFF7B1FA2);
        SEEDS.put("Green", 0xFF00796B);
        SEEDS.put("Crimson", 0xFFC2185B);
        SEEDS.put("Ocean", 0xFF0288D1);
        SEEDS.put("Amber", 0xFF8B4000);
        SEEDS.put("Rose", 0xFF9D2C3D);
        SEEDS.put("Slate", 0xFF3B4CB8);
        SEEDS.put("Cyan", 0xFF00838F);
        SEEDS.put("Neon", 0xFF7C4DFF);
    }

    private static final int DEFAULT_SEED = 0xFF4355B9;

    private static final Map<String, Function<MaterialDynamicColors, DynamicColor>> ROLES = new LinkedHashMap<>();

    static {
        ROLES.put("colorPrimary", MaterialDynamicColors::primary);
        ROLES.put("colorOnPrimary", MaterialDynamicColors::onPrimary);
        ROLES.put("colorPrimaryContainer", MaterialDynamicColors::primaryContainer);
        ROLES.put("colorOnPrimaryContainer", MaterialDynamicColors::onPrimaryContainer);
        ROLES.put("colorPrimaryInverse", MaterialDynamicColors::inversePrimary);
        ROLES.put("colorPrimaryFixed", MaterialDynamicColors::primaryFixed);
        ROLES.put("colorPrimaryFixedDim", MaterialDynamicColors::primaryFixedDim);
        ROLES.put("colorOnPrimaryFixed", MaterialDynamicColors::onPrimaryFixed);
        ROLES.put("colorOnPrimaryFixedVariant", MaterialDynamicColors::onPrimaryFixedVariant);
        ROLES.put("colorSecondary", MaterialDynamicColors::secondary);
        ROLES.put("colorOnSecondary", MaterialDynamicColors::onSecondary);
        ROLES.put("colorSecondaryContainer", MaterialDynamicColors::secondaryContainer);
        ROLES.put("colorOnSecondaryContainer", MaterialDynamicColors::onSecondaryContainer);
        ROLES.put("colorSecondaryFixed", MaterialDynamicColors::secondaryFixed);
        ROLES.put("colorSecondaryFixedDim", MaterialDynamicColors::secondaryFixedDim);
        ROLES.put("colorOnSecondaryFixed", MaterialDynamicColors::onSecondaryFixed);
        ROLES.put("colorOnSecondaryFixedVariant", MaterialDynamicColors::onSecondaryFixedVariant);
        ROLES.put("colorTertiary", MaterialDynamicColors::tertiary);
        ROLES.put("colorOnTertiary", MaterialDynamicColors::onTertiary);
        ROLES.put("colorTertiaryContainer", MaterialDynamicColors::tertiaryContainer);
        ROLES.put("colorOnTertiaryContainer", MaterialDynamicColors::onTertiaryContainer);
        ROLES.put("colorTertiaryFixed", MaterialDynamicColors::tertiaryFixed);
        ROLES.put("colorTertiaryFixedDim", MaterialDynamicColors::tertiaryFixedDim);
        ROLES.put("colorOnTertiaryFixed", MaterialDynamicColors::onTertiaryFixed);
        ROLES.put("colorOnTertiaryFixedVariant", MaterialDynamicColors::onTertiaryFixedVariant);
        ROLES.put("colorError", MaterialDynamicColors::error);
        ROLES.put("colorOnError", MaterialDynamicColors::onError);
        ROLES.put("colorErrorContainer", MaterialDynamicColors::errorContainer);
        ROLES.put("colorOnErrorContainer", MaterialDynamicColors::onErrorContainer);
        ROLES.put("android:colorBackground", MaterialDynamicColors::background);
        ROLES.put("colorOnBackground", MaterialDynamicColors::onBackground);
        ROLES.put("colorSurface", MaterialDynamicColors::surface);
        ROLES.put("colorOnSurface", MaterialDynamicColors::onSurface);
        ROLES.put("colorSurfaceVariant", MaterialDynamicColors::surfaceVariant);
        ROLES.put("colorOnSurfaceVariant", MaterialDynamicColors::onSurfaceVariant);
        ROLES.put("colorSurfaceInverse", MaterialDynamicColors::inverseSurface);
        ROLES.put("colorOnSurfaceInverse", MaterialDynamicColors::inverseOnSurface);
        ROLES.put("colorSurfaceDim", MaterialDynamicColors::surfaceDim);
        ROLES.put("colorSurfaceBright", MaterialDynamicColors::surfaceBright);
        ROLES.put("colorSurfaceContainerLowest", MaterialDynamicColors::surfaceContainerLowest);
        ROLES.put("colorSurfaceContainerLow", MaterialDynamicColors::surfaceContainerLow);
        ROLES.put("colorSurfaceContainer", MaterialDynamicColors::surfaceContainer);
        ROLES.put("colorSurfaceContainerHigh", MaterialDynamicColors::surfaceContainerHigh);
        ROLES.put("colorSurfaceContainerHighest", MaterialDynamicColors::surfaceContainerHighest);
        ROLES.put("colorOutline", MaterialDynamicColors::outline);
        ROLES.put("colorOutlineVariant", MaterialDynamicColors::outlineVariant);
    }

    /** Contrast level name suffix ("" for standard) to MCU contrast level. */
    private static final String[][] CONTRASTS = {{"", "0.0"}, {".MediumContrast", "0.5"}, {".HighContrast", "1.0"}};

    private enum Variant { TONAL_SPOT, CONTENT, FIDELITY, VIBRANT, ACCENT }

    /** Primary chroma ceiling for the ACCENT variant (TonalSpot uses a fixed 36). */
    static double accentChromaCap = 64;

    /** The variant the generated XML uses (see ACCENT in scheme()). --compare shows the others. */
    private static final Variant VARIANT = Variant.ACCENT;

    private static DynamicScheme scheme(Variant variant, int seed, boolean dark, double contrast) {
        Hct hct = Hct.fromInt(seed);
        switch (variant) {
            case TONAL_SPOT:
                return new SchemeTonalSpot(hct, dark, contrast);
            case FIDELITY:
                return new SchemeFidelity(hct, dark, contrast);
            case VIBRANT:
                return new SchemeVibrant(hct, dark, contrast);
            case ACCENT: {
                // The CorePalette recipe the base theme was built with (primary chroma >= 48, muted
                // secondary, tertiary hue +60, neutrals 4/8) on TonalSpot's tone mapping. Primary
                // keeps the seed's chroma up to a cap, so an accent keeps its colour without
                // Content's full-strength containers or Vibrant's neon greens. With the Default seed
                // it reproduces the base theme, so Default's contrast variants match its look.
                double hue = hct.getHue();
                double chroma = Math.max(48, Math.min(hct.getChroma(), accentChromaCap));
                return new DynamicScheme(hct, com.google.android.material.color.utilities.Variant.TONAL_SPOT, dark, contrast,
                    TonalPalette.fromHueAndChroma(hue, chroma),
                    TonalPalette.fromHueAndChroma(hue, 16),
                    TonalPalette.fromHueAndChroma((hue + 60) % 360, 24),
                    TonalPalette.fromHueAndChroma(hue, 4),
                    TonalPalette.fromHueAndChroma(hue, 8));
            }
            default:
                return new SchemeContent(hct, dark, contrast);
        }
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    public static void main(String[] args) {
        MaterialDynamicColors colors = new MaterialDynamicColors();
        if (args.length > 1) accentChromaCap = Double.parseDouble(args[1]);
        if (args.length > 0 && args[0].equals("--compare")) {
            System.out.println("accent   seed     | variant     light-primary dark-primary light-container light-secondary-container");
            for (Map.Entry<String, Integer> e : SEEDS.entrySet()) {
                for (Variant v : Variant.values()) {
                    System.out.printf(Locale.ROOT, "%-8s %s | %-11s %s       %s      %s         %s%n", e.getKey(), hex(e.getValue()), v,
                        hex(colors.primary().getArgb(scheme(v, e.getValue(), false, 0))),
                        hex(colors.primary().getArgb(scheme(v, e.getValue(), true, 0))),
                        hex(colors.primaryContainer().getArgb(scheme(v, e.getValue(), false, 0))),
                        hex(colors.secondaryContainer().getArgb(scheme(v, e.getValue(), false, 0))));
                }
            }
            DynamicScheme dl = scheme(Variant.ACCENT, DEFAULT_SEED, false, 0), dd = scheme(Variant.ACCENT, DEFAULT_SEED, true, 0);
            System.out.println("Default ACCENT light primary/secondary/tertiary/surface " + hex(colors.primary().getArgb(dl)) + " "
                + hex(colors.secondary().getArgb(dl)) + " " + hex(colors.tertiary().getArgb(dl)) + " " + hex(colors.surface().getArgb(dl))
                + " | dark " + hex(colors.primary().getArgb(dd)) + " " + hex(colors.secondary().getArgb(dd)) + " "
                + hex(colors.tertiary().getArgb(dd)) + " " + hex(colors.surface().getArgb(dd)));
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        out.append("<!-- Generated by scripts/dev/gen-accent-palettes.sh from the seeds in\n");
        out.append("     scripts/dev/palette/GenerateAccents.java (").append(VARIANT).append(" variant). Do not edit by hand. -->\n");
        out.append("<resources>\n");
        for (Map.Entry<String, Integer> e : SEEDS.entrySet()) {
            for (String[] c : CONTRASTS) {
                appendStyle(out, colors, "ThemeOverlay.Accent." + e.getKey() + c[0], e.getValue(), false, Double.parseDouble(c[1]));
                appendStyle(out, colors, "ThemeOverlay.Accent." + e.getKey() + ".Dark" + c[0], e.getValue(), true, Double.parseDouble(c[1]));
            }
        }
        for (int i = 1; i < CONTRASTS.length; i++) {
            String[] c = CONTRASTS[i];
            appendStyle(out, colors, "ThemeOverlay.Accent.Default" + c[0], DEFAULT_SEED, false, Double.parseDouble(c[1]), VARIANT);
            appendStyle(out, colors, "ThemeOverlay.Accent.Default.Dark" + c[0], DEFAULT_SEED, true, Double.parseDouble(c[1]), VARIANT);
        }
        // Lookup arrays for ThemeDelegateImpl: light Standard/Medium/High, then dark. Default has no
        // standard entry (@null): its standard scheme is the base theme.
        for (String accent : SEEDS.keySet()) {
            appendArray(out, accent, true);
        }
        appendArray(out, "Default", false);
        out.append("</resources>\n");
        System.out.print(out);
    }

    private static void appendArray(StringBuilder out, String accent, boolean hasStandard) {
        out.append("    <array name=\"theme_overlay_accent_").append(accent.toLowerCase(Locale.ROOT)).append("\">\n");
        for (String mode : new String[] {"", ".Dark"}) {
            for (String[] c : CONTRASTS) {
                boolean none = !hasStandard && c[0].isEmpty();
                out.append("        <item>").append(none ? "@null" : "@style/ThemeOverlay.Accent." + accent + mode + c[0]).append("</item>\n");
            }
        }
        out.append("    </array>\n");
    }

    private static void appendStyle(StringBuilder out, MaterialDynamicColors colors, String name, int seed, boolean dark, double contrast) {
        appendStyle(out, colors, name, seed, dark, contrast, VARIANT);
    }

    private static void appendStyle(StringBuilder out, MaterialDynamicColors colors, String name, int seed, boolean dark, double contrast,
                                    Variant variant) {
        DynamicScheme s = scheme(variant, seed, dark, contrast);
        out.append("    <style name=\"").append(name).append("\" parent=\"\">\n");
        for (Map.Entry<String, Function<MaterialDynamicColors, DynamicColor>> r : ROLES.entrySet()) {
            out.append("        <item name=\"").append(r.getKey()).append("\">").append(hex(r.getValue().apply(colors).getArgb(s))).append("</item>\n");
        }
        out.append("    </style>\n");
    }
}
