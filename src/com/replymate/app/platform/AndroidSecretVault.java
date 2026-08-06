package com.replymate.app.platform;

import android.content.Context;
import android.content.SharedPreferences;
import com.replymate.core.ports.SecretVault;


public final class AndroidSecretVault implements SecretVault {
    private static final String PREFIX = "secret.";
    private static final String PREFS = "replymate_secrets";
    private final KeystoreCrypto crypto = new KeystoreCrypto();
    private final SharedPreferences prefs;

    public AndroidSecretVault(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, 0);
    }

    @Override // com.replymate.core.ports.SecretVault
    public void putSecret(String str, String str2) {
        if (str2 == null) {
            deleteSecret(str);
            return;
        }
        String encrypt = this.crypto.encrypt(str2);
        if (encrypt != null) {
            this.prefs.edit().putString(PREFIX + str, encrypt).apply();
        }
    }

    @Override // com.replymate.core.ports.SecretVault
    public String getSecret(String str) {
        String string = this.prefs.getString(PREFIX + str, null);
        if (string == null) {
            return null;
        }
        return this.crypto.decrypt(string);
    }

    @Override // com.replymate.core.ports.SecretVault
    public boolean hasSecret(String str) {
        return this.prefs.contains(PREFIX + str);
    }

    @Override // com.replymate.core.ports.SecretVault
    public void deleteSecret(String str) {
        this.prefs.edit().remove(PREFIX + str).apply();
    }
}
