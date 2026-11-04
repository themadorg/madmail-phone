# Building a release candidate

Releases are built, signed and published by GitHub Actions in `themadorg/madmail-phone`. Nothing is uploaded to an app store.

To ship a version:

1. Set `versionCode` (always higher than the last) and `versionName` in `release.properties`.
2. Write the release notes in `docs/releases/<versionName>.md`.
3. Commit, push, then push an annotated tag `v<versionName>` on that commit.

The **Android release** workflow does the rest. Versions before 1.0, and any version with a suffix such as `-alpha.5`, are published as prereleases.

## Workflows

- **Android CI** runs on branch pushes, pull requests and manual dispatch. It installs JDK 17, SDK/build-tools 35, pinned NDK/Bun/uv, and Rust from `scripts/toolchain.env`; builds the pinned, patched engine and embedded admin dashboard; runs JVM tests and lint for both variants; builds debug and unsigned release APKs; verifies engine hashes, ARM64 ELF/16 KiB alignment, metadata and size; exercises signing with a disposable test key. Reports and the debug APK are retained for 14 days. PRs receive no signing secrets.
- **Android release** runs on `v*` tags (and manual dispatch, which builds but never publishes). The tag must match `release.properties`.
  - The `release` job validates first and uses the signing credentials only in the signing step. The certificate fingerprint must match, and Android Debug certificates are rejected. It uploads the APK, source archive, engine manifest, source revisions, tool versions and SHA256SUMS as a 30-day artifact.
  - The `publish` job never sees the signing secrets. It downloads that artifact, checks every checksum, and creates the GitHub Release from `docs/releases/<version>.md` with all files attached. It is the only job that can write to the repository.

Actions are pinned by commit; Dependabot proposes updates. Runs have timeouts, cancellation rules, read-only repository permissions and checkout credentials disabled. There is no `pull_request_target`, privileged PR execution or implicit publication. Configure the `release` environment to permit only trusted release tags (and your trusted branch if manual builds are needed); configure reviewers if desired. GitHub environment controls are repository settings and cannot be supplied by this checkout.

## One-time signing setup

Run `./scripts/create-release-key.sh` in your terminal. It prompts twice without echoing the password, refuses to overwrite an existing key, and saves the key and private GitHub import files under `~/.local/share/madmail-release/` with restricted permissions. The alias is `madmail`; the store and key use the same password. Back up that directory securely and retain the password separately. Do not include it in release uploads.

To give CI the key, run `./scripts/github-signing-secrets.sh themadorg/madmail-phone` in your terminal while `gh` is signed in. It does the following:

- asks once for your existing key password;
- copies the key into a new keystore under a fresh 48-character password from `openssl rand`;
- checks that the copy's certificate matches `ANDROID_SIGNER_SHA256.txt`;
- limits the `release` environment to `v*` tags;
- sends the keystore, alias and password to that environment's secrets, and the fingerprint to its variables.

> **Warning:** on a private repository on GitHub Free, tags can't be protected and the environment can't require a reviewer. Anyone with write access can push a `v*` tag pointing at an edited workflow and read the signing key. Give write access only to people you would trust with the key.

The signing key never changes, so CI builds keep updating earlier releases. The CI password is never printed or saved locally; it exists only in GitHub. Run the script again to rotate it. Your local keystore and its password are untouched.

After key creation, `./scripts/local-release.sh` builds/tests the release, prompts privately for the existing password, and signs/packages the APK and full sharing ZIP. Set `MADMAIL_ENGINE_SOURCE` if your pinned engine checkout is outside `build/engine-source`. The password is used only for that process and is not saved. Commit tracked source changes first so the source archive matches the release.

Create or reuse a permanent Android release signing key, keep an offline backup, and put these secrets in GitHub's **release** environment:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the release keystore |
| `ANDROID_STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Set environment variable `ANDROID_SIGNER_SHA256` to the independently verified signing certificate SHA-256 fingerprint (hex, with or without colons). Never use the debug key or the test fixture's disposable key. The workflow decodes the key into a private runner-temporary file and removes it on exit.

The existing phone development installation uses the debug signer. A production-signed APK cannot update it in place; do not uninstall that installation to test a release, since doing so deletes its server data. Use another device or a separate test application ID for release acceptance. Guided backup/restore and signing-key migration are not implemented.

## Version and build

Edit `release.properties` before each distributed version. Increase `versionCode` monotonically, including prereleases. `versionName` is the user-facing semantic version. A repeated manual workflow run does not automatically increment the code. Tags should be protected against modification.

Local unsigned validation:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./scripts/prepare-engine.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
uv run python scripts/verify-apk.py app/build/outputs/apk/release/app-release-unsigned.apk
./tests/release-signing.sh
```

For a locally signed build, supply `MADMAIL_KEYSTORE`, `MADMAIL_STORE_PASSWORD`, `MADMAIL_KEY_ALIAS`, `MADMAIL_KEY_PASSWORD` securely through the environment, then run `./gradlew --no-daemon -PrequireReleaseSigning=true :app:assembleRelease`. Do not put secrets in command-line properties or the repository. `EXPECTED_SIGNER_SHA256=... uv run python scripts/verify-apk.py app/build/outputs/apk/release/app-release.apk --signed` verifies the output.

For the shareable compressed artifact, build unsigned first, then supply those signing variables plus `EXPECTED_SIGNER_SHA256` and run `./scripts/sign-release.sh`. This is also the GitHub release path: it recompresses deflated entries at level 9, verifies every extracted file is byte-identical, preserves stored entries, runs 16 KiB-aware zipalign, signs, and validates the final APK. Compression must precede signing. No engine/service feature is removed. `./scripts/package-release.sh` then collects the APK and corresponding source/provenance.

## Native provenance and source

The engine is compiled from a pinned upstream commit, locked Cargo dependencies, the checked-in Android patch, a pinned admin submodule, and a checksum-verified Iroh relay. Fresh native builds regenerate `assets/engine/releases.json` because different hosts/toolchains may produce different bytes. The packaged manifest describes the actual binary, and the APK validator checks it. This is source-pinned provenance, not a claim of bit-reproducible cross-host builds. Review generated manifest changes; never silently commit them as unrelated code changes.

The source archive includes this repository, the engine at its exact revision, the admin dashboard source and the Android patch. Apply `engine/android-storage.patch` to the engine tree when rebuilding it; the mobile build script normally checks out and patches its own source tree. Cargo/Bun lockfiles and the build scripts identify fetched dependencies and the embedded Iroh release. Preserve upstream license notices; the embedded engine license is shipped in the APK. The mobile app's own distribution license still needs an explicit owner decision; no license for your original code is invented here.

## Acceptance boundaries

This prepares an **initial ARM64 release candidate**, not a claim that every product feature is production-complete. See [feature status](feature-status.md). Before public distribution, run the signed APK on a clean device, validate a subsequent same-signer upgrade with retained data, and complete the network/background/VPS acceptance relevant to the advertised use case. Root forwarding, guided restore and full web-admin parity remain unavailable. No Play Store submission or store-policy review is included.

## Recorded local checks · 2026-11-01

- Debug and unsigned release builds succeeded; 24 JVM tests and both lint variants passed.
- The 36 MiB unsigned ARM64 release APK passed metadata, embedded engine hash, updater size limit, ELF LOAD alignment and ZIP alignment checks.
- A disposable non-debug signing certificate passed signature verification; an unexpected signer was rejected. The fixture key/APK were deleted. Required signing with missing credentials failed as intended.
- `actionlint`, ShellCheck (`-x`), Ruff, isort and `git diff --check` passed. Matching release tag/version validation passed.
- The APK checks used the already-built, device-tested patched engine. A fresh Linux engine compilation, hosted Actions execution, permanent-key signing and final source/archive artifact generation remain unverified until the workflow runs after push. No release APK was installed on the user's phone.
