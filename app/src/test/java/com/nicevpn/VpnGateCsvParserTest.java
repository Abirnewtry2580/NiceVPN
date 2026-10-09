package com.nicevpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.Test;

public final class VpnGateCsvParserTest {
    @Test
    public void parsesQuotedFieldsAndOpenVpnProfile() {
        String profile = "client\\nremote test.opengw.net 443\\n";
        String encoded = Base64.getEncoder().encodeToString(profile.getBytes(StandardCharsets.UTF_8));
        String csv = "*vpn_servers\\n"
                + "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\\n"
                + "relay-1,203.0.113.10,99,18,125000000,Japan,JP,7,1000,500,6000,2weeks,\"Operator, Inc.\",Academic," + encoded + "\\n";

        List<VpnGateServer> servers = VpnGateCsvParser.parse(csv);

        assertEquals(1, servers.size());
        assertEquals("Japan", servers.get(0).country);
        assertEquals("Operator, Inc.", servers.get(0).operator);
        assertEquals("125.0 Mbps", servers.get(0).speedLabel());
        assertEquals(profile, VpnGateCsvParser.decodeProfile(servers.get(0).profileBase64));
    }

    @Test
    public void skipsMalformedRowsAndReturnsEmptyForNoHeader() {
        assertTrue(VpnGateCsvParser.parse("not a VPN Gate CSV").isEmpty());
    }
}
