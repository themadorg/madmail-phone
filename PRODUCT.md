# Madmail Mobile

## Platform
android

## Stack
User requested Kotlin. Native Compose UI; Rust Madmail integration planned.

## Users
Nontechnical hosts who need guided setup for a local-network mail server.

## Product Purpose
Run and manage Madmail on an Android device. Display registration QR codes and copyable setup information for other devices. This app is not a messaging client.

## Capabilities and Constraints
Local Wi-Fi or hotspot operation. Optional user-approved root access for privileged ports; unrooted operation remains supported. Server integration and client compatibility are not yet proven.

## Product Principles
Explain the next action in plain language. Never show a running state without engine evidence. Keep registration secrets out of diagnostics. Preserve server identity and mail across restarts.

## Remote hosting

The user requested an add-server entry to install Madmail on a remote server/VPS through SSH. The current UI collects non-secret connection details and offers review; SSH transport, host-key verification, authentication, installation, and remote management remain unimplemented.

Invite and Settings belong to each individual server. The web admin repository is the reference for management feature coverage. Disconnected settings remain unknown until read from that server.
