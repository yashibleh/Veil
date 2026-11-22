package veil.models;

/**
 * Categories of sensitive entities detected and governed by VEIL.
 */
public enum EntityCategory {
    PERSONAL_NAME("NAME", "Personal Name"),
    EMAIL("EMAIL", "Email Address"),
    PHONE("PHONE", "Phone Number"),
    AADHAAR("AADHAAR", "Aadhaar Number"),
    PAN("PAN", "PAN Number"),
    CREDIT_CARD("CARD", "Payment Card"),
    IP_ADDRESS("IP", "IP Address"),
    URL("URL", "Private/Internal URL"),
    API_KEY("API_KEY", "API Key"),
    ACCESS_TOKEN("TOKEN", "Access Token"),
    PASSWORD("PASSWORD", "Password"),
    DB_CREDENTIAL("DB_CRED", "Database Credential"),
    PRIVATE_IDENTIFIER("PRIV_ID", "Private Identifier"),
    CODE_IDENTIFIER("CODE_ID", "Code Identifier"),
    CUSTOM("CUSTOM", "Custom Rule");

    private final String tokenPrefix;
    private final String displayName;

    EntityCategory(String tokenPrefix, String displayName) {
        this.tokenPrefix = tokenPrefix;
        this.displayName = displayName;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public String getDisplayName() {
        return displayName;
    }
}
