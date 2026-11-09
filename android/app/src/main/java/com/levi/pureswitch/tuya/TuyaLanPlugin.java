package com.levi.pureswitch.tuya;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;
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

    @Override
    public void load() {
        vault = new KeyVault(getContext());
    }

    @PluginMethod
    public void discover(PluginCall call) {
        int seconds = Math.max(2, Math.min(15, call.getInt("seconds", 6)));
        io.execute(() -> {
            try {
                JSObject result = new JSObject();
                result.put("devices", TuyaDiscovery.run(getContext(), seconds));
                call.resolve(result);
            } catch (Exception e) {
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
        withOutlet(call, (device) -> device.setSwitch(on), null);
    }

    @PluginMethod
    public void getPower(PluginCall call) {
        final boolean[] state = new boolean[1];
        withOutlet(call, (device) -> state[0] = device.readSwitch(), (result) -> result.put("on", state[0]));
    }

    private interface ResultFiller {
        void fill(JSObject result);
    }

    private void withOutlet(PluginCall call, TuyaDevice.Action action, ResultFiller filler) {
        String id = call.getString("id", "");
        String ip = call.getString("ip", "");
        String devId = call.getString("devId", "");
        String version = call.getString("version", "");
        if (id.isEmpty() || devId.isEmpty()) {
            call.reject("Set up");
            return;
        }
        if (!LocalNet.isPrivateIPv4(ip)) {
            call.reject("Not local");
            return;
        }

        io.execute(() -> {
            Object lock = outletLocks.computeIfAbsent(id, k -> new Object());
            synchronized (lock) {
                try {
                    String key = vault.get(id);
                    if (key == null) {
                        call.reject("No key");
                        return;
                    }
                    String used = TuyaDevice.withDevice(ip, devId, key, version, action);
                    JSObject result = new JSObject();
                    result.put("version", used);
                    if (filler != null) filler.fill(result);
                    call.resolve(result);
                } catch (Exception e) {
                    call.reject(message(e, "No reply"));
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
                call.resolve();
            } catch (Exception e) {
                call.reject("Couldn't save key");
            }
        });
    }

    @PluginMethod
    public void deleteKey(PluginCall call) {
        vault.remove(call.getString("id", ""));
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
