package com.levi.pureswitch.tuya;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One direct LAN connection (TCP 6668) to a Tuya plug, speaking protocol 3.3, 3.4 or 3.5.
 * Nothing here ever talks to the Tuya cloud.
 */
final class TuyaDevice implements Closeable {

    /** A failure worth showing the user as-is. Short, so it fits on an outlet row. */
    static final class TuyaException extends IOException {
        TuyaException(String message) {
            super(message);
        }
    }

    interface Action {
        void run(TuyaDevice device) throws IOException;
    }

    static final int PORT = 6668;
    private static final int TIMEOUT_MS = 3000;

    // Command codes
    static final int SESS_KEY_NEG_START = 3;
    static final int SESS_KEY_NEG_RESP = 4;
    static final int SESS_KEY_NEG_FINISH = 5;
    static final int CONTROL = 7;
    static final int STATUS = 8;
    static final int HEART_BEAT = 9;
    static final int DP_QUERY = 10;
    static final int CONTROL_NEW = 13;
    static final int DP_QUERY_NEW = 16;

    static final int PREFIX_55AA = 0x000055AA;
    static final int SUFFIX_55AA = 0x0000AA55;
    static final int PREFIX_6699 = 0x00006699;
    static final int SUFFIX_6699 = 0x00009966;

    /** Data point 1 is the main on/off switch on Tuya plugs. */
    private static final String SWITCH_DP = "1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String devId;
    private final byte[] localKey;
    private final String version;
    private byte[] sessionKey;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int seq = 1;

    private TuyaDevice(String devId, String localKey, String version) {
        this.devId = devId;
        this.localKey = localKey.getBytes(StandardCharsets.UTF_8);
        this.version = version;
    }

    /**
     * Connects, runs the action and disconnects. With an unknown version it tries 3.3, 3.4 and
     * 3.5 in turn. Returns the protocol version that worked.
     */
    static String withDevice(String ip, String devId, String localKey, String version, Action action)
            throws IOException {
        if (!LocalNet.isPrivateIPv4(ip)) throw new TuyaException("Not local");
        if (localKey.length() != 16) throw new TuyaException("Bad key");

        String[] versions;
        if (version == null || version.isEmpty()) {
            versions = new String[] {"3.3", "3.4", "3.5"};
        } else if (version.startsWith("3.1")) {
            throw new TuyaException("Unsupported");
        } else if (version.equals("3.2")) {
            versions = new String[] {"3.3"}; // 3.2 speaks the 3.3 wire format
        } else {
            versions = new String[] {version};
        }

        IOException last = null;
        for (String v : versions) {
            try (TuyaDevice device = new TuyaDevice(devId, localKey, v)) {
                device.connect(ip);
                action.run(device);
                return v;
            } catch (TuyaException e) {
                if ("Offline".equals(e.getMessage())) throw e; // no point trying other versions
                last = e;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last instanceof TuyaException) throw last;
        throw new TuyaException("No reply");
    }

    /* ---------------- Connection ---------------- */

    private void connect(String ip) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, PORT), TIMEOUT_MS);
        } catch (IOException e) {
            throw new TuyaException("Offline");
        }
        socket.setSoTimeout(TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = socket.getOutputStream();
        if (!version.equals("3.3")) negotiateSessionKey();
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    /** Protocol 3.4/3.5: agree on a per-connection session key derived from the local key. */
    private void negotiateSessionKey() throws IOException {
        byte[] localNonce = random(16);
        send(SESS_KEY_NEG_START, localNonce);
        byte[] p = readUntil(SESS_KEY_NEG_RESP, -1).data;
        if (p.length < 48) throw new IOException("Bad handshake");

        byte[] remoteNonce = Arrays.copyOfRange(p, 0, 16);
        try {
            if (!MessageDigest.isEqual(hmac(localKey, localNonce), Arrays.copyOfRange(p, 16, 48))) {
                throw new TuyaException("Wrong key");
            }
            send(SESS_KEY_NEG_FINISH, hmac(localKey, remoteNonce));

            byte[] mixed = new byte[16];
            for (int i = 0; i < 16; i++) mixed[i] = (byte) (localNonce[i] ^ remoteNonce[i]);
            sessionKey = version.equals("3.4")
                    ? aesEcb(Cipher.ENCRYPT_MODE, localKey, mixed, false)
                    : Arrays.copyOf(gcmEncrypt(localKey, Arrays.copyOf(localNonce, 12), mixed, null), 16);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private byte[] currentKey() {
        return sessionKey != null ? sessionKey : localKey;
    }

    /* ---------------- Commands ---------------- */

    void setSwitch(boolean on) throws IOException {
        try {
            JSONObject dps = new JSONObject().put(SWITCH_DP, on);
            long t = System.currentTimeMillis() / 1000;
            int cmd;
            JSONObject body;
            if (version.equals("3.3")) {
                cmd = CONTROL;
                body = new JSONObject().put("devId", devId).put("uid", devId)
                        .put("t", String.valueOf(t)).put("dps", dps);
            } else {
                cmd = CONTROL_NEW;
                body = new JSONObject().put("protocol", 5).put("t", t)
                        .put("data", new JSONObject().put("dps", dps));
            }
            send(cmd, body.toString().getBytes(StandardCharsets.UTF_8));
            Message reply = readUntil(cmd, STATUS);
            if (reply.cmd == cmd && reply.retcode != 0) throw new TuyaException("Refused");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    boolean readSwitch() throws IOException {
        try {
            int cmd;
            byte[] body;
            if (version.equals("3.3")) {
                cmd = DP_QUERY;
                String t = String.valueOf(System.currentTimeMillis() / 1000);
                body = new JSONObject().put("gwId", devId).put("devId", devId).put("uid", devId)
                        .put("t", t).toString().getBytes(StandardCharsets.UTF_8);
            } else {
                cmd = DP_QUERY_NEW;
                body = "{}".getBytes(StandardCharsets.UTF_8);
            }
            send(cmd, body);
            Message reply = readUntil(cmd, STATUS);
            // Only believe a reply that proves it knows the local key. A fake device on the
            // Wi-Fi could otherwise answer in plain text. The app relies on this check before it
            // trusts a new address for an outlet (see src/main.js, verifyAddress).
            if (!reply.authed) throw new TuyaException("Not trusted");
            JSONObject json = new JSONObject(new String(reply.data, StandardCharsets.UTF_8));
            JSONObject dps = json.optJSONObject("dps");
            if (dps == null && json.optJSONObject("data") != null) dps = json.getJSONObject("data").optJSONObject("dps");
            if (dps == null || !dps.has(SWITCH_DP)) throw new TuyaException("No state");
            return dps.getBoolean(SWITCH_DP);
        } catch (JSONException e) {
            throw new TuyaException("No state");
        }
    }

    /* ---------------- Framing ---------------- */

    private static final class Message {
        int cmd;
        int retcode;
        byte[] data;
        /** True if the message was signed or encrypted with our key (so it came from the real plug). */
        boolean authed;
    }

    private void send(int cmd, byte[] plain) throws IOException {
        boolean withHeader = cmd != DP_QUERY && cmd != DP_QUERY_NEW && cmd != HEART_BEAT
                && cmd != SESS_KEY_NEG_START && cmd != SESS_KEY_NEG_RESP && cmd != SESS_KEY_NEG_FINISH;
        byte[] versionHeader = withHeader
                ? concat(version.getBytes(StandardCharsets.US_ASCII), new byte[12])
                : new byte[0];
        byte[] frame;
        try {
            switch (version) {
                case "3.5":
                    frame = pack6699(seq++, cmd, concat(versionHeader, plain), currentKey());
                    break;
                case "3.4":
                    byte[] enc34 = aesEcb(Cipher.ENCRYPT_MODE, currentKey(), concat(versionHeader, plain), true);
                    frame = pack55AA(seq++, cmd, enc34, currentKey());
                    break;
                default: // 3.3: the version header goes outside the encrypted part
                    byte[] enc33 = aesEcb(Cipher.ENCRYPT_MODE, localKey, plain, true);
                    frame = pack55AA(seq++, cmd, concat(versionHeader, enc33), null);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        out.write(frame);
        out.flush();
    }

    /** Reads messages until one has command {@code a} or {@code b}; others (heartbeats etc.) are skipped. */
    private Message readUntil(int a, int b) throws IOException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Message m = read();
            if (m.cmd == a || m.cmd == b) return m;
        }
        throw new IOException("No reply");
    }

    private Message read() throws IOException {
        int prefix = in.readInt();
        Message m = new Message();

        if (prefix == PREFIX_55AA) {
            int seqNo = in.readInt();
            m.cmd = in.readInt();
            int len = in.readInt();
            boolean hmacTail = !version.equals("3.3");
            int tail = hmacTail ? 36 : 8; // HMAC-SHA256 or CRC32, plus the suffix
            if (len < tail || len > 65536) throw new IOException("Bad frame");
            byte[] body = new byte[len];
            in.readFully(body);
            byte[] payload = Arrays.copyOfRange(body, 0, len - tail);

            if (hmacTail) {
                byte[] header = ByteBuffer.allocate(16).putInt(prefix).putInt(seqNo).putInt(m.cmd).putInt(len).array();
                try {
                    byte[] expected = hmac(currentKey(), concat(header, payload));
                    if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(body, len - 36, len - 4))) {
                        throw new TuyaException("Wrong key");
                    }
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }

            // Replies start with a 4-byte return code. Encrypted data is always a multiple of 16
            // bytes (plus 15 for a 3.3 version header), so the remainder tells us if it's there.
            int rem = payload.length % 16;
            if (payload.length >= 4 && (rem == 4 || rem == 3)) {
                m.retcode = ByteBuffer.wrap(payload).getInt();
                payload = Arrays.copyOfRange(payload, 4, payload.length);
            }
            if (version.equals("3.3") && startsWithVersion(payload)) {
                payload = Arrays.copyOfRange(payload, 15, payload.length);
            }
            if (payload.length == 0 || payload.length % 16 != 0 || payload[0] == '{') {
                m.data = payload; // empty ack or plain-text message
                m.authed = hmacTail; // 3.4: the HMAC above already proved the key
            } else {
                try {
                    m.data = stripVersion(aesEcb(Cipher.DECRYPT_MODE, currentKey(), payload, true));
                    m.authed = true;
                } catch (GeneralSecurityException e) {
                    throw new TuyaException("Wrong key");
                }
            }
            return m;
        }

        if (prefix == PREFIX_6699) {
            byte[] rest = new byte[14];
            in.readFully(rest);
            ByteBuffer hb = ByteBuffer.wrap(rest);
            hb.getShort();
            hb.getInt(); // seq
            m.cmd = hb.getInt();
            int len = hb.getInt();
            if (len < 28 || len > 65536) throw new IOException("Bad frame");
            byte[] body = new byte[len];
            in.readFully(body);
            in.readInt(); // suffix

            byte[] plain;
            try {
                plain = gcmDecrypt(currentKey(), Arrays.copyOf(body, 12), Arrays.copyOfRange(body, 12, len), rest);
            } catch (GeneralSecurityException e) {
                throw new TuyaException("Wrong key");
            }
            if (plain.length >= 4 && plain[0] == 0 && plain[1] == 0 && plain[2] == 0) {
                m.retcode = ByteBuffer.wrap(plain).getInt();
                plain = Arrays.copyOfRange(plain, 4, plain.length);
            }
            m.data = stripVersion(plain);
            m.authed = true; // AES-GCM tag checked
            return m;
        }

        throw new IOException("Bad frame");
    }

    static byte[] pack55AA(int seqNo, int cmd, byte[] payload, byte[] hmacKey) throws GeneralSecurityException {
        int tail = hmacKey != null ? 32 : 4;
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + tail + 4);
        b.putInt(PREFIX_55AA).putInt(seqNo).putInt(cmd).putInt(payload.length + tail + 4).put(payload);
        byte[] signed = Arrays.copyOf(b.array(), 16 + payload.length);
        if (hmacKey != null) {
            b.put(hmac(hmacKey, signed));
        } else {
            CRC32 crc = new CRC32();
            crc.update(signed);
            b.putInt((int) crc.getValue());
        }
        b.putInt(SUFFIX_55AA);
        return b.array();
    }

    static byte[] pack6699(int seqNo, int cmd, byte[] plain, byte[] key) throws GeneralSecurityException {
        byte[] iv = random(12);
        ByteBuffer h = ByteBuffer.allocate(18);
        h.putInt(PREFIX_6699).putShort((short) 0).putInt(seqNo).putInt(cmd).putInt(12 + plain.length + 16);
        byte[] header = h.array();
        byte[] sealed = gcmEncrypt(key, iv, plain, Arrays.copyOfRange(header, 4, 18));
        return ByteBuffer.allocate(18 + 12 + sealed.length + 4)
                .put(header).put(iv).put(sealed).putInt(SUFFIX_6699).array();
    }

    /* ---------------- Crypto & helpers ---------------- */

    static byte[] aesEcb(int mode, byte[] key, byte[] data, boolean pad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(pad ? "AES/ECB/PKCS5Padding" : "AES/ECB/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    /** Returns ciphertext followed by the 16-byte tag. */
    static byte[] gcmEncrypt(byte[] key, byte[] iv, byte[] plain, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(plain);
    }

    static byte[] gcmDecrypt(byte[] key, byte[] iv, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(sealed);
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static boolean startsWithVersion(byte[] p) {
        return p.length >= 15 && p[0] == '3' && p[1] == '.' && p[2] >= '0' && p[2] <= '9';
    }

    private static byte[] stripVersion(byte[] p) {
        return startsWithVersion(p) ? Arrays.copyOfRange(p, 15, p.length) : p;
    }
}
