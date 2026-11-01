#!/usr/bin/env bash
# Explicitly run by the owner to transfer an existing key to a chosen repository.
set -euo pipefail
set +x
umask 077
repo="${1:?Usage: scripts/github-signing-secrets.sh OWNER/REPOSITORY}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Use OWNER/REPOSITORY'; exit 1; }
key_dir="${MADMAIL_SIGNING_DIR:-$HOME/.local/share/madmail-release}"
[ -s "$key_dir/ANDROID_KEYSTORE_BASE64.txt" ]
[ -s "$key_dir/ANDROID_SIGNER_SHA256.txt" ]
if [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/keytool ]; then
    export PATH="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH"
fi
printf 'Upload the existing signing identity to %s, environment: release\n' "$repo"
read -r -s -p 'Existing signing password: ' MADMAIL_STORE_PASSWORD
printf '\n'
export MADMAIL_STORE_PASSWORD
trap 'unset MADMAIL_STORE_PASSWORD' EXIT
keytool -list -keystore "$key_dir/madmail-release.p12" -alias madmail -storepass:env MADMAIL_STORE_PASSWORD >/dev/null
# Environment must exist; configure its trusted refs/review rules in GitHub first.
gh api "repos/$repo/environments/release" >/dev/null
gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo" --env release < "$key_dir/ANDROID_KEYSTORE_BASE64.txt"
gh secret set ANDROID_KEY_ALIAS --repo "$repo" --env release < "$key_dir/ANDROID_KEY_ALIAS.txt"
printf '%s' "$MADMAIL_STORE_PASSWORD" | gh secret set ANDROID_STORE_PASSWORD --repo "$repo" --env release
printf '%s' "$MADMAIL_STORE_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$repo" --env release
gh variable set ANDROID_SIGNER_SHA256 --repo "$repo" --env release < "$key_dir/ANDROID_SIGNER_SHA256.txt"
printf 'Signing credentials configured for %s / release.\n' "$repo"
