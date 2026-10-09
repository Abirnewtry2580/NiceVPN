# NiceVPN

Personal Android app for choosing VPN Gate or VPNBook servers and connecting through the OpenVPN for Android app.

## Current implementation

- Shows VPN Gate and VPNBook as provider choices.
- Fetches VPN Gate's published CSV server list over HTTPS.
- Fetches VPNBook's current shared password at each app launch, keeps it in memory only, and fetches the selected OpenVPN profile over HTTPS.
- Shows relay country, host, reported speed, ping, sessions, and logging policy.
- Requests connection through OpenVPN for Android's documented external AIDL API.
- Can export a selected VPN Gate .ovpn profile separately.
- Reports connection states received from the external OpenVPN app; it does not invent a connected state.

**OpenVPN for Android must also be installed on the phone.** NiceVPN controls that app; it does not bundle the OpenVPN tunnel engine.

VPN Gate relays are operated by volunteers. Availability and logging policies differ by relay. VPNBook is a shared free service. NiceVPN checks the selected VPNBook profile's server address before passing it to OpenVPN. Neither option is a private VPN service.

## Build

The project uses Android Gradle Plugin 9.4.0, Gradle 9.6.0, JDK 17, and Android SDK 36. GitHub Actions builds a debug APK and runs parser unit tests.

## Roadmap

See docs/ROADMAP.md.
