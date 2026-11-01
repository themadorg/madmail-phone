#!/usr/bin/env bash
# Disposable signing fixture; never distribute its APK or key.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
umask 077
export FIXTURE_PASSWORD
FIXTURE_PASSWORD=$(uv run python -c 'import secrets; print(secrets.token_hex(24))')
keytool -genkeypair -keystore "$stage/fixture.jks" -alias fixture -keyalg RSA -keysize 2048 -validity 1 \
    -dname 'CN=Disposable Madmail CI Fixture' -storepass:env FIXTURE_PASSWORD -keypass:env FIXTURE_PASSWORD >/dev/null 2>&1
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
uv run python scripts/compress-apk.py app/build/outputs/apk/release/app-release-unsigned.apk "$stage/compressed.apk"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -P 16 -f 4 "$stage/compressed.apk" "$stage/aligned.apk"
"$signer" sign --ks "$stage/fixture.jks" --ks-pass env:FIXTURE_PASSWORD --key-pass env:FIXTURE_PASSWORD \
    --out "$stage/fixture.apk" "$stage/aligned.apk"
export EXPECTED_SIGNER_SHA256
EXPECTED_SIGNER_SHA256=$("$signer" verify --print-certs "$stage/fixture.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
uv run python scripts/verify-apk.py "$stage/fixture.apk" --signed
if EXPECTED_SIGNER_SHA256=$(printf '%064d' 0) uv run python scripts/verify-apk.py "$stage/fixture.apk" --signed > "$stage/rejection.log" 2>&1; then
    echo 'ERROR: Wrong signer was accepted' >&2; exit 1
fi
grep -q 'APK signer differs' "$stage/rejection.log"
echo 'PASS: wrong signing identity rejected; disposable key removed on exit.'
