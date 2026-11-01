# Per-server administration

Reference inspected: https://github.com/themadorg/madmail-admin-web at commit `1cc77414e30a38d573bf40b137135a658e866f6d`.

The Android feature inventory is independently authored from the upstream routes and API contracts; no Svelte source was copied.

## Navigation and ownership

The selected server owns Overview, Invite, and Settings in the floating bottom dock. A fourth Server button opens a bottom-sheet picker. Switching servers preserves the current page while replacing its server context. The separate Add button opens remote setup.

Invitation links are keyed by immutable profile ID, held only in memory, and restored when returning to that server during the app session. They are never stored in preferences or Android saved state. Removing a profile clears its invitation. A new process starts without invitation secrets. Phone preferences use `server-settings-phone`; the old global root-port preference migrates only to the phone. Remote settings cannot read phone preferences.

## Reference mapping

| Android section | Reference routes/contracts |
| --- | --- |
| Registration & accounts | accounts, accounts/tokens, accounts/blocked; registration, JIT registration, token requirement, account and quota APIs |
| Mail & storage | overview/storage/quota/queue; message size, retention and auto-purge settings |
| Network & ports | ports; per-listener access and port fields, shared HTTPS IMAP/SMTP, SMTP hostname and login security |
| Services | services; TURN/Iroh/push, web IMAP/SMTP, CORS and admin web |
| Federation | federation, federation/dkim, endpoints, exchangers, traffic; policy, rules, DNS, DKIM, size |
| Proxies | proxy; Shadowsocks and HTTP proxy settings |
| Administration | notice; logging, language/admin paths, reload/restart |

Source contracts: `src/lib/api.ts`; version detection: `src/lib/stores/serverCapabilities.svelte.ts`.

## Current boundary

Settings now opens focused subpages backed by the authenticated local API or the remote SSH tunnel. See [settings behavior and limits](settings-flow.md) for the implemented controls, protected management endpoints, mutation/refresh semantics, restart ownership and validation. Invitation management is documented in [invitation flow](invitation-flow.md). The original feature mapping above remains the broader upstream inventory, not a claim of complete parity.
