package veil.models;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Metadata record for a genuine request processed by VEIL.
 * Never stores raw sensitive prompt content.
 */
public class RequestEvent {
    public enum Status {
        FORWARDED,
        BLOCKED,
        WARN_DENIED,
        PROVIDER_ERROR,
        VALIDATION_ERROR,
        AUTH_REJECTED
    }

    private String id;
    private Instant timestamp;
    private String sessionId;
    private String source; // "GATEWAY", "PLAYGROUND", "SDK"
    private ProviderType provider;
    private String model;
    private String profileName;
    private Status status;
    private int detectedCount;
    private int maskedCount;
    private boolean blocked;
    private boolean streaming;
    private long durationMs;
    private String summaryNote;
    private Map<EntityCategory, Integer> categoryCounts = new EnumMap<>(EntityCategory.class);

    public RequestEvent() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public ProviderType getProvider() {
        return provider;
    }

    public void setProvider(ProviderType provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getDetectedCount() {
        return detectedCount;
    }

    public void setDetectedCount(int detectedCount) {
        this.detectedCount = detectedCount;
    }

    public int getMaskedCount() {
        return maskedCount;
    }

    public void setMaskedCount(int maskedCount) {
        this.maskedCount = maskedCount;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getSummaryNote() {
        return summaryNote;
    }

    public void setSummaryNote(String summaryNote) {
        this.summaryNote = summaryNote;
    }

    public Map<EntityCategory, Integer> getCategoryCounts() {
        return categoryCounts;
    }

    public void setCategoryCounts(Map<EntityCategory, Integer> categoryCounts) {
        this.categoryCounts = new EnumMap<>(EntityCategory.class);
        if (categoryCounts != null) {
            this.categoryCounts.putAll(categoryCounts);
        }
    }
}
