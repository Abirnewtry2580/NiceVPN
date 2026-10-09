package com.nicevpn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class VpnGateCsvParser {
    private static final String HEADER_PREFIX = "#HostName,";

    private VpnGateCsvParser() {}

    static List<VpnGateServer> parse(String csv) {
        List<VpnGateServer> servers = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return servers;

        String[] lines = csv.replace("\uFEFF", "").split("\\r?\\n");
        boolean foundHeader = false;
        for (String line : lines) {
            if (!foundHeader) {
                if (line.startsWith(HEADER_PREFIX)) foundHeader = true;
                continue;
            }
            if (line.trim().isEmpty() || line.startsWith("*")) continue;

            List<String> columns = parseRow(line);
            if (columns.size() < 15) continue;

            String profile = columns.get(14).trim();
            if (profile.isEmpty()) continue;

            try {
                decodeProfile(profile);
                servers.add(new VpnGateServer(
                        columns.get(0).trim(),
                        columns.get(1).trim(),
                        columns.get(5).trim(),
                        columns.get(6).trim(),
                        columns.get(3).trim(),
                        parseLong(columns.get(4)),
                        columns.get(7).trim(),
                        columns.get(11).trim(),
                        columns.get(12).trim(),
                        profile));
            } catch (RuntimeException ignored) {
                // A malformed row should not prevent the remaining relays from loading.
            }
        }
        return servers;
    }

    static String decodeProfile(String profileBase64) {
        byte[] decoded = Base64.getMimeDecoder().decode(profileBase64);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static List<String> parseRow(String row) {
        List<String> columns = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < row.length(); i++) {
            char current = row.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                columns.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        columns.add(field.toString());
        return columns;
    }
}
