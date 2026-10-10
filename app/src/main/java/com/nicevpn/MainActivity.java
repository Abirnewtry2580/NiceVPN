package com.nicevpn;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
    private TextView providerSelection;
    private Button vpnGateButton;
    private Button vpnBookButton;
    private Button connectButton;
    private GlobeView globe;
    private VpnGateServer selectedGateServer;
    private VpnBookServer selectedVpnBookServer;
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
                if ("TCP_CONNECT".equalsIgnoreCase(shownState)
                        || isRelayConnectingState(shownState)
                        || "CONNECTRETRY".equalsIgnoreCase(shownState)) {
                    globe.setSpinning(true);
                    armRelayConnectWatchdog();
                    status.setText("Connecting through " + providerName() + "…");
                } else if ("CONNECTED".equalsIgnoreCase(shownState)) {
                    clearRelayConnectWatchdog();
                    String country = activeCountry();
                    globe.setConnectedCountry(country);
                    status.setText("Connected · " + country);
                } else {
                    clearRelayConnectWatchdog();
                    if ("EXITING".equalsIgnoreCase(shownState) || "NOPROCESS".equalsIgnoreCase(shownState)) {
                        globe.clearConnection();
                        status.setText("Disconnected");
                    } else {
                        status.setText("VPN status: " + shownState);
                    }
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
        note.setText("Select a provider, then connect");
        note.setTextSize(14);
        note.setTextColor(0xFF52616B);
        note.setPadding(0, dp(6), 0, dp(10));
        root.addView(note);

        LinearLayout providers = new LinearLayout(this);
        providers.setGravity(Gravity.CENTER_VERTICAL);
        vpnGateButton = new Button(this);
        vpnGateButton.setText("VPN Gate");
        vpnGateButton.setOnClickListener(v -> showVpnGateProvider());
        providers.addView(vpnGateButton, new LinearLayout.LayoutParams(0, -2, 1));
        vpnBookButton = new Button(this);
        vpnBookButton.setText("VPNBook");
        vpnBookButton.setOnClickListener(v -> showVpnBookProvider());
        providers.addView(vpnBookButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(providers);

        providerSelection = new TextView(this);
        providerSelection.setText("Choose VPN Gate or VPNBook");
        providerSelection.setTextSize(14);
        providerSelection.setTypeface(null, 1);
        providerSelection.setTextColor(0xFF17384B);
        providerSelection.setPadding(0, dp(8), 0, dp(4));
        root.addView(providerSelection);

        vpnBookCredentialStatus = new TextView(this);
        vpnBookCredentialStatus.setText("VPNBook password: fetching current password…");
        vpnBookCredentialStatus.setTextSize(12);
        vpnBookCredentialStatus.setTextColor(0xFF52616B);
        vpnBookCredentialStatus.setVisibility(View.GONE);
        root.addView(vpnBookCredentialStatus);

        globe = new GlobeView();
        root.addView(globe, new LinearLayout.LayoutParams(-1, dp(250)));

        status = new TextView(this);
        status.setText("Choose a provider to begin");
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(0xFF425563);
        status.setPadding(dp(8), dp(4), dp(8), dp(8));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        connectButton = new Button(this);
        connectButton.setText("Connect");
        connectButton.setEnabled(false);
        connectButton.setOnClickListener(v -> connectSelectedProvider());
        root.addView(connectButton, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
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

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        root.addView(content);

        TextView disclaimer = new TextView(this);
        disclaimer.setText("VPN Gate relays are volunteer-operated. VPNBook is a shared free service.");
        disclaimer.setTextSize(11);
        disclaimer.setTextColor(0xFF6A4B24);
        disclaimer.setPadding(dp(10), dp(8), dp(10), dp(8));
        disclaimer.setBackgroundColor(0xFFFFF1D8);
        root.addView(disclaimer);

        setContentView(root);
        updateProviderButtons();
    }

    private void updateProviderButtons() {
        if (vpnGateButton == null || vpnBookButton == null) return;
        boolean gate = "gate".equals(activeProvider);
        boolean book = "vpnbook".equals(activeProvider);
        vpnGateButton.setBackgroundTintList(ColorStateList.valueOf(gate ? 0xFF17384B : 0xFF5A5A5A));
        vpnBookButton.setBackgroundTintList(ColorStateList.valueOf(book ? 0xFF17384B : 0xFF5A5A5A));
        vpnGateButton.setTextColor(0xFFFFFFFF);
        vpnBookButton.setTextColor(0xFFFFFFFF);
    }

    private String providerName() {
        return "vpnbook".equals(activeProvider) ? "VPNBook" : "VPN Gate";
    }

    private String activeCountry() {
        if (activeGateServer != null && activeGateServer.country != null) return activeGateServer.country;
        if (activeVpnBookServer != null && activeVpnBookServer.country != null) return activeVpnBookServer.country;
        return "selected location";
    }

    private void showProviderChooser() {
        activeProvider = "chooser";
        updateProviderButtons();
        providerSelection.setText("Choose VPN Gate or VPNBook");
        vpnBookCredentialStatus.setVisibility(View.GONE);
        status.setText("Choose a provider to begin");
        progress.setVisibility(View.GONE);
        connectButton.setEnabled(false);
        content.removeAllViews();
        globe.clearConnection();
    }

    private void showVpnGateProvider() {
        activeProvider = "gate";
        updateProviderButtons();
        vpnBookCredentialStatus.setVisibility(View.GONE);
        providerSelection.setText("Selected provider: VPN Gate");
        if (servers.isEmpty()) {
            refreshServers();
        } else {
            showServers(new ArrayList<>(servers));
        }
    }

    private void showVpnBookProvider() {
        activeProvider = "vpnbook";
        updateProviderButtons();
        vpnBookCredentialStatus.setVisibility(View.VISIBLE);
        providerSelection.setText("Selected provider: VPNBook");
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
        selectedVpnBookServer = bookServers.isEmpty() ? null : bookServers.get(0);
        providerSelection.setText(selectedVpnBookServer == null
                ? "Selected provider: VPNBook · no servers available"
                : "Selected provider: VPNBook · location selected automatically");
        status.setText(selectedVpnBookServer == null
                ? "No VPNBook servers are available. Tap Refresh to retry."
                : "Ready to connect through VPNBook");
        connectButton.setEnabled(selectedVpnBookServer != null && vpnBookPassword != null);
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
        selectedGateServer = result.isEmpty() ? null : result.get(0);
        if (selectedGateServer == null) {
            providerSelection.setText("Selected provider: VPN Gate · no servers available");
            status.setText("No usable VPN Gate relays were found. Tap Refresh to retry.");
            connectButton.setEnabled(false);
            return;
        }
        providerSelection.setText("Selected provider: VPN Gate · fastest available location selected");
        status.setText("Ready to connect through VPN Gate");
        connectButton.setEnabled(true);
    }

    private void connectSelectedProvider() {
        if ("gate".equals(activeProvider)) {
            if (selectedGateServer == null) {
                refreshServers();
            } else {
                connectTo(selectedGateServer);
            }
        } else if ("vpnbook".equals(activeProvider)) {
            if (selectedVpnBookServer == null) {
                showVpnBookServers();
            } else {
                connectToVpnBook(selectedVpnBookServer);
            }
        }
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
        globe.setSpinning(true);
        status.setText("Connecting through VPN Gate…");
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
        globe.setSpinning(true);
        status.setText("Preparing VPNBook connection…");
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
                mainHandler.post(() -> {
                    globe.setSpinning(false);
                    status.setText("VPNBook profile failed: " + failure);
                });
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
        globe.clearConnection();
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

    private final class GlobeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean spinning;
        private boolean connected;
        private String country;
        private float rotation;

        GlobeView() {
            super(MainActivity.this);
        }

        void setSpinning(boolean value) {
            spinning = value;
            if (value) connected = false;
            invalidate();
        }

        void setConnectedCountry(String value) {
            country = value == null ? "Selected location" : value;
            connected = true;
            spinning = false;
            invalidate();
        }

        void clearConnection() {
            spinning = false;
            connected = false;
            country = null;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.39f;
            if (radius <= 0) return;

            paint.setShader(new RadialGradient(cx - radius * .32f, cy - radius * .38f,
                    radius * 1.65f, 0xFF65B7D8, 0xFF12364F, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1) + getResources().getDisplayMetrics().density * 0.4f);
            paint.setColor(0xFF9DDCF0);
            canvas.drawCircle(cx, cy, radius, paint);

            int save = canvas.save();
            canvas.clipPath(new Path() {{
                addCircle(cx, cy, radius - dp(1), Path.Direction.CW);
            }});
            canvas.rotate(rotation, cx, cy);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x667ED4E7);
            for (float factor : new float[]{0.25f, 0.52f, 0.78f}) {
                float half = radius * factor;
                canvas.drawOval(cx - half, cy - radius, cx + half, cy + radius, paint);
            }
            for (float factor : new float[]{-0.66f, -0.34f, 0f, 0.34f, 0.66f}) {
                float y = cy + radius * factor;
                float halfWidth = radius * (float)Math.sqrt(1f - factor * factor);
                canvas.drawOval(cx - halfWidth, y - radius * .12f, cx + halfWidth, y + radius * .12f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF66B77B);
            Path land = new Path();
            land.moveTo(cx - radius*.62f, cy - radius*.18f);
            land.cubicTo(cx-radius*.68f, cy-radius*.45f, cx-radius*.36f, cy-radius*.55f, cx-radius*.24f, cy-radius*.31f);
            land.cubicTo(cx-radius*.13f, cy-radius*.16f, cx-radius*.25f, cy-radius*.02f, cx-radius*.2f, cy+radius*.17f);
            land.cubicTo(cx-radius*.25f, cy+radius*.34f, cx-radius*.42f, cy+radius*.5f, cx-radius*.45f, cy+radius*.31f);
            land.cubicTo(cx-radius*.51f, cy+radius*.16f, cx-radius*.68f, cy+radius*.08f, cx-radius*.62f, cy-radius*.18f);
            land.close();
            canvas.drawPath(land, paint);
            Path asia = new Path();
            asia.moveTo(cx-radius*.1f, cy-radius*.35f);
            asia.cubicTo(cx+radius*.05f, cy-radius*.55f, cx+radius*.42f, cy-radius*.48f, cx+radius*.62f, cy-radius*.28f);
            asia.cubicTo(cx+radius*.72f, cy-radius*.13f, cx+radius*.5f, cy+radius*.02f, cx+radius*.28f, cy-radius*.05f);
            asia.cubicTo(cx+radius*.16f, cy+radius*.02f, cx+radius*.06f, cy-radius*.12f, cx-radius*.1f, cy-radius*.12f);
            asia.close();
            canvas.drawPath(asia, paint);
            Path africa = new Path();
            africa.moveTo(cx-radius*.09f, cy+radius*.02f);
            africa.cubicTo(cx+radius*.14f, cy-radius*.02f, cx+radius*.24f, cy+radius*.14f, cx+radius*.12f, cy+radius*.42f);
            africa.cubicTo(cx+radius*.03f, cy+radius*.56f, cx-radius*.09f, cy+radius*.37f, cx-radius*.14f, cy+radius*.2f);
            africa.close();
            canvas.drawPath(africa, paint);
            canvas.restoreToCount(save);

            if (connected) drawPin(canvas, cx, cy, radius);
            if (spinning) {
                rotation = (rotation + 4f) % 360f;
                postInvalidateDelayed(35L);
            }
        }

        private void drawPin(Canvas canvas, float cx, float cy, float radius) {
            float[] point = countryPoint(country);
            float x = cx + point[0] * radius;
            float y = cy + point[1] * radius;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE53935);
            Path pin = new Path();
            pin.moveTo(x, y + dp(11));
            pin.cubicTo(x - dp(2), y + dp(4), x - dp(10), y - dp(2), x - dp(10), y - dp(9));
            pin.arcTo(new RectF(x - dp(10), y - dp(19), x + dp(10), y + dp(1)), 180, 360);
            pin.cubicTo(x + dp(10), y - dp(2), x + dp(2), y + dp(4), x, y + dp(11));
            pin.close();
            canvas.drawPath(pin, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(x, y - dp(9), dp(3), paint);
        }

        private float[] countryPoint(String value) {
            String c = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
            if (c.contains("japan")) return new float[]{.62f, -.10f};
            if (c.contains("canada")) return new float[]{-.34f, -.38f};
            if (c.contains("united states") || c.contains("usa") || c.equals("us")) return new float[]{-.48f, -.02f};
            if (c.contains("bangladesh")) return new float[]{.42f, .10f};
            if (c.contains("germany")) return new float[]{.08f, -.16f};
            if (c.contains("france")) return new float[]{.03f, -.10f};
            if (c.contains("united kingdom") || c.contains("uk")) return new float[]{-.02f, -.19f};
            if (c.contains("singapore")) return new float[]{.36f, .22f};
            if (c.contains("netherlands")) return new float[]{.06f, -.20f};
            if (c.contains("india")) return new float[]{.30f, .08f};
            return new float[]{.08f, .02f};
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
