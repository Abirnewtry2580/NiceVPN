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
                new VpnBookServer("United States", "us16.vpnbook.com", "us16"),
                new VpnBookServer("United States", "us178.vpnbook.com", "us178"),
                new VpnBookServer("Canada", "ca149.vpnbook.com", "ca149"),
                new VpnBookServer("Canada", "ca196.vpnbook.com", "ca196"),
                new VpnBookServer("United Kingdom", "uk205.vpnbook.com", "uk205"),
                new VpnBookServer("United Kingdom", "uk68.vpnbook.com", "uk68"),
                new VpnBookServer("Germany", "de20.vpnbook.com", "de20"),
                new VpnBookServer("Germany", "de220.vpnbook.com", "de220"),
                new VpnBookServer("France", "fr200.vpnbook.com", "fr200"),
                new VpnBookServer("France", "fr2311.vpnbook.com", "fr2311")));
    }
}
