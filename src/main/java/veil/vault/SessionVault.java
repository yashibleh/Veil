package veil.vault;

import veil.models.EntityCategory;
import veil.models.PolicyAction;
import veil.tokenization.RealisticFakeGenerator;
import veil.tokenization.TokenGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Isolated per-session bidirectional mapping store with consistent token reuse and TTL expiry.
 */
public class SessionVault {

    private final String sessionId;
    private final Instant createdAt;
    private Instant lastAccessedAt;
    private final Map<String, TokenMapping> originalToMapping = new LinkedHashMap<>();
    private final Map<String, TokenMapping> tokenToMapping = new LinkedHashMap<>();
    private final TokenGenerator tokenGenerator = new TokenGenerator();
    private final RealisticFakeGenerator fakeGenerator = new RealisticFakeGenerator();

    public SessionVault(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
    }

    public synchronized String getOrCreateToken(String originalValue,
                                                EntityCategory category,
                                                PolicyAction action,
                                                String sourceText) {
        this.lastAccessedAt = Instant.now();
        String key = category.name() + "::" + originalValue;
        TokenMapping existing = originalToMapping.get(key);
        if (existing != null) {
            return existing.getToken();
        }
        String replacement = (action == PolicyAction.REALISTIC_FAKE)
                ? fakeGenerator.nextFake(category, tokenToMapping.keySet(), sourceText)
                : tokenGenerator.nextToken(category, tokenToMapping.keySet(), sourceText);

        TokenMapping mapping = new TokenMapping(replacement, originalValue, category, action);
        originalToMapping.put(key, mapping);
        tokenToMapping.put(replacement, mapping);
        return replacement;
    }

    public synchronized void restoreLoadedMapping(TokenMapping mapping) {
        String key = mapping.getCategory().name() + "::" + mapping.getOriginalValue();
        originalToMapping.put(key, mapping);
        tokenToMapping.put(mapping.getToken(), mapping);
    }

    public synchronized Optional<String> resolveOriginal(String token) {
        this.lastAccessedAt = Instant.now();
        TokenMapping m = tokenToMapping.get(token);
        return m == null ? Optional.empty() : Optional.of(m.getOriginalValue());
    }

    public synchronized boolean containsToken(String token) {
        return tokenToMapping.containsKey(token);
    }

    public synchronized Collection<TokenMapping> getMappings() {
        return Collections.unmodifiableList(new ArrayList<>(tokenToMapping.values()));
    }

    public synchronized List<String> getAllTokens() {
        return new ArrayList<>(tokenToMapping.keySet());
    }

    public synchronized int size() {
        return tokenToMapping.size();
    }

    public synchronized void clear() {
        originalToMapping.clear();
        tokenToMapping.clear();
        tokenGenerator.reset();
        fakeGenerator.reset();
    }

    public boolean isExpired(Duration ttl) {
        return Instant.now().isAfter(lastAccessedAt.plus(ttl));
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }
}
