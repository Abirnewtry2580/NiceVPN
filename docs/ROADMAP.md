# NiceVPN roadmap

## Product scope

- Android app for one person's personal use.
- One installable app: provider selection, server list, and OpenVPN tunnel engine are bundled together.
- Support VPN Gate's public relay list and VPNBook's published free OpenVPN profiles; do not operate VPN servers or collect user accounts.
- Be explicit that VPN Gate relays are volunteer-operated and may have different logging policies.
- Never show a connected state until an OpenVPN status callback reports it.

## Current implementation

- Provider UI and parsers are maintained in this repository.
- The standalone build overlays that UI onto a pinned OpenVPN for Android source checkout.
- The OpenVPN service, native OpenVPN 3 core, and UI ship in the same APK under the NiceVPN package name.
- Android's system VPN consent is requested only when connecting.
- The integration script authorizes only the bundled app's own UID to call its internal AIDL service; other callers still use OpenVPN's allow-list.

## Build and verification

- CI fetches OpenVPN for Android commit `bd8677a8056290aa444d36f64df037c34a2c2ec6` and initializes its pinned submodules.
- CI overlays NiceVPN Java sources, parser tests, launcher resources, and manifest entry.
- CI runs parser unit tests and builds the integrated debug APK.
- Do not request a phone test until the integrated build passes CI.
- Then verify on-device: Android VPN consent, VPN Gate connection, VPNBook password/profile, disconnect, and connection-state reporting.
- Test Wi-Fi/mobile handoff and verify egress IP through the selected relay.

## Known constraints

- VPN Gate listings and relays can change or disappear. Reported speed and ping are not guarantees. Logging is determined by each relay operator.
- VPNBook is a shared free service; its credentials and servers can change.
- The integrated engine is GPLv2 with upstream clarifications and exceptions. See `THIRD_PARTY_NOTICES.md` and the pinned upstream `doc/LICENSE.txt`.
