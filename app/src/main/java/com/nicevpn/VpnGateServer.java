package com.nicevpn;

final class VpnGateServer {
    final String host;
    final String ip;
    final String country;
    final String countryCode;
    final String ping;
    final long speedBitsPerSecond;
    final String sessions;
    final String logPolicy;
    final String operator;
    final String profileBase64;

    VpnGateServer(
            String host,
            String ip,
            String country,
            String countryCode,
            String ping,
            long speedBitsPerSecond,
            String sessions,
            String logPolicy,
            String operator,
            String profileBase64) {
        this.host = host;
        this.ip = ip;
        this.country = country;
        this.countryCode = countryCode;
        this.ping = ping;
        this.speedBitsPerSecond = speedBitsPerSecond;
        this.sessions = sessions;
        this.logPolicy = logPolicy;
        this.operator = operator;
        this.profileBase64 = profileBase64;
    }

    String speedLabel() {
        if (speedBitsPerSecond <= 0) return "Unknown speed";
        double mbps = speedBitsPerSecond / 1_000_000.0;
        return String.format(java.util.Locale.US, "%.1f Mbps", mbps);
    }
}
