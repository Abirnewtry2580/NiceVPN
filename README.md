# NiceVPN

Personal Android app for browsing VPN Gate public relays and starting a selected relay through the OpenVPN for Android app.

## Current implementation

- Fetches VPN Gate's published CSV server list over HTTPS.
- Shows relay country, host, reported speed, ping, sessions, and logging policy.
- Requests connection through OpenVPN for Android's documented external AIDL API.
- Can export a selected .ovpn profile separately.
- Reports connection states received from the external OpenVPN app; it does not invent a connected state.

**OpenVPN for Android must also be installed on the phone.** NiceVPN controls that app; it does not bundle the OpenVPN tunnel engine.

VPN Gate relays are operated by volunteers. Availability and logging policies differ by relay. This is not a private VPN service.

## Build

The project uses Android Gradle Plugin 9.4.0, Gradle 9.6.0, JDK 17, and Android SDK 36. GitHub Actions builds a debug APK and runs parser unit tests.

## Roadmap

See docs/ROADMAP.md.
