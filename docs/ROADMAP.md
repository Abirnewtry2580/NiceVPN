# NiceVPN roadmap

## Product scope

- Android only, for one person's personal use.
- Use VPN Gate's public relay list; do not operate VPN servers or collect user accounts.
- Use OpenVPN for Android's documented AIDL API as the tunnel engine. That separate app must be installed.
- Be explicit that VPN Gate relays are volunteer-operated and may have different logging policies.
- Never show a connected state until the OpenVPN status callback reports it.

## Milestone 1 — server discovery

- Fetch VPN Gate's published CSV endpoint over HTTPS.
- Parse server metadata and embedded OpenVPN profiles.
- Show country, host, ping, reported speed, active sessions, and logging policy.
- Refresh safely, handle empty or malformed responses, and export a selected .ovpn profile.
- Add parser unit tests and a CI debug build.

## Milestone 2 — connection control

- Bind to OpenVPN for Android through its documented external AIDL API.
- Request the external API authorization and Android VPN consent only when the user taps Connect.
- Start a selected relay from its inline profile and provide Disconnect.
- Show state received from OpenVPN's callback; surface missing-app, denied-permission, and failed-connection states.
- Preserve the standalone profile export as a fallback.

## Milestone 3 — reliability and usability

- Add country search/filter, favorites, clear sort choices, and refresh timestamp.
- Handle stale or unreachable relays and network changes without hiding failure.
- Make the dependency on OpenVPN for Android clear before connection.
- Keep VPN credentials and profiles on-device; add no analytics.

## Milestone 4 — validation and release artifact

- CI builds the APK and stores it as a GitHub Actions artifact.
- Test server selection, profile export, external API permission, VPN permission denial, disconnect, and status callback.
- Test Wi-Fi/mobile handoff and verify egress IP through the chosen relay on a real Android phone.
- Ask for phone installation only once the build is ready for that test.

## Known constraints

VPN Gate listings and relays can change or disappear. Reported line speed and ping are not guarantees. Logging is determined by each relay operator. The OpenVPN tunnel engine is provided by the separate OpenVPN for Android app.
