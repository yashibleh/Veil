package veil.models;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Genuine configurable privacy profile bundling enabled categories,
 * category-specific policies, code identifiers, and custom rules.
 */
public class PrivacyProfile {
    private String id;
    private String name;
    private String description;
    private boolean builtIn;
    private Set<EntityCategory> enabledCategories = EnumSet.allOf(EntityCategory.class);
    private Map<EntityCategory, PolicyAction> categoryPolicies = new EnumMap<>(EntityCategory.class);
    private List<String> codeIdentifiers = new ArrayList<>();
    private List<CustomRule> customRules = new ArrayList<>();

    public PrivacyProfile() {
        this.id = UUID.randomUUID().toString();
        for (EntityCategory cat : EntityCategory.values()) {
            this.categoryPolicies.put(cat, PolicyAction.MASK);
        }
    }

    public PrivacyProfile(String id, String name, String description, boolean builtIn) {
        this();
        this.id = id;
        this.name = name;
        this.description = description;
        this.builtIn = builtIn;
    }

    public PrivacyProfile copyAsCustom(String newName) {
        PrivacyProfile copy = new PrivacyProfile();
        copy.setName(newName);
        copy.setDescription(this.description);
        copy.setBuiltIn(false);
        copy.setEnabledCategories(EnumSet.copyOf(this.enabledCategories));
        copy.setCategoryPolicies(new EnumMap<>(this.categoryPolicies));
        copy.setCodeIdentifiers(new ArrayList<>(this.codeIdentifiers));
        List<CustomRule> copiedRules = new ArrayList<>();
        for (CustomRule r : this.customRules) {
            CustomRule nr = new CustomRule(r.getName(), r.getPattern(), r.isRegex(), r.isCaseSensitive(), r.getCategory());
            nr.setEnabled(r.isEnabled());
            copiedRules.add(nr);
        }
        copy.setCustomRules(copiedRules);
        return copy;
    }

    public PolicyAction getPolicyFor(EntityCategory category) {
        if (!enabledCategories.contains(category)) {
            return PolicyAction.ALLOW;
        }
        return categoryPolicies.getOrDefault(category, PolicyAction.MASK);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    public Set<EntityCategory> getEnabledCategories() {
        return enabledCategories;
    }

    public void setEnabledCategories(Set<EntityCategory> enabledCategories) {
        this.enabledCategories = enabledCategories == null || enabledCategories.isEmpty()
                ? EnumSet.noneOf(EntityCategory.class)
                : EnumSet.copyOf(enabledCategories);
    }

    public Map<EntityCategory, PolicyAction> getCategoryPolicies() {
        return categoryPolicies;
    }

    public void setCategoryPolicies(Map<EntityCategory, PolicyAction> categoryPolicies) {
        this.categoryPolicies = new EnumMap<>(EntityCategory.class);
        if (categoryPolicies != null) {
            this.categoryPolicies.putAll(categoryPolicies);
        }
    }

    public List<String> getCodeIdentifiers() {
        return codeIdentifiers;
    }

    public void setCodeIdentifiers(List<String> codeIdentifiers) {
        this.codeIdentifiers = codeIdentifiers == null ? new ArrayList<>() : new ArrayList<>(codeIdentifiers);
    }

    public List<CustomRule> getCustomRules() {
        return customRules;
    }

    public void setCustomRules(List<CustomRule> customRules) {
        this.customRules = customRules == null ? new ArrayList<>() : new ArrayList<>(customRules);
    }

    @Override
    public String toString() {
        return name;
    }
}
