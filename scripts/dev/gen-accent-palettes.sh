#!/usr/bin/env bash
# Generates manager/src/main/res/values/themes_accents.xml: full Material 3 colour schemes for
# every custom accent at Standard, Medium and High contrast, from MDC's bundled
# material-color-utilities. Run after changing a seed in scripts/dev/palette/GenerateAccents.java.
#
# Usage: bash scripts/dev/gen-accent-palettes.sh            # write the XML
#        bash scripts/dev/gen-accent-palettes.sh --compare  # print seeds under each variant
set -euo pipefail
cd "$(dirname "$0")/../.."

MDC_VERSION="$(sed -n 's/^material = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
AAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.google.android.material/material/${MDC_VERSION}" -name "material-${MDC_VERSION}.aar" 2>/dev/null | head -1)"
if [[ -z "$AAR" ]]; then
    echo "✗ material-${MDC_VERSION}.aar is not in the Gradle cache; run a build first." >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$AAR" classes.jar -d "$WORK"
javac -nowarn -d "$WORK/out" -cp "$WORK/classes.jar" scripts/dev/palette/GenerateAccents.java
if [[ "${1:-}" == "--compare" ]]; then
    java -cp "$WORK/out:$WORK/classes.jar" GenerateAccents --compare "${@:2}"
else
    java -cp "$WORK/out:$WORK/classes.jar" GenerateAccents > manager/src/main/res/values/themes_accents.xml
    echo "▸ wrote manager/src/main/res/values/themes_accents.xml (MDC ${MDC_VERSION})"
fi
