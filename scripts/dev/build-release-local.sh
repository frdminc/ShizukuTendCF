#!/usr/bin/env bash
# Release-signed build on this machine, for installing over the fleet's release-signed app
# without waiting for CI. The signing secrets come from the 1Password Service vault (escrowed
# from the Actions secrets on 2026-10-05) into a temporary directory outside the checkout, which
# is removed on exit; nothing is written into the tree.
#   scripts/dev/build-release-local.sh [flavor]   (default: dropin; also shizukuplus)
# Needs the 1Password service-account token (OP_ENV, default below) and the op CLI.
set -euo pipefail
cd "$(dirname "$0")/../.."

flavor="${1:-dropin}"
OP_ENV="${OP_ENV:-$HOME/.config/secretspec/op-service-account-shizuku-keystore.env}"
VAULT="uo2uexgtc6rsapjs4hk4dpx2ky"
ITEM="ShizukuTendCF release signing"

set -a
# shellcheck source=/dev/null
. "$OP_ENV"
set +a

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"

op read "op://$VAULT/$ITEM/KEYSTORE" | base64 --decode > "$tmp/key.jks"
{
  printf 'KEYSTORE_PASSWORD=%s\n' "$(op read "op://$VAULT/$ITEM/KEYSTORE_PASSWORD")"
  printf 'KEYSTORE_ALIAS=%s\n' "$(op read "op://$VAULT/$ITEM/KEYSTORE_ALIAS")"
  printf 'KEYSTORE_ALIAS_PASSWORD=%s\n' "$(op read "op://$VAULT/$ITEM/KEYSTORE_ALIAS_PASSWORD")"
  printf 'KEYSTORE_FILE=%s\n' "$tmp/key.jks"
} > "$tmp/signing.properties"
unset OP_SERVICE_ACCOUNT_TOKEN

task=":manager:assemble${flavor^}Release"
SIGNING_PROPERTIES="$tmp/signing.properties" bash gradlew "$task" "${@:2}"
find "manager/build/outputs/apk/$flavor/release" -name '*.apk' -print
