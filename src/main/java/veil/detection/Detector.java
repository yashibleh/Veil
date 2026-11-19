package veil.detection;

import veil.models.DetectedEntity;
import veil.models.PrivacyProfile;

import java.util.List;

/**
 * Modular interface for sensitive information detectors in VEIL.
 */
public interface Detector {

    /**
     * Unique stable identifier used in configuration and UI toggles.
     */
    String getName();

    /**
     * Human-readable summary of what this detector inspects.
     */
    String getDescription();

    /**
     * Scans input text under the active privacy profile and returns all candidate detections.
     */
    List<DetectedEntity> detect(String text, PrivacyProfile profile);
}
