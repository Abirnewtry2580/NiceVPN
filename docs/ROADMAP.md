# NiceVPN roadmap

## Product scope

- Android only, for one person's personal use.
- Use VPN Gate's public relay list; do not operate VPN servers or collect user accounts.
- Be explicit that VPN Gate relays are volunteer-operated and may have different logging policies.
- Never show a connected state until the Android tunnel reports a real connection.

## Milestone 1 — server discovery and profile export

- Fetch VPN Gate's published CSV endpoint over HTTPS.
- Parse server metadata and embedded OpenVPN profiles.
- Show country, host, ping, reported speed, active sessions, and logging policy.
- Refresh safely, handle empty or malformed responses, and export a selected .ovpn profile through Android's document picker.
- Add parser unit tests and a CI debug build.

## Milestone 2 — tunnel integration spike

- Evaluate a maintained Android OpenVPN engine and its integration model and license.
- Check profile compatibility with current VPN Gate relay records.
- Prototype permission, connection state, disconnect, and failure reporting.
- Keep the app's status truthful; don't infer a tunnel from a successful profile export.
- If the standalone engine is not viable, document the OpenVPN client handoff and its extra-app requirement before proceeding.

## Milestone 3 — usable connection flow

- Connect and disconnect from the selected relay.
- Show connecting, connected, reconnecting, and failed states.
- Retry after Wi-Fi/mobile network changes, without hiding failures.
- Provide a server details screen and a clear route to the relay's published logging policy.
- Keep all secrets and private configuration on-device; do not add analytics.

## Milestone 4 — validation and release artifact

- CI builds the APK and stores it as a GitHub Actions artifact.
- Test network changes, stale relays, profile import, VPN permission denial, app restart, and disconnect on a real Android phone.
- Verify the egress IP through the chosen relay before calling the tunnel working.
- Ask for phone installation only once the build and tunnel flow are ready for that test.

## Known constraints

VPN Gate server listings and relays can change or disappear. The published line speed and ping are measurements, not guarantees. Logging is determined by each relay operator.
