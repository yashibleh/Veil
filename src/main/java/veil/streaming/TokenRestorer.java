package veil.streaming;

import veil.vault.SessionVault;
import veil.vault.TokenMapping;
import veil.vault.TokenVault;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Contextually restores VEIL-generated tokens and synthetic values back to original values
 * ONLY when they match an exact mapping stored in the active session's vault.
 * Unrelated or hallucinated token-like strings are left untouched.
 */
public class TokenRestorer {

    private final TokenVault vault;

    public TokenRestorer(TokenVault vault) {
        this.vault = vault;
    }

    public String restore(String sessionId, String providerText) {
        if (providerText == null || providerText.isEmpty() || sessionId == null) {
            return providerText;
        }
        Optional<SessionVault> opt = vault.getSession(sessionId);
        if (opt.isEmpty()) {
            return providerText;
        }
        SessionVault session = opt.get();
        List<TokenMapping> mappings = session.getMappings().stream()
                .sorted(Comparator.comparingInt((TokenMapping m) -> m.getToken().length()).reversed())
                .toList();
        if (mappings.isEmpty()) {
            return providerText;
        }

        String restored = providerText;
        for (TokenMapping mapping : mappings) {
            String token = mapping.getToken();
            if (restored.contains(token)) {
                restored = restored.replace(token, mapping.getOriginalValue());
            }
        }
        return restored;
    }
}
