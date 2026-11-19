package veil.models;

/**
 * Enforcement actions supported by the VEIL Privacy Policy Engine.
 */
public enum PolicyAction {
    MASK("Mask with Reversible Token"),
    BLOCK("Block Request"),
    WARN("Warn & Require Confirmation"),
    REALISTIC_FAKE("Replace with Realistic Synthetic Value"),
    ALLOW("Allow Unmodified");

    private final String description;

    PolicyAction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
