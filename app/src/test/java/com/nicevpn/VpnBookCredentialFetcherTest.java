package com.nicevpn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class VpnBookCredentialFetcherTest {
    @Test
    public void extractsPasswordFromCredentialsSection() {
        String html = "<h3>VPN Credentials</h3>"
                + "<p>Username</p><code>vpnbook</code><button>Copy</button>"
                + "<p>Password</p><code>samplePass92</code><button>Copy</button>"
                + "<p>Last updated: today</p>";

        assertEquals("samplePass92", VpnBookCredentialFetcher.extractPassword(html));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPageWithoutPassword() {
        VpnBookCredentialFetcher.extractPassword(
                "<h3>VPN Credentials</h3><p>Password unavailable</p>");
    }
}
