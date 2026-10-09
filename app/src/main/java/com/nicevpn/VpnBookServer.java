package com.nicevpn;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class VpnBookServer {
    public final String country;
    public final String host;
    public final String archiveId;

    private VpnBookServer(String country, String host, String archiveId) {
        this.country = country;
        this.host = host;
        this.archiveId = archiveId;
    }

    public static List<VpnBookServer> available() {
        return Collections.unmodifiableList(Arrays.asList(
                new VpnBookServer("United States", "us16.vpnbook.com", "us1"),
                new VpnBookServer("United States", "us178.vpnbook.com", "us2"),
                new VpnBookServer("Canada", "ca149.vpnbook.com", "ca1"),
                new VpnBookServer("Canada", "ca196.vpnbook.com", "ca2"),
                new VpnBookServer("United Kingdom", "uk205.vpnbook.com", "uk1"),
                new VpnBookServer("United Kingdom", "uk68.vpnbook.com", "uk2"),
                new VpnBookServer("Germany", "de20.vpnbook.com", "de1"),
                new VpnBookServer("Germany", "de220.vpnbook.com", "de2"),
                new VpnBookServer("France", "fr200.vpnbook.com", "fr1"),
                new VpnBookServer("France", "fr2311.vpnbook.com", "fr2")));
    }
}
