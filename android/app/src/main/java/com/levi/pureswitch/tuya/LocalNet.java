package com.levi.pureswitch.tuya;

/** The app only ever talks to private (home network) IPv4 addresses. */
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
}
