# Remote authentication

Remote setup offers SSH and Admin API profiles. Saving connection metadata does not connect or install anything. Authentication takes place from Connect server. Every profile has an immutable ID; credentials and trust are scoped to it.

## SSH

- Generate a per-server RSA-3072 key on the phone and copy its OpenSSH public key into the selected SSH user's `~/.ssh/authorized_keys`. Generation reuses an existing saved key instead of rotating it silently.
- Import a supported SSH private key from a document or pasted text, with its passphrase when encrypted. The key is parsed and decrypted for validation before replacing storage. Replacement requires confirmation; removing a public key from the actual server remains the operator's task.
- Password login remains available and is memory-only.
- Host identity is probed separately, then the user verifies and pins the SHA-256 fingerprint before authentication. Changed host keys remain refused. Key authentication uses publickey only, with no password fallback.

Without a supplied API token, the SSH account needs root or passwordless sudo for the dedicated app-managed lifecycle. With “Use an existing server’s API token”, the app forwards the existing server's loopback admin gateway at `127.0.0.1:18080`. This path does not upload the lifecycle script, require sudo, install, adopt, restart, or stop the existing service. A successful connection saves the supplied token encrypted for reuse.

## Direct API

Enter the HTTPS JSON admin-gateway endpoint and API token. A bare HTTPS origin gains `/api/admin`; an explicit path is preserved. Embedded credentials, query strings, fragments and HTTP are rejected. Android must trust the TLS certificate and its hostname; there is no trust-all or hostname bypass in production code. Self-signed deployments can use the pinned SSH tunnel instead.

API management supports the existing overview, settings, accounts and invitation API paths. Browser/account provisioning stays on the same origin (`/inv/...` and `/new`) without sending the admin token to those web routes. SSH interface discovery and lifecycle/version controls are unavailable in API-only mode. The initial guest URL uses the API origin and port but can be changed independently. Deployments with web provisioning on a separate origin are not supported by this connection path yet.

Successful direct API credentials are stored after authentication. Editing the endpoint requires entering a token again; a saved token cannot silently follow an edited URL. HTTP redirects are never followed for credential-bearing requests. The gateway's bearer value remains inside its established JSON request envelope.

## Storage and removal

Private keys, imported key passphrases, supplied tunnel tokens and direct API tokens use AES-256-GCM envelope encryption in private preferences, with a per-profile Android Keystore wrapping key. Associated data binds each record to its profile ID and credential type. Each write gets a fresh IV. App backup is disabled. SSH private material must be decrypted into process memory for JSch authentication; it is not a non-exportable Keystore SSH signing key.

Secret text fields use password masking and password keyboards, and never use saved instance state. Only the public SSH key has a copy action. Removing a profile deletes its credential records, Keystore wrapping key, connection options and pinned host key; it does not change the remote server or revoke authorized_keys entries.

## Validation

`ApiEndpointTest` checks URL normalization and rejection. Device tests validate generated-key persistence, encrypted imported keys, wrong-passphrase rejection, encrypted storage, and per-profile separation. Generated and imported keys authenticate against a real SSH fixture with PasswordAuthentication disabled, install the real engine and read settings. Another test supplies the fixture's real admin token through SSH and verifies lifecycle controls are denied. The HTTPS fixture checks trusted-CA success, untrusted-certificate and wrong-token failure, and redirect rejection. The test temporarily pins only its fixture certificate in its own process; production trust settings are unchanged.

These tests do not claim installation on a user's production VPS or biometric gating. Clearing app data/uninstalling removes the local keys; retain another means of accessing your server.
