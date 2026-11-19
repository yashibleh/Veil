package veil.policies;

import veil.models.DetectedEntity;
import veil.models.PrivacyProfile;

import java.util.List;

/**
 * Callback invoked when a WARN policy triggers, allowing the UI or SDK caller
 * to approve or reject forwarding the request.
 */
@FunctionalInterface
public interface WarnCallback {
    boolean confirmForward(List<DetectedEntity> warnedEntities, PrivacyProfile profile);
}
