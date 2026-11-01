<img src="assets/icon.svg" width="64" height="64" alt="">

# Madmail Mobile

A Kotlin Android host and management UI for a local Madmail server. Guests use Delta Chat; this app is not a messaging client.

## Current implementation

ARM64 Android hosting with upstream Madmail 2.30.5: foreground service, start/stop, authenticated readiness, live overview metrics, persistent private database/TLS identity, and packaged-version selection. Engine startup verifies its pinned SHA-256. A small native launcher terminates the engine if its owning Android process dies.

Android updates download a signed Madmail Mobile APK over HTTPS, check its package, signer and increasing app version, then hand installation to Android. Loose downloaded executables cannot be activated by a modern unrooted Android app. The version dialog loads stable releases directly from GitHub, marks installed versions, and offers Android packages when present. Releases without an APK remain visible. A signed package URL can also be supplied. Upstream has not published Android packages yet. Native updates create a cold private backup before first opening existing data with a newer engine. Automatic data downgrades are blocked.

Remote SSH management supports explicit host-key trust, password authentication held only for the connection, verified upstream release downloads, app-owned systemd installs, start/stop, authenticated overview through an SSH tunnel, retained-version inventory/removal, and cold backups before upgrades. It requires Linux ARM64/x86-64 and root or passwordless sudo. It refuses to adopt existing Madmail/maddy installations automatically. SSH key authentication, automatic update feeds, backup restoration UI, root port forwarding, and full admin settings editing are not implemented.

Phone ports: SMTP TLS **11465**, IMAP TLS **11993**, HTTPS **18443**. The internal admin HTTP listener is loopback-only on **18080**. Initial certificates are self-signed for `madmail.local`; automatic Delta Chat discovery/trust and real LAN client messaging still need validation. Never describe a running process as proof that guest onboarding works.

See [the product and architecture brainstorm](docs/architecture.md) for scope, root design, compatibility gates, and implementation milestones.

## Build

Requires JDK 17, Android SDK 35, NDK 28.2.13676358, and network access for initial dependencies. This build supports ARM64 Android devices. Set `ANDROID_HOME` or local `sdk.dir` in `local.properties`.

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./scripts/prepare-engine.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Minimum Android 8.0/API 26. This development bootstrap targets API 35; reassess target SDK and local-network permissions before distribution. No signing keys or server secrets belong in this repository.


Release APKs must use a stable signing key and increasing `-PappVersionCode=N -PappVersionName=X`. Never distribute the development signing key. The pinned engine binary is fetched by `scripts/prepare-engine.sh`, not committed. Its license is included in app assets and its exact source is [Madmail v2.30.5](https://github.com/themadorg/madmail/tree/v2.30.5).

See [engine lifecycle and validation](docs/engine-lifecycle.md) for storage paths, update behavior and the SSH integration fixture.
