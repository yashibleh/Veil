package veil.detection;

import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PrivacyProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates the modular detection pipeline, manages enable/disable states,
 * filters by profile categories, and resolves overlapping detections deterministically.
 */
public class DetectionEngine {

    private final List<Detector> detectors = new ArrayList<>();
    private final Map<String, Boolean> enabledStates = new ConcurrentHashMap<>();

    public DetectionEngine() {
        registerDetector(new SecretDetector());
        registerDetector(new RegexDetector());
        registerDetector(new CustomRuleDetector());
        registerDetector(new CodeIdentifierDetector());
        registerDetector(new NameDetector());
    }

    public void registerDetector(Detector detector) {
        detectors.add(detector);
        enabledStates.putIfAbsent(detector.getName(), true);
    }

    public List<Detector> getDetectors() {
        return Collections.unmodifiableList(detectors);
    }

    public void setDetectorEnabled(String detectorName, boolean enabled) {
        enabledStates.put(detectorName, enabled);
    }

    public boolean isDetectorEnabled(String detectorName) {
        return enabledStates.getOrDefault(detectorName, true);
    }

    public Map<String, Boolean> getEnabledStates() {
        return new LinkedHashMap<>(enabledStates);
    }

    public void applyEnabledStates(Map<String, Boolean> states) {
        if (states != null) {
            enabledStates.putAll(states);
        }
    }

    /**
     * Runs all enabled detectors, filters to enabled categories on the profile,
     * and resolves overlaps (preferring longer, higher-priority spans).
     */
    public List<DetectedEntity> detect(String text, PrivacyProfile profile) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<DetectedEntity> raw = new ArrayList<>();
        for (Detector detector : detectors) {
            if (!isDetectorEnabled(detector.getName())) {
                continue;
            }
            List<DetectedEntity> found = detector.detect(text, profile);
            for (DetectedEntity entity : found) {
                if (profile == null || profile.getEnabledCategories().contains(entity.getCategory())) {
                    raw.add(entity);
                }
            }
        }

        return resolveOverlaps(raw);
    }

    /**
     * Deterministically resolves overlapping spans:
     * 1) Higher category priority (secrets/DB credentials > structured IDs > URLs > names)
     * 2) Longer span length
     * 3) Earlier start index
     */
    private List<DetectedEntity> resolveOverlaps(List<DetectedEntity> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<DetectedEntity> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparingInt((DetectedEntity e) -> categoryPriority(e.getCategory())).reversed()
                .thenComparing(Comparator.comparingInt(DetectedEntity::length).reversed())
                .thenComparingInt(DetectedEntity::getStartIndex));

        List<DetectedEntity> accepted = new ArrayList<>();
        for (DetectedEntity candidate : sorted) {
            boolean collides = false;
            for (DetectedEntity existing : accepted) {
                if (candidate.overlaps(existing)) {
                    collides = true;
                    break;
                }
            }
            if (!collides) {
                accepted.add(candidate);
            }
        }
        accepted.sort(Comparator.comparingInt(DetectedEntity::getStartIndex));
        return accepted;
    }

    private int categoryPriority(EntityCategory category) {
        return switch (category) {
            case DB_CREDENTIAL, API_KEY, ACCESS_TOKEN, PASSWORD -> 100;
            case CREDIT_CARD, AADHAAR, PAN -> 90;
            case CUSTOM, CODE_IDENTIFIER, PRIVATE_IDENTIFIER -> 80;
            case EMAIL, PHONE, IP_ADDRESS, URL -> 70;
            case PERSONAL_NAME -> 50;
        };
    }
}
