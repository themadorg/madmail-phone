#!/usr/bin/env bash
# Run interactively. Passwords are never echoed, logged, or written to disk.
set -euo pipefail
set +x
umask 077
key_dir="${MADMAIL_SIGNING_DIR:-$HOME/.local/share/madmail-release}"
mkdir -p "$key_dir"
chmod 700 "$key_dir"
keystore="$key_dir/madmail-release.p12"
[ ! -e "$keystore" ] || { echo "Signing key already exists: $keystore. Reuse it; do not rotate it for an update."; exit 1; }
if [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/keytool ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v keytool >/dev/null
printf 'Create the permanent Madmail release signing key.\nKeep this password and an offline backup of the key.\n\n'
read -r -s -p 'New signing password (at least 8 characters): ' MADMAIL_STORE_PASSWORD
printf '\n'
[ "${#MADMAIL_STORE_PASSWORD}" -ge 8 ] || { echo 'Use at least 8 characters.'; exit 1; }
read -r -s -p 'Confirm password: ' confirmation
printf '\n'
[ "$confirmation" = "$MADMAIL_STORE_PASSWORD" ] || { echo 'Passwords do not match; no key created.'; exit 1; }
unset confirmation
export MADMAIL_STORE_PASSWORD
trap 'unset MADMAIL_STORE_PASSWORD' EXIT
keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias madmail \
    -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Madmail Mobile Release' \
    -storepass:env MADMAIL_STORE_PASSWORD -keypass:env MADMAIL_STORE_PASSWORD
keytool -exportcert -keystore "$keystore" -alias madmail -storepass:env MADMAIL_STORE_PASSWORD \
    -file "$key_dir/release-certificate.der"
shasum -a 256 "$key_dir/release-certificate.der" | cut -d ' ' -f 1 > "$key_dir/ANDROID_SIGNER_SHA256.txt"
base64 < "$keystore" | tr -d '\r\n' > "$key_dir/ANDROID_KEYSTORE_BASE64.txt"
printf 'madmail\n' > "$key_dir/ANDROID_KEY_ALIAS.txt"
printf '\nSigning key created: %s\nGitHub import files: %s\n' "$keystore" "$key_dir"
printf 'Use the same password for ANDROID_STORE_PASSWORD and ANDROID_KEY_PASSWORD.\nDo not share this folder. It is not part of the app download.\n'
