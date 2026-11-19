package veil.policies;

import veil.models.DetectedEntity;
import veil.models.PolicyAction;
import veil.vault.TokenVault;

/**
 * REALISTIC_FAKE strategy: replaces sensitive data with a plausible synthetic value
 * stored in the session vault for contextual restoration.
 */
public class RealisticFakePolicyStrategy implements PolicyStrategy {

    @Override
    public PolicyAction getAction() {
        return PolicyAction.REALISTIC_FAKE;
    }

    @Override
    public String apply(String sessionId, DetectedEntity entity, String fullSourceText, TokenVault vault) {
        return vault.tokenize(sessionId, entity.getOriginalValue(), entity.getCategory(), PolicyAction.REALISTIC_FAKE, fullSourceText);
    }
}
