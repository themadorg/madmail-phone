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
(
    cd dist
    shasum -a 256 ./*.apk ./*.tar.gz engine-build.json source-revisions.txt toolchain.env > SHA256SUMS
)
