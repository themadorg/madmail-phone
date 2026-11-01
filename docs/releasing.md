# Building a release candidate

The repository is prepared for GitHub Actions; this checkout has no remote and no workflow has been run on GitHub. Push it to your chosen repository. No workflow publishes a GitHub Release or uploads to a store automatically.

## Workflows

- **Android CI** runs on branch pushes, pull requests and manual dispatch. It installs JDK 17, SDK/build-tools 35, pinned NDK/Bun/uv, and Rust from `scripts/toolchain.env`; builds the pinned, patched engine and embedded admin dashboard; runs JVM tests and lint for both variants; builds debug and unsigned release APKs; verifies engine hashes, ARM64 ELF/16 KiB alignment, metadata and size; exercises signing with a disposable test key. Reports and the debug APK are retained for 14 days. PRs receive no signing secrets.
- **Android release candidate** runs on `v*` tags or manual dispatch. A tag must match `release.properties`. It validates first, then uses credentials only in the signing step. The expected certificate fingerprint must match and Android Debug certificates are rejected. It uploads the APK, source archive, exact engine manifest, source revisions, tool versions and SHA256SUMS for 30 days. Download and preserve these before artifact expiry.

Actions are pinned by commit; Dependabot proposes updates. Runs have timeouts, cancellation rules, read-only repository permissions and checkout credentials disabled. There is no `pull_request_target`, privileged PR execution or implicit publication. Configure the `release` environment to permit only trusted release tags (and your trusted branch if manual builds are needed); configure reviewers if desired. GitHub environment controls are repository settings and cannot be supplied by this checkout.

## One-time signing setup

Create or reuse a permanent Android release signing key, keep an offline backup, and put these secrets in GitHub's **release** environment:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the release keystore |
| `ANDROID_STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Set environment variable `ANDROID_SIGNER_SHA256` to the independently verified signing certificate SHA-256 fingerprint (hex, with or without colons). Never use the debug key or the test fixture's disposable key. The workflow decodes the key into a private runner-temporary file and removes it on exit. No signing key has been generated or configured by this task.

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
