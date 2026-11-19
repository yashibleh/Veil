package veil.vault;

import veil.models.EntityCategory;
import veil.models.PolicyAction;

import java.time.Instant;

/**
 * Single bidirectional token mapping entry stored inside a SessionVault.
 */
public class TokenMapping {
    private String token;
    private String originalValue;
    private EntityCategory category;
    private PolicyAction action;
    private Instant createdAt;

    public TokenMapping() {
    }

    public TokenMapping(String token, String originalValue, EntityCategory category, PolicyAction action) {
        this.token = token;
        this.originalValue = originalValue;
        this.category = category;
        this.action = action;
        this.createdAt = Instant.now();
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getOriginalValue() {
        return originalValue;
    }

    public void setOriginalValue(String originalValue) {
        this.originalValue = originalValue;
    }

    public EntityCategory getCategory() {
        return category;
    }

    public void setCategory(EntityCategory category) {
        this.category = category;
    }

    public PolicyAction getAction() {
        return action;
    }

    public void setAction(PolicyAction action) {
        this.action = action;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns a masked preview safe for default UI display until user explicitly reveals.
     */
    public String getRedactedPreview() {
        if (originalValue == null || originalValue.length() <= 4) {
            return "••••";
        }
        return originalValue.substring(0, 2) + "••••" + originalValue.substring(originalValue.length() - 2);
    }
}
