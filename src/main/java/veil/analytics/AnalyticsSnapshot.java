package veil.analytics;

import veil.models.EntityCategory;
import veil.models.ProviderType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable snapshot of genuine analytics derived exclusively from real recorded RequestEvents.
 */
public final class AnalyticsSnapshot {
    private final long totalRequests;
    private final long forwardedRequests;
    private final long blockedRequests;
    private final long errorRequests;
    private final long totalEntitiesDetected;
    private final long totalEntitiesMasked;
    private final double averageDurationMs;
    private final Map<EntityCategory, Long> categoryDistribution;
    private final Map<ProviderType, Long> providerUsage;

    public AnalyticsSnapshot(long totalRequests,
                             long forwardedRequests,
                             long blockedRequests,
                             long errorRequests,
                             long totalEntitiesDetected,
                             long totalEntitiesMasked,
                             double averageDurationMs,
                             Map<EntityCategory, Long> categoryDistribution,
                             Map<ProviderType, Long> providerUsage) {
        this.totalRequests = totalRequests;
        this.forwardedRequests = forwardedRequests;
        this.blockedRequests = blockedRequests;
        this.errorRequests = errorRequests;
        this.totalEntitiesDetected = totalEntitiesDetected;
        this.totalEntitiesMasked = totalEntitiesMasked;
        this.averageDurationMs = averageDurationMs;
        this.categoryDistribution = Collections.unmodifiableMap(new EnumMap<>(categoryDistribution));
        this.providerUsage = Collections.unmodifiableMap(new EnumMap<>(providerUsage));
    }

    public boolean isEmpty() {
        return totalRequests == 0;
    }

    public long getTotalRequests() {
        return totalRequests;
    }

    public long getForwardedRequests() {
        return forwardedRequests;
    }

    public long getBlockedRequests() {
        return blockedRequests;
    }

    public long getErrorRequests() {
        return errorRequests;
    }

    public long getTotalEntitiesDetected() {
        return totalEntitiesDetected;
    }

    public long getTotalEntitiesMasked() {
        return totalEntitiesMasked;
    }

    public double getAverageDurationMs() {
        return averageDurationMs;
    }

    public Map<EntityCategory, Long> getCategoryDistribution() {
        return categoryDistribution;
    }

    public Map<ProviderType, Long> getProviderUsage() {
        return providerUsage;
    }
}
