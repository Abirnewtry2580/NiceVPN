# NiceVPN

Personal Android VPN app for selecting public VPN Gate and VPNBook servers.

## Current connection engine

The single-app build combines the NiceVPN provider screen with the OpenVPN for Android tunnel engine. The engine is pinned to [schwabe/ics-openvpn](https://github.com/schwabe/ics-openvpn) commit `bd8677a8056290aa444d36f64df037c34a2c2ec6`. NiceVPN is built as a separate package (`com.nicevpn`), so it does not replace an independently installed OpenVPN app.

The first connection still requires Android's standard system VPN consent. No separate OpenVPN app installation is required by the integrated build.

## Providers

- VPN Gate's published CSV server list is fetched over HTTPS.
- VPNBook's current shared password is fetched at each app launch and kept in memory only.
- VPNBook's selected OpenVPN profile bundle is fetched over HTTPS, and its server address is checked before use.
- VPN Gate profiles can be exported separately.
- VPN Gate relays are volunteer-operated; availability and logging policy vary by relay. VPNBook is a shared free service.

## Build

GitHub Actions fetches the pinned OpenVPN source and its pinned submodules, overlays the NiceVPN Java UI and parser tests, then builds a debug APK with the OpenVPN 3 core. The overlay is in `scripts/prepare-standalone.py`. The exact upstream source revision and license are recorded in `THIRD_PARTY_NOTICES.md`.

A successful CI build verifies compilation and parser tests. It does not verify that a public relay accepts a connection; real-device testing is still needed after the integrated APK is available.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md).
