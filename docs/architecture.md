# Madmail Mobile: original product brainstorm and implementation plan

This document records the initial plan. For the implemented Android and SSH lifecycle, current paths, update policies, and validation boundaries, see [engine-lifecycle.md](engine-lifecycle.md). The bootstrap-only statements below are historical.

## Product boundary

An Android appliance that hosts Madmail and guides a person through running it. It manages the server and displays/copies registration codes. It does not contain a chat client, inbox, contact list, or message composer. Guests use Delta Chat separately. A working local network must be sufficient; internet is not a runtime prerequisite for local delivery.

## Guided first-run journey

1. Explain that this device hosts the network's mail and needs to remain available.
2. Detect usable Wi-Fi/hotspot interfaces. Explain unavailable Wi-Fi, client isolation, VPN routes, and missing permissions with recovery steps. Do not silently change network settings.
3. Choose a durable server identity separately from the current IP. Explain that moving networks changes how guests connect.
4. Default to unprivileged ports. Offer standard ports through an optional root flow.
5. Prepare private storage and TLS identity. Validate listener bindings and storage before claiming readiness.
6. Start hosting with a foreground notification and explicit Stop action.
7. Display registration only after the endpoint is ready. Show network/address, scan instructions, copy action, and registration status.
8. Confirm one guest connects; offer troubleshooting if it cannot.

Bootstrap UI has explanatory screens and externally supplied registration QR rendering. The guided networking/hosting wizard follows real engine integration, so it cannot show invented success.

## Screens and management scope

- Server: stopped/starting/running/stopping/error; selected network, active ports, account count, used storage, last meaningful error. Start/stop is primary. Runtime readiness comes from listeners and storage, not a timer.
- Registration: open/closed registration, current server-generated invitation, display/copy, revoke/rotate if supported by upstream. Keep a reusable registration code distinct from a single-account login QR. Never log either payload.
- Accounts: counts, create/disable/delete where upstream supports them, per-account quotas, confirmation for destructive operations. No viewing message content. Do not invent API capabilities; map each operation to upstream first.
- Settings: network binding, port mode, retention/quotas, device power guidance, backups, version and licenses.
- Diagnostics: bounded redacted logs, listener health, connection failure reason, export with preview. No credentials, registration tokens, mail bodies, or account identifiers by default.

## Architecture and ownership

Start with one Android Gradle app and package boundaries. Split modules when a second consumer or test isolation justifies it.

- `app/.../core`: platform-independent configuration, registration validation, engine contract.
- Future `engine`: JNI adapter and structured native events.
- Future `service`: foreground service in `:server`, Binder control API, notification, restart reconciliation.
- Future `network`: interface selection, address changes, Android local-network permission handling by target SDK.
- Future `root`: bounded privileged helper with fixed operations and validated numeric ports/interface identifiers.
- Future feature packages: setup, server, registration, accounts, settings, diagnostics.
- Future `native/crates/mobile-runtime`: explicit startup/cancellation/status around upstream Rust.
- Future `native/crates/android-bridge`: small JNI interface; no protocol rewrite.
- Future `upstream/madmail`: pinned submodule or pinned fork after compatibility spike identifies needed changes.

Keep email storage/authentication authoritative in Rust. Android preferences hold only UI/server settings. Database writes from UI are prohibited; account changes go through engine methods. Use structured errors and a serialized lifecycle state machine. Stop cancels listeners/tasks, drains with a deadline, and closes storage. Recovery must reconcile persisted state with actual runtime state.

## Optional root and privileged ports

Unrooted defaults: SMTP TLS 11465, IMAP TLS 11993; registration HTTPS port selected during engine integration. Root preference: SMTP TLS 465, IMAP TLS 993, HTTPS 443 if needed. Port 25 is unnecessary for local-only delivery and is not part of the initial profile.

Root is requested only by an explicit user action. Model unknown, unavailable, requesting, denied, granted, failed, and revoked. No root prompt on app launch. Denial leaves normal operation available; do not silently publish endpoints with different ports.

Preferred spike: a narrowly scoped helper forwards standard ports to the unprivileged engine, restricted to the selected LAN interface. Investigate Android firewall/SELinux constraints and root-manager compatibility before choosing redirection versus a privileged listener. Never run the whole Rust mail engine as root by default. No arbitrary shell strings from UI, no global firewall flush, and no persistent system modifications. Rules/listeners must be app-owned and idempotent, removable on stop and reconcilable after crashes. Verify same-device connections separately from other LAN clients. Surface port conflicts before applying anything.

Bootstrap saves only the preference. It neither invokes `su` nor installs forwarding rules.

## Registration and TLS: first compatibility gate

Delta Chat documents `DCACCOUNT:<domain>` and `DCACCOUNT:https://...`. The HTTPS endpoint returns email/password; extra fields are currently ignored. Therefore a valid QR image alone does not establish working custom-port onboarding. Test actual client discovery and TLS trust before selecting the local provisioning strategy. `DCLOGIN` may support an explicit per-account setup path; evaluate it separately without conflating it with reusable registration.

A self-signed private LAN certificate is not automatically trusted by clients. Do not disable certificate verification globally. Investigate supported client trust enrollment or a stable trusted identity with local resolution. Public ACME must not become a mandatory offline dependency. Account identity must not rotate with DHCP. Root standard ports can help port discovery, but cannot solve DNS or certificate trust.

The bootstrap accepts an exact HTTPS registration URL and renders/copies its DCACCOUNT payload. It does not claim the endpoint exists, generate fake tokens, persist invitation URLs, or create accounts.

## Storage, power, and lifecycle

Use private app storage, preserve TLS identity and database across restart, and disable automatic cloud backup until a consistent encrypted export is designed. Plan quotas, low-space admission control, retention, and a migration rollback strategy. Back up a consistent database/message set with server coordination, not live file copying.

Foreground hosting must have an ongoing notification. Evaluate `remoteMessaging` against the actual Android use case and distribution policy. Screen-off, Doze, OEM restrictions, force-stop, reboot, and hotspot sleep need device tests. Do not promise uninterrupted hosting or auto-start after force-stop. Ask for battery exceptions only with a clear measured need. Root does not remove the need for lifecycle/power handling.

## Staged delivery and acceptance

1. Kotlin bootstrap: buildable app, guided explanatory screens, QR/copy, preferences, honest engine-unavailable state, validation tests.
2. Rust Android spike: pin upstream revision, cross-compile ARM64, audit native/systemd/signal/installer dependencies and 16 KB page compatibility, add explicit shutdown. No desktop installer on Android.
3. Offline vertical slice: Android host plus two real Delta Chat clients, no WAN; create accounts, exchange text/attachment, restart host, retrieve stored mail. Prove TLS and custom-port onboarding.
4. Service and wizard: network selection, true readiness, notification stop, state recovery, permissions, guided invitation.
5. Root spike: rooted/unrooted/denied/revoked cases, port conflicts, forwarding cleanup, crashes, same-device and LAN access; root-free path remains tested.
6. Management: real account operations, registration controls, quotas, bounded diagnostics.
7. Release hardening: backup/restore, upgrades, full disk, long idle, network changes, large fonts/TalkBack/RTL, native ABI checks, license and source distribution obligations review.

Deferred: federation, public SMTP, TURN/calls, proxy services, public admin UI, automatic boot hosting, and embedded chat UI. Enable optional upstream services only after LAN messaging works.

## References and evidence

- Upstream: https://github.com/themadorg/madmail
- Library boundary: https://github.com/themadorg/madmail/blob/main/crates/chatmail/src/lib.rs
- Registration specification: https://github.com/deltachat/interface/blob/main/uri-schemes.md
- Android foreground services: https://developer.android.com/develop/background-work/services/fgs/service-types

Repository source inspected during planning; no Android Rust compile or real-client interoperability proof yet. The source currently has a library boundary, but that does not establish embeddability without changes.

## Remote SSH setup addition

The floating + action opens SSH or Admin API setup. Connection supports generated/imported SSH keys, passwords, supplied API tokens over a pinned SSH tunnel, and HTTPS API-only access. Lifecycle changes remain restricted to app-owned SSH installs. See [remote authentication](remote-authentication.md) and [engine lifecycle](engine-lifecycle.md) for current implementation and limits.

## Server selection

A shared selector lists this phone and saved SSH connection profiles across Server, Invite, and Settings. Adding a remote selects it; selection and non-secret metadata persist across restarts. Profiles with the same host/user/port are deduplicated. Invitation state is isolated by immutable server ID in memory; switching restores only that server's entry. Remote Settings displays connection metadata instead of local root/port controls. Removing a saved profile falls back to the phone and does not modify the remote host. No connections are made by selecting or saving a profile.
