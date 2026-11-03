# Native control panel

One design language everywhere, introduced by onboarding and built from `DesignKit.kt`. Prioritize a readable server state and one next action. Server, Registration, and Settings are the primary destinations. Empty states explain what is missing. QR codes stay black on white with an intact quiet zone. Support font scaling, scrolling, semantic labels, and 48dp controls; follow system light/dark appearance.

## Design kit

- **Page and groups.** Every page and sheet uses the warm panel colour (light F3F0EF, dark 1E1B1A, the theme background). Content sits in white grouped cards (20dp radius, 2B2726 in dark), inset 20dp from the screen edge. There are no elevated or outlined cards.
- **Captions and footers.** An uppercase caption sits over each group and an optional small footer sits under it. Sections are 24dp apart.
- **Marks.** Every row leads with a gradient squircle mark: 32dp in rows, 40dp for primary choices, 56dp in heroes. Icons are white Rounded glyphs. Hue carries meaning and is the same app-wide:
  - red: this phone, danger
  - blue: remote server, mail
  - green: running, healthy, battery, services
  - teal: network, connection
  - purple: invites, registration
  - orange: accounts, warnings
  - yellow: storage
  - indigo: federation
  - pink: activity
  - graphite: security, keys, SSH
  - gray: administration
- **Rows.** Rows are 60dp or taller with a 14dp gap after the mark. Dividers are inset to where the text starts.
  - Navigation rows end in a chevron and can show their current value.
  - Switch rows toggle on the whole row.
  - Value rows place the label left and the value right; addresses, hosts and fingerprints copy on tap with a check and a haptic.
  - Form values are inline label/value rows. The label grows rather than breaking a word, and stacks above the value at 130% font scale.
- **Actions.**
  - At most one red action per screen: the red pill with a white arrow disc at the foot of the screen, or a solid red pill when it sits inside content.
  - Secondary actions are soft red pills.
  - Text actions inside a group, such as removing a server, are plain rows, red only when they destroy something.
- **Motion.** One Emphasized curve (0.2, 0, 0, 1) and ExpoOut for arrivals. Content fades and rises at most 24px. There is no spring overshoot and no bouncing.

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

One continuous scroll, with all data visible. No accordions, large metric cards, or repeated navigation shortcuts. The server identity is a 56dp hero mark, name and one line saying where it runs. Hosting is one group: a status row, then Start hosting as the only red pill (Stop is tonal) beside a tonal Versions pill, then background access. Each metric section is a caption over a group of value rows with small squircle marks, values on the trailing edge. Overview never shows invitations or account QRs; a single Invite row links to the Invite page. Counts use locale grouping; bytes use readable units; technical ports and versions retain their exact strings. Connection values copy on tap with a visible copy/check affordance, brief confirmation, and haptic feedback. Separate combined counters into individually labeled rows. Keep actual listener ports distinct from saved preferences. Unknown metrics remain dashes, never zeros. Invite and Settings stay directly accessible in the dock. Support wrapping names/values, font scaling, semantic headings and 48dp interactive targets.

Phone hosting includes a background-access row inside the hosting group: green with Allowed once granted, orange with an Allow pill before. Before exemption, explain screen-off access and battery cost; after Android grants it, show the verified status without the explanatory paragraph. Re-check on resume. Keep Android's permission prompt as the confirmation surface rather than adding another app modal.

## Invite

The selected server owns all invitation state. A compact white QR is the focal point once an invitation exists; copy/share are primary, enlargement and image sharing secondary. The page uses the kit: a purple QR hero, the guest address as a teal row, Copy as the red pill with Share tonal, and secondary QR actions as text rows in a group. Creation and management use focused native sheets. Distinguish a browser invite for multiple separate accounts from a personal Delta Chat login QR. Show real usage and expiration; never fabricate compatibility or readiness. The guest-facing origin remains editable without exposing SSH/admin credentials.

## Server settings

App security is an explicitly device-scoped group at the bottom of Settings, separate from per-server controls. App lock uses the system authentication prompt, a labeled switch, and a single-action locked screen in the existing red theme. Keep hosting independent of UI authentication; do not reveal server identities or invite content on the locked screen.

Telegram-style grouped navigation opens dedicated subpages rather than accordion descriptions or another horizontal tab bar. Keep the existing floating main dock. The subpage header names the section and server, with native Android back behavior and a refresh action. Rows sit in captioned white groups (Server, Delivery, Advanced, On this phone) with squircle marks coloured by meaning and a page title naming the section and server. Use the existing light/dark red tokens and platform typography. Editable rows show current values and a pencil; public technical values have a separate copy action; secrets stay masked and are never prefilled into editors. Native switches show only known states. Save feedback distinguishes confirmed writes from failed refresh, and restart-required state is explicit. No decorative page animations.

Remote connection uses the kit sheet: transport, server identity and sign-in method as captioned groups of check rows, one red pill whose label follows the current step (verify, trust, connect). Setup distinguishes SSH from Admin API before requesting credentials. The connection sheet keeps generation/import/password choices together, shows only a public-key preview with a copy action, masks secret fields, and makes host verification a separate visible step. API-only connections show their endpoint in Overview and Settings and omit lifecycle controls. Credential errors stay in the sheet; changing API transport or endpoint clears newly entered tokens.

## Hosting setup and connection UX

First launch asks where to host with equally weighted Phone and Remote server actions. No host or management dock appears until setup is saved. Phone setup offers a server name and explains network, ports and power expectations; remote setup retains SSH/API review. Existing phone configuration and saved remote selections survive upgrades. The picker lists only configured hosts, and Add returns to the host choice.

Workspace destinations use a short shared-axis transition; setup and settings subpages fade between states. Compose animation timing follows Android animator duration scaling. Hosting state has a finite animated change, persistent text and a polite accessibility announcement, driven by engine state. It never implies firewall protection.

Invitation controls live only on the Invite page. Generate connection QR starts a rotating account QR, only on explicit tap; viewing a page never creates accounts. While the Invite page is on screen and the app is in front, a new account replaces the code every 5 seconds, with a red bar emptying under the QR and a short crossfade between codes. Leaving the page pauses it; Stop ends it. A replaced code nobody signed in to is deleted after 2 minutes (the engine blocks its address so the old QR cannot recreate it); a background sweep finishes this off screen and after restarts. Copying, sharing or opening a code in Delta Chat keeps that account. Rotation tokens are hidden from Manage invitations. Invitation visibility does not change registration permissions.

The phone server's mail domain is always its guest IP (`user@[ip]`), never a `.local` name. Saving a different IP, or creating an invitation for one, restarts hosting onto it. Accounts from older versions keep their `madmail.local` address and a footer in Accounts explains it.

## First-run onboarding

A four-page swipe intro (logo → phone → invite QR → own server) runs on one canvas stage that morphs with the finger; nothing slides off screen. The QR page draws a real code for https://madmail.chat/mobile. The only action is a red Get started pill in the logo's red with the arrow in a white disc.

Get started is one surface driven by a single progress value: it widens, grows and hands its red over to a warm sheet colour in the first half, then the content fades in. The sheet is measured at its final width so text never reflows, the page behind dims and recedes slightly, and dragging the sheet down scrubs it back into the button. The sheet offers three choices in one grouped card with gradient squircle icons, one hue per path: red phone, blue remote server, green existing instance. Each row grows into its next step through shared elements.

Every page after the choice keeps the sheet's warm colour (light F3F0EF, dark 1E1B1A) with white groups (20dp radius, 2B2726 in dark), so the sheet reads as having grown to fill the screen. Each page has the same frame: back in the top bar, the tapped row's mark flying into a 56dp hero (its text settles in after the mark has passed), uppercase captions over groups, small footers under them, and exactly one red pill pinned above the keyboard. There are no modal sheets, secondary buttons or review sub-steps in setup.

Phone is one page: an inline Name row, then "Before you start" with network and battery rows. The battery row shows the real exemption state (Allowed with a green check, or an Allow pill) and re-checks on resume. The pill is "Add this phone", and the page says hosting stays off until it is started from Overview. There is no pre-save "ready" badge.

Remote lists the self-hosted server first, then cloud providers as gradient monogram tiles with spread hues. Providers only prefill the user and a hint; nothing is provisioned or charged. Details are inline Address, Username and Port rows. A pasted or typed ssh command (user@host, host:port, -p) is split into those fields, followed by a short "What happens next" group. The existing-instance page asks only for the address, which accepts a bare domain and shows the resolved endpoint live, and the admin key, which has paste and show/hide and is never kept in saved state.

Validation waits for the first tap on the pill, then follows every keystroke. Errors sit under their own row, and the first invalid field takes focus with a reject haptic; the pill is never disabled. After a remote is saved, its connection sheet opens once so verification and sign-in follow straight on. Back always steps back, even from a focused field. Configured installations skip the intro and open the choices as a page. No engine, account or server mutation runs from viewing onboarding.
