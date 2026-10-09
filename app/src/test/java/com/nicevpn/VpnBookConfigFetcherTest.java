package com.nicevpn;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VpnBookConfigFetcherTest {
    @Test
    public void injectsCurrentCredentialsAndKeepsSelectedHost() {
        String profile = "client\nremote us16.vpnbook.com 443\n"
                + "auth-user-pass\n<ca>\ncertificate\n</ca>\n";
        String configured = VpnBookConfigFetcher.inlineCredentials(
                profile, "us16.vpnbook.com", "samplePass92");

        assertTrue(configured.contains("<auth-user-pass>\nvpnbook\nsamplePass92\n</auth-user-pass>"));
        assertTrue(configured.contains("<ca>\ncertificate\n</ca>"));
        assertFalse(configured.contains("\nauth-user-pass\n"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsProfileForAnotherServer() {
        VpnBookConfigFetcher.inlineCredentials(
                "client\nremote us178.vpnbook.com 443\n<ca>\ncert\n</ca>",
                "us16.vpnbook.com", "samplePass92");
    }
}
