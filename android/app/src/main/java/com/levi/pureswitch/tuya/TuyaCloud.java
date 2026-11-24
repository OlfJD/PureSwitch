package com.levi.pureswitch.tuya;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The one place PureSwitch talks to the internet: a one-time "Connect Smart Life" login that
 * fetches the local key of each outlet. It uses Tuya's QR-code login for Home Assistant (no
 * developer account needed), so Smart Life shows the request as coming from Home Assistant.
 *
 * Ported from Tuya's open-source tuya-device-sharing-sdk (customerapi.py / user.py),
 * Copyright (c) 2023 Tuya, MIT License (full text in THIRD_PARTY_NOTICES.md), with the client ID
 * from Home Assistant's Tuya integration (Apache 2.0, const.py). The login is ended again right
 * after the keys are fetched; no tokens are stored.
 */
final class TuyaCloud {
    private TuyaCloud() {}

    private static final String CLIENT_ID = "HA_3y9q4ak7g4ephrvke";
    private static final String SCHEMA = "haauthorize";
    private static final String LOGIN_HOST = "https://apigw.iotbing.com";
    /** Only ever send the login token to Tuya's own API servers. */
    private static final Pattern TUYA_ENDPOINT =
            Pattern.compile("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(tuya[a-z]*|iotbing)\\.com/?$");
    private static final int TIMEOUT_MS = 15000;
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Device {
        final String devId;
        final String name;
        final String category;
        final String localKey;

        Device(String devId, String name, String category, String localKey) {
            this.devId = devId;
            this.name = name;
            this.category = category;
            this.localKey = localKey;
        }
    }

    /** A logged-in session, only kept in memory while the keys are fetched. */
    static final class Session {
        final String endpoint;
        final String accessToken;
        final String refreshToken;
        final String terminalId;

        Session(JSONObject login) throws IOException {
            endpoint = login.optString("endpoint", "").replaceAll("/+$", "");
            accessToken = login.optString("access_token", "");
            refreshToken = login.optString("refresh_token", "");
            terminalId = login.optString("terminal_id", "");
            if (!TUYA_ENDPOINT.matcher(endpoint).matches() || accessToken.isEmpty()) {
                throw new TuyaDevice.TuyaException("Unexpected reply from Smart Life");
            }
        }
    }

    /* ---------------- QR login ---------------- */

    /** Asks Tuya for a login QR code. Returns the token to put in the QR code. */
    static String requestQr(String userCode) throws IOException {
        JSONObject r = http("POST", LOGIN_HOST + "/v1.0/m/life/home-assistant/qrcode/tokens?clientid="
                + CLIENT_ID + "&usercode=" + enc(userCode) + "&schema=" + SCHEMA, null, null);
        if (!r.optBoolean("success")) {
            throw new TuyaDevice.TuyaException(friendly(r, "Smart Life didn't accept the user code"));
        }
        JSONObject result = r.optJSONObject("result");
        String token = result != null ? result.optString("qrcode", "") : "";
        if (token.isEmpty()) throw new TuyaDevice.TuyaException("Unexpected reply from Smart Life");
        return token;
    }

    /** Has the QR code been scanned and confirmed? Returns the login, or null if not yet. */
    static JSONObject pollLogin(String token, String userCode) throws IOException {
        JSONObject r = http("GET", LOGIN_HOST + "/v1.0/m/life/home-assistant/qrcode/tokens/" + enc(token)
                + "?clientid=" + CLIENT_ID + "&usercode=" + enc(userCode), null, null);
        if (!r.optBoolean("success")) return null;
        return r.optJSONObject("result");
    }

    /* ---------------- After login ---------------- */

    /** All devices in all homes of the account, with their local keys. */
    static List<Device> fetchDevices(Session s) throws IOException {
        List<Device> devices = new ArrayList<>();
        Object homes = request(s, "GET", "/v1.0/m/life/users/homes", null, null);
        if (!(homes instanceof JSONArray)) return devices;
        JSONArray homeList = (JSONArray) homes;
        for (int i = 0; i < homeList.length(); i++) {
            JSONObject home = homeList.optJSONObject(i);
            if (home == null) continue;
            String homeId = home.optString("ownerId", "");
            if (homeId.isEmpty()) continue;
            JSONObject params = new JSONObject();
            try {
                params.put("homeId", homeId);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            Object list = request(s, "GET", "/v1.0/m/life/ha/home/devices", params, null);
            if (!(list instanceof JSONArray)) continue;
            JSONArray arr = (JSONArray) list;
            for (int j = 0; j < arr.length(); j++) {
                JSONObject d = arr.optJSONObject(j);
                if (d == null) continue;
                String id = d.optString("id", "");
                if (id.isEmpty()) continue;
                devices.add(new Device(id, d.optString("name", ""), d.optString("category", ""),
                        d.optString("local_key", "")));
            }
        }
        return devices;
    }

    /** Ends the login, so it doesn't stay authorised in the Smart Life account. Best effort. */
    static void logout(Session s) {
        try {
            JSONObject body = new JSONObject();
            body.put("accessToken", s.accessToken);
            body.put("terminalId", s.terminalId);
            request(s, "POST", "/v1.0/m/token/terminal/expire", null, body);
        } catch (Exception ignored) {
            // The token expires on its own after a while anyway
        }
    }

    /* ---------------- Signed + encrypted requests (customerapi.py) ---------------- */

    private static Object request(Session s, String method, String path, JSONObject params, JSONObject body)
            throws IOException {
        try {
            String rid = UUID.randomUUID().toString();
            String hashKey = hex(MessageDigest.getInstance("MD5")
                    .digest((rid + s.refreshToken).getBytes(StandardCharsets.UTF_8)));
            String secret = hex(hmac(rid.getBytes(StandardCharsets.UTF_8), hashKey.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);

            String url = s.endpoint + path;
            String queryEnc = "";
            if (params != null && params.length() > 0) {
                queryEnc = encrypt(params.toString(), secret);
                url += "?encdata=" + enc(queryEnc);
            }
            String bodyEnc = "";
            String bodyJson = null;
            if (body != null && body.length() > 0) {
                bodyEnc = encrypt(body.toString(), secret);
                bodyJson = new JSONObject().put("encdata", bodyEnc).toString();
            }

            String time = String.valueOf(System.currentTimeMillis());
            String[][] headers = {
                {"X-appKey", CLIENT_ID},
                {"X-requestId", rid},
                {"X-sid", ""},
                {"X-time", time},
                {"X-token", s.accessToken},
            };
            StringBuilder sign = new StringBuilder();
            for (String[] h : headers) {
                if (h[1].isEmpty()) continue;
                if (sign.length() > 0) sign.append("||");
                sign.append(h[0]).append('=').append(h[1]);
            }
            sign.append(queryEnc).append(bodyEnc);
            String[][] all = new String[headers.length + 1][];
            System.arraycopy(headers, 0, all, 0, headers.length);
            all[headers.length] = new String[] {"X-sign",
                hex(hmac(hashKey.getBytes(StandardCharsets.UTF_8), sign.toString().getBytes(StandardCharsets.UTF_8)))};

            JSONObject r = http(method, url, all, bodyJson);
            if (!r.optBoolean("success")) {
                throw new TuyaDevice.TuyaException(friendly(r, "Smart Life refused the request"));
            }
            String result = r.optString("result", "");
            if (result.isEmpty()) return null;
            String plain = decrypt(result, secret);
            try {
                return new JSONTokener(plain).nextValue();
            } catch (JSONException e) {
                return plain;
            }
        } catch (GeneralSecurityException | JSONException e) {
            throw new IOException(e);
        }
    }

    private static final String NONCE_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";

    private static String encrypt(String plain, String secret) throws GeneralSecurityException {
        StringBuilder nonce = new StringBuilder();
        for (int i = 0; i < 12; i++) nonce.append(NONCE_CHARS.charAt(RANDOM.nextInt(NONCE_CHARS.length())));
        byte[] iv = nonce.toString().getBytes(StandardCharsets.UTF_8);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + Base64.encodeToString(sealed, Base64.NO_WRAP);
    }

    private static String decrypt(String data, String secret) throws GeneralSecurityException {
        byte[] raw = Base64.decode(data, Base64.DEFAULT);
        if (raw.length < 12 + 16) throw new GeneralSecurityException("short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, raw, 0, 12));
        return new String(c.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
    }

    /* ---------------- Plain HTTPS ---------------- */

    private static JSONObject http(String method, String url, String[][] headers, String jsonBody) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("Not HTTPS");
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
        } catch (IOException e) {
            throw new TuyaDevice.TuyaException("No internet connection");
        }
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) for (String[] h : headers) conn.setRequestProperty(h[0], h[1]);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            } else if (method.equals("POST")) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
                conn.getOutputStream().close();
            }
            int code;
            try {
                code = conn.getResponseCode();
            } catch (IOException e) {
                throw new TuyaDevice.TuyaException("No internet connection");
            }
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new TuyaDevice.TuyaException("Smart Life didn't answer");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream is = in) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = is.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 4 * 1024 * 1024) throw new IOException("Too large");
                }
            }
            try {
                return new JSONObject(buf.toString("UTF-8"));
            } catch (JSONException e) {
                throw new TuyaDevice.TuyaException("Smart Life didn't answer");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Tuya's own error message, kept short so it fits in the app. */
    private static String friendly(JSONObject r, String fallback) {
        String msg = r.optString("msg", "").trim();
        if (msg.isEmpty() || msg.length() > 120) return fallback;
        return fallback + ": " + msg;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
