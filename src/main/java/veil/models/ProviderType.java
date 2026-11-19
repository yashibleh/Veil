package veil.models;

/**
 * Supported external and local AI providers.
 */
public enum ProviderType {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", false, true),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "claude-3-5-sonnet-20241022", false, true),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", false, true),
    OLLAMA("Ollama (Local)", "http://127.0.0.1:11434", "llama3.2", true, false);

    private final String displayName;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final boolean localOnly;
    private final boolean requiresApiKey;

    ProviderType(String displayName, String defaultBaseUrl, String defaultModel, boolean localOnly, boolean requiresApiKey) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.localOnly = localOnly;
        this.requiresApiKey = requiresApiKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public boolean isLocalOnly() {
        return localOnly;
    }

    public boolean isRequiresApiKey() {
        return requiresApiKey;
    }
}
