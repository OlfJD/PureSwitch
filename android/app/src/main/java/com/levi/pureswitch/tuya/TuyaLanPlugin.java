package com.levi.pureswitch.tuya;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the web app calls (see src/devices.js). Outlets are identified by the app's own outlet id;
 * local keys are kept in {@link KeyVault} and never handed back to the web side.
 */
@CapacitorPlugin(name = "TuyaLan")
public class TuyaLanPlugin extends Plugin {
    private final ExecutorService io = Executors.newCachedThreadPool();
    /** One connection per outlet at a time; plugs usually only accept a single client. */
    private final ConcurrentHashMap<String, Object> outletLocks = new ConcurrentHashMap<>();
    private KeyVault vault;

    private ConnectivityManager.NetworkCallback wifiWatch;
    private ConnectivityManager.NetworkCallback defaultWatch;

    @Override
    public void load() {
        vault = new KeyVault(getContext());
        DiagLog.init(getContext());
        String version = "?";
        try {
            version = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // keep "?"
        }
        DiagLog.log("app", "started, PureSwitch " + version + ", Android " + Build.VERSION.RELEASE
                + ", Wi-Fi " + (LocalNet.wifi(getContext()) != null ? "connected" : "not connected"));
        watchNetwork();
    }

    @Override
    protected void handleOnDestroy() {
        ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
        try {
            if (cm != null && wifiWatch != null) cm.unregisterNetworkCallback(wifiWatch);
            if (cm != null && defaultWatch != null) cm.unregisterNetworkCallback(defaultWatch);
        } catch (Exception ignored) {
            // already gone
        }
    }

    /** Logs Wi-Fi coming and going, losing internet, and which network Android uses by default. */
    private void watchNetwork() {
        ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        try {
            wifiWatch = new ConnectivityManager.NetworkCallback() {
                private Boolean internet;
                private String address = "";

                @Override
                public void onAvailable(Network n) {
                    DiagLog.log("net", "Wi-Fi connected");
                }

                @Override
                public void onLost(Network n) {
                    internet = null;
                    address = "";
                    DiagLog.log("net", "Wi-Fi lost");
                }

                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    boolean v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (internet == null || internet != v) {
                        internet = v;
                        DiagLog.log("net", v ? "Wi-Fi has internet" : "Wi-Fi without internet");
                    }
                }

                @Override
                public void onLinkPropertiesChanged(Network n, LinkProperties props) {
                    StringBuilder ips = new StringBuilder();
                    for (LinkAddress la : props.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address) ips.append(la).append(' ');
                    }
                    String now = ips.toString().trim();
                    if (!now.equals(address)) {
                        address = now;
                        DiagLog.log("net", "phone's Wi-Fi address " + (now.isEmpty() ? "none" : now));
                    }
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiWatch);

            defaultWatch = new ConnectivityManager.NetworkCallback() {
                private String last = "";

                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    String kind = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                            : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data"
                            : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : "other";
                    if (!kind.equals(last)) {
                        last = kind;
                        DiagLog.log("net", "default network: " + kind);
                    }
                }

                @Override
                public void onLost(Network n) {
                    last = "";
                    DiagLog.log("net", "default network: none");
                }
            };
            cm.registerDefaultNetworkCallback(defaultWatch);
        } catch (Exception e) {
            DiagLog.log("net", "can't watch the network: " + e);
        }
    }

    /** A line from the web side for the diagnostics log. */
    @PluginMethod
    public void log(PluginCall call) {
        DiagLog.log("ui", call.getString("message", ""));
        call.resolve();
    }

    /** The kept diagnostics log (the last 2–3 days), as text. */
    @PluginMethod
    public void readLog(PluginCall call) {
        io.execute(() -> {
            try {
                JSObject result = new JSObject();
                result.put("text", DiagLog.read());
                call.resolve(result);
            } catch (Exception e) {
                call.reject("Couldn't read the log");
            }
        });
    }

    /** Last 6 characters: enough to tell outlets apart in the log. */
    static String shortId(String id) {
        return id == null ? "?" : id.length() <= 6 ? id : id.substring(id.length() - 6);
    }

    @PluginMethod
    public void discover(PluginCall call) {
        int seconds = Math.max(2, Math.min(15, call.getInt("seconds", 6)));
        io.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                JSArray devices = TuyaDiscovery.run(getContext(), seconds);
                StringBuilder found = new StringBuilder();
                for (int i = 0; i < devices.length(); i++) {
                    org.json.JSONObject d = devices.optJSONObject(i);
                    if (d == null) continue;
                    found.append(' ').append(shortId(d.optString("devId"))).append('@').append(d.optString("ip"))
                            .append(" v").append(d.optString("version", "?"));
                }
                DiagLog.log("scan", devices.length() + " found in " + (System.currentTimeMillis() - start) + " ms:" + found);
                JSObject result = new JSObject();
                result.put("devices", devices);
                call.resolve(result);
            } catch (Exception e) {
                DiagLog.log("scan", "failed: " + e);
                call.reject(message(e, "Couldn't scan. Make sure Wi-Fi is on."));
            }
        });
    }

    @PluginMethod
    public void setPower(PluginCall call) {
        Boolean on = call.getBoolean("on");
        if (on == null) {
            call.reject("Missing on");
            return;
        }
        withOutlet(call, on ? "switch on" : "switch off", (device) -> device.setSwitch(on), null);
    }

    @PluginMethod
    public void getPower(PluginCall call) {
        final boolean[] state = new boolean[1];
        withOutlet(call, "read", (device) -> state[0] = device.readSwitch(), (result) -> {
            result.put("on", state[0]);
            return state[0] ? "on" : "off";
        });
    }

    private interface ResultFiller {
        /** Fills the result and returns a short description of it for the log. */
        String fill(JSObject result);
    }

    private void withOutlet(PluginCall call, String what, TuyaDevice.Action action, ResultFiller filler) {
        String id = call.getString("id", "");
        String ip = call.getString("ip", "");
        String devId = call.getString("devId", "");
        String version = call.getString("version", "");
        String label = what + " " + shortId(id) + " @" + ip + " v" + (version.isEmpty() ? "?" : version);
        if (id.isEmpty() || devId.isEmpty()) {
            DiagLog.log("outlet", label + ": not set up");
            call.reject("Set up");
            return;
        }
        if (!LocalNet.isPrivateIPv4(ip)) {
            DiagLog.log("outlet", label + ": refused, not a home-network address");
            call.reject("Not local");
            return;
        }

        io.execute(() -> {
            long start = System.currentTimeMillis();
            Object lock = outletLocks.computeIfAbsent(id, k -> new Object());
            synchronized (lock) {
                long waited = System.currentTimeMillis() - start;
                try {
                    String key = vault.get(id);
                    if (key == null) {
                        DiagLog.log("outlet", label + ": no key saved");
                        call.reject("No key");
                        return;
                    }
                    Network wifi = LocalNet.wifi(getContext());
                    String used = TuyaDevice.withDevice(wifi, ip, devId, key, version, action);
                    JSObject result = new JSObject();
                    result.put("version", used);
                    String state = filler != null ? filler.fill(result) : "ok";
                    DiagLog.log("outlet", label + ": " + state + " (v" + used + ", "
                            + (System.currentTimeMillis() - start) + " ms" + (waited > 50 ? ", waited " + waited + " ms" : "")
                            + (wifi == null ? ", not on Wi-Fi" : "") + ")");
                    call.resolve(result);
                } catch (Exception e) {
                    String shown = message(e, "No reply");
                    DiagLog.log("outlet", label + ": FAILED " + shown + " (" + e.getClass().getSimpleName()
                            + (e.getMessage() != null && !e.getMessage().equals(shown) ? ": " + e.getMessage() : "")
                            + ", " + (System.currentTimeMillis() - start) + " ms)");
                    call.reject(shown);
                }
            }
        });
    }

    @PluginMethod
    public void saveKey(PluginCall call) {
        String id = call.getString("id", "");
        String key = call.getString("localKey", "");
        if (id.isEmpty() || key.length() != 16) {
            call.reject("Local keys are 16 characters");
            return;
        }
        io.execute(() -> {
            try {
                vault.put(id, key);
                DiagLog.log("key", "saved for " + shortId(id)); // never the key itself
                call.resolve();
            } catch (Exception e) {
                DiagLog.log("key", "saving for " + shortId(id) + " failed: " + e.getClass().getSimpleName());
                call.reject("Couldn't save key");
            }
        });
    }

    @PluginMethod
    public void deleteKey(PluginCall call) {
        String id = call.getString("id", "");
        vault.remove(id);
        DiagLog.log("key", "removed for " + shortId(id));
        call.resolve();
    }

    /* ---------------- Connect Smart Life (see TuyaCloud) ---------------- */

    /** The login in progress. Keys fetched from the cloud wait here until the app has saved them. */
    private final Object cloudLock = new Object();
    private String cloudUserCode;
    private String cloudToken;
    private final java.util.Map<String, String> pendingKeys = new java.util.HashMap<>();

    @PluginMethod
    public void cloudStart(PluginCall call) {
        String userCode = call.getString("userCode", "").trim();
        if (!userCode.matches("[A-Za-z0-9]{4,64}")) {
            call.reject("That doesn't look like a user code");
            return;
        }
        io.execute(() -> {
            try {
                String token = TuyaCloud.requestQr(userCode);
                synchronized (cloudLock) {
                    cloudUserCode = userCode;
                    cloudToken = token;
                    pendingKeys.clear();
                }
                JSObject result = new JSObject();
                result.put("qr", "tuyaSmart--qrLogin?token=" + token);
                DiagLog.log("cloud", "QR login started"); // never the user code or token
                call.resolve(result);
            } catch (Exception e) {
                DiagLog.log("cloud", "starting the login failed: " + message(e, e.getClass().getSimpleName()));
                call.reject(message(e, "Couldn't reach Smart Life"));
            }
        });
    }

    /**
     * Resolves to { done: false } until the QR code has been scanned and confirmed. Then fetches
     * the devices, keeps their keys here (never handed to the web side), ends the login and
     * resolves to { done: true, devices: [{ devId, name, category, hasKey }] }.
     */
    @PluginMethod
    public void cloudPoll(PluginCall call) {
        final String token;
        final String userCode;
        synchronized (cloudLock) {
            token = cloudToken;
            userCode = cloudUserCode;
        }
        if (token == null) {
            call.reject("Start again");
            return;
        }
        io.execute(() -> {
            try {
                org.json.JSONObject login = TuyaCloud.pollLogin(token, userCode);
                JSObject result = new JSObject();
                if (login == null) {
                    result.put("done", false);
                    call.resolve(result);
                    return;
                }
                synchronized (cloudLock) {
                    if (!token.equals(cloudToken)) { // cancelled or restarted meanwhile
                        call.reject("Start again");
                        return;
                    }
                    cloudToken = null; // a token can only be used once
                }
                TuyaCloud.Session session = new TuyaCloud.Session(login);
                java.util.List<TuyaCloud.Device> devices;
                try {
                    devices = TuyaCloud.fetchDevices(session);
                } finally {
                    TuyaCloud.logout(session);
                }
                JSArray list = new JSArray();
                synchronized (cloudLock) {
                    pendingKeys.clear();
                    for (TuyaCloud.Device d : devices) {
                        boolean hasKey = d.localKey.length() == 16;
                        if (hasKey) pendingKeys.put(d.devId, d.localKey);
                        JSObject o = new JSObject();
                        o.put("devId", d.devId);
                        o.put("name", d.name);
                        o.put("category", d.category);
                        o.put("hasKey", hasKey);
                        list.put(o);
                    }
                }
                result.put("done", true);
                result.put("devices", list);
                DiagLog.log("cloud", "logged in, got " + devices.size() + " devices, " + pendingKeys.size()
                        + " with a key; logged out again");
                call.resolve(result);
            } catch (Exception e) {
                DiagLog.log("cloud", "getting the devices failed: " + message(e, e.getClass().getSimpleName()));
                call.reject(message(e, "Couldn't get your outlets from Smart Life"));
            }
        });
    }

    /** Moves fetched keys into the vault: pairs = [{ id (app outlet id), devId }]. */
    @PluginMethod
    public void cloudSaveKeys(PluginCall call) {
        JSArray pairs = call.getArray("pairs", new JSArray());
        io.execute(() -> {
            JSArray saved = new JSArray();
            try {
                synchronized (cloudLock) {
                    for (int i = 0; i < pairs.length(); i++) {
                        org.json.JSONObject p = pairs.optJSONObject(i);
                        if (p == null) continue;
                        String id = p.optString("id", "");
                        String key = pendingKeys.get(p.optString("devId", ""));
                        if (id.isEmpty() || key == null) continue;
                        vault.put(id, key);
                        saved.put(id);
                    }
                    pendingKeys.clear();
                }
                JSObject result = new JSObject();
                result.put("ids", saved);
                DiagLog.log("cloud", "saved " + saved.length() + " of " + pairs.length() + " keys");
                call.resolve(result);
            } catch (Exception e) {
                DiagLog.log("cloud", "saving keys failed: " + e.getClass().getSimpleName());
                call.reject("Couldn't save keys");
            }
        });
    }

    @PluginMethod
    public void cloudCancel(PluginCall call) {
        synchronized (cloudLock) {
            cloudToken = null;
            cloudUserCode = null;
            pendingKeys.clear();
        }
        call.resolve();
    }

    @PluginMethod
    public void listKeys(PluginCall call) {
        JSObject result = new JSObject();
        result.put("ids", new JSArray(vault.ids()));
        call.resolve(result);
    }

    private static String message(Exception e, String fallback) {
        return e instanceof TuyaDevice.TuyaException || (e instanceof IOException && e.getMessage() != null
                && e.getMessage().startsWith("Couldn't"))
                ? e.getMessage()
                : fallback;
    }
}
