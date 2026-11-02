# Native control panel

Use standard Material 3 controls and system light/dark appearance. Prioritize a readable server state and one next action. Server, Registration, and Settings are the primary destinations. Empty states explain what is missing. QR codes stay black on white with an intact quiet zone. Support font scaling, scrolling, semantic labels, and 48dp controls. This bootstrap establishes functional layout; device visual validation is tracked separately.

## Floating navigation

User-directed liquid-glass navigation: a rounded floating dock with source-backed Haze blur, a subtle reflective edge, labeled icons, and a separate circular add-server action. Content scrolls beneath it with sufficient bottom clearance. Use an opaque fallback where blur is unavailable. Selection is semantic and animates color without locking interaction. System bars follow the device appearance through edge-to-edge setup.

The add action opens a guided SSH setup sheet. Until transport exists, it explicitly offers connection-detail review only and never collects credentials or implies installation succeeded.

## Server workspace navigation

Overview, Invite and Settings are server-scoped buttons in the floating bottom dock. A fourth Server button opens a bottom-sheet picker; Add remains a separate circular action. There are no top tabs or top selector. All screen titles identify their owning server. Admin settings use expandable sections based on the web admin feature inventory; disconnected values remain explicitly not loaded.

Dock controls are icon-only with accessible names and 48dp touch targets. The server control pairs its icon with a disclosure chevron to indicate the bottom-sheet selector. Visible navigation labels are omitted to keep the glass dock compact.

## Server identities

The server picker uses a compact list with an avatar, display name, host/connection subtitle and explicit current-server state. Switching uses the whole row. A header Edit mode changes rows into appearance-edit actions. The editor stays within the same sheet, with Back/Done navigation, unsaved-change protection, and separate Icon/Emoji choices. Per-server display names, built-in symbols, and custom emoji persist locally and appear in the dock and overview. Long names truncate in rows; a search field appears for more than five entries. No connection or server-side rename occurs during customization.

The switcher has no redundant title or close button. An accessible pencil/check icon in the footer toggles editing beside Add remote server. Native swipe, outside tap, and Back dismiss the list.

## Overview

One continuous scroll, with all data visible. No accordions, large metric cards, or repeated navigation shortcuts. Use consistent outlined Material icons for sections, metrics, and actions. The server identity is the page heading. Hosting is one compact saturated violet action; Versions is a text button. Flat sections use a shared label/value column, tabular numerals, clear dividers, and 32dp separation. Neutral surfaces and strong violet accents support both light and dark appearances. Counts use locale grouping; bytes use readable units; technical ports and versions retain their exact strings. Connection values copy on tap with a visible copy/check affordance, brief confirmation, and haptic feedback. Separate combined counters into individually labeled rows. Keep actual listener ports distinct from saved preferences. Unknown metrics remain dashes, never zeros. Invite and Settings stay directly accessible in the dock. Support wrapping names/values, font scaling, semantic headings and 48dp interactive targets.

Phone hosting includes one outlined battery-icon text action below Start/Stop. Before exemption, explain screen-off access and battery cost; after Android grants it, show the verified status without the explanatory paragraph. Re-check on resume. Keep Android's permission prompt as the confirmation surface rather than adding another app modal.

## Invite

The selected server owns all invitation state. A compact white QR is the focal point once an invitation exists; copy/share are primary, enlargement and image sharing secondary. Consistent outlined icons, 48dp targets, 16dp internal spacing, and the existing violet action color match Overview. Creation and management use focused native sheets. Distinguish a browser invite for multiple separate accounts from a personal Delta Chat login QR. Show real usage and expiration; never fabricate compatibility or readiness. The guest-facing origin remains editable without exposing SSH/admin credentials.

## Server settings

App security is an explicitly device-scoped group at the bottom of Settings, separate from per-server controls. App lock uses the system authentication prompt, a labeled switch, and a single-action locked screen in the existing violet theme. Keep hosting independent of UI authentication; do not reveal server identities or invite content on the locked screen.

Telegram-style grouped navigation opens dedicated subpages rather than accordion descriptions or another horizontal tab bar. Keep the existing floating main dock. The subpage header names the section and server, with native Android back behavior and a refresh action. Group rows on a 16dp-radius low surface; 24dp outlined section icons, 14dp icon/content spacing, 72dp navigation rows, and 48dp minimum action targets. Use the existing light/dark violet tokens and platform typography. Editable rows show current values and a pencil; public technical values have a separate copy action; secrets stay masked and are never prefilled into editors. Native switches show only known states. Save feedback distinguishes confirmed writes from failed refresh, and restart-required state is explicit. No decorative page animations.

Remote connection uses the same native sheet, violet actions and outlined icons. Setup distinguishes SSH from Admin API before requesting credentials. The connection sheet keeps generation/import/password choices together, shows only a public-key preview with a copy action, masks secret fields, and makes host verification a separate visible step. API-only connections show their endpoint in Overview and Settings and omit lifecycle controls. Credential errors stay in the sheet; changing API transport or endpoint clears newly entered tokens.

## Hosting setup and connection UX

First launch asks where to host with equally weighted Phone and Remote server actions. No host or management dock appears until setup is saved. Phone setup offers a server name and explains network, ports and power expectations; remote setup retains SSH/API review. Existing phone configuration and saved remote selections survive upgrades. The picker lists only configured hosts, and Add returns to the host choice.

Workspace destinations use a short shared-axis transition; setup and settings subpages fade between states. Compose animation timing follows Android animator duration scaling. Hosting state has a finite animated change, persistent text and a polite accessibility announcement, driven by engine state. It never implies firewall protection.

Phone Overview exposes invitation controls before Activity by default. Generate connection QR creates a random personal account through the existing server operation, only on explicit tap; it never creates accounts on screen entry. QR display, enlargement, sharing, expiry and uncertain-write handling stay server-scoped. Invitation visibility does not change registration permissions.

## First-run onboarding

A short welcome introduces hosting and QR invitations, with a direct remote-server shortcut for existing hosts. Host choice remains explicit with no preselection. Phone configuration collects a display name and explains network reachability and optional Android background access. A review screen names the host and explains the Start hosting → generate QR sequence before saving. No engine, account or server mutation runs simply from viewing onboarding.

Use the existing violet Material theme, outlined icons, readable flat detail rows and a bottom primary action above the keyboard. Content scrolls independently, setup steps fade, drafts survive back navigation, and native Back follows the steps. Existing configured installations skip welcome; adding another host goes straight to host choice. Remote SSH/API review and identity verification remain in the existing connection flow.
