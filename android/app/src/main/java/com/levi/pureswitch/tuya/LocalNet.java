package com.levi.pureswitch.tuya;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** The app only ever talks to private (home network) IPv4 addresses, over Wi-Fi. */
final class LocalNet {
    private LocalNet() {}

    static boolean isPrivateIPv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return false;
            for (char ch : parts[i].toCharArray()) if (ch < '0' || ch > '9') return false;
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) return false;
        }
        return n[0] == 10
                || (n[0] == 172 && n[1] >= 16 && n[1] <= 31)
                || (n[0] == 192 && n[1] == 168);
    }

    /**
     * The phone's Wi-Fi network, or null if it isn't on Wi-Fi. Outlet connections are bound to it:
     * when the Wi-Fi's internet drops, Android makes mobile data the default network, and an
     * unbound connection to 192.168.x.x would then go out over mobile data and never arrive.
     */
    @SuppressWarnings("deprecation") // getAllNetworks: still the simplest way, and works on all versions
    static Network wifi(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }
}
