package com.replymate.core.model;

/** NON-SECRET provider configuration only. The API key itself never lives in the DB;
 *  keyRef is an alias into the SecretVault (Keystore-backed). */
public class ProviderDef {
    public long id;
    public ProviderType type = ProviderType.GEMINI;
    public String label = "";
    public String baseUrl = "";
    /** NEVER defaulted/hardcoded — set from live model discovery or user input. */
    public String modelName = "";
    public String keyRef = "";
    public boolean isActive;
    public long createdAt;

    public ProviderDef() { }
}
