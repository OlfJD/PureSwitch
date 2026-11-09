package com.levi.pureswitch.tuya;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps outlet local keys encrypted at rest. The encryption key lives in the Android Keystore
 * (hardware-backed on most phones) and never leaves it; only the encrypted keys are stored.
 */
final class KeyVault {
    private static final String ALIAS = "pureswitch_local_keys";
    private static final String PREFS = "pureswitch_keys";

    private final SharedPreferences prefs;

    KeyVault(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey masterKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    synchronized void put(String id, String localKey) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, masterKey());
        byte[] sealed = c.doFinal(localKey.getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(sealed, Base64.NO_WRAP);
        if (!prefs.edit().putString(id, value).commit()) throw new IllegalStateException("Couldn't save key");
    }

    /** The local key, or null if none is stored. */
    synchronized String get(String id) throws Exception {
        String value = prefs.getString(id, null);
        if (value == null) return null;
        String[] parts = value.split(":", 2);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, masterKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    synchronized void remove(String id) {
        prefs.edit().remove(id).commit();
    }

    synchronized List<String> ids() {
        return new ArrayList<>(prefs.getAll().keySet());
    }
}
