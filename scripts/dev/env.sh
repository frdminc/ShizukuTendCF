# shellcheck shell=bash
# Sourced (never executed) by the scripts in scripts/dev/: picks a JDK 21 and exports
# JAVA_HOME and PATH, on macOS and Linux alike.
#
# Order: an existing $JAVA_HOME that is a 21; then JDK homes under the search dirs
# (Linux /usr/lib/jvm, macOS /Library/Java/JavaVirtualMachines and the Homebrew
# openjdk@21 prefixes); then `/usr/libexec/java_home -v 21`, accepted only when its
# answer really is a 21 (on the build Mac it has returned JDK 27, which fails the build).
#
# SHIZUKU_JDK_SEARCH_DIRS (colon-separated) replaces the default search dirs. Each entry
# is either a JDK home itself or a directory holding JDK homes with "21" in the name.
# Bash 3.2 compatible (stock macOS); no GNU-only tools.

# Print the major version of the JDK at $1, or nothing if it is not a working JDK.
_shizuku_jdk_major() {
    [ -x "$1/bin/java" ] || return 0
    # The JVM may print "Picked up JAVA_TOOL_OPTIONS ..." or a VM warning before the
    # version line, so look for the line that has the version instead of taking line 1.
    "$1/bin/java" -version 2>&1 | sed -n -E '/version "/{s/.*version "([0-9]+).*/\1/p;q;}'
}

_shizuku_is_jdk21() {
    [ "$(_shizuku_jdk_major "$1")" = "21" ]
}

# Print the first JDK 21 home found under the search dirs, if any.
_shizuku_find_jdk21() {
    _sj_dirs="${SHIZUKU_JDK_SEARCH_DIRS:-/usr/lib/jvm:/Library/Java/JavaVirtualMachines:/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home:/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
    _sj_old_ifs="$IFS"
    IFS=:
    # shellcheck disable=SC2086
    set -- $_sj_dirs
    IFS="$_sj_old_ifs"
    for _sj_entry in "$@"; do
        [ -n "$_sj_entry" ] || continue
        for _sj_home in "$_sj_entry" "$_sj_entry"/*21* "$_sj_entry"/*21*/Contents/Home; do
            if _shizuku_is_jdk21 "$_sj_home"; then
                printf '%s\n' "$_sj_home"
                return 0
            fi
        done
    done
    return 1
}

_shizuku_pick_jdk21() {
    if [ -n "${JAVA_HOME:-}" ] && _shizuku_is_jdk21 "$JAVA_HOME"; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi
    _shizuku_find_jdk21 && return 0
    if [ -x /usr/libexec/java_home ]; then
        _sj_cand="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [ -n "$_sj_cand" ] && _shizuku_is_jdk21 "$_sj_cand"; then
            printf '%s\n' "$_sj_cand"
            return 0
        fi
    fi
    # Last resort: a `java` on PATH that is a 21 (SDKMAN, ~/.jdks, /opt, Termux...).
    _sj_bin="$(command -v java 2>/dev/null || true)"
    if [ -n "$_sj_bin" ]; then
        _sj_cand="$("$_sj_bin" -XshowSettings:properties -version 2>&1 |
            sed -n -E 's/^[[:space:]]*java\.home = (.*)$/\1/p' | head -1 || true)"
        if [ -n "$_sj_cand" ] && _shizuku_is_jdk21 "$_sj_cand"; then
            printf '%s\n' "$_sj_cand"
            return 0
        fi
    fi
    return 1
}

if _sj_picked="$(_shizuku_pick_jdk21)"; then
    if [ -n "${JAVA_HOME:-}" ] && [ "$JAVA_HOME" != "$_sj_picked" ]; then
        echo "env.sh: JAVA_HOME=$JAVA_HOME is not a JDK 21; using $_sj_picked" >&2
    fi
    JAVA_HOME="$_sj_picked"
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
else
    echo "error: JDK 21 not found (JAVA_HOME=${JAVA_HOME:-unset})." >&2
    echo "  Linux: install openjdk-21 (it lands in /usr/lib/jvm)." >&2
    echo "  macOS: brew install openjdk@21 (JDK 21 specifically; java_home may return a newer JDK)." >&2
    echo "  Or set JAVA_HOME to a JDK 21, or SHIZUKU_JDK_SEARCH_DIRS to where one lives." >&2
    exit 1
fi
unset _sj_picked _sj_bin _sj_dirs _sj_old_ifs _sj_entry _sj_home _sj_cand
unset -f _shizuku_jdk_major _shizuku_is_jdk21 _shizuku_find_jdk21 _shizuku_pick_jdk21
