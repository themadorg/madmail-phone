# Feature status · 2026-11-01

The core application is implemented, but the complete requested product is not finished. This inventory separates working code from live acceptance evidence.

| Area | Implemented | Remaining |
| --- | --- | --- |
| Overview and server picker | Per-server metrics, copyable connection details, local/remote selection, server emoji, guided setup | Wider accessibility, font-scale and device coverage |
| Phone engine | Bundled patched ARM64 engine, start/stop, health checks, sticky recovery, battery exemption | Root port routing, boot hosting, sustained screen-off/offline acceptance, other architectures |
| Versions and updates | GitHub release list, packaged version selection, verified signed-APK handoff, migration floor and cold backups | Published signed Android releases/update feed, real newer-version upgrade and restore acceptance |
| Invitations | Account QR, browser tokens, expiry/use limits/revocation, copy/share/image, address selection | Remote first-login race needs upstream fix; separate physical guest, offline LAN and Tailscale acceptance |
| Delta Chat | Separate profiles, SecureJoin, encrypted text and replies verified on the Pixel | Calls, attachments and multi-device network acceptance |
| Remote servers | Pinned SSH, generated/imported keys, passwords, supplied API tokens, HTTPS API, managed install/start/stop/update and backups | Real systemd VPS acceptance; fixture uses a service shim |
| Settings | Registration, accounts/quotas, storage, network, services, federation policy/DKIM, proxies, administration | Blocklist editing, detailed access controls, federation rules/DNS/exchanger changes, notices, queue purge, guided backup restore |
| App lock | Optional device-wide management UI lock, system authentication, background relock, protected screenshots/Recents | See app-lock validation below; this does not authenticate remote HTTP clients or encrypt mail storage |

## App lock

Settings → On this phone → App lock applies to this installation and all saved servers. Server settings above it remain per-server. Enabling/disabling requires Android authentication. Android 11+ uses strong biometrics or the device PIN/pattern/password; Android 8–10 uses the system device-credential screen. No app PIN is collected or stored.

Leaving the activity locks it; cold starts and activity recreation always require authentication. Cancelled/failed authentication keeps it locked. A result delivered while the activity is backgrounded cannot unlock or change the preference. Screenshot and Recents content capture are blocked while protection is enabled, including while unlocked. Explicit QR image sharing still works and intentionally exports that QR. Hosting and its notification Stop action remain independent of the UI gate. Removing the device screen lock requires setting one up again to authenticate; there is no silent bypass.

This is an application UI gate, not protection against a rooted device, a debuggable build owner, or removal of application data. Existing server authentication and Android Keystore credential encryption remain separate.

Validation: JVM cases cover cold start/recreation, background relock, cancelled and late authentication, and guarded preference changes. Physical authentication must be performed by the device owner; never inject a success result or bypass the system prompt for acceptance testing.

The debug APK was built and installed in place on the Pixel 6a. All 24 JVM tests and lint passed. The Settings control was visually inspected and opened the real Android authentication prompt. HTTPS/IMAP/SMTP passed three probes over 15 seconds while that prompt was open. Activation and physical unlock/relock acceptance are pending the owner's authentication; the setting is not enabled merely by opening its prompt.

Authentication API reference: [Android BiometricPrompt.Builder](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder).
