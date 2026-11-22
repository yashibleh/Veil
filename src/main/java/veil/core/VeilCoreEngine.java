package veil.core;

import veil.analytics.AnalyticsService;
import veil.detection.DetectionEngine;
import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
import veil.models.ChatMessage;
import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PrivacyProfile;
import veil.models.ProviderConfig;
import veil.models.ProviderType;
import veil.models.RequestEvent;
import veil.models.SanitizationResult;
import veil.models.VeilConfig;
import veil.policies.PolicyEngine;
import veil.policies.WarnCallback;
import veil.profiles.ProfileManager;
import veil.providers.AIProviderAdapter;
import veil.providers.ProviderException;
import veil.providers.ProviderRegistry;
import veil.streaming.StreamingTokenRestorer;
import veil.streaming.TokenRestorer;
import veil.vault.TokenVault;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Core JavaFX-independent privacy facade orchestrating detection, policy enforcement,
 * reversible tokenization, provider routing, response restoration, streaming restoration,
 * and genuine activity tracking.
 */
public class VeilCoreEngine {

    private final Path homeDir;
    private final ConfigManager configManager;
    private final CredentialStore credentialStore;
    private final ProfileManager profileManager;
    private final DetectionEngine detectionEngine;
    private final PolicyEngine policyEngine;
    private final TokenVault tokenVault;
    private final TokenRestorer tokenRestorer;
    private final ProviderRegistry providerRegistry;
    private final AnalyticsService analyticsService;
    private final List<PrivacyEventListener> listeners = new CopyOnWriteArrayList<>();

    public VeilCoreEngine(Path homeDir) {
        this.homeDir = homeDir;
        this.configManager = new ConfigManager(homeDir);
        this.credentialStore = new CredentialStore(homeDir);
        this.profileManager = new ProfileManager(homeDir.resolve("profiles.json"));
        this.detectionEngine = new DetectionEngine();
        this.policyEngine = new PolicyEngine();
        this.tokenVault = new TokenVault(homeDir.resolve("vault.enc"));
        this.tokenRestorer = new TokenRestorer(this.tokenVault);
        this.providerRegistry = new ProviderRegistry();
        this.analyticsService = new AnalyticsService(homeDir.resolve("analytics.db"));

        applyConfigToRuntime();
    }

    public void applyConfigToRuntime() {
        VeilConfig cfg = configManager.getConfig();
        detectionEngine.applyEnabledStates(cfg.getEnabledDetectors());
        tokenVault.setSessionTtl(Duration.ofMinutes(cfg.getSessionTtlMinutes()));
        analyticsService.pruneOlderThanDays(cfg.getLogRetentionDays());
    }

    public PrivacyProfile getActiveProfile() {
        String id = configManager.getConfig().getGateway().getActiveProfileId();
        return profileManager.getProfileOrDefault(id);
    }

    /**
     * Runs detection + policy enforcement + masking on a single text block without forwarding.
     */
    public SanitizationResult sanitizeText(String sessionId, String text, PrivacyProfile profile) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        PrivacyProfile active = profile == null ? getActiveProfile() : profile;
        List<DetectedEntity> detected = detectionEngine.detect(text, active);
        return policyEngine.enforce(sid, text, detected, active, tokenVault);
    }

    /**
     * Restores tokens in a completed text response using the given session vault.
     */
    public String restoreText(String sessionId, String providerText) {
        return tokenRestorer.restore(sessionId, providerText);
    }

    /**
     * Creates a streaming token restorer bound to the given session.
     */
    public StreamingTokenRestorer createStreamingRestorer(String sessionId) {
        return new StreamingTokenRestorer(sessionId, tokenVault);
    }

    /**
     * End-to-end non-streaming chat completion pipeline:
     * sanitize all messages -> check BLOCK/WARN -> route to provider -> restore response -> log genuine telemetry.
     */
    public ChatCompletionResponse processChatCompletion(ChatCompletionRequest request,
                                                        ProviderType providerOverride,
                                                        String sessionId,
                                                        String source) throws ProviderException {
        long startNs = System.nanoTime();
        String sid = (sessionId == null || sessionId.isBlank()) ? "sess-" + UUID.randomUUID().toString().substring(0, 8) : sessionId;
        ProviderType providerType = providerOverride != null ? providerOverride : configManager.getConfig().getGateway().getActiveProvider();
        ProviderConfig providerCfg = configManager.getConfig().getProviderConfig(providerType);
        PrivacyProfile profile = getActiveProfile();
        String model = (request.getModel() != null && !request.getModel().isBlank()) ? request.getModel() : providerCfg.getDefaultModel();

        List<ChatMessage> sanitizedMessages = new ArrayList<>();
        int totalDetected = 0;
        int totalMasked = 0;
        Map<EntityCategory, Integer> catCounts = new EnumMap<>(EntityCategory.class);

        for (ChatMessage msg : request.getMessages()) {
            String content = msg.getContent() == null ? "" : msg.getContent();
            SanitizationResult res = sanitizeText(sid, content, profile);
            totalDetected += res.getDetectedEntities().size();
            totalMasked += res.getMaskedCount();
            PolicyEngine.countByCategory(res.getDetectedEntities())
                    .forEach((k, v) -> catCounts.merge(k, v, Integer::sum));

            if (res.isBlocked()) {
                long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
                RequestEvent event = buildEvent(sid, source, providerType, model, profile.getName(),
                        res.isWarnTriggered() ? RequestEvent.Status.WARN_DENIED : RequestEvent.Status.BLOCKED,
                        totalDetected, totalMasked, true, false, elapsed, res.getBlockReason(), catCounts);
                recordAndNotify(event);
                notifyAll("WARN", "Request Blocked by Privacy Policy", res.getBlockReason());
                throw new ProviderException(res.getBlockReason(), 403);
            }
            ChatMessage clean = new ChatMessage(msg.getRole(), res.getSanitizedText());
            clean.setName(msg.getName());
            sanitizedMessages.add(clean);
        }

        ChatCompletionRequest sanitizedRequest = request.copyWithMessages(sanitizedMessages);
        sanitizedRequest.setModel(model);
        sanitizedRequest.setStream(false);

        AIProviderAdapter adapter = providerRegistry.getAdapter(providerType);
        String apiKey = credentialStore.getKey(providerType).orElse(null);

        try {
            ChatCompletionResponse providerResponse = adapter.sendChat(sanitizedRequest, providerCfg, apiKey);
            // Restore tokens in all assistant choices
            if (providerResponse.getChoices() != null) {
                for (ChatCompletionResponse.Choice choice : providerResponse.getChoices()) {
                    if (choice.getMessage() != null && choice.getMessage().getContent() != null) {
                        String restored = tokenRestorer.restore(sid, choice.getMessage().getContent());
                        choice.getMessage().setContent(restored);
                    }
                }
            }
            long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
            RequestEvent event = buildEvent(sid, source, providerType, model, profile.getName(),
                    RequestEvent.Status.FORWARDED, totalDetected, totalMasked, false, false, elapsed,
                    providerType.isLocalOnly() ? "Routed locally via Ollama" : "Sanitized & forwarded", catCounts);
            recordAndNotify(event);
            return providerResponse;
        } catch (ProviderException e) {
            long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
            RequestEvent event = buildEvent(sid, source, providerType, model, profile.getName(),
                    RequestEvent.Status.PROVIDER_ERROR, totalDetected, totalMasked, false, false, elapsed,
                    e.getMessage(), catCounts);
            recordAndNotify(event);
            notifyAll("ERROR", "Provider Request Failed", e.getMessage());
            throw e;
        }
    }

    /**
     * End-to-end streaming chat completion pipeline with incremental split-token restoration.
     */
    public void processStreamingChatCompletion(ChatCompletionRequest request,
                                               ProviderType providerOverride,
                                               String sessionId,
                                               String source,
                                               Consumer<String> restoredChunkConsumer) throws ProviderException {
        long startNs = System.nanoTime();
        String sid = (sessionId == null || sessionId.isBlank()) ? "sess-" + UUID.randomUUID().toString().substring(0, 8) : sessionId;
        ProviderType providerType = providerOverride != null ? providerOverride : configManager.getConfig().getGateway().getActiveProvider();
        ProviderConfig providerCfg = configManager.getConfig().getProviderConfig(providerType);
        PrivacyProfile profile = getActiveProfile();
        String model = (request.getModel() != null && !request.getModel().isBlank()) ? request.getModel() : providerCfg.getDefaultModel();

        List<ChatMessage> sanitizedMessages = new ArrayList<>();
        int totalDetected = 0;
        int totalMasked = 0;
        Map<EntityCategory, Integer> catCounts = new EnumMap<>(EntityCategory.class);

        for (ChatMessage msg : request.getMessages()) {
            String content = msg.getContent() == null ? "" : msg.getContent();
            SanitizationResult res = sanitizeText(sid, content, profile);
            totalDetected += res.getDetectedEntities().size();
            totalMasked += res.getMaskedCount();
            PolicyEngine.countByCategory(res.getDetectedEntities())
                    .forEach((k, v) -> catCounts.merge(k, v, Integer::sum));

            if (res.isBlocked()) {
                long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
                RequestEvent event = buildEvent(sid, source, providerType, model, profile.getName(),
                        res.isWarnTriggered() ? RequestEvent.Status.WARN_DENIED : RequestEvent.Status.BLOCKED,
                        totalDetected, totalMasked, true, true, elapsed, res.getBlockReason(), catCounts);
                recordAndNotify(event);
                notifyAll("WARN", "Streaming Request Blocked", res.getBlockReason());
                throw new ProviderException(res.getBlockReason(), 403);
            }
            ChatMessage clean = new ChatMessage(msg.getRole(), res.getSanitizedText());
            clean.setName(msg.getName());
            sanitizedMessages.add(clean);
        }

        ChatCompletionRequest sanitizedRequest = request.copyWithMessages(sanitizedMessages);
        sanitizedRequest.setModel(model);
        sanitizedRequest.setStream(true);

        AIProviderAdapter adapter = providerRegistry.getAdapter(providerType);
        String apiKey = credentialStore.getKey(providerType).orElse(null);
        StreamingTokenRestorer restorer = new StreamingTokenRestorer(sid, tokenVault);

        try {
            adapter.streamChat(sanitizedRequest, providerCfg, apiKey, rawChunk -> {
                String out = restorer.processChunk(rawChunk);
                if (!out.isEmpty()) {
                    restoredChunkConsumer.accept(out);
                }
            });
            String tail = restorer.flush();
            if (!tail.isEmpty()) {
                restoredChunkConsumer.accept(tail);
            }
            long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
            RequestEvent event = buildEvent(sid, source, providerType, model, profile.getName(),
                    RequestEvent.Status.FORWARDED, totalDetected, totalMasked, false, true, elapsed,
                    "Stream sanitized & restored", catCounts);
            recordAndNotify(event);
        } catch (ProviderException e) {
            String tail = restorer.flush();
            if (!tail.isEmpty()) {
                restoredChunkConsumer.accept(tail);
            }
            long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
            RequestEvent event = buildEvent(sid, source, providerType, model, profile.getName(),
                    RequestEvent.Status.PROVIDER_ERROR, totalDetected, totalMasked, false, true, elapsed,
                    e.getMessage(), catCounts);
            recordAndNotify(event);
            notifyAll("ERROR", "Streaming Provider Failure", e.getMessage());
            throw e;
        }
    }

    // ───────────────────────── multi-turn conversations ─────────────────────────

    /** Receives progress from a conversation turn. Called on the request thread. */
    public interface TurnObserver {
        /** The new user message has been sanitized (and approved by policy); the provider call is about to start. */
        void onSanitized(SanitizationResult sanitized, ProviderType provider, String model);

        /** A piece of the reply, already restored to original values. */
        void onDelta(String restoredText);

        /** True once the caller no longer wants the reply (e.g. the user pressed Stop). */
        default boolean cancelled() {
            return false;
        }
    }

    /** Outcome of one conversation turn. {@code rawReply} is the provider's text with tokens still in place. */
    public static final class TurnResult {
        public final String sanitizedUser;
        public final String rawReply;
        public final String restoredReply;
        public final String model;
        public final ProviderType provider;
        public final int detected;
        public final int masked;
        public final Map<EntityCategory, Integer> categories;
        public final boolean cancelled;

        TurnResult(String sanitizedUser, String rawReply, String restoredReply, String model, ProviderType provider,
                   int detected, int masked, Map<EntityCategory, Integer> categories, boolean cancelled) {
            this.sanitizedUser = sanitizedUser;
            this.rawReply = rawReply;
            this.restoredReply = restoredReply;
            this.model = model;
            this.provider = provider;
            this.detected = detected;
            this.masked = masked;
            this.categories = categories;
            this.cancelled = cancelled;
        }
    }

    /**
     * Runs one turn of a long conversation. Only the NEW user message is sanitized here; earlier turns
     * arrive in {@code priorWire} exactly as they were sent to the provider before (tokens, not originals),
     * so a WARN rule is asked about once, not on every turn, and token numbering stays stable all session long.
     */
    public TurnResult runChatTurn(String sessionId,
                                  List<ChatMessage> priorWire,
                                  String userText,
                                  ProviderType providerOverride,
                                  String source,
                                  TurnObserver observer) throws ProviderException {
        long startNs = System.nanoTime();
        ProviderType providerType = providerOverride != null ? providerOverride : configManager.getConfig().getGateway().getActiveProvider();
        ProviderConfig providerCfg = configManager.getConfig().getProviderConfig(providerType);
        PrivacyProfile profile = getActiveProfile();
        String model = providerCfg.getDefaultModel();
        boolean stream = providerCfg.isStreamingEnabled();

        SanitizationResult res = sanitizeText(sessionId, userText, profile);
        int detected = res.getDetectedEntities().size();
        int masked = res.getMaskedCount();
        Map<EntityCategory, Integer> catCounts = new EnumMap<>(EntityCategory.class);
        PolicyEngine.countByCategory(res.getDetectedEntities()).forEach((k, v) -> catCounts.merge(k, v, Integer::sum));

        if (res.isBlocked()) {
            long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
            recordAndNotify(buildEvent(sessionId, source, providerType, model, profile.getName(),
                    res.isWarnTriggered() ? RequestEvent.Status.WARN_DENIED : RequestEvent.Status.BLOCKED,
                    detected, masked, true, stream, elapsed, res.getBlockReason(), catCounts));
            notifyAll("WARN", "Message held back by privacy policy", res.getBlockReason());
            throw new ProviderException(res.getBlockReason(), 403);
        }
        observer.onSanitized(res, providerType, model);

        List<ChatMessage> wire = new ArrayList<>(priorWire);
        wire.add(new ChatMessage("user", res.getSanitizedText()));
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setMessages(wire);
        request.setModel(model);
        request.setStream(stream);

        AIProviderAdapter adapter = providerRegistry.getAdapter(providerType);
        String apiKey = credentialStore.getKey(providerType).orElse(null);
        StringBuilder raw = new StringBuilder();
        StringBuilder restored = new StringBuilder();
        StreamingTokenRestorer restorer = stream ? new StreamingTokenRestorer(sessionId, tokenVault) : null;

        try {
            if (stream) {
                adapter.streamChat(request, providerCfg, apiKey, chunk -> {
                    raw.append(chunk);
                    String out = restorer.processChunk(chunk);
                    if (!out.isEmpty()) {
                        restored.append(out);
                        observer.onDelta(out);
                    }
                });
                String tail = restorer.flush();
                if (!tail.isEmpty()) {
                    restored.append(tail);
                    observer.onDelta(tail);
                }
            } else {
                ChatCompletionResponse response = adapter.sendChat(request, providerCfg, apiKey);
                String text = response.getFirstAssistantContent();
                raw.append(text == null ? "" : text);
                restored.append(tokenRestorer.restore(sessionId, raw.toString()));
                observer.onDelta(restored.toString());
            }
        } catch (ProviderException e) {
            long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
            if (observer.cancelled()) {
                recordAndNotify(buildEvent(sessionId, source, providerType, model, profile.getName(), RequestEvent.Status.FORWARDED,
                        detected, masked, false, stream, elapsed, "Stopped by user", catCounts));
                return new TurnResult(res.getSanitizedText(), raw.toString(), restored.toString(), model, providerType, detected, masked, catCounts, true);
            }
            recordAndNotify(buildEvent(sessionId, source, providerType, model, profile.getName(), RequestEvent.Status.PROVIDER_ERROR,
                    detected, masked, false, stream, elapsed, e.getMessage(), catCounts));
            notifyAll("ERROR", "Provider request failed", e.getMessage());
            throw e;
        }
        long elapsed = (System.nanoTime() - startNs) / 1_000_000L;
        recordAndNotify(buildEvent(sessionId, source, providerType, model, profile.getName(), RequestEvent.Status.FORWARDED,
                detected, masked, false, stream, elapsed,
                providerType.isLocalOnly() ? "Conversation turn routed locally" : "Conversation turn sanitized & restored", catCounts));
        return new TurnResult(res.getSanitizedText(), raw.toString(), restored.toString(), model, providerType, detected, masked, catCounts, false);
    }

    public boolean testProviderConnection(ProviderType providerType) throws ProviderException {
        ProviderConfig cfg = configManager.getConfig().getProviderConfig(providerType);
        String apiKey = credentialStore.getKey(providerType).orElse(null);
        return providerRegistry.getAdapter(providerType).testConnection(cfg, apiKey);
    }

    private RequestEvent buildEvent(String sessionId,
                                    String source,
                                    ProviderType provider,
                                    String model,
                                    String profileName,
                                    RequestEvent.Status status,
                                    int detected,
                                    int masked,
                                    boolean blocked,
                                    boolean streaming,
                                    long durationMs,
                                    String note,
                                    Map<EntityCategory, Integer> cats) {
        RequestEvent e = new RequestEvent();
        e.setSessionId(sessionId);
        e.setSource(source);
        e.setProvider(provider);
        e.setModel(model);
        e.setProfileName(profileName);
        e.setStatus(status);
        e.setDetectedCount(detected);
        e.setMaskedCount(masked);
        e.setBlocked(blocked);
        e.setStreaming(streaming);
        e.setDurationMs(durationMs);
        e.setSummaryNote(note);
        e.setCategoryCounts(cats);
        return e;
    }

    public void recordAndNotify(RequestEvent event) {
        analyticsService.recordEvent(event);
        for (PrivacyEventListener l : listeners) {
            try {
                l.onRequestEvent(event);
            } catch (Exception ignored) {
            }
        }
    }

    public void notifyGatewayState(boolean running, String message) {
        for (PrivacyEventListener l : listeners) {
            try {
                l.onGatewayStateChanged(running, message);
            } catch (Exception ignored) {
            }
        }
    }

    public void notifyAll(String level, String title, String message) {
        for (PrivacyEventListener l : listeners) {
            try {
                l.onNotification(level, title, message);
            } catch (Exception ignored) {
            }
        }
    }

    public void addListener(PrivacyEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PrivacyEventListener listener) {
        listeners.remove(listener);
    }

    public void setWarnCallback(WarnCallback callback) {
        policyEngine.setWarnCallback(callback);
    }

    public Path getHomeDir() {
        return homeDir;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public CredentialStore getCredentialStore() {
        return credentialStore;
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }

    public DetectionEngine getDetectionEngine() {
        return detectionEngine;
    }

    public PolicyEngine getPolicyEngine() {
        return policyEngine;
    }

    public TokenVault getTokenVault() {
        return tokenVault;
    }

    public ProviderRegistry getProviderRegistry() {
        return providerRegistry;
    }

    public AnalyticsService getAnalyticsService() {
        return analyticsService;
    }
}
