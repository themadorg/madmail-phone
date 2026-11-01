#!/usr/bin/env bash
# Run after an unsigned release build. All file bytes are preserved during compression.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${MADMAIL_KEYSTORE:?Set MADMAIL_KEYSTORE}"
: "${MADMAIL_STORE_PASSWORD:?Set MADMAIL_STORE_PASSWORD}"
: "${MADMAIL_KEY_ALIAS:?Set MADMAIL_KEY_ALIAS}"
: "${MADMAIL_KEY_PASSWORD:?Set MADMAIL_KEY_PASSWORD}"
: "${EXPECTED_SIGNER_SHA256:?Set EXPECTED_SIGNER_SHA256}"
export MADMAIL_STORE_PASSWORD MADMAIL_KEY_PASSWORD
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
uv run python scripts/compress-apk.py app/build/outputs/apk/release/app-release-unsigned.apk "$stage/compressed.apk"
tools="$ANDROID_HOME/build-tools/35.0.0"
"$tools/zipalign" -P 16 -f 4 "$stage/compressed.apk" "$stage/aligned.apk"
"$tools/apksigner" sign --ks "$MADMAIL_KEYSTORE" --ks-key-alias "$MADMAIL_KEY_ALIAS" \
    --ks-pass env:MADMAIL_STORE_PASSWORD --key-pass env:MADMAIL_KEY_PASSWORD \
    --out "$stage/signed.apk" "$stage/aligned.apk"
uv run python scripts/verify-apk.py "$stage/signed.apk" --signed
cp "$stage/signed.apk" app/build/outputs/apk/release/app-release.apk
