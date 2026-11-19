package veil.providers;

import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
import veil.models.ProviderConfig;
import veil.models.ProviderType;

import java.util.function.Consumer;

/**
 * Modular Adapter interface decoupling VEIL's core privacy engine and local gateway
 * from individual external or local AI provider wire protocols.
 */
public interface AIProviderAdapter {

    ProviderType getProviderType();

    /**
     * Sends a sanitized non-streaming chat request and returns a normalized OpenAI-compatible response.
     */
    ChatCompletionResponse sendChat(ChatCompletionRequest request, ProviderConfig config, String apiKey)
            throws ProviderException;

    /**
     * Sends a sanitized streaming chat request and emits incremental text deltas to deltaConsumer.
     */
    void streamChat(ChatCompletionRequest request,
                    ProviderConfig config,
                    String apiKey,
                    Consumer<String> deltaConsumer) throws ProviderException;

    /**
     * Performs a lightweight genuine connectivity check against the configured provider endpoint.
     */
    boolean testConnection(ProviderConfig config, String apiKey) throws ProviderException;
}
