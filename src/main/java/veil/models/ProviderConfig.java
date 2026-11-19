package veil.models;

/**
 * Configuration for an individual AI provider (API secrets are stored separately in CredentialStore).
 */
public class ProviderConfig {
    private ProviderType type;
    private String baseUrl;
    private String defaultModel;
    private int timeoutSeconds = 60;
    private boolean streamingEnabled = true;

    public ProviderConfig() {
    }

    public ProviderConfig(ProviderType type) {
        this.type = type;
        this.baseUrl = type.getDefaultBaseUrl();
        this.defaultModel = type.getDefaultModel();
    }

    public ProviderType getType() {
        return type;
    }

    public void setType(ProviderType type) {
        this.type = type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = Math.max(5, Math.min(300, timeoutSeconds));
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }
}
