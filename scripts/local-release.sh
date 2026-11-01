#!/usr/bin/env bash
# Interactive local release. Keep the password only in this process's environment.
set -euo pipefail
set +x
cd "$(dirname "$0")/.."
key_dir="${MADMAIL_SIGNING_DIR:-$HOME/.local/share/madmail-release}"
[ -s "$key_dir/madmail-release.p12" ] || { echo 'Create the release key first.' >&2; exit 1; }
[ -s "$key_dir/ANDROID_SIGNER_SHA256.txt" ]
if [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    export PATH="$JAVA_HOME/bin:$PATH"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
source_dir="${MADMAIL_ENGINE_SOURCE:-$PWD/build/engine-source}"
[ -d "$source_dir/.git" ] || { echo 'Set MADMAIL_ENGINE_SOURCE to the pinned engine checkout for source packaging.' >&2; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo 'Commit tracked changes before packaging corresponding source.' >&2; exit 1; }
printf 'Building and checking the release before requesting your password…\n'
env -u MADMAIL_KEYSTORE -u MADMAIL_STORE_PASSWORD -u MADMAIL_KEY_ALIAS -u MADMAIL_KEY_PASSWORD \
    ./gradlew --no-daemon :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
printf '\nSign with your existing Madmail release key.\n'
read -r -s -p 'Signing password: ' MADMAIL_STORE_PASSWORD
printf '\n'
export MADMAIL_STORE_PASSWORD
export MADMAIL_KEY_PASSWORD="$MADMAIL_STORE_PASSWORD"
export MADMAIL_KEYSTORE="$key_dir/madmail-release.p12"
export MADMAIL_KEY_ALIAS=madmail
export EXPECTED_SIGNER_SHA256
EXPECTED_SIGNER_SHA256=$(cat "$key_dir/ANDROID_SIGNER_SHA256.txt")
trap 'unset MADMAIL_STORE_PASSWORD MADMAIL_KEY_PASSWORD' EXIT
./scripts/sign-release.sh
./scripts/package-release.sh
printf '\nRelease complete. Share the APK or the -full.zip in:\n%s/dist\n' "$PWD"
