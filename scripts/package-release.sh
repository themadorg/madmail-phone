#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/check-release-version.sh
version=$(sed -n 's/^versionName=//p' release.properties)
source_dir="${MADMAIL_ENGINE_SOURCE:-$PWD/build/engine-source}"
[ -f app/build/outputs/apk/release/app-release.apk ] || { echo 'Signed release APK missing' >&2; exit 1; }
uv run python scripts/verify-apk.py app/build/outputs/apk/release/app-release.apk --signed
[ -d "$source_dir/.git" ] || { echo 'Engine source checkout missing' >&2; exit 1; }
mkdir -p dist
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
git archive --format=tar --prefix=mobile/ HEAD | tar -xf - -C "$stage"
# Build-generated hashes belong with this exact binary, not the previous host's binary.
cp app/src/main/assets/engine/releases.json "$stage/mobile/app/src/main/assets/engine/releases.json"
mkdir -p "$stage/engine"
git -C "$source_dir" archive HEAD | tar -xf - -C "$stage/engine"
mkdir -p "$stage/engine/external/madmail-admin-web"
git -C "$source_dir/external/madmail-admin-web" archive HEAD | tar -xf - -C "$stage/engine/external/madmail-admin-web"
cp native/patches/0001-android-storage-copy-fallback.patch "$stage/engine/android-storage.patch"
cp app/build/outputs/apk/release/app-release.apk "dist/madmail-mobile-$version-arm64.apk"
cp app/src/main/assets/engine/releases.json dist/engine-build.json
printf 'mobile=%s\nengine=%s\nadmin_web=%s\n' "$(git rev-parse HEAD)" "$(git -C "$source_dir" rev-parse HEAD)" "$(git -C "$source_dir/external/madmail-admin-web" rev-parse HEAD)" > dist/source-revisions.txt
cp scripts/toolchain.env dist/toolchain.env
tar -czf "dist/madmail-mobile-$version-source.tar.gz" -C "$stage" mobile engine
cat > dist/INSTALL.txt <<EOF
Madmail Mobile $version — ARM64 Android release candidate

Install madmail-mobile-$version-arm64.apk on Android 8 or later.
Allow installation from your file manager when Android asks.
The complete Madmail engine is bundled; no separate engine download is needed.
Hosting uses Wi-Fi/hotspot or a reachable VPN network. Internet access is needed
for remote SSH/API management and update discovery, not for extracting the engine.

This release is signed with the certificate SHA-256:
$EXPECTED_SIGNER_SHA256

An existing development/debug-signed installation cannot be updated with this
release key. Do not uninstall a phone holding mail data just to install this APK;
uninstalling deletes its private server data. Test on a clean device instead.
Future updates must retain the same release signing identity.

This is an alpha release. Root ports, guided backup restoration and complete
web-admin parity are not available. See mobile/docs/feature-status.md in the
source archive for tested capabilities and remaining work.

SHA256SUMS verifies the APK, source and provenance files. The source archive
contains the mobile app, pinned engine/admin sources and Android storage patch.
No signing keys, passwords or live server account data are included.
EOF
(
    cd dist
    shasum -a 256 "madmail-mobile-$version-arm64.apk" "madmail-mobile-$version-source.tar.gz" engine-build.json source-revisions.txt toolchain.env INSTALL.txt > SHA256SUMS
)
uv run python - "$version" <<'PY'
import hashlib
import sys
import zipfile
from pathlib import Path

version = sys.argv[1]
root = Path("dist")
bundle = root / f"madmail-mobile-{version}-arm64-full.zip"
names = [f"madmail-mobile-{version}-arm64.apk", f"madmail-mobile-{version}-source.tar.gz",
         "engine-build.json", "source-revisions.txt", "toolchain.env", "INSTALL.txt", "SHA256SUMS"]
with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name in names:
        archive.write(root / name, name)
with zipfile.ZipFile(bundle) as archive:
    assert archive.testzip() is None
digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
bundle.with_suffix(".zip.sha256").write_text(f"{digest}  {bundle.name}\n")
print(f"Shareable bundle: {bundle} ({bundle.stat().st_size:,} bytes)")
PY
