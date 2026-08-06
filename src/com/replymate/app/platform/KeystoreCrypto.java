package com.replymate.app.platform;

import android.security.keystore.KeyGenParameterSpec;
import android.util.Base64;
import java.nio.charset.Charset;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;


public final class KeystoreCrypto {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int IV_LEN = 12;
    private static final String MASTER_ALIAS = "rm_master";
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private SecretKey masterKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(MASTER_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE);
                keyGenerator.init(new KeyGenParameterSpec.Builder(MASTER_ALIAS, 3).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setKeySize(256).build());
                keyGenerator.generateKey();
            }
            KeyStore.SecretKeyEntry secretKeyEntry = (KeyStore.SecretKeyEntry) keyStore.getEntry(MASTER_ALIAS, null);
            if (secretKeyEntry == null) {
                return null;
            }
            return secretKeyEntry.getSecretKey();
        } catch (Exception e) {
            return null;
        }
    }

    public String encrypt(String str) {
        if (str == null) {
            return null;
        }
        try {
            SecretKey masterKey = masterKey();
            if (masterKey == null) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(1, masterKey);
            byte[] doFinal = cipher.doFinal(str.getBytes(UTF8));
            byte[] iv = cipher.getIV();
            byte[] bArr = new byte[iv.length + doFinal.length];
            System.arraycopy(iv, 0, bArr, 0, iv.length);
            System.arraycopy(doFinal, 0, bArr, iv.length, doFinal.length);
            return Base64.encodeToString(bArr, 2);
        } catch (Exception e) {
            return null;
        }
    }

    public String decrypt(String str) {
        if (str == null) {
            return null;
        }
        try {
            SecretKey masterKey = masterKey();
            if (masterKey == null) {
                return null;
            }
            byte[] decode = Base64.decode(str, 2);
            if (decode.length <= 12) {
                return null;
            }
            byte[] bArr = new byte[12];
            int length = decode.length - 12;
            byte[] bArr2 = new byte[length];
            System.arraycopy(decode, 0, bArr, 0, 12);
            System.arraycopy(decode, 12, bArr2, 0, length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(2, masterKey, new GCMParameterSpec(TAG_BITS, bArr));
            return new String(cipher.doFinal(bArr2), UTF8);
        } catch (Exception e) {
            return null;
        }
    }
}
