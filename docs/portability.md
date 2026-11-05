# Script portability (macOS and Linux)

Scripts under `scripts/` must run on macOS (BSD userland, bash 3.2) and on Linux (GNU userland)
without the caller installing `gsed`, `gtimeout` and friends.

## Rules

- No `gsed`, `gtimeout`, `gawk`, `ggrep`: use the plain name and portable syntax.
- `sed -i` always takes a suffix: `sed -i.bak '...' f && rm f.bak`. Use `sed -E`; never `\+`, `\|`, `\?` in basic regex.
- No `grep -P`: use `grep -E`, or Python for anything harder.
- No `readlink -f`: use `cd "$(dirname "$0")" && pwd -P`.
- No `stat -c` on the host (it is `stat -f` on BSD): use `wc -c < file` for sizes, or Python.
- No `echo -e`: use `printf`.
- No `xargs -r`: guard with `[ -n "$list" ]` or a `while read` loop.
- Write `head -n 5` / `tail -n 5`, not `head -5`. Avoid `date -d`, `sort -V`, `find -printf`.
- bash 3.2 only: no `declare -A`, `mapfile`/`readarray`, `${var,,}`/`${var^^}`, `&>>`, `|&`.
- With `set -u`, an empty array breaks `"${arr[@]}"` on 3.2: use `${arr[@]+"${arr[@]}"}`.
- `mktemp` needs a trailing `XXXXXX` template on BSD: `mktemp -d "${TMPDIR:-/tmp}/name.XXXXXX"`.

Exempt: commands that run on the Android device (`adb shell ...`, toybox) and steps that only run on
GitHub's Linux runners inside `.github/workflows/`. Everything a developer runs on their own machine is covered.
Linux cannot run bash 3.2, so the bash-version rules are checked by review; when a Mac is available, run the script there.

## When a GNU tool is unavoidable

Detect it, never assume it, and fail with a clear message:

```bash
if command -v gtimeout >/dev/null 2>&1; then TIMEOUT=gtimeout
elif command -v timeout >/dev/null 2>&1; then TIMEOUT=timeout
else echo "need timeout (brew install coreutils)" >&2; exit 1; fi
```

The same shape (`command -v gsed || sed`) applies to `gdate` and `gawk`.

## Simulating BSD on Linux

Put shims that reject GNU-only forms in a directory and put it first on `PATH`:

```bash
mkdir -p /tmp/bsd-shim
cat > /tmp/bsd-shim/head <<'SH'
#!/bin/bash
for a in "$@"; do case "$a" in -[0-9]*) echo "head: BSD wants -n" >&2; exit 64;; esac; done
exec /usr/bin/head "$@"
SH
chmod +x /tmp/bsd-shim/head
env PATH="/tmp/bsd-shim:$PATH" bash scripts/ci/major_release.sh
```

Do the same for `sed` (reject `-i` without a suffix), `grep` (reject `-P`), `readlink` (reject `-f`),
`stat` (reject `-c`) and `xargs` (reject `-r`). The output must match the unshimmed run.
Also run `bash -n script.sh` and `shellcheck -S warning script.sh`.

## JDK 21

Source `scripts/dev/env.sh`; it locates a JDK 21 on macOS (Homebrew arm64 and x86_64) and on Linux
and exports `JAVA_HOME`. `/usr/libexec/java_home -v 21` can return JDK 27 on the build Mac, so
scripts must not call it directly.
