#!/usr/bin/env bash
# Run interactively by the key owner. Gives GitHub Actions its own copy of the existing release key.
#
# The copy is re-encrypted under a fresh random password from openssl, and that password goes straight
# into the repository's `release` environment: it is never printed, logged or written to disk. The signing
# key itself is unchanged, so CI-signed APKs keep updating earlier releases. The local keystore and its
# password stay as they are. Run again any time to rotate the CI password; the old one simply stops existing.
set -euo pipefail
set +x
umask 077
repo="${1:?Usage: scripts/github-signing-secrets.sh OWNER/REPOSITORY}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Use OWNER/REPOSITORY' >&2; exit 1; }
key_dir="${MADMAIL_SIGNING_DIR:-$HOME/.local/share/madmail-release}"
keystore="$key_dir/madmail-release.p12"
alias_name="$(tr -d '[:space:]' < "$key_dir/ANDROID_KEY_ALIAS.txt")"
expected="$(tr -d '[:space:]:' < "$key_dir/ANDROID_SIGNER_SHA256.txt" | tr 'A-F' 'a-f')"
[ -s "$keystore" ] && [ -n "$alias_name" ] && [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "Signing key files missing in $key_dir" >&2; exit 1; }
if [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/keytool ]; then
    export PATH="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH"
fi
for tool in keytool openssl gh; do command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }; done
gh auth status --hostname github.com >/dev/null

work="$(mktemp -d)"
cleanup() { rm -rf "$work"; unset MADMAIL_STORE_PASSWORD CI_STORE_PASSWORD; }
trap cleanup EXIT

printf 'Give %s (environment: release) a CI copy of the Madmail signing key.\n' "$repo"
read -r -s -p 'Existing signing password: ' MADMAIL_STORE_PASSWORD
printf '\n'
export MADMAIL_STORE_PASSWORD
keytool -list -keystore "$keystore" -alias "$alias_name" -storepass:env MADMAIL_STORE_PASSWORD >/dev/null

# 36 random bytes, base64 without the characters that need quoting anywhere: about 48 letters and digits.
CI_STORE_PASSWORD="$(openssl rand -base64 36 | tr -d '\n/+=')"
[ "${#CI_STORE_PASSWORD}" -ge 40 ] || { echo 'openssl produced too short a password' >&2; exit 1; }
export CI_STORE_PASSWORD
# PKCS12 keeps one password for the store and its key, so both secrets carry the same value.
keytool -importkeystore -noprompt -srckeystore "$keystore" -srcstoretype PKCS12 -srcalias "$alias_name" \
    -srcstorepass:env MADMAIL_STORE_PASSWORD -destkeystore "$work/ci.p12" -deststoretype PKCS12 \
    -destalias "$alias_name" -deststorepass:env CI_STORE_PASSWORD >/dev/null 2>&1 || { echo 'Could not copy the key; nothing uploaded.' >&2; exit 1; }
unset MADMAIL_STORE_PASSWORD

# The copy must hold the very certificate earlier releases were signed with, or updates would break.
keytool -exportcert -keystore "$work/ci.p12" -alias "$alias_name" -storepass:env CI_STORE_PASSWORD -file "$work/cert.der" >/dev/null 2>&1
actual="$(openssl dgst -sha256 -r "$work/cert.der" | cut -d ' ' -f 1)"
[ "$actual" = "$expected" ] || { echo "CI copy has signer $actual, expected $expected; nothing uploaded." >&2; exit 1; }

# Secrets are only released to `v*` tags, so a branch or pull request can never reach them.
gh api -X PUT "repos/$repo/environments/release" --input - >/dev/null <<'JSON'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON
if ! gh api "repos/$repo/environments/release/deployment-branch-policies" --jq '.branch_policies[] | select(.type == "tag" and .name == "v*") | .id' | grep -q .; then
    gh api -X POST "repos/$repo/environments/release/deployment-branch-policies" -f name='v*' -f type=tag >/dev/null
fi

# Values go through stdin, never the command line, so they never appear in the process list.
base64 < "$work/ci.p12" | tr -d '\r\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo" --env release
printf '%s' "$alias_name" | gh secret set ANDROID_KEY_ALIAS --repo "$repo" --env release
printf '%s' "$CI_STORE_PASSWORD" | gh secret set ANDROID_STORE_PASSWORD --repo "$repo" --env release
printf '%s' "$CI_STORE_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$repo" --env release
printf '%s' "$expected" | gh variable set ANDROID_SIGNER_SHA256 --repo "$repo" --env release
printf 'Done. CI signs as %s.\nThe CI password exists only in GitHub; run this again to rotate it.\n' "$expected"
