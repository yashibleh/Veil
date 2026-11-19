package veil.models;

/**
 * Local API Gateway runtime configuration.
 * Always defaults to loopback (127.0.0.1) for safety.
 */
public class GatewayConfig {
    private String bindAddress = "127.0.0.1";
    private int port = 8080;
    private boolean requireLocalAuth = true;
    private String gatewayAccessToken; // generated automatically on first launch
    private int maxRequestBodyBytes = 2 * 1024 * 1024; // 2 MB
    private int rateLimitPerMinute = 120;
    private int requestTimeoutSeconds = 60;
    private boolean streamingAllowed = true;
    private ProviderType activeProvider = ProviderType.OPENAI;
    private String activeProfileId = "developer";

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        // Restrict to loopback unless explicitly overridden with awareness
        this.bindAddress = (bindAddress == null || bindAddress.isBlank()) ? "127.0.0.1" : bindAddress.trim();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1024 and 65535");
        }
        this.port = port;
    }

    public boolean isRequireLocalAuth() {
        return requireLocalAuth;
    }

    public void setRequireLocalAuth(boolean requireLocalAuth) {
        this.requireLocalAuth = requireLocalAuth;
    }

    public String getGatewayAccessToken() {
        return gatewayAccessToken;
    }

    public void setGatewayAccessToken(String gatewayAccessToken) {
        this.gatewayAccessToken = gatewayAccessToken;
    }

    public int getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(int maxRequestBodyBytes) {
        this.maxRequestBodyBytes = Math.max(4096, maxRequestBodyBytes);
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = Math.max(1, rateLimitPerMinute);
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(5, Math.min(300, requestTimeoutSeconds));
    }

    public boolean isStreamingAllowed() {
        return streamingAllowed;
    }

    public void setStreamingAllowed(boolean streamingAllowed) {
        this.streamingAllowed = streamingAllowed;
    }

    public ProviderType getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(ProviderType activeProvider) {
        this.activeProvider = activeProvider == null ? ProviderType.OPENAI : activeProvider;
    }

    public String getActiveProfileId() {
        return activeProfileId;
    }

    public void setActiveProfileId(String activeProfileId) {
        this.activeProfileId = activeProfileId;
    }

    public String getChatCompletionsUrl() {
        return "http://" + bindAddress + ":" + port + "/v1/chat/completions";
    }

    public String getBaseUrl() {
        return "http://" + bindAddress + ":" + port + "/v1";
    }
}
