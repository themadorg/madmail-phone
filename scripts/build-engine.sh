#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
revision=22db4602768364955670b0f08dd97f03db6e22cc
source_dir="${MADMAIL_ENGINE_SOURCE:-$root/build/engine-source}"
patch="$root/native/patches/0001-android-storage-copy-fallback.patch"
if [ ! -d "$source_dir/.git" ]; then
    mkdir -p "$(dirname "$source_dir")"
    git clone --no-checkout https://github.com/themadorg/madmail.git "$source_dir"
    git -C "$source_dir" checkout --detach "$revision"
fi
[ "$(git -C "$source_dir" rev-parse HEAD)" = "$revision" ] || { echo 'Engine source revision mismatch.' >&2; exit 1; }
if git -C "$source_dir" diff --quiet; then
    git -C "$source_dir" apply "$patch"
else
    # Never build unrelated edits or silently overwrite an existing checkout.
    diff -u "$patch" <(git -C "$source_dir" diff) >/dev/null || { echo 'Engine source has unexpected changes.' >&2; exit 1; }
fi
git -C "$source_dir" -c submodule.external/madmail-admin-web.url=https://github.com/themadorg/madmail-admin-web.git submodule update --init external/madmail-admin-web
if [ ! -f "$source_dir/external/madmail-admin-web/build/index.html" ]; then
    (
        cd "$source_dir/external/madmail-admin-web"
        bun install --frozen-lockfile
        bun run build
    )
fi
iroh_assets="$source_dir/crates/chatmail-iroh/assets"
iroh_sha=f8ef27631fac213b3ef668d02acd5b3e215292746a3fc71d90c63115446008b1
if [ ! -f "$iroh_assets/iroh-relay" ] || [ "$(shasum -a 256 "$iroh_assets/iroh-relay" | cut -d ' ' -f 1)" != "$iroh_sha" ]; then
    mkdir -p "$iroh_assets"
    curl --fail --location --proto '=https' --tlsv1.2 'https://github.com/n0-computer/iroh/releases/download/v0.35.0/iroh-relay-v0.35.0-aarch64-unknown-linux-musl.tar.gz' -o "$iroh_assets/relay.tar.gz"
    [ "$(shasum -a 256 "$iroh_assets/relay.tar.gz" | cut -d ' ' -f 1)" = '9e4bea5072f91844c4f09ea1f796a16081c28124538476373661954ff53bfbb5' ] || { echo 'Iroh archive checksum mismatch.' >&2; exit 1; }
    tar -xzf "$iroh_assets/relay.tar.gz" -C "$iroh_assets" ./iroh-relay
    rm "$iroh_assets/relay.tar.gz"
fi
printf '%s\n' 'v0.35.0' > "$iroh_assets/VERSION"
rustup target add aarch64-unknown-linux-musl
(
    cd "$source_dir"
    CHATMAIL_ADMIN_WEB_BUILD="$source_dir/external/madmail-admin-web/build" \
        PATH="$(dirname "$(rustup which cargo)"):$PATH" CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-4}" RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384' \
        uv tool run --from cargo-zigbuild==0.24.0 cargo-zigbuild build --release --locked --target aarch64-unknown-linux-musl --bin madmail
)
mkdir -p "$root/app/src/main/jniLibs/arm64-v8a"
cp "$source_dir/target/aarch64-unknown-linux-musl/release/madmail" "$root/app/src/main/jniLibs/arm64-v8a/libmadmail_v2_30_5.so"
uv run python - "$root" "$revision" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
binary = root / "app/src/main/jniLibs/arm64-v8a/libmadmail_v2_30_5.so"
patch = root / "native/patches/0001-android-storage-copy-fallback.patch"
entry = {
    "version": "2.30.5",
    "file": binary.name,
    "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
    "source": f"https://github.com/themadorg/madmail/tree/{sys.argv[2]}",
    "build": "android-storage-1",
    "patch_sha256": hashlib.sha256(patch.read_bytes()).hexdigest(),
}
(root / "app/src/main/assets/engine/releases.json").write_text(json.dumps([entry], indent=2) + "\n")
PY
