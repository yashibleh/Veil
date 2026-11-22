package veil.policies;

import veil.models.DetectedEntity;
import veil.models.PolicyAction;
import veil.vault.TokenVault;

/**
 * Strategy interface for transforming or blocking a detected sensitive entity.
 */
public interface PolicyStrategy {

    PolicyAction getAction();

    /**
     * Computes replacement text for the entity, or returns null if the entity blocks the request.
     */
    String apply(String sessionId, DetectedEntity entity, String fullSourceText, TokenVault vault);
}
