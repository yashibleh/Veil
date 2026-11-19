package veil.policies;

import veil.models.DetectedEntity;
import veil.models.PolicyAction;
import veil.vault.TokenVault;

/**
 * MASK strategy: replaces sensitive data with a consistent reversible token such as [EMAIL_001].
 */
public class MaskPolicyStrategy implements PolicyStrategy {

    @Override
    public PolicyAction getAction() {
        return PolicyAction.MASK;
    }

    @Override
    public String apply(String sessionId, DetectedEntity entity, String fullSourceText, TokenVault vault) {
        return vault.tokenize(sessionId, entity.getOriginalValue(), entity.getCategory(), PolicyAction.MASK, fullSourceText);
    }
}
