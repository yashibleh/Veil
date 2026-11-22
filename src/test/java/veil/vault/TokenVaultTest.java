package veil.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import veil.models.EntityCategory;
import veil.models.PolicyAction;

import java.nio.file.Path;
import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.*;

class TokenVaultTest {

    @Test
    void generatesConsistentTokensWithinSessionAndIsolatesSessions(@TempDir Path tempDir) {
        TokenVault vault = new TokenVault(tempDir.resolve("vault.enc"));
        String t1 = vault.tokenize("sess-A", "rahul@example.com", EntityCategory.EMAIL, PolicyAction.MASK, "");
        String t2 = vault.tokenize("sess-A", "rahul@example.com", EntityCategory.EMAIL, PolicyAction.MASK, "");
        String t3 = vault.tokenize("sess-B", "anita@example.com", EntityCategory.EMAIL, PolicyAction.MASK, "");

        assertEquals("[EMAIL_001]", t1);
        assertEquals(t1, t2);
        assertEquals("[EMAIL_001]", t3);

        assertEquals("rahul@example.com", vault.resolve("sess-A", t1).orElseThrow());
        assertEquals("anita@example.com", vault.resolve("sess-B", t3).orElseThrow());
        assertNotEquals(vault.resolve("sess-A", t1), vault.resolve("sess-B", t1));
    }

    @Test
    void encryptsLocksUnlocksAndRejectsWrongPassphrase(@TempDir Path tempDir) throws Exception {
        Path vaultFile = tempDir.resolve("vault.enc");
        TokenVault vault = new TokenVault(vaultFile);
        vault.enablePersistence("StrongPassphrase!2026".toCharArray());

        String tok = vault.tokenize("sess-1", "192.168.1.55", EntityCategory.IP_ADDRESS, PolicyAction.MASK, "");
        vault.lock();

        assertTrue(vault.isLocked());
        assertTrue(vault.resolve("sess-1", tok).isEmpty());
        assertThrows(IllegalStateException.class, () ->
                vault.tokenize("sess-1", "10.0.0.1", EntityCategory.IP_ADDRESS, PolicyAction.MASK, ""));

        assertThrows(GeneralSecurityException.class, () ->
                vault.unlock("WrongPassphrase!".toCharArray()));

        vault.unlock("StrongPassphrase!2026".toCharArray());
        assertFalse(vault.isLocked());
        assertEquals("192.168.1.55", vault.resolve("sess-1", tok).orElseThrow());

        vault.secureWipeAll();
        assertEquals(0, vault.getTotalMappingCount());
    }
}
