# Engine lifecycle

## Android

`LocalEngine` serializes lifecycle/version changes. `HostingService` owns the process, foreground notification and wake lock. The UI observes state flows. Startup checks the packaged SHA-256, rejects data downgrades, writes first-run config only when absent, checks port availability, and launches the upstream static ARM64 executable from Android's extracted native library directory. The parent-death launcher sets `PR_SET_PDEATHSIG` before exec. Native output is drained without logging credentials or account data.

Readiness requires an authenticated `/admin/overview` response and reachable SMTP, IMAP and HTTPS ports; monitoring refreshes every five seconds. Stop sends SIGTERM, waits up to 15 seconds, then forces termination with an explicit failure state if needed. The foreground service is sticky and survives task removal: Android can recreate it after process reclamation while a persisted user-started session remains requested. Stop clears that request before shutdown; engine failures clear it too, avoiding crash loops. There is no boot receiver, automatic UI-triggered restart, or force-stop bypass. Recovery timing belongs to Android, so uninterrupted operation is not guaranteed.

Overview exposes **Allow background hosting**, opening Android's battery-optimization exemption prompt. The app checks the actual exemption again on resume and shows **Background access allowed** only when granted. A partial wake lock is held only during hosting. The exemption lets the core local-network server request CPU/network availability during idle; it is not an OEM-wide or indefinite-uptime guarantee. Leaving the app or dismissing its task is distinct from Android's explicit Force stop/Stop app actions, which remain respected. Background hosting uses additional battery.

`tests/background_hosting.py` uses bounded, pinned-certificate probes of HTTPS/IMAP/SMTP through temporary USB forwards. Its `recover` mode deliberately kills only the Madmail application process and requires Android to restore all three listeners without an app launch; `stopped` checks that an explicit UI stop stays stopped; `monitor` checks unchanged process identity and listeners while the UI is backgrounded or the screen is off. UI and battery-setting actions remain manual/device-panel actions, not ADB shortcuts. Temporary forwards are removed on completion.

Paths under private app files:

- `madmail/chatmail.toml`: rewritten on every start from the selected guest IP (`primary_domain = "[ip]"`). Every earlier domain stays in `local_domains`; the previous file and certificate are kept under `backups/<timestamp>-domain`.
- `madmail/data`: upstream databases, account/mail data, admin token and certificates.
- `madmail/backups/<timestamp>-<version>`: cold data/config copy before changing the version that opens existing data.

`lastStarted` is recorded before launch, because a failed startup may still have performed a database migration. Do not infer that a failed health check permits safe binary rollback. Backup restore is currently manual and must restore matching config, data and engine version together while stopped. Uninstalling the app deletes private server data.

The default bundled release is 2.30.5, source revision `22db4602768364955670b0f08dd97f03db6e22cc`, rebuilt with `native/patches/0001-android-storage-copy-fallback.patch` as build `android-storage-1`. `app/src/main/assets/engine/releases.json` records its exact binary and patch hashes. Both the patched static ARM64 Linux-musl engine and Android launcher use 16 KiB ELF LOAD alignment. This is not a JNI/Bionic port or a claim of universal Android compatibility. The patch replaces sandbox-denied hard links with durable file copies; see [patch details](../native/patches/README.md). The original downloaded upstream binary failed mail delivery in the Pixel's app sandbox.

`AppUpdates` accepts HTTPS APK links, bounds download size, checks package identity, exact signing certificates and a higher app version code, and hands off to Android's package installer. Android performs final installation/signature validation. Downloaded APKs are immutable uniquely named cache files. Installing requires hosting to be stopped. The version selector loads paginated stable releases from `themadorg/madmail` on GitHub, sorted by numeric version, independently of whether a release has a compatible asset. Installed versions stay visible offline. Android packages, when published, are passed through the same signer/package/version verification; releases without an APK are labeled accordingly. Manual package links remain available. Upstream has no published Android update package yet. `build-engine.sh` rebuilds pinned source plus the checked-in patch and records output hashes; `prepare-engine.sh` also builds the launcher. Builds fail if a binary is absent or does not match the packaged manifest. Review source revision, patch and manifest together when shipping a newer engine.

## Remote SSH

The app probes and pins the server's SSH host key before authentication. Generated/imported keys and memory-only passwords are supported; lifecycle operations need root or passwordless sudo. Existing API management is available through a supplied token over SSH or a trusted HTTPS endpoint. See [remote authentication](remote-authentication.md) for storage, capabilities and validation.

Releases come from `themadorg/madmail` via GitHub HTTPS metadata. Only stable numeric releases with a matching ARM64/AMD64 musl asset and GitHub SHA-256 digest are offered. Downloads are checked locally and again after copying into a root-owned remote path. This validates release integrity against GitHub metadata; it is not independent offline signature verification.

Dedicated paths:

- `/opt/madmail-mobile/releases/<version>` and atomic `current` symlink.
- `/opt/madmail-mobile/selected-version`, conservative migration/version floor.
- `/opt/madmail-mobile/backups/<timestamp>-<version>.tar`, cold data/config snapshot.
- `/var/lib/madmail-mobile`, private to the unprivileged `madmail-mobile` service account.
- `/etc/madmail-mobile/chatmail.toml`.
- `madmail-mobile.service`, systemd service; not enabled at boot automatically.

The installer refuses other Madmail/maddy units and unrecognized directories. It does not alter firewalls, request ACME certificates, open public admin HTTP, or replace unrelated services. TLS uses a self-signed identity initially. Remote stats go through an authenticated loopback SSH tunnel. Failed/ambiguous mutations are not retried automatically; refresh and inspect status first. A failed update can leave the service stopped, with its backup and previously retained engine available for operator recovery.

Changing versions stops hosting, creates the backup, stages/verifies the root-owned executable, switches atomically, then starts and verifies health. Older binaries cannot be selected against newer data. Retained non-selected binaries can be removed without removing mail or backups. A native systemd VPS acceptance test remains separate from the container integration fixture.

## Validation

Unit tests cover numeric version ordering, shell literal quoting and bounded response reads. Device tests cover actual engine start/stop/restart, duplicate start, listener closure, persistent TLS/admin identity and invalid update-package rejection. The optional remote device test connects to a disposable local SSH container, downloads the real release, installs it, checks live admin data, stops/restarts and reinstalls to exercise backup handling.

Fixture setup (loopback binding only; its fixed password is test-only):

```sh
docker build -t madmail-ssh-fixture tests/ssh-fixture
docker run -d --rm --init --name madmail-ssh-fixture -p 127.0.0.1:22222:22 madmail-ssh-fixture
adb reverse tcp:22222 tcp:22222
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e remoteFixture true org.madmail.mobile.test/androidx.test.runner.AndroidJUnitRunner
adb reverse --remove tcp:22222
docker rm -f madmail-ssh-fixture
```

The container uses a minimal `systemctl` shim to manage a real unprivileged engine process. It proves SSH, download verification, scripts, permissions, backups and API tunneling; it does not prove native systemd behavior. Normal device tests skip that fixture unless explicitly enabled. Run while local hosting is stopped; tests preserve existing local identities and data.

Not yet validated: a signed newer Android package through the installer, a cross-version database migration/restore, long screen-off hosting, a real systemd VPS, privileged Android port routing, or end-to-end Delta Chat guest onboarding on an isolated LAN.

### Recorded checks (2026-11-01)

- Background-hosting update: debug build, JVM tests and lint passed; installed in place on Pixel 6a / Android 17 with existing server and accounts preserved. Python regression script passes Ruff and isort checks.
- Android's battery-exemption prompt was accepted through the device UI; both the app and system diagnostics confirmed exemption.
- With Madmail backgrounded, killing only its app process caused Android to restore the sticky foreground service and all three TLS listeners without reopening the app. Explicit Stop stayed stopped for 15 seconds; hosting was then restarted.
- Background monitoring passed 12 HTTPS/IMAP/SMTP samples over 60 seconds with unchanged app process. The phone briefly entered screen-off Dozing but woke again during observation, so this is not sustained screen-off or deep-idle validation. USB remained connected. Hosting was left running.

- Pixel 6a / Android 17: 3 instrumentation tests passed, including the opt-in SSH fixture. Host-key changes and active-version removal were rejected. Local immediate restart preserved both TLS certificate and admin token.
- 7 JVM tests passed; debug APK/test APK builds and lint passed (warnings remain).
- Pinned-certificate TLS 1.3 checks returned a real SMTP greeting, IMAP greeting and HTTPS 200 from the phone's engine.
- The SSH fixture created a nonempty cold backup tar and was removed after the test. Its service shim is not a native systemd acceptance test.
- Device UI verified Start/Stop, live counters and the versions dialog. A real newer signed APK install and a cross-version database migration remain untested.
