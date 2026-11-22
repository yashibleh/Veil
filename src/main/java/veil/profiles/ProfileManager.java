package veil.profiles;

import com.fasterxml.jackson.core.type.TypeReference;
import veil.models.CustomRule;
import veil.models.EntityCategory;
import veil.models.PolicyAction;
import veil.models.PrivacyProfile;
import veil.utils.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages built-in (Personal, Developer, Enterprise, Strict) and user-created privacy profiles
 * with real JSON persistence.
 */
public class ProfileManager {

    private final Map<String, PrivacyProfile> profiles = new LinkedHashMap<>();
    private final Path profilesFile;

    public ProfileManager(Path profilesFile) {
        this.profilesFile = profilesFile;
        seedBuiltInProfiles();
        loadPersistedProfiles();
    }

    private void seedBuiltInProfiles() {
        profiles.put("personal", buildPersonalProfile());
        profiles.put("developer", buildDeveloperProfile());
        profiles.put("enterprise", buildEnterpriseProfile());
        profiles.put("strict", buildStrictProfile());
    }

    private PrivacyProfile buildPersonalProfile() {
        PrivacyProfile p = new PrivacyProfile(
                "personal",
                "Personal",
                "Protects personal identity details (names, emails, phones, Aadhaar, PAN, cards) with reversible masking.",
                true
        );
        p.setEnabledCategories(EnumSet.of(
                EntityCategory.PERSONAL_NAME,
                EntityCategory.EMAIL,
                EntityCategory.PHONE,
                EntityCategory.AADHAAR,
                EntityCategory.PAN,
                EntityCategory.CREDIT_CARD,
                EntityCategory.PASSWORD,
                EntityCategory.CUSTOM
        ));
        Map<EntityCategory, PolicyAction> map = new EnumMap<>(EntityCategory.class);
        for (EntityCategory c : EntityCategory.values()) {
            map.put(c, PolicyAction.MASK);
        }
        map.put(EntityCategory.PASSWORD, PolicyAction.BLOCK);
        map.put(EntityCategory.CREDIT_CARD, PolicyAction.BLOCK);
        p.setCategoryPolicies(map);
        return p;
    }

    private PrivacyProfile buildDeveloperProfile() {
        PrivacyProfile p = new PrivacyProfile(
                "developer",
                "Developer",
                "Blocks live API keys, passwords, and DB credentials while masking IPs, internal hosts, emails, and code identifiers.",
                true
        );
        p.setEnabledCategories(EnumSet.allOf(EntityCategory.class));
        Map<EntityCategory, PolicyAction> map = new EnumMap<>(EntityCategory.class);
        for (EntityCategory c : EntityCategory.values()) {
            map.put(c, PolicyAction.MASK);
        }
        map.put(EntityCategory.API_KEY, PolicyAction.BLOCK);
        map.put(EntityCategory.PASSWORD, PolicyAction.BLOCK);
        map.put(EntityCategory.DB_CREDENTIAL, PolicyAction.BLOCK);
        map.put(EntityCategory.ACCESS_TOKEN, PolicyAction.BLOCK);
        p.setCategoryPolicies(map);
        p.setCodeIdentifiers(List.of("INTERNAL_SIGNING_SECRET", "PROD_CLUSTER_MASTER"));
        return p;
    }

    private PrivacyProfile buildEnterpriseProfile() {
        PrivacyProfile p = new PrivacyProfile(
                "enterprise",
                "Enterprise",
                "Covers employee IDs, internal project names, credentials, PII, and custom organizational rules.",
                true
        );
        p.setEnabledCategories(EnumSet.allOf(EntityCategory.class));
        Map<EntityCategory, PolicyAction> map = new EnumMap<>(EntityCategory.class);
        for (EntityCategory c : EntityCategory.values()) {
            map.put(c, PolicyAction.MASK);
        }
        map.put(EntityCategory.API_KEY, PolicyAction.BLOCK);
        map.put(EntityCategory.PASSWORD, PolicyAction.BLOCK);
        map.put(EntityCategory.DB_CREDENTIAL, PolicyAction.BLOCK);
        map.put(EntityCategory.PRIVATE_IDENTIFIER, PolicyAction.MASK);
        map.put(EntityCategory.CUSTOM, PolicyAction.MASK);
        p.setCategoryPolicies(map);
        p.setCustomRules(List.of(
                new CustomRule("Confidential Tag", "CONFIDENTIAL-INTERNAL", false, false, EntityCategory.CUSTOM)
        ));
        return p;
    }

    private PrivacyProfile buildStrictProfile() {
        PrivacyProfile p = new PrivacyProfile(
                "strict",
                "Strict",
                "High-assurance profile that blocks requests containing secrets, financial IDs, government IDs, or credentials.",
                true
        );
        p.setEnabledCategories(EnumSet.allOf(EntityCategory.class));
        Map<EntityCategory, PolicyAction> map = new EnumMap<>(EntityCategory.class);
        for (EntityCategory c : EntityCategory.values()) {
            map.put(c, PolicyAction.BLOCK);
        }
        map.put(EntityCategory.EMAIL, PolicyAction.MASK);
        map.put(EntityCategory.PERSONAL_NAME, PolicyAction.WARN);
        map.put(EntityCategory.IP_ADDRESS, PolicyAction.MASK);
        p.setCategoryPolicies(map);
        return p;
    }

    public Collection<PrivacyProfile> getAllProfiles() {
        return new ArrayList<>(profiles.values());
    }

    public Optional<PrivacyProfile> getProfile(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public PrivacyProfile getProfileOrDefault(String id) {
        PrivacyProfile found = profiles.get(id);
        return found != null ? found : profiles.get("developer");
    }

    public void saveProfile(PrivacyProfile profile) {
        if (profile == null || profile.getId() == null || profile.getId().isBlank()) {
            throw new IllegalArgumentException("Profile must have a valid ID");
        }
        profiles.put(profile.getId(), profile);
        persistProfiles();
    }

    public PrivacyProfile duplicateProfile(String sourceId, String newName) {
        PrivacyProfile source = getProfileOrDefault(sourceId);
        PrivacyProfile copy = source.copyAsCustom(newName);
        profiles.put(copy.getId(), copy);
        persistProfiles();
        return copy;
    }

    public boolean deleteCustomProfile(String id) {
        PrivacyProfile existing = profiles.get(id);
        if (existing == null || existing.isBuiltIn()) {
            return false;
        }
        profiles.remove(id);
        persistProfiles();
        return true;
    }

    private void loadPersistedProfiles() {
        if (profilesFile == null || !Files.exists(profilesFile)) {
            return;
        }
        try {
            List<PrivacyProfile> loaded = JsonUtil.mapper()
                    .readValue(profilesFile.toFile(), new TypeReference<List<PrivacyProfile>>() {});
            for (PrivacyProfile p : loaded) {
                if (p.getId() != null && !p.getId().isBlank()) {
                    profiles.put(p.getId(), p);
                }
            }
        } catch (IOException ignored) {
            // Keep built-in defaults if persisted file is corrupted
        }
    }

    public void persistProfiles() {
        if (profilesFile == null) {
            return;
        }
        try {
            if (profilesFile.getParent() != null) {
                Files.createDirectories(profilesFile.getParent());
            }
            JsonUtil.prettyMapper().writeValue(profilesFile.toFile(), new ArrayList<>(profiles.values()));
        } catch (IOException ignored) {
        }
    }
}
