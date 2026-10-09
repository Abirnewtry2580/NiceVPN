package com.nicevpn;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class VpnBookConfigFetcher {
    private static final int MAX_ARCHIVE_BYTES = 8_000_000;
    private static final int MAX_PROFILE_BYTES = 1_000_000;
    private static final String USERNAME = "vpnbook";

    private VpnBookConfigFetcher() {
    }

    public static String downloadProfile(VpnBookServer server, String password) throws Exception {
        return downloadProfile(server, password, true);
    }

    static String downloadProfile(VpnBookServer server, String password, boolean preferUdp)
            throws Exception {
        if (server == null || password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("VPNBook server and current password are required");
        }
        String url = configArchiveUrl(server);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NiceVPN/0.1 Android");
        connection.setRequestProperty("Accept", "application/zip, application/octet-stream");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VPNBook config returned HTTP " + code);
            }
            byte[] archive;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    if (output.size() > MAX_ARCHIVE_BYTES) {
                        throw new IllegalStateException("VPNBook config archive is too large");
                    }
                }
                archive = output.toByteArray();
            }
            return extractProfile(archive, server, password, preferUdp);
        } finally {
            connection.disconnect();
        }
    }

    static String configArchiveUrl(VpnBookServer server) {
        return "https://www.vpnbook.com/free-openvpn-account/VPNBook.com-OpenVPN-"
                + server.archiveId.toUpperCase(Locale.ROOT) + ".zip";
    }

    static String extractProfile(byte[] archive, VpnBookServer server, String password)
            throws Exception {
        return extractProfile(archive, server, password, true);
    }

    static String extractProfile(
            byte[] archive, VpnBookServer server, String password, boolean preferUdp)
            throws Exception {
        String bestProfile = null;
        int bestPriority = Integer.MAX_VALUE;
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                int priority = profilePriority(name, preferUdp);
                if (entry.isDirectory() || !name.endsWith(".ovpn") || priority >= bestPriority) {
                    continue;
                }
                ByteArrayOutputStream profileBytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    profileBytes.write(buffer, 0, read);
                    if (profileBytes.size() > MAX_PROFILE_BYTES) {
                        throw new IllegalStateException("VPNBook profile is too large");
                    }
                }
                String profile = new String(profileBytes.toByteArray(), StandardCharsets.UTF_8);
                int port = profilePort(name);
                if (hasExpectedRemote(profile, server.host, port)) {
                    bestProfile = profile;
                    bestPriority = priority;
                }
            }
        }
        if (bestProfile == null) {
            throw new IllegalArgumentException("Matching VPNBook profile was not in the archive");
        }
        return inlineCredentials(bestProfile, server.host, password);
    }

    private static int profilePriority(String name, boolean preferUdp) {
        if (preferUdp) {
            if (name.contains("udp25000")) return 0;
            if (name.contains("udp53")) return 1;
            if (name.contains("tcp443")) return 2;
            if (name.contains("tcp80")) return 3;
        } else {
            if (name.contains("tcp443")) return 0;
            if (name.contains("udp25000")) return 1;
            if (name.contains("udp53")) return 2;
            if (name.contains("tcp80")) return 3;
        }
        return Integer.MAX_VALUE;
    }

    private static int profilePort(String name) {
        if (name.contains("udp25000")) return 25000;
        if (name.contains("udp53")) return 53;
        if (name.contains("tcp80")) return 80;
        return 443;
    }

    private static boolean hasExpectedRemote(String profile, String expectedHost, int expectedPort) {
        if (profile == null || expectedHost == null) return false;
        for (String line : profile.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("(?i)^remote\\s+"
                    + java.util.regex.Pattern.quote(expectedHost)
                    + "\\s+" + expectedPort + "(?:\\s+.*)?$")) {
                return true;
            }
        }
        return false;
    }

    static String inlineCredentials(String profile, String expectedHost, String password) {
        if (password == null || !password.matches("[A-Za-z0-9._-]{4,64}")) {
            throw new IllegalArgumentException("VPNBook password has an unexpected format");
        }
        if (profile == null || !profile.contains("<ca>") || !profile.contains("</ca>")) {
            throw new IllegalArgumentException("VPNBook profile is missing its certificate");
        }
        boolean expectedRemote = false;
        for (String line : profile.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("(?i)^remote\\s+" + java.util.regex.Pattern.quote(expectedHost)
                    + "\\s+(?:53|80|443|25000)(?:\\s+.*)?$")) {
                expectedRemote = true;
                break;
            }
        }
        if (!expectedRemote) {
            throw new IllegalArgumentException("VPNBook profile host did not match the selected server");
        }
        String normalized = profile
                .replaceAll("(?is)<auth-user-pass>.*?</auth-user-pass>", "")
                .replaceAll("(?im)^[ \\t]*auth-user-pass(?:[ \\t]+[^\\r\\n]+)?[ \\t]*$", "");
        String auth = "\n<auth-user-pass>\n" + USERNAME + "\n" + password
                + "\n</auth-user-pass>\n";
        return auth + normalized;
    }
}
