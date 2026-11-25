package com.levi.pureswitch.tuya;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;

/**
 * Finds Tuya plugs on the Wi-Fi. Plugs broadcast a small "I'm here" packet every few seconds
 * on UDP 6666 (old, plain), 6667 (encrypted) or 7000 (protocol 3.5, in reply to a request).
 * The packets contain the IP, device ID and protocol version, but never the local key.
 */
final class TuyaDiscovery {
    private TuyaDiscovery() {}

    private static final int[] PORTS = {6666, 6667, 7000};
    private static final int REQ_DEVINFO = 0x25;

    /** Well-known key Tuya uses for these broadcasts (md5 of a fixed string, same for everyone). */
    private static final byte[] UDP_KEY = md5("yGAdlopoPVldABfn");

    static JSArray run(Context context, int seconds) throws IOException {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // Many phones drop broadcast packets to save battery unless an app holds this lock.
        WifiManager.MulticastLock lock = wifi.createMulticastLock("PureSwitch scan");
        lock.setReferenceCounted(false);
        lock.acquire();

        Network wifiNet = LocalNet.wifi(context);
        List<DatagramSocket> sockets = new ArrayList<>();
        try {
            for (int port : PORTS) {
                try {
                    DatagramSocket s = new DatagramSocket(null);
                    s.setReuseAddress(true);
                    s.setBroadcast(true);
                    s.setSoTimeout(500);
                    if (wifiNet != null) {
                        try {
                            wifiNet.bindSocket(s); // listen and ask on Wi-Fi, not mobile data
                        } catch (IOException ignored) {
                            // Wi-Fi went away meanwhile
                        }
                    }
                    s.bind(new InetSocketAddress(port));
                    sockets.add(s);
                } catch (IOException ignored) {
                    // Port taken (e.g. by the Smart Life app); use the others
                }
            }
            if (sockets.isEmpty()) {
                throw new IOException("Couldn't listen for outlets. Close the Smart Life app and try again.");
            }

            sendDiscoveryRequest(context, wifiNet, sockets.get(0));

            Map<String, JSObject> found = new LinkedHashMap<>();
            long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
            List<Thread> threads = new ArrayList<>();
            for (DatagramSocket s : sockets) {
                Thread t = new Thread(() -> listen(s, deadline, found), "pureswitch-scan");
                t.start();
                threads.add(t);
            }
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            JSArray result = new JSArray();
            synchronized (found) {
                for (JSObject d : found.values()) result.put(d);
            }
            return result;
        } finally {
            for (DatagramSocket s : sockets) s.close();
            lock.release();
        }
    }

    private static void listen(DatagramSocket socket, long deadline, Map<String, JSObject> found) {
        byte[] buf = new byte[4096];
        while (SystemClock.elapsedRealtime() < deadline) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return;
            }

            InetAddress from = packet.getAddress();
            if (!(from instanceof Inet4Address) || !LocalNet.isPrivateIPv4(from.getHostAddress())) continue;

            String json = decode(packet.getData(), packet.getLength());
            if (json == null) continue;
            try {
                JSONObject o = new JSONObject(json);
                String id = o.optString("gwId", "");
                if (id.isEmpty()) continue; // e.g. our own discovery request echoing back
                String ip = o.optString("ip", "");
                if (!LocalNet.isPrivateIPv4(ip)) ip = from.getHostAddress();

                JSObject device = new JSObject();
                device.put("devId", id);
                device.put("ip", ip);
                device.put("version", o.optString("version", ""));
                synchronized (found) {
                    found.put(id, device);
                }
            } catch (Exception ignored) {
                // not a Tuya broadcast
            }
        }
    }

    /** Protocol 3.5 plugs only announce themselves when asked, so ask on the local broadcast address. */
    private static void sendDiscoveryRequest(Context context, Network wifiNet, DatagramSocket socket) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            // The Wi-Fi's own address range, even when mobile data is the default network
            Network network = wifiNet != null ? wifiNet : cm.getActiveNetwork();
            LinkProperties props = network != null ? cm.getLinkProperties(network) : null;
            if (props == null) return;

            for (LinkAddress la : props.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address)) continue;
                String myIp = la.getAddress().getHostAddress();
                if (!LocalNet.isPrivateIPv4(myIp)) continue;

                int ip = ByteBuffer.wrap(la.getAddress().getAddress()).getInt();
                int prefix = la.getPrefixLength();
                int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
                InetAddress broadcast = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip | ~mask).array());

                byte[] body = new JSONObject().put("from", "app").put("ip", myIp).toString()
                        .getBytes(StandardCharsets.UTF_8);
                byte[] frame = TuyaDevice.pack6699(0, REQ_DEVINFO, body, UDP_KEY);
                socket.send(new DatagramPacket(frame, frame.length, broadcast, 7000));
                return;
            }
        } catch (Exception ignored) {
            // Older plugs still announce themselves on their own
        }
    }

    /** Returns the JSON inside a broadcast packet, or null. */
    static String decode(byte[] data, int len) {
        try {
            if (len < 20) return null;
            ByteBuffer b = ByteBuffer.wrap(data, 0, len);
            int prefix = b.getInt(0);
            byte[] plain;

            if (prefix == TuyaDevice.PREFIX_55AA) {
                int payloadLen = b.getInt(12) - 8; // minus CRC and suffix
                if (payloadLen <= 0 || 16 + payloadLen > len) return null;
                byte[] p = Arrays.copyOfRange(data, 16, 16 + payloadLen);
                if (p.length >= 5 && (p[0] == '{' || p[4] == '{')) {
                    plain = p; // protocol 3.1: not encrypted
                } else {
                    if (p.length % 16 == 4) p = Arrays.copyOfRange(p, 4, p.length); // return code
                    plain = TuyaDevice.aesEcb(Cipher.DECRYPT_MODE, UDP_KEY, p, true);
                }
            } else if (prefix == TuyaDevice.PREFIX_6699) {
                int payloadLen = b.getInt(14);
                if (payloadLen < 28 || 18 + payloadLen > len) return null;
                plain = TuyaDevice.gcmDecrypt(UDP_KEY,
                        Arrays.copyOfRange(data, 18, 30),
                        Arrays.copyOfRange(data, 30, 18 + payloadLen),
                        Arrays.copyOfRange(data, 4, 18));
            } else {
                return null;
            }

            String s = new String(plain, StandardCharsets.UTF_8);
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            return start >= 0 && end > start ? s.substring(start, end + 1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
