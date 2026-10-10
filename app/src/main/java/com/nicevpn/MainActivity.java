package com.nicevpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

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
    private static final int REQUEST_API_PERMISSION = 42;
    private static final int REQUEST_VPN_PERMISSION = 43;
    private static final String API_URL = "https://www.vpngate.net/api/iphone/";
    private static final String OPENVPN_PACKAGE = "de.blinkt.openvpn";
    private static final String OPENVPN_SERVICE_ACTION = "de.blinkt.openvpn.api.IOpenVPNAPIService";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<VpnGateServer> servers = new ArrayList<>();
    private LinearLayout content;
    private TextView status;
    private TextView vpnBookCredentialStatus;
    private volatile String vpnBookPassword;
    private String activeProvider = "chooser";
    private String pendingInlineConfig;
    private String pendingConnectionLabel;
    private ProgressBar progress;
    private VpnGateServer pendingExport;
    private VpnGateServer pendingConnect;
    private IOpenVPNAPIService openVpnService;
    private boolean serviceBound;
    private boolean callbackRegistered;
    private boolean relayConnectWatchdogArmed;
    private long relayConnectWatchdogGeneration;
    private VpnGateServer activeGateServer;
    private VpnBookServer activeVpnBookServer;
    private boolean vpnBookTcpFallbackAttempted;
    private final List<String> attemptedGateHosts = new ArrayList<>();
    private static final int MAX_AUTO_RELAY_RETRIES = 3;
    private static final long RELAY_CONNECT_WARNING_MS = 30_000L;

    private final IOpenVPNStatusCallback statusCallback = new IOpenVPNStatusCallback.Stub() {
        @Override
        public void newStatus(String uuid, String state, String message, String level) {
            mainHandler.post(() -> {
                String shownState = state == null || state.trim().isEmpty() ? "unknown" : state;
                if ("TCP_CONNECT".equalsIgnoreCase(shownState)) {
                    armRelayConnectWatchdog();
                    status.setText("Trying to reach the selected VPN relay…");
                } else if (isRelayConnectingState(shownState)) {
                    armRelayConnectWatchdog();
                    status.setText("Waiting for the selected VPN relay…");
                } else if ("CONNECTRETRY".equalsIgnoreCase(shownState)) {
                    armRelayConnectWatchdog();
                    status.setText("This relay is not responding. It is retrying; if this continues, disconnect and choose another relay.");
                } else if ("CONNECTED".equalsIgnoreCase(shownState)) {
                    clearRelayConnectWatchdog();
                    status.setText("VPN connected"
                            + (message == null || message.trim().isEmpty() ? "" : " · " + message));
                } else {
                    clearRelayConnectWatchdog();
                    status.setText("VPN status: " + shownState
                            + (message == null || message.trim().isEmpty() ? "" : " · " + message));
                }
            });
        }
    };

    private boolean isRelayConnectingState(String state) {
        return "CONNECTING".equalsIgnoreCase(state)
                || "WAIT".equalsIgnoreCase(state)
                || "RESOLVE".equalsIgnoreCase(state)
                || "AUTH".equalsIgnoreCase(state)
                || "GET_CONFIG".equalsIgnoreCase(state)
                || "ASSIGN_IP".equalsIgnoreCase(state)
                || "UDP_CONNECT".equalsIgnoreCase(state);
    }

    private void armRelayConnectWatchdog() {
        if (relayConnectWatchdogArmed) return;
        relayConnectWatchdogArmed = true;
        long generation = ++relayConnectWatchdogGeneration;
        mainHandler.postDelayed(() -> {
            if (relayConnectWatchdogArmed && relayConnectWatchdogGeneration == generation) {
                if (activeGateServer != null) {
                    retryNextVpnGateRelay();
                } else if ("vpnbook".equals(activeProvider)
                        && activeVpnBookServer != null && !vpnBookTcpFallbackAttempted) {
                    retryVpnBookWithTcp();
                } else {
                    status.setText("This relay has not responded after 30 seconds. Disconnect and select another relay.");
                }
            }
        }, RELAY_CONNECT_WARNING_MS);
    }

    private void clearRelayConnectWatchdog() {
        relayConnectWatchdogArmed = false;
        relayConnectWatchdogGeneration++;
    }

    private void retryNextVpnGateRelay() {
        if (!"gate".equals(activeProvider)) {
            clearRelayConnectWatchdog();
            return;
        }
        if (attemptedGateHosts.size() > MAX_AUTO_RELAY_RETRIES) {
            clearRelayConnectWatchdog();
            status.setText("Four VPN Gate relays did not respond. Select a different server and retry.");
            return;
        }

        VpnGateServer next = null;
        for (VpnGateServer candidate : servers) {
            if (!attemptedGateHosts.contains(candidate.host)) {
                next = candidate;
                break;
            }
        }
        if (next == null) {
            clearRelayConnectWatchdog();
            status.setText("No more VPN Gate relays are available to try. Refresh the list and retry.");
            return;
        }

        clearRelayConnectWatchdog();
        attemptedGateHosts.add(next.host);
        activeGateServer = next;
        pendingConnect = next;
        pendingInlineConfig = null;
        pendingConnectionLabel = next.country + " relay (automatic retry)";
        status.setText("This relay did not respond. Automatically trying another VPN Gate relay…");

        try {
            if (openVpnService != null) openVpnService.disconnect();
        } catch (RemoteException ignored) {
            // Continue with the next relay; startVPN replaces any retrying profile.
        }
        VpnGateServer selected = next;
        mainHandler.postDelayed(() -> {
            if ("gate".equals(activeProvider) && pendingConnect == selected && openVpnService != null) {
                requestVpnPermission();
            }
        }, 1500L);
    }

    private void retryVpnBookWithTcp() {
        VpnBookServer server = activeVpnBookServer;
        String password = vpnBookPassword;
        if (server == null || password == null || vpnBookTcpFallbackAttempted) {
            clearRelayConnectWatchdog();
            status.setText("VPNBook relay did not respond. Disconnect and try another server.");
            return;
        }
        clearRelayConnectWatchdog();
        vpnBookTcpFallbackAttempted = true;
        status.setText("UDP 25000 did not respond. Retrying this server with TCP 443…");
        try {
            if (openVpnService != null) openVpnService.disconnect();
        } catch (RemoteException ignored) {
            // Continue with TCP; startVPN replaces the retrying profile.
        }
        executor.execute(() -> {
            try {
                String profile = VpnBookConfigFetcher.downloadProfile(server, password, false);
                mainHandler.post(() -> {
                    if (!"vpnbook".equals(activeProvider) || activeVpnBookServer != server) return;
                    pendingConnect = null;
                    pendingInlineConfig = profile;
                    pendingConnectionLabel = "VPNBook " + server.country + " (TCP 443 fallback)";
                    status.setText("Starting VPNBook TCP 443 fallback…");
                    requestVpnPermission();
                });
            } catch (Exception error) {
                String reason = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                String failure = reason;
                mainHandler.post(() -> status.setText("VPNBook UDP and TCP profiles failed: " + failure));
            }
        });
    }

    private final ServiceConnection openVpnConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            openVpnService = IOpenVPNAPIService.Stub.asInterface(binder);
            requestOpenVpnApiPermission();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            openVpnService = null;
            callbackRegistered = false;
            status.setText("Built-in VPN engine disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
        showProviderChooser();
        refreshVpnBookPassword();
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
        note.setText("Choose a VPN provider, then select a server to connect");
        note.setTextSize(13);
        note.setTextColor(0xFF52616B);
        note.setPadding(0, dp(6), 0, dp(8));
        root.addView(note);

        LinearLayout providerButtons = new LinearLayout(this);
        providerButtons.setGravity(Gravity.CENTER_VERTICAL);
        Button vpnGateButton = new Button(this);
        vpnGateButton.setText("VPN Gate");
        vpnGateButton.setOnClickListener(v -> showVpnGateProvider());
        providerButtons.addView(vpnGateButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button vpnBookButton = new Button(this);
        vpnBookButton.setText("VPNBook");
        vpnBookButton.setOnClickListener(v -> showVpnBookProvider());
        providerButtons.addView(vpnBookButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(providerButtons);

        vpnBookCredentialStatus = new TextView(this);
        vpnBookCredentialStatus.setText("VPNBook password: fetching current password…");
        vpnBookCredentialStatus.setTextSize(12);
        vpnBookCredentialStatus.setTextColor(0xFF52616B);
        vpnBookCredentialStatus.setPadding(0, 0, 0, dp(8));
        root.addView(vpnBookCredentialStatus);

        status = new TextView(this);
        status.setText("Choose a VPN provider.");
        status.setTextSize(14);
        status.setTextColor(0xFF425563);
        status.setPadding(0, dp(8), 0, dp(4));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refreshCurrentProvider());
        actions.addView(refresh);

        Button disconnect = new Button(this);
        disconnect.setText("Disconnect");
        disconnect.setOnClickListener(v -> disconnectVpn());
        actions.addView(disconnect);
        root.addView(actions);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView disclaimer = new TextView(this);
        disclaimer.setText("NiceVPN includes its own VPN engine. VPN Gate relays are volunteer-operated; VPNBook is a shared free service.");
        disclaimer.setTextSize(12);
        disclaimer.setTextColor(0xFF6A4B24);
        disclaimer.setPadding(dp(10), dp(10), dp(10), dp(10));
        disclaimer.setBackgroundColor(0xFFFFF1D8);
        root.addView(disclaimer);

        setContentView(root);
    }

    private void showProviderChooser() {
        activeProvider = "chooser";
        status.setText("Choose VPN Gate or VPNBook above.");
        progress.setVisibility(View.GONE);
        content.removeAllViews();
        TextView hint = new TextView(this);
        hint.setText("Choose a provider to view its servers. VPNBook credentials are refreshed whenever NiceVPN opens.");
        hint.setTextSize(15);
        hint.setTextColor(0xFF52616B);
        hint.setPadding(dp(8), dp(16), dp(8), dp(16));
        content.addView(hint);
    }

    private void showVpnGateProvider() {
        activeProvider = "gate";
        if (servers.isEmpty()) {
            refreshServers();
        } else {
            showServers(new ArrayList<>(servers));
        }
    }

    private void showVpnBookProvider() {
        activeProvider = "vpnbook";
        showVpnBookServers();
    }

    private void refreshCurrentProvider() {
        if ("vpnbook".equals(activeProvider)) {
            refreshVpnBookPassword();
            showVpnBookServers();
        } else if ("gate".equals(activeProvider)) {
            refreshServers();
        } else {
            showProviderChooser();
        }
    }

    private void showVpnBookServers() {
        progress.setVisibility(View.GONE);
        content.removeAllViews();
        List<VpnBookServer> bookServers = VpnBookServer.available();
        status.setText(bookServers.size() + " VPNBook OpenVPN servers · UDP 25000 first, TCP 443 fallback");
        for (VpnBookServer server : bookServers) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, dp(6), 0, dp(6));
            card.setLayoutParams(params);

            TextView name = new TextView(this);
            name.setText(server.country + " · " + server.host);
            name.setTextSize(17);
            name.setTypeface(null, 1);
            name.setTextColor(0xFF17384B);
            card.addView(name);

            TextView details = new TextView(this);
            details.setText("OpenVPN · UDP 25000 · shared free relay");
            details.setTextSize(13);
            details.setTextColor(0xFF52616B);
            details.setPadding(0, dp(5), 0, dp(8));
            card.addView(details);

            Button connect = new Button(this);
            connect.setText("Connect");
            connect.setEnabled(vpnBookPassword != null);
            connect.setOnClickListener(v -> connectToVpnBook(server));
            card.addView(connect);
            content.addView(card);
        }
    }

    private void refreshVpnBookPassword() {
        vpnBookCredentialStatus.setText("VPNBook password: fetching current password…");
        executor.execute(() -> {
            try {
                String password = VpnBookCredentialFetcher.fetchCurrentPassword();
                vpnBookPassword = password;
                mainHandler.post(() -> {
                    vpnBookCredentialStatus.setText("VPNBook password retrieved for this session.");
                    if ("vpnbook".equals(activeProvider)) showVpnBookServers();
                });
            } catch (Exception error) {
                vpnBookPassword = null;
                mainHandler.post(() -> {
                    vpnBookCredentialStatus.setText("VPNBook password unavailable. Check internet and tap Refresh.");
                    if ("vpnbook".equals(activeProvider)) showVpnBookServers();
                });
            }
        });
    }

    private void refreshServers() {
        progress.setVisibility(View.VISIBLE);
        status.setText("Loading VPN Gate server list…");
        executor.execute(() -> {
            try {
                String body = downloadCsv();
                List<VpnGateServer> result = VpnGateCsvParser.parse(body);
                result.sort(Comparator.comparingLong((VpnGateServer s) -> s.speedBitsPerSecond).reversed());
                mainHandler.post(() -> {
                    servers.clear();
                    servers.addAll(result);
                    if ("gate".equals(activeProvider)) showServers(result);
                    else progress.setVisibility(View.GONE);
                });
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

        Button connect = new Button(this);
        connect.setText("Connect");
        connect.setOnClickListener(v -> connectTo(server));
        card.addView(connect);

        Button export = new Button(this);
        export.setText("Save profile only");
        export.setOnClickListener(v -> saveProfile(server));
        card.addView(export);
        return card;
    }

    private void connectTo(VpnGateServer server) {
        clearRelayConnectWatchdog();
        activeVpnBookServer = null;
        vpnBookTcpFallbackAttempted = false;
        attemptedGateHosts.clear();
        attemptedGateHosts.add(server.host);
        activeGateServer = server;
        pendingConnect = server;
        pendingInlineConfig = null;
        pendingConnectionLabel = server.country + " relay";
        status.setText("Starting selected relay…");
        beginOpenVpnConnection();
    }

    private void connectToVpnBook(VpnBookServer server) {
        clearRelayConnectWatchdog();
        activeGateServer = null;
        activeVpnBookServer = server;
        vpnBookTcpFallbackAttempted = false;
        attemptedGateHosts.clear();
        String password = vpnBookPassword;
        if (password == null || password.isEmpty()) {
            status.setText("VPNBook password is not ready. Tap Refresh and try again.");
            refreshVpnBookPassword();
            return;
        }
        status.setText("Downloading fast UDP 25000 profile for " + server.host + "…");
        executor.execute(() -> {
            try {
                String profile = VpnBookConfigFetcher.downloadProfile(server, password);
                mainHandler.post(() -> {
                    pendingConnect = null;
                    pendingInlineConfig = profile;
                    pendingConnectionLabel = "VPNBook " + server.country + " (UDP 25000)";
                    status.setText("Starting VPNBook " + server.country + " relay…");
                    beginOpenVpnConnection();
                });
            } catch (Exception error) {
                String reason = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                if (reason.length() > 110) reason = reason.substring(0, 107) + "…";
                String failure = reason;
                mainHandler.post(() -> status.setText("VPNBook profile failed: " + failure));
            }
        });
    }

    private void beginOpenVpnConnection() {
        if (openVpnService != null) {
            requestVpnPermission();
            return;
        }
        Intent serviceIntent = new Intent(OPENVPN_SERVICE_ACTION);
        serviceIntent.setPackage(OPENVPN_PACKAGE);
        try {
            serviceBound = bindService(serviceIntent, openVpnConnection, Context.BIND_AUTO_CREATE);
            if (!serviceBound) showOpenVpnMissing();
        } catch (RuntimeException error) {
            showOpenVpnMissing();
        }
    }

    private void showOpenVpnMissing() {
        status.setText("OpenVPN for Android is required to connect.");
        new AlertDialog.Builder(this)
                .setTitle("Install OpenVPN for Android")
                .setMessage("NiceVPN uses OpenVPN for Android as its VPN tunnel engine. Install it, then return to NiceVPN and tap Connect again.")
                .setPositiveButton("Install", (dialog, which) -> openOpenVpnStorePage())
                .setNegativeButton("Later", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void openOpenVpnStorePage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + OPENVPN_PACKAGE)));
        } catch (RuntimeException noStoreApp) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + OPENVPN_PACKAGE)));
        }
    }

    private void requestOpenVpnApiPermission() {
        if (openVpnService == null) return;
        try {
            Intent consent = openVpnService.prepare(getPackageName());
            if (consent != null) {
                startActivityForResult(consent, REQUEST_API_PERMISSION);
            } else {
                requestVpnPermission();
            }
        } catch (RemoteException error) {
            status.setText("Could not request OpenVPN client permission.");
        }
    }

    private void requestVpnPermission() {
        if (openVpnService == null || (pendingConnect == null && pendingInlineConfig == null)) return;
        try {
            Intent consent = openVpnService.prepareVPNService();
            if (consent != null) {
                startActivityForResult(consent, REQUEST_VPN_PERMISSION);
            } else {
                startSelectedServer();
            }
        } catch (RemoteException error) {
            status.setText("Could not request Android VPN permission.");
        }
    }

    private void startSelectedServer() {
        if (openVpnService == null || (pendingConnect == null && pendingInlineConfig == null)) return;
        try {
            if (!callbackRegistered) {
                openVpnService.registerStatusCallback(statusCallback);
                callbackRegistered = true;
            }
            String config = pendingInlineConfig != null
                    ? pendingInlineConfig
                    : VpnGateCsvParser.decodeProfile(pendingConnect.profileBase64);
            String label = pendingConnectionLabel == null ? "VPN relay" : pendingConnectionLabel;
            clearRelayConnectWatchdog();
            status.setText("Starting " + label + "…");
            openVpnService.startVPN(config);
            pendingInlineConfig = null;
            pendingConnect = null;
            pendingConnectionLabel = null;
        } catch (Exception error) {
            status.setText("OpenVPN could not start this relay.");
            Toast.makeText(this, error.getMessage() == null ? "Could not start VPN" : error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void disconnectVpn() {
        clearRelayConnectWatchdog();
        activeGateServer = null;
        activeVpnBookServer = null;
        vpnBookTcpFallbackAttempted = false;
        attemptedGateHosts.clear();
        if (openVpnService == null) {
            status.setText("No VPN connection is active.");
            return;
        }
        try {
            openVpnService.disconnect();
            status.setText("Disconnect requested");
        } catch (RemoteException error) {
            status.setText("Could not disconnect OpenVPN.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_API_PERMISSION) {
            if (resultCode == RESULT_OK) requestVpnPermission();
            else status.setText("OpenVPN client permission was not granted.");
            return;
        }
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startSelectedServer();
            else status.setText("Android VPN permission was not granted.");
            return;
        }
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

    private void saveProfile(VpnGateServer server) {
        pendingExport = server;
        String fileName = server.countryCode + "-" + server.host + ".ovpn";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-openvpn-profile");
        intent.putExtra(Intent.EXTRA_TITLE, fileName.replaceAll("[^A-Za-z0-9._-]", "_"));
        startActivityForResult(intent, CREATE_PROFILE_REQUEST);
    }

    private String display(String value, String fallback) {
        return value == null || value.trim().isEmpty() || value.equals("-") ? fallback : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (openVpnService != null && callbackRegistered) {
            try {
                openVpnService.unregisterStatusCallback(statusCallback);
            } catch (RemoteException ignored) {
                // The remote app may have stopped.
            }
        }
        if (serviceBound) {
            unbindService(openVpnConnection);
            serviceBound = false;
        }
        vpnBookPassword = null;
        pendingInlineConfig = null;
        executor.shutdownNow();
        super.onDestroy();
    }
}
