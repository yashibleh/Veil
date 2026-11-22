package veil.sdk;

import veil.core.VeilCoreEngine;
import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
import veil.models.ChatMessage;
import veil.models.PrivacyProfile;
import veil.models.ProviderType;
import veil.models.SanitizationResult;
import veil.providers.ProviderException;
import veil.streaming.StreamingTokenRestorer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight, JavaFX-independent Developer SDK for embedding VEIL privacy protection
 * directly into custom Java applications, CLI tools, or backend services.
 */
public class VeilClient {

    private final VeilCoreEngine engine;

    public VeilClient(Path veilHomeDir) {
        this.engine = new VeilCoreEngine(veilHomeDir);
    }

    public VeilClient(VeilCoreEngine engine) {
        this.engine = engine;
    }

    public String createSession() {
        String id = "sdk-" + UUID.randomUUID().toString().substring(0, 8);
        engine.getTokenVault().getOrCreateSession(id);
        return id;
    }

    public SanitizationResult sanitize(String sessionId, String text) {
        return engine.sanitizeText(sessionId, text, engine.getActiveProfile());
    }

    public SanitizationResult sanitize(String sessionId, String text, PrivacyProfile profile) {
        return engine.sanitizeText(sessionId, text, profile);
    }

    public String restore(String sessionId, String responseText) {
        return engine.restoreText(sessionId, responseText);
    }

    public StreamingTokenRestorer createStreamingRestorer(String sessionId) {
        return engine.createStreamingRestorer(sessionId);
    }

    public ChatCompletionResponse chat(String sessionId, String prompt, ProviderType provider) throws ProviderException {
        ChatCompletionRequest req = new ChatCompletionRequest();
        req.setMessages(List.of(new ChatMessage("user", prompt)));
        return engine.processChatCompletion(req, provider, sessionId, "SDK");
    }

    public void clearSession(String sessionId) {
        engine.getTokenVault().clearSession(sessionId);
    }

    public VeilCoreEngine getEngine() {
        return engine;
    }
}
