package veil.providers;

import veil.models.ProviderType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory/Registry resolving provider adapters without coupling the gateway to concrete implementations.
 */
public class ProviderRegistry {

    private final Map<ProviderType, AIProviderAdapter> adapters = new EnumMap<>(ProviderType.class);

    public ProviderRegistry() {
        register(new OpenAIAdapter());
        register(new AnthropicAdapter());
        register(new GeminiAdapter());
        register(new OllamaAdapter());
    }

    public void register(AIProviderAdapter adapter) {
        adapters.put(adapter.getProviderType(), adapter);
    }

    public AIProviderAdapter getAdapter(ProviderType type) {
        AIProviderAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for provider: " + type);
        }
        return adapter;
    }
}
