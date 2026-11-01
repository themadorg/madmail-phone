#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^versionName=//p' release.properties)
code=$(sed -n 's/^versionCode=//p' release.properties)
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]] || { echo 'Invalid release versionName' >&2; exit 1; }
[[ "$code" =~ ^[1-9][0-9]*$ ]] && [ "$code" -le 2100000000 ] || { echo 'Invalid release versionCode' >&2; exit 1; }
if [ "${GITHUB_REF_TYPE:-}" = tag ]; then
    [ "${GITHUB_REF_NAME:-}" = "v$version" ] || { echo 'Tag must match release.properties versionName' >&2; exit 1; }
fi
printf 'Release %s (Android version code %s)\n' "$version" "$code"
