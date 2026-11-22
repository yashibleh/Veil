package veil.core;

import com.fasterxml.jackson.core.type.TypeReference;
import veil.models.ProviderType;
import veil.utils.JsonUtil;
import veil.vault.VaultCrypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Secure local credential store for AI provider API keys.
 *
 * Security model:
 * - Never hardcodes API keys, never logs raw keys, never returns full keys to UI labels.
 * - Encrypts keys at rest with AES-256-GCM using a locally generated random key-encryption material
 *   stored with owner-only file permissions (and supports overriding via environment variables
 *   OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY).
 * - On Windows/macOS/Linux, OS native keychains can also be plugged in by replacing the key-material loader.
 */
public class CredentialStore {

    private final Path credentialsFile;
    private final Path keyMaterialFile;
    private final Map<ProviderType, String> inMemoryKeys = new EnumMap<>(ProviderType.class);

    public CredentialStore(Path veilHomeDir) {
        this.credentialsFile = veilHomeDir.resolve("credentials.enc");
        this.keyMaterialFile = veilHomeDir.resolve(".veil.key");
        loadFromDisk();
    }

    public synchronized void saveKey(ProviderType provider, String apiKey) throws IOException, GeneralSecurityException {
        if (apiKey == null || apiKey.isBlank()) {
            deleteKey(provider);
            return;
        }
        inMemoryKeys.put(provider, apiKey.trim());
        persistToDisk();
    }

    public synchronized void deleteKey(ProviderType provider) throws IOException, GeneralSecurityException {
        inMemoryKeys.remove(provider);
        persistToDisk();
    }

    public synchronized Optional<String> getKey(ProviderType provider) {
        String stored = inMemoryKeys.get(provider);
        if (stored != null && !stored.isBlank()) {
            return Optional.of(stored);
        }
        // Fallback to standard environment variables without storing them to disk
        String envVar = switch (provider) {
            case OPENAI -> System.getenv("OPENAI_API_KEY");
            case ANTHROPIC -> System.getenv("ANTHROPIC_API_KEY");
            case GEMINI -> System.getenv("GEMINI_API_KEY");
            case OLLAMA -> null;
        };
        return (envVar != null && !envVar.isBlank()) ? Optional.of(envVar.trim()) : Optional.empty();
    }

    public synchronized boolean hasKey(ProviderType provider) {
        return getKey(provider).isPresent();
    }

    /**
     * Returns a masked representation safe for UI display (never reveals the full secret).
     */
    public synchronized String getMaskedDisplay(ProviderType provider) {
        Optional<String> opt = getKey(provider);
        if (opt.isEmpty()) {
            return "Not configured";
        }
        String key = opt.get();
        if (key.length() <= 8) {
            return "••••••••";
        }
        return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
    }

    private void loadFromDisk() {
        if (!Files.exists(credentialsFile) || !Files.exists(keyMaterialFile)) {
            return;
        }
        try {
            char[] secret = Files.readString(keyMaterialFile, StandardCharsets.UTF_8).trim().toCharArray();
            byte[] encrypted = Files.readAllBytes(credentialsFile);
            byte[] plain = VaultCrypto.decrypt(encrypted, secret);
            Map<String, String> raw = JsonUtil.mapper().readValue(plain, new TypeReference<Map<String, String>>() {});
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                try {
                    inMemoryKeys.put(ProviderType.valueOf(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception ignored) {
            // If corrupted or unreadable, start empty rather than crashing
        }
    }

    private void persistToDisk() throws IOException, GeneralSecurityException {
        Files.createDirectories(credentialsFile.getParent());
        char[] secret = loadOrGenerateKeyMaterial();
        Map<String, String> serializable = new java.util.LinkedHashMap<>();
        for (Map.Entry<ProviderType, String> e : inMemoryKeys.entrySet()) {
            serializable.put(e.getKey().name(), e.getValue());
        }
        byte[] json = JsonUtil.mapper().writeValueAsBytes(serializable);
        byte[] encrypted = VaultCrypto.encrypt(json, secret);
        Files.write(credentialsFile, encrypted);
        restrictPermissions(credentialsFile);
    }

    private char[] loadOrGenerateKeyMaterial() throws IOException {
        if (Files.exists(keyMaterialFile)) {
            return Files.readString(keyMaterialFile, StandardCharsets.UTF_8).trim().toCharArray();
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String encoded = Base64.getEncoder().encodeToString(random);
        Files.writeString(keyMaterialFile, encoded, StandardCharsets.UTF_8);
        restrictPermissions(keyMaterialFile);
        return encoded.toCharArray();
    }

    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows NTFS inherits user-profile ACLs
        }
    }
}
