package com.nicevpn;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CREATE_PROFILE_REQUEST = 41;
    private static final String API_URL = "https://www.vpngate.net/api/iphone/";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<VpnGateServer> servers = new ArrayList<>();
    private LinearLayout content;
    private TextView status;
    private ProgressBar progress;
    private VpnGateServer pendingExport;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
        refreshServers();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(12));
        root.setBackgroundColor(0xFFF5F7FA);

        TextView title = new TextView(this);
        title.setText("NiceVPN");
        title.setTextSize(26);
        title.setTextColor(0xFF122A3A);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("VPN Gate volunteer relays · choose a server and export its OpenVPN profile");
        note.setTextSize(13);
        note.setTextColor(0xFF52616B);
        note.setPadding(0, dp(6), 0, dp(12));
        root.addView(note);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setText("Loading servers…");
        status.setTextSize(14);
        status.setTextColor(0xFF425563);
        actions.addView(status, new LinearLayout.LayoutParams(0, -2, 1));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refreshServers());
        actions.addView(refresh);
        root.addView(actions);

        progress = new ProgressBar(this);
        root.addView(progress);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView disclaimer = new TextView(this);
        disclaimer.setText("This first build does not create a VPN tunnel. Relay operators may log traffic; policies differ by server.");
        disclaimer.setTextSize(12);
        disclaimer.setTextColor(0xFF6A4B24);
        disclaimer.setPadding(dp(10), dp(10), dp(10), dp(10));
        disclaimer.setBackgroundColor(0xFFFFF1D8);
        root.addView(disclaimer);

        setContentView(root);
    }

    private void refreshServers() {
        progress.setVisibility(View.VISIBLE);
        status.setText("Loading VPN Gate server list…");
        executor.execute(() -> {
            try {
                String body = downloadCsv();
                List<VpnGateServer> result = VpnGateCsvParser.parse(body);
                result.sort(Comparator.comparingLong((VpnGateServer s) -> s.speedBitsPerSecond).reversed());
                mainHandler.post(() -> showServers(result));
            } catch (Exception error) {
                mainHandler.post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Could not load servers. Check your connection and retry.");
                    Toast.makeText(this, error.getMessage() == null ? "Server list unavailable" : error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String downloadCsv() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("User-Agent", "NiceVPN/0.1 Android");
        connection.setRequestProperty("Accept", "text/plain");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("VPN Gate returned HTTP " + code);
            try (InputStream stream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line).append('\n');
                    if (body.length() > 6_000_000) throw new IllegalStateException("Server list is unexpectedly large");
                }
                return body.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void showServers(List<VpnGateServer> result) {
        progress.setVisibility(View.GONE);
        servers.clear();
        servers.addAll(result);
        content.removeAllViews();
        status.setText(result.size() + " servers · sorted by reported speed");

        if (result.isEmpty()) {
            status.setText("No usable OpenVPN profiles were returned.");
            return;
        }

        int count = Math.min(result.size(), 100);
        for (int i = 0; i < count; i++) {
            content.addView(serverCard(result.get(i)));
        }
    }

    private View serverCard(VpnGateServer server) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(cardParams);

        TextView name = new TextView(this);
        name.setText(server.country + " · " + server.host);
        name.setTextSize(17);
        name.setTypeface(null, 1);
        name.setTextColor(0xFF17384B);
        card.addView(name);

        TextView details = new TextView(this);
        details.setText(server.speedLabel()
                + " · Ping " + display(server.ping, "unknown") + " ms"
                + " · " + display(server.sessions, "?") + " sessions"
                + "\nLogging policy: " + display(server.logPolicy, "not listed")
                + (server.operator.isEmpty() ? "" : "\nOperator: " + server.operator));
        details.setTextSize(13);
        details.setTextColor(0xFF52616B);
        details.setPadding(0, dp(5), 0, dp(8));
        card.addView(details);

        Button export = new Button(this);
        export.setText("Save OpenVPN profile");
        export.setOnClickListener(v -> saveProfile(server));
        card.addView(export);
        return card;
    }

    private void saveProfile(VpnGateServer server) {
        pendingExport = server;
        String fileName = server.countryCode + "-" + server.host + ".ovpn";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-openvpn-profile");
        intent.putExtra(Intent.EXTRA_TITLE, fileName.replaceAll("[^A-Za-z0-9._-]", "_"));
        startActivityForResult(intent, CREATE_PROFILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_PROFILE_REQUEST || resultCode != RESULT_OK || data == null || pendingExport == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("Could not open destination");
            byte[] profile = VpnGateCsvParser.decodeProfile(pendingExport.profileBase64).getBytes(StandardCharsets.UTF_8);
            output.write(profile);
            Toast.makeText(this, "Profile saved. Open it with an OpenVPN client.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Could not save profile: " + error.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            pendingExport = null;
        }
    }

    private String display(String value, String fallback) {
        return value == null || value.trim().isEmpty() || value.equals("-") ? fallback : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
