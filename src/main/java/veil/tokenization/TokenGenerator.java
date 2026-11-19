package veil.tokenization;

import veil.models.EntityCategory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates unique, collision-free reversible tokens such as [EMAIL_001], [IP_001].
 */
public class TokenGenerator {

    private final Map<EntityCategory, AtomicInteger> counters = new ConcurrentHashMap<>();

    public String nextToken(EntityCategory category, Set<String> existingTokens, String sourceText) {
        AtomicInteger counter = counters.computeIfAbsent(category, c -> new AtomicInteger(0));
        while (true) {
            int seq = counter.incrementAndGet();
            String candidate = String.format("[%s_%03d]", category.getTokenPrefix(), seq);
            boolean existsInVault = existingTokens != null && existingTokens.contains(candidate);
            boolean existsInText = sourceText != null && sourceText.contains(candidate);
            if (!existsInVault && !existsInText) {
                return candidate;
            }
        }
    }

    public void reset() {
        counters.clear();
    }
}
