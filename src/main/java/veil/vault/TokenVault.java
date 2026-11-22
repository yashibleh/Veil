package veil.vault;

import com.fasterxml.jackson.core.type.TypeReference;
import veil.models.EntityCategory;
import veil.models.PolicyAction;
import veil.utils.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages isolated SessionVault instances with optional AES-256-GCM persistent storage,
 * lock/unlock controls, session expiry, and secure clearing.
 */
public class TokenVault {

    private final Map<String, SessionVault> sessions = new ConcurrentHashMap<>();
    private final Path encryptedVaultPath;
    private volatile Duration sessionTtl = Duration.ofMinutes(60);
    private volatile boolean persistentEnabled = false;
    private volatile boolean locked = false;
    private char[] activePassphrase;

    public TokenVault(Path encryptedVaultPath) {
        this.encryptedVaultPath = encryptedVaultPath;
    }

    public SessionVault getOrCreateSession(String sessionId) {
        ensureUnlocked();
        evictExpiredSessions();
        return sessions.computeIfAbsent(sessionId, SessionVault::new);
    }

    public Optional<SessionVault> getSession(String sessionId) {
        if (locked) {
            return Optional.empty();
        }
        evictExpiredSessions();
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public String tokenize(String sessionId,
                           String originalValue,
                           EntityCategory category,
                           PolicyAction action,
                           String sourceText) {
        ensureUnlocked();
        SessionVault sv = getOrCreateSession(sessionId);
        String token = sv.getOrCreateToken(originalValue, category, action, sourceText);
        if (persistentEnabled && activePassphrase != null) {
            try {
                saveEncryptedQuietly();
            } catch (Exception ignored) {
                // Persistence error surfaced via explicit save; in-memory remains consistent
            }
        }
        return token;
    }

    public Optional<String> resolve(String sessionId, String token) {
        if (locked) {
            return Optional.empty();
        }
        SessionVault sv = sessions.get(sessionId);
        return sv == null ? Optional.empty() : sv.resolveOriginal(token);
    }

    public Set<String> getActiveSessionIds() {
        evictExpiredSessions();
        return Collections.unmodifiableSet(sessions.keySet());
    }

    public List<TokenMapping> getSessionMappings(String sessionId) {
        if (locked) {
            return List.of();
        }
        SessionVault sv = sessions.get(sessionId);
        return sv == null ? List.of() : new ArrayList<>(sv.getMappings());
    }

    public int getTotalMappingCount() {
        evictExpiredSessions();
        return sessions.values().stream().mapToInt(SessionVault::size).sum();
    }

    public void clearSession(String sessionId) {
        SessionVault sv = sessions.remove(sessionId);
        if (sv != null) {
            sv.clear();
        }
        if (persistentEnabled && activePassphrase != null && !locked) {
            saveEncryptedQuietly();
        }
    }

    public void secureWipeAll() {
        for (SessionVault sv : sessions.values()) {
            sv.clear();
        }
        sessions.clear();
        if (encryptedVaultPath != null) {
            try {
                Files.deleteIfExists(encryptedVaultPath);
            } catch (IOException ignored) {
            }
        }
    }

    public void evictExpiredSessions() {
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired(sessionTtl)) {
                entry.getValue().clear();
                return true;
            }
            return false;
        });
    }

    /**
     * Locks the vault: saves encrypted snapshot if persistence is enabled, then wipes in-memory mappings and passphrase.
     */
    public synchronized void lock() throws GeneralSecurityException, IOException {
        if (persistentEnabled && activePassphrase != null) {
            saveEncrypted();
        }
        for (SessionVault sv : sessions.values()) {
            sv.clear();
        }
        sessions.clear();
        if (activePassphrase != null) {
            Arrays.fill(activePassphrase, '\0');
            activePassphrase = null;
        }
        this.locked = true;
    }

    /**
     * Unlocks the vault using the user's passphrase and loads persisted mappings if present.
     */
    public synchronized void unlock(char[] passphrase) throws GeneralSecurityException, IOException {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Passphrase cannot be empty");
        }
        if (encryptedVaultPath != null && Files.exists(encryptedVaultPath)) {
            byte[] blob = Files.readAllBytes(encryptedVaultPath);
            byte[] plain = VaultCrypto.decrypt(blob, passphrase);
            Map<String, List<TokenMapping>> loaded = JsonUtil.mapper()
                    .readValue(plain, new TypeReference<Map<String, List<TokenMapping>>>() {});
            sessions.clear();
            for (Map.Entry<String, List<TokenMapping>> entry : loaded.entrySet()) {
                SessionVault sv = new SessionVault(entry.getKey());
                for (TokenMapping m : entry.getValue()) {
                    sv.restoreLoadedMapping(m);
                }
                sessions.put(entry.getKey(), sv);
            }
        }
        if (this.activePassphrase != null) {
            Arrays.fill(this.activePassphrase, '\0');
        }
        this.activePassphrase = Arrays.copyOf(passphrase, passphrase.length);
        this.locked = false;
    }

    public synchronized void enablePersistence(char[] passphrase) throws GeneralSecurityException, IOException {
        if (passphrase == null || passphrase.length < 8) {
            throw new IllegalArgumentException("Vault passphrase must be at least 8 characters");
        }
        if (this.activePassphrase != null) {
            Arrays.fill(this.activePassphrase, '\0');
        }
        this.activePassphrase = Arrays.copyOf(passphrase, passphrase.length);
        this.persistentEnabled = true;
        this.locked = false;
        saveEncrypted();
    }

    public synchronized void disablePersistence(boolean deleteVaultFile) {
        this.persistentEnabled = false;
        if (this.activePassphrase != null) {
            Arrays.fill(this.activePassphrase, '\0');
            this.activePassphrase = null;
        }
        this.locked = false;
        if (deleteVaultFile && encryptedVaultPath != null) {
            try {
                Files.deleteIfExists(encryptedVaultPath);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void saveEncrypted() throws GeneralSecurityException, IOException {
        if (encryptedVaultPath == null || activePassphrase == null) {
            return;
        }
        if (encryptedVaultPath.getParent() != null) {
            Files.createDirectories(encryptedVaultPath.getParent());
        }
        Map<String, List<TokenMapping>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, SessionVault> entry : sessions.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue().getMappings()));
        }
        byte[] json = JsonUtil.mapper().writeValueAsBytes(snapshot);
        byte[] encrypted = VaultCrypto.encrypt(json, activePassphrase);
        Files.write(encryptedVaultPath, encrypted);
    }

    private void saveEncryptedQuietly() {
        try {
            saveEncrypted();
        } catch (Exception ignored) {
        }
    }

    private void ensureUnlocked() {
        if (locked) {
            throw new IllegalStateException("Token vault is locked. Unlock the vault before processing requests.");
        }
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isPersistentEnabled() {
        return persistentEnabled;
    }

    public boolean hasEncryptedVaultFile() {
        return encryptedVaultPath != null && Files.exists(encryptedVaultPath);
    }

    public Path getEncryptedVaultPath() {
        return encryptedVaultPath;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl == null ? Duration.ofMinutes(60) : sessionTtl;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }
}
