#!/usr/bin/env bash
# Install the Android SDK pieces this repo's Gradle build needs (idempotent).
# Works on Linux and macOS, and as a cloud environment "Setup script".
#
#   scripts/dev/setup-android-sdk.sh            # install into $ANDROID_HOME or ~/android-sdk
#
# Prints the ANDROID_HOME it used on the last line, and writes local.properties
# (sdk.dir) in the repo root if that file is missing. Needs JDK 17+ and network
# access to dl.google.com and maven.google.com.
set -euo pipefail

CMDLINE_TOOLS_REV="${CMDLINE_TOOLS_REV:-11076708}"
PLATFORM="${ANDROID_PLATFORM:-android-37.0}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
CMAKE_VERSION="${ANDROID_CMAKE:-3.22.1}"

case "$(uname -s)" in
    Darwin) host=mac ;;
    Linux) host=linux ;;
    *) echo "setup-android-sdk: unsupported OS $(uname -s)" >&2; exit 1 ;;
esac

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
mkdir -p "$SDK"

sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$sdkmanager" ]; then
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    url="https://dl.google.com/android/repository/commandlinetools-${host}-${CMDLINE_TOOLS_REV}_latest.zip"
    echo "setup-android-sdk: downloading $url" >&2
    curl -fsSL -o "$tmp/cmdline-tools.zip" "$url"
    unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

export ANDROID_HOME="$SDK"
# `yes` exits with SIGPIPE when sdkmanager stops reading; that is expected.
{ yes 2>/dev/null || true; } | "$sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true

"$sdkmanager" --sdk_root="$SDK" \
    "platform-tools" \
    "platforms;$PLATFORM" \
    "build-tools;$BUILD_TOOLS" \
    "cmake;$CMAKE_VERSION" >&2

# repo.maven.apache.org rate-limits shared cloud egress (HTTP 429). Point Gradle at Google's
# Maven Central mirror via a user-level init script (outside the repo). MAVEN_MIRROR=0 skips it.
if [ "${MAVEN_MIRROR:-1}" != "0" ]; then
    init_dir="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
    mkdir -p "$init_dir"
    cat > "$init_dir/central-mirror.gradle" <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def fix = { repos ->
    repos.configureEach { r ->
        if (r instanceof MavenArtifactRepository && r.url.toString().startsWith('https://repo.maven.apache.org/maven2')) {
            r.url = mirror
        }
    }
}
gradle.beforeSettings { s ->
    fix(s.pluginManagement.repositories)
    fix(s.dependencyResolutionManagement.repositories)
}
gradle.allprojects { p ->
    fix(p.repositories)
    fix(p.buildscript.repositories)
}
GRADLE
fi

top="$(cd "$(dirname "$0")/../.." && pwd)"
if [ ! -f "$top/local.properties" ]; then
    printf 'sdk.dir=%s\n' "$SDK" > "$top/local.properties"
fi

echo "$SDK"
