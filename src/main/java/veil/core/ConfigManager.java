package veil.core;

import veil.models.VeilConfig;
import veil.utils.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Loads and persists VEIL configuration under ~/.veil/config.json.
 * Automatically generates a cryptographically strong local gateway bearer token on first run.
 */
public class ConfigManager {

    private final Path configFile;
    private VeilConfig config;

    public ConfigManager(Path veilHomeDir) {
        this.configFile = veilHomeDir.resolve("config.json");
        this.config = loadOrInitialize();
    }

    private VeilConfig loadOrInitialize() {
        VeilConfig loaded = null;
        if (Files.exists(configFile)) {
            try {
                loaded = JsonUtil.mapper().readValue(configFile.toFile(), VeilConfig.class);
            } catch (IOException ignored) {
            }
        }
        if (loaded == null) {
            loaded = new VeilConfig();
        }
        if (loaded.getGateway().getGatewayAccessToken() == null
                || loaded.getGateway().getGatewayAccessToken().isBlank()) {
            loaded.getGateway().setGatewayAccessToken(generateLocalToken());
        }
        this.config = loaded;
        save();
        return loaded;
    }

    public VeilConfig getConfig() {
        return config;
    }

    public synchronized void save() {
        try {
            if (configFile.getParent() != null) {
                Files.createDirectories(configFile.getParent());
            }
            JsonUtil.prettyMapper().writeValue(configFile.toFile(), config);
        } catch (IOException ignored) {
        }
    }

    public synchronized String rotateGatewayToken() {
        String token = generateLocalToken();
        config.getGateway().setGatewayAccessToken(token);
        save();
        return token;
    }

    public static String generateLocalToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "veil_gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
