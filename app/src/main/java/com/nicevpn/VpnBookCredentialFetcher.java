package com.nicevpn;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VpnBookCredentialFetcher {
    private static final String CREDENTIALS_URL =
            "https://www.vpnbook.com/freevpn/openvpn";
    private static final int MAX_RESPONSE_CHARS = 1_500_000;
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)\\bPassword\\b\\s+([A-Za-z0-9._-]{4,64})\\s+Copy\\b");

    private VpnBookCredentialFetcher() {
    }

    public static String fetchCurrentPassword() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(CREDENTIALS_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NiceVPN/0.1 Android");
        connection.setRequestProperty("Accept", "text/html");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VPNBook page returned HTTP " + code);
            }
            String html;
            try (InputStream stream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    body.append(buffer, 0, read);
                    if (body.length() > MAX_RESPONSE_CHARS) {
                        throw new IllegalStateException("VPNBook page is unexpectedly large");
                    }
                }
                html = body.toString();
            }
            return extractPassword(html);
        } finally {
            connection.disconnect();
        }
    }

    static String extractPassword(String html) {
        if (html == null || html.isEmpty()) {
            throw new IllegalArgumentException("VPNBook page is empty");
        }
        String visibleText = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();

        int credentialsSection = visibleText.toLowerCase().indexOf("vpn credentials");
        if (credentialsSection < 0) {
            throw new IllegalArgumentException("VPNBook credentials section was not found");
        }
        String credentialsText = visibleText.substring(credentialsSection);
        Matcher matcher = PASSWORD_PATTERN.matcher(credentialsText);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Current VPNBook password was not found");
        }
        return matcher.group(1);
    }
}
