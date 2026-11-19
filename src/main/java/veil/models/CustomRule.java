package veil.models;

import java.util.UUID;

/**
 * User-defined detection rule (literal term or regular expression).
 */
public class CustomRule {
    private String id;
    private String name;
    private String pattern;
    private boolean regex;
    private boolean caseSensitive;
    private EntityCategory category;
    private boolean enabled;

    public CustomRule() {
        this.id = UUID.randomUUID().toString();
        this.category = EntityCategory.CUSTOM;
        this.enabled = true;
    }

    public CustomRule(String name, String pattern, boolean regex, boolean caseSensitive, EntityCategory category) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.pattern = pattern;
        this.regex = regex;
        this.caseSensitive = caseSensitive;
        this.category = category == null ? EntityCategory.CUSTOM : category;
        this.enabled = true;
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

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public boolean isRegex() {
        return regex;
    }

    public void setRegex(boolean regex) {
        this.regex = regex;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public EntityCategory getCategory() {
        return category;
    }

    public void setCategory(EntityCategory category) {
        this.category = category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
