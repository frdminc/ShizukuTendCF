#!/usr/bin/env bash
# Run lint + spotless. Use --fix to apply formatting.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/../.."
bash scripts/dev/check-notification-icons.sh
if [[ "${1:-}" == "--fix" ]]; then
    ./gradlew :manager:spotlessApply :manager:lint
else
    ./gradlew :manager:spotlessCheck :manager:lint
fi
