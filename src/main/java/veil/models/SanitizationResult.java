package veil.models;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Outcome of running detection + policy enforcement + masking on a text payload.
 */
public final class SanitizationResult {
    private final String sessionId;
    private final String originalText;
    private final String sanitizedText;
    private final List<DetectedEntity> detectedEntities;
    private final Map<String, String> appliedReplacements; // token/fake -> category label (redacted safe summary)
    private final boolean blocked;
    private final boolean warnTriggered;
    private final boolean warnApproved;
    private final String blockReason;

    public SanitizationResult(String sessionId,
                              String originalText,
                              String sanitizedText,
                              List<DetectedEntity> detectedEntities,
                              Map<String, String> appliedReplacements,
                              boolean blocked,
                              boolean warnTriggered,
                              boolean warnApproved,
                              String blockReason) {
        this.sessionId = sessionId;
        this.originalText = originalText;
        this.sanitizedText = sanitizedText;
        this.detectedEntities = detectedEntities == null ? List.of() : Collections.unmodifiableList(detectedEntities);
        this.appliedReplacements = appliedReplacements == null ? Map.of() : Collections.unmodifiableMap(appliedReplacements);
        this.blocked = blocked;
        this.warnTriggered = warnTriggered;
        this.warnApproved = warnApproved;
        this.blockReason = blockReason;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getOriginalText() {
        return originalText;
    }

    public String getSanitizedText() {
        return sanitizedText;
    }

    public List<DetectedEntity> getDetectedEntities() {
        return detectedEntities;
    }

    public Map<String, String> getAppliedReplacements() {
        return appliedReplacements;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public boolean isWarnTriggered() {
        return warnTriggered;
    }

    public boolean isWarnApproved() {
        return warnApproved;
    }

    public String getBlockReason() {
        return blockReason;
    }

    public int getMaskedCount() {
        return appliedReplacements.size();
    }
}
