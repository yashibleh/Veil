package veil.models;

import java.util.Objects;

/**
 * Immutable representation of a detected sensitive span inside a text payload.
 */
public final class DetectedEntity implements Comparable<DetectedEntity> {
    private final EntityCategory category;
    private final String originalValue;
    private final int startIndex;
    private final int endIndex;
    private final String detectorName;
    private final Double confidence; // null when detector has no genuine scoring model
    private final String ruleLabel;

    public DetectedEntity(EntityCategory category,
                          String originalValue,
                          int startIndex,
                          int endIndex,
                          String detectorName,
                          Double confidence,
                          String ruleLabel) {
        this.category = Objects.requireNonNull(category, "category");
        this.originalValue = Objects.requireNonNull(originalValue, "originalValue");
        if (startIndex < 0 || endIndex <= startIndex) {
            throw new IllegalArgumentException("Invalid span [" + startIndex + ", " + endIndex + ")");
        }
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.detectorName = Objects.requireNonNull(detectorName, "detectorName");
        this.confidence = confidence;
        this.ruleLabel = ruleLabel == null ? category.getDisplayName() : ruleLabel;
    }

    public EntityCategory getCategory() {
        return category;
    }

    public String getOriginalValue() {
        return originalValue;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public int length() {
        return endIndex - startIndex;
    }

    public String getDetectorName() {
        return detectorName;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getRuleLabel() {
        return ruleLabel;
    }

    public boolean overlaps(DetectedEntity other) {
        return this.startIndex < other.endIndex && other.startIndex < this.endIndex;
    }

    @Override
    public int compareTo(DetectedEntity other) {
        int cmp = Integer.compare(this.startIndex, other.startIndex);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(other.length(), this.length());
    }
}
