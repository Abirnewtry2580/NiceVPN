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
        private final List<WorldMapData.Ring> rings = WorldMapData.RINGS;
        private boolean spinning;
        private boolean focusing;
        private boolean pinVisible;
        private float rotation;
        private float centerLatitude;
        private float zoom = 1f;
        private float targetLongitude;
        private float targetLatitude;
        private int selectedCountry = -1;
        private String connectedCountry;

        GlobeView() {
            super(MainActivity.this);
        }

        void setSpinning(boolean value) {
            spinning = value;
            focusing = false;
            pinVisible = false;
            connectedCountry = null;
            selectedCountry = -1;
            centerLatitude = 0f;
            zoom = 1f;
            if (value) invalidate();
        }

        void setConnectedCountry(String value) {
            connectedCountry = value == null ? "Selected location" : value;
            selectedCountry = WorldMapData.findCountry(connectedCountry);
            targetLongitude = selectedCountry >= 0 ? WorldMapData.CENTER_LON[selectedCountry] : 0f;
            targetLatitude = selectedCountry >= 0 ? WorldMapData.CENTER_LAT[selectedCountry] : 0f;
            spinning = false;
            focusing = true;
            pinVisible = false;
            invalidate();
        }

        void clearConnection() {
            spinning = false;
            focusing = false;
            pinVisible = false;
            connectedCountry = null;
            selectedCountry = -1;
            centerLatitude = 0f;
            zoom = 1f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.40f;
            if (radius <= 0f) return;

            paint.setShader(new RadialGradient(cx - radius * .32f, cy - radius * .38f,
                    radius * 1.65f, 0xFF63B5D7, 0xFF102F48, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);

            int clip = canvas.save();
            Path globeClip = new Path();
            globeClip.addCircle(cx, cy, radius - dp(1), Path.Direction.CW);
            canvas.clipPath(globeClip);
            drawGraticule(canvas, cx, cy, radius);

            // Draw the actual country outlines and coastlines. The map is projected onto a
            // rotating sphere, so the far side naturally disappears behind the globe.
            for (WorldMapData.Ring ring : rings) {
                if (ring.country != selectedCountry) drawCountryRing(canvas, ring, cx, cy, radius, false);
            }
            if (selectedCountry >= 0) {
                for (WorldMapData.Ring ring : rings) {
                    if (ring.country == selectedCountry) drawCountryRing(canvas, ring, cx, cy, radius, true);
                }
            }
            canvas.restoreToCount(clip);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFB7E8F4);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.FILL);

            if (pinVisible) drawPin(canvas, cx, cy, radius);
            advanceAnimation();
        }

        private void drawCountryRing(Canvas canvas, WorldMapData.Ring ring,
                                     float cx, float cy, float radius, boolean selected) {
            Path path = projectRing(ring.coordinates, cx, cy, radius);
            if (path.isEmpty()) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(selected ? 0xFFFFC857 : 0xFF68B482);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(selected ? dp(1.5f) : dp(.65f));
            paint.setColor(selected ? 0xFFFFF0B3 : 0xFF17495A);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private Path projectRing(float[] coordinates, float cx, float cy, float radius) {
            Path path = new Path();
            int count = coordinates.length / 2;
            if (count < 3) return path;
            float scale = radius * zoom;
            Projected previous = project(coordinates[(count - 1) * 2],
                    coordinates[(count - 1) * 2 + 1], cx, cy, scale);
            boolean open = false;
            for (int i = 0; i < count; i++) {
                Projected current = project(coordinates[i * 2], coordinates[i * 2 + 1], cx, cy, scale);
                if (previous.visible && current.visible) {
                    if (!open) {
                        path.moveTo(previous.x, previous.y);
                        open = true;
                    }
                    path.lineTo(current.x, current.y);
                } else if (previous.visible) {
                    Projected horizon = horizon(previous, current, coordinates[(i + count - 1) % count * 2],
                            coordinates[(i + count - 1) % count * 2 + 1], coordinates[i * 2],
                            coordinates[i * 2 + 1], cx, cy, scale);
                    if (!open) path.moveTo(previous.x, previous.y);
                    path.lineTo(horizon.x, horizon.y);
                    path.close();
                    open = false;
                } else if (current.visible) {
                    Projected edge = horizon(previous, current, coordinates[(i + count - 1) % count * 2],
                            coordinates[(i + count - 1) % count * 2 + 1], coordinates[i * 2],
                            coordinates[i * 2 + 1], cx, cy, scale);
                    path.moveTo(edge.x, edge.y);
                    path.lineTo(current.x, current.y);
                    open = true;
                }
                previous = current;
            }
            if (open) path.close();
            return path;
        }

        private Projected horizon(Projected a, Projected b, float lonA, float latA,
                                  float lonB, float latB, float cx, float cy, float scale) {
            float low = 0f, high = 1f;
            boolean aVisible = a.visible;
            for (int i = 0; i < 14; i++) {
                float mid = (low + high) * .5f;
                Projected test = project(lonA + (lonB - lonA) * mid,
                        latA + (latB - latA) * mid, cx, cy, scale);
                if (test.visible == aVisible) low = mid; else high = mid;
            }
            float t = (low + high) * .5f;
            return project(lonA + (lonB - lonA) * t, latA + (latB - latA) * t, cx, cy, scale);
        }

        private Projected project(float longitude, float latitude, float cx, float cy, float scale) {
            double lat = Math.toRadians(latitude);
            double delta = Math.toRadians(longitude - rotation);
            double center = Math.toRadians(centerLatitude);
            double cosLat = Math.cos(lat);
            float x = cx + scale * (float)(cosLat * Math.sin(delta));
            float y = cy - scale * (float)(Math.sin(lat) * Math.cos(center)
                    - cosLat * Math.cos(delta) * Math.sin(center));
            double depth = Math.sin(lat) * Math.sin(center) + cosLat * Math.cos(delta) * Math.cos(center);
            return new Projected(x, y, depth >= -0.0001);
        }

        private void drawGraticule(Canvas canvas, float cx, float cy, float radius) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(.7f));
            paint.setColor(0x558FE0EE);
            for (int lon = -150; lon <= 180; lon += 30) {
                Path path = new Path();
                boolean started = false;
                for (int lat = -88; lat <= 88; lat += 3) {
                    Projected p = project(rotation + lon, lat, cx, cy, radius * zoom);
                    if (p.visible) {
                        if (!started) { path.moveTo(p.x, p.y); started = true; }
                        else path.lineTo(p.x, p.y);
                    } else started = false;
                }
                canvas.drawPath(path, paint);
            }
            for (int lat = -60; lat <= 60; lat += 30) {
                Path path = new Path();
                boolean started = false;
                for (int lon = -180; lon <= 180; lon += 3) {
                    Projected p = project(rotation + lon, lat, cx, cy, radius * zoom);
                    if (p.visible) {
                        if (!started) { path.moveTo(p.x, p.y); started = true; }
                        else path.lineTo(p.x, p.y);
                    } else started = false;
                }
                canvas.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawPin(Canvas canvas, float cx, float cy, float radius) {
            Projected p = project(targetLongitude, targetLatitude, cx, cy, radius * zoom);
            float x = p.x;
            float y = p.y - dp(12);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE53935);
            Path pin = new Path();
            pin.moveTo(x, y + dp(18));
            pin.cubicTo(x - dp(3), y + dp(10), x - dp(12), y + dp(1), x - dp(12), y - dp(7));
            pin.arcTo(new RectF(x - dp(12), y - dp(19), x + dp(12), y + dp(5)), 180, 360);
            pin.cubicTo(x + dp(12), y + dp(1), x + dp(3), y + dp(10), x, y + dp(18));
            pin.close();
            canvas.drawPath(pin, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(x, y - dp(7), dp(4), paint);
        }

        private void advanceAnimation() {
            if (spinning) {
                rotation = normalizeAngle(rotation + 1.3f);
            } else if (focusing) {
                float difference = shortestAngle(targetLongitude - rotation);
                rotation = normalizeAngle(rotation + difference * .12f);
                centerLatitude += (targetLatitude - centerLatitude) * .12f;
                zoom += (2.1f - zoom) * .10f;
                if (Math.abs(difference) < .25f
                        && Math.abs(targetLatitude - centerLatitude) < .15f
                        && Math.abs(2.1f - zoom) < .015f) {
                    rotation = normalizeAngle(targetLongitude);
                    centerLatitude = targetLatitude;
                    zoom = 2.1f;
                    focusing = false;
                    pinVisible = true;
                }
            }
            if (spinning || focusing) postInvalidateDelayed(40L);
        }

        private float normalizeAngle(float angle) {
            angle %= 360f;
            return angle < 0 ? angle + 360f : angle;
        }

        private float shortestAngle(float angle) {
            angle = normalizeAngle(angle);
            return angle > 180f ? angle - 360f : angle;
        }

        private final class Projected {
            final float x, y;
            final boolean visible;
            Projected(float x, float y, boolean visible) {
                this.x = x;
                this.y = y;
                this.visible = visible;
            }
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
