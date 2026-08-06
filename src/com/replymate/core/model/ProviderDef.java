package com.replymate.core.model;

/** NON-SECRET provider configuration only. The API key itself never lives in the DB;
 *  keyRef is an alias into the SecretVault (Keystore-backed). */
public class ProviderDef {
    public long id;
    public ProviderType type = ProviderType.GEMINI;
    public String label = "Gemini";
    public String baseUrl = "https://generativelanguage.googleapis.com";
    public String modelName = "gemini-2.5-flash";
    public String keyRef = "";
    public boolean isActive;
    public long createdAt;

    public ProviderDef() { }
}
