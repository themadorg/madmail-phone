# Per-server settings

The Settings hub opens focused Registration, Accounts, Mail & storage, Network & ports, Calls & services, Federation, Proxies, Administration and Connection pages. Android Back returns to the hub (or account list); the shared main dock stays visible. Each request and controller belongs to an immutable server ID.

## Transport and lifecycle

- Phone: authenticated admin API over the owned engine's loopback listener.
- Remote: the same API over a pinned, authenticated SSH tunnel. API tokens are retrieved over SSH and never displayed.
- Local restart uses the process owner without publishing an intermediate Stopped state that would destroy the foreground service. Remote restart stops/starts the managed systemd unit over SSH and waits for authenticated readiness.
- Do not call Madmail's `/admin/restart` as proof of restart: the inspected upstream implementation returns a restarting-shaped reply but does not actually automate a process restart.

## Implemented controls

- Registration open/closed, first-login registration, required tokens.
- Account listing, copyable addresses, default and individual quotas, confirmed account deletion (including its documented re-registration block).
- Read-message cleanup, retention enablement/duration, SMTP/IMAP size limits.
- Server hostname and shared HTTPS-port mail controls.
- TURN/Iroh/push/browser services and their supported connection/configuration values.
- Federation enablement, ACCEPT/REJECT policy, message limit and copyable DKIM DNS records.
- Shadowsocks and HTTP proxy controls; masked secret replacement.
- Web admin enablement, web language/path, managed restart, SSH metadata and connection management.

Fields absent from the server snapshot are marked unreported and cannot be edited; unknown toggles are not interpreted as disabled. Main mail/web ports and the admin API path are read-only because startup health checks, local invitation transport and SSH forwarding currently depend on those addresses. Root forwarding is not implemented; the phone Connection page can clear a legacy preference that prevents unprivileged startup. Fine-grained access restrictions, blocklist editing, federation rules/DNS/exchanger mutation, notices, queue purging and backup restoration remain outside this initial settings implementation.

## Save semantics

Writes are serialized per server, survive page navigation, and never automatically retry. A confirmed write followed by failed refresh is reported as saved with unavailable refreshed values. An ambiguous write disables further edits until a successful refresh; explicit validation rejections instead show an actionable error. Restart hints are stored per-server and clear only after a successful managed restart/readiness check. Secrets remain memory-only and are not offered as copy targets. Public addresses, ports, host fingerprints and DKIM records support copying with confirmation.

The initial quota editor uses MiB. Account storage uses Android's locale-aware file-size formatter; setting values use readable units such as “100 MiB” and “30 days”, with locale-aware number grouping. Copy preserves the original server value. Technical port strings remain exact.

## Validation

Unit tests cover the settings catalog, documented federation policy choices, duration/size input, phone privileged-port restrictions and protected management endpoints. `SettingsDeviceTest` writes a reversible web-language setting, refreshes it, restarts the owned engine, verifies certificate preservation and persistence, and restores the original setting. Its opt-in SSH fixture case installs a real engine, writes/reads settings through the tunnel, and restarts the managed service using the fixture's service shim. The shim validates the SSH/process path, not real systemd behavior on a production VPS.
