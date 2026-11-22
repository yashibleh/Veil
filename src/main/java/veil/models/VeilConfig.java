package veil.models;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Top-level persistent configuration for VEIL.
 */
public class VeilConfig {
    private GatewayConfig gateway = new GatewayConfig();
    private Map<ProviderType, ProviderConfig> providers = new EnumMap<>(ProviderType.class);
    private Map<String, Boolean> enabledDetectors = new HashMap<>();
    private boolean persistentVaultEnabled = false;
    private int sessionTtlMinutes = 60;
    private int logRetentionDays = 14;
    private boolean logPromptPreviews = false; // Off by default for privacy
    private boolean reducedMotion = false;
    private boolean minimizeToTray = true;
    private boolean desktopNotifications = true;

    public VeilConfig() {
        for (ProviderType type : ProviderType.values()) {
            providers.put(type, new ProviderConfig(type));
        }
        enabledDetectors.put("RegexDetector", true);
        enabledDetectors.put("SecretDetector", true);
        enabledDetectors.put("NameDetector", true);
        enabledDetectors.put("CodeIdentifierDetector", true);
        enabledDetectors.put("CustomRuleDetector", true);
    }

    public GatewayConfig getGateway() {
        return gateway;
    }

    public void setGateway(GatewayConfig gateway) {
        this.gateway = gateway == null ? new GatewayConfig() : gateway;
    }

    public Map<ProviderType, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<ProviderType, ProviderConfig> providers) {
        this.providers = new EnumMap<>(ProviderType.class);
        for (ProviderType type : ProviderType.values()) {
            this.providers.put(type, new ProviderConfig(type));
        }
        if (providers != null) {
            this.providers.putAll(providers);
        }
    }

    public ProviderConfig getProviderConfig(ProviderType type) {
        return providers.computeIfAbsent(type, ProviderConfig::new);
    }

    public Map<String, Boolean> getEnabledDetectors() {
        return enabledDetectors;
    }

    public void setEnabledDetectors(Map<String, Boolean> enabledDetectors) {
        this.enabledDetectors = enabledDetectors == null ? new HashMap<>() : new HashMap<>(enabledDetectors);
    }

    public boolean isPersistentVaultEnabled() {
        return persistentVaultEnabled;
    }

    public void setPersistentVaultEnabled(boolean persistentVaultEnabled) {
        this.persistentVaultEnabled = persistentVaultEnabled;
    }

    public int getSessionTtlMinutes() {
        return sessionTtlMinutes;
    }

    public void setSessionTtlMinutes(int sessionTtlMinutes) {
        this.sessionTtlMinutes = Math.max(1, sessionTtlMinutes);
    }

    public int getLogRetentionDays() {
        return logRetentionDays;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = Math.max(1, logRetentionDays);
    }

    public boolean isLogPromptPreviews() {
        return logPromptPreviews;
    }

    public void setLogPromptPreviews(boolean logPromptPreviews) {
        this.logPromptPreviews = logPromptPreviews;
    }

    public boolean isReducedMotion() {
        return reducedMotion;
    }

    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    public boolean isMinimizeToTray() {
        return minimizeToTray;
    }

    public void setMinimizeToTray(boolean minimizeToTray) {
        this.minimizeToTray = minimizeToTray;
    }

    public boolean isDesktopNotifications() {
        return desktopNotifications;
    }

    public void setDesktopNotifications(boolean desktopNotifications) {
        this.desktopNotifications = desktopNotifications;
    }
}
