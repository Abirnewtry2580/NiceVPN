// API definition from schwabe/ics-openvpn remoteExample (Apache-2.0).
package de.blinkt.openvpn.api;

oneway interface IOpenVPNStatusCallback {
    void newStatus(String uuid, String state, String message, String level);
}
