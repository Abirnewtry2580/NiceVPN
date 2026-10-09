# Third-party notices

## Bundled OpenVPN engine

The integrated NiceVPN APK includes the OpenVPN for Android service and native OpenVPN 3 engine from [schwabe/ics-openvpn](https://github.com/schwabe/ics-openvpn), pinned to commit `bd8677a8056290aa444d36f64df037c34a2c2ec6`. The build overlay and the one-file self-API authorization patch are maintained in this repository. The upstream project is GPL version 2 with clarifications and additional terms, including its OpenSSL and Apache-library linking exceptions. The full license is `doc/LICENSE.txt` at the pinned upstream commit; the build script copies it to `build-openvpn-license.txt` in the CI workspace. OpenVPN and the engine's native submodules carry their own notices and licenses, which remain in the fetched source tree.

The NiceVPN provider UI and server parsers are combined with that GPL engine in the debug APK. Source changes and build steps for the combined app are provided in this public repository and in the pinned upstream source.

## VPN control API

The NiceVPN integration uses the upstream OpenVPN AIDL API within the same APK. The upstream `remoteExample` API definitions are licensed under Apache License 2.0. The integrated engine also contains its own GPL-licensed API implementation. See the pinned upstream `doc/LICENSE.txt`.
