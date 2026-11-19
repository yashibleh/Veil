package veil.policies;

import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PolicyAction;
import veil.models.PrivacyProfile;
import veil.models.SanitizationResult;
import veil.vault.TokenVault;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enforces MASK, BLOCK, WARN, REALISTIC_FAKE, and ALLOW policies per entity category.
 * Guarantees that any BLOCK-classified entity halts forwarding immediately.
 */
public class PolicyEngine {

    private final Map<PolicyAction, PolicyStrategy> strategies = new EnumMap<>(PolicyAction.class);
    private volatile WarnCallback warnCallback;

    public PolicyEngine() {
        strategies.put(PolicyAction.MASK, new MaskPolicyStrategy());
        strategies.put(PolicyAction.REALISTIC_FAKE, new RealisticFakePolicyStrategy());
    }

    public void setWarnCallback(WarnCallback warnCallback) {
        this.warnCallback = warnCallback;
    }

    /**
     * Applies the active profile's policies to the text and its detected entities.
     */
    public SanitizationResult enforce(String sessionId,
                                      String text,
                                      List<DetectedEntity> entities,
                                      PrivacyProfile profile,
                                      TokenVault vault) {
        if (text == null) {
            return new SanitizationResult(sessionId, "", "", List.of(), Map.of(), false, false, true, null);
        }
        if (entities == null || entities.isEmpty()) {
            return new SanitizationResult(sessionId, text, text, List.of(), Map.of(), false, false, true, null);
        }

        // 1. Check BLOCK policy first — highest precedence
        List<DetectedEntity> blockedEntities = new ArrayList<>();
        List<DetectedEntity> warnedEntities = new ArrayList<>();

        for (DetectedEntity entity : entities) {
            PolicyAction action = profile.getPolicyFor(entity.getCategory());
            if (action == PolicyAction.BLOCK) {
                blockedEntities.add(entity);
            } else if (action == PolicyAction.WARN) {
                warnedEntities.add(entity);
            }
        }

        if (!blockedEntities.isEmpty()) {
            String cats = blockedEntities.stream()
                    .map(e -> e.getCategory().getDisplayName())
                    .distinct()
                    .collect(Collectors.joining(", "));
            return new SanitizationResult(
                    sessionId,
                    text,
                    null,
                    entities,
                    Map.of(),
                    true,
                    false,
                    false,
                    "Blocked by privacy policy (" + profile.getName() + "): detected " + cats
            );
        }

        // 2. Check WARN policy — require confirmation before forwarding
        boolean warnTriggered = !warnedEntities.isEmpty();
        boolean warnApproved = true;
        if (warnTriggered) {
            WarnCallback cb = this.warnCallback;
            if (cb != null) {
                warnApproved = cb.confirmForward(warnedEntities, profile);
            } else {
                // Fail safe: if no interactive callback is attached in headless gateway mode, block WARN requests
                warnApproved = false;
            }
            if (!warnApproved) {
                String cats = warnedEntities.stream()
                        .map(e -> e.getCategory().getDisplayName())
                        .distinct()
                        .collect(Collectors.joining(", "));
                return new SanitizationResult(
                        sessionId,
                        text,
                        null,
                        entities,
                        Map.of(),
                        true,
                        true,
                        false,
                        "Request halted by WARN policy (" + profile.getName() + "): confirmation declined or unavailable for " + cats
                );
            }
        }

        // 3. Apply MASK / REALISTIC_FAKE / WARN (masked after approval) / ALLOW transformations
        StringBuilder sanitized = new StringBuilder(text.length());
        Map<String, String> appliedReplacements = new LinkedHashMap<>();
        int cursor = 0;

        for (DetectedEntity entity : entities) {
            if (entity.getStartIndex() < cursor) {
                continue; // safeguard against unexpected overlaps
            }
            sanitized.append(text, cursor, entity.getStartIndex());
            PolicyAction action = profile.getPolicyFor(entity.getCategory());

            if (action == PolicyAction.ALLOW) {
                sanitized.append(entity.getOriginalValue());
            } else if (action == PolicyAction.REALISTIC_FAKE) {
                String replacement = strategies.get(PolicyAction.REALISTIC_FAKE)
                        .apply(sessionId, entity, text, vault);
                sanitized.append(replacement);
                appliedReplacements.put(replacement, entity.getCategory().getDisplayName());
            } else {
                // MASK or approved WARN -> mask with reversible token
                String replacement = strategies.get(PolicyAction.MASK)
                        .apply(sessionId, entity, text, vault);
                sanitized.append(replacement);
                appliedReplacements.put(replacement, entity.getCategory().getDisplayName());
            }
            cursor = entity.getEndIndex();
        }
        sanitized.append(text.substring(cursor));

        return new SanitizationResult(
                sessionId,
                text,
                sanitized.toString(),
                entities,
                appliedReplacements,
                false,
                warnTriggered,
                warnApproved,
                null
        );
    }

    public static Map<EntityCategory, Integer> countByCategory(List<DetectedEntity> entities) {
        Map<EntityCategory, Integer> counts = new EnumMap<>(EntityCategory.class);
        if (entities != null) {
            for (DetectedEntity e : entities) {
                counts.merge(e.getCategory(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
