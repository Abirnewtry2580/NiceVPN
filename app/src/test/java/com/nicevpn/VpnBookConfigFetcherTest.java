package com.nicevpn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VpnBookConfigFetcherTest {
    @Test
    public void buildsTheCurrentVpnBookArchiveNameFromCurrentServerSlug() {
        assertEquals(
                "https://www.vpnbook.com/free-openvpn-account/vpnbook-openvpn-us16.zip",
                VpnBookConfigFetcher.configArchiveUrl(VpnBookServer.available().get(0)));
    }

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

    @Test
    public void findsRenamedHostByProfileRemoteNotArchiveEntryName() throws Exception {
        String source = "client\nremote us16.vpnbook.com 443\n"
                + "<ca>\ncertificate\n</ca>\n";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("vpnbook-openvpn-us1-tcp443.ovpn"));
            zip.write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        VpnBookServer server = VpnBookServer.available().get(0);
        org.junit.Assert.assertEquals("us16", server.archiveId);
        String configured = VpnBookConfigFetcher.extractProfile(bytes.toByteArray(), server, "samplePass92");

        assertTrue(configured.contains("remote us16.vpnbook.com 443"));
        assertTrue(configured.contains("samplePass92"));
    }

    @Test
    public void prefersFastUdp25000ProfileOverTcp443() throws Exception {
        String tcp = "client\nremote us16.vpnbook.com 443\n<ca>\ncertificate\n</ca>\n";
        String udp = "client\nproto udp\nremote us16.vpnbook.com 25000\n<ca>\ncertificate\n</ca>\n";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("vpnbook-us1-tcp443.ovpn"));
            zip.write(tcp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("vpnbook-us1-udp25000.ovpn"));
            zip.write(udp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        String selected = VpnBookConfigFetcher.extractProfile(
                bytes.toByteArray(), VpnBookServer.available().get(0), "samplePass92", true);
        assertTrue(selected.contains("remote us16.vpnbook.com 25000"));
        assertFalse(selected.contains("remote us16.vpnbook.com 443"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsProfileForAnotherServer() {
        VpnBookConfigFetcher.inlineCredentials(
                "client\nremote us178.vpnbook.com 443\n<ca>\ncert\n</ca>",
                "us16.vpnbook.com", "samplePass92");
    }
}
