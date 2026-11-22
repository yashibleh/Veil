package veil.policies;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import veil.detection.DetectionEngine;
import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PolicyAction;
import veil.models.PrivacyProfile;
import veil.models.SanitizationResult;
import veil.streaming.TokenRestorer;
import veil.vault.TokenVault;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {

    @Test
    void enforcesBlockMaskWarnAndRealisticFake(@TempDir Path tempDir) {
        DetectionEngine detection = new DetectionEngine();
        PolicyEngine policyEngine = new PolicyEngine();
        TokenVault vault = new TokenVault(tempDir.resolve("vault.enc"));
        PrivacyProfile profile = new PrivacyProfile("custom", "Custom", "Test", false);

        // 1. BLOCK policy
        profile.getCategoryPolicies().put(EntityCategory.API_KEY, PolicyAction.BLOCK);
        String withKey = "Use key sk-proj-9kLmN8pQrStUvWxYz1234567890AbCdEf";
        List<DetectedEntity> keyEntities = detection.detect(withKey, profile);
        SanitizationResult blocked = policyEngine.enforce("s1", withKey, keyEntities, profile, vault);
        assertTrue(blocked.isBlocked());
        assertNull(blocked.getSanitizedText());

        // 2. REALISTIC_FAKE policy + contextual restoration
        profile.getCategoryPolicies().put(EntityCategory.EMAIL, PolicyAction.REALISTIC_FAKE);
        String withEmail = "Send report to rahul@example.com";
        List<DetectedEntity> emailEntities = detection.detect(withEmail, profile);
        SanitizationResult fakeRes = policyEngine.enforce("s2", withEmail, emailEntities, profile, vault);
        assertFalse(fakeRes.isBlocked());
        assertFalse(fakeRes.getSanitizedText().contains("rahul@example.com"));
        assertTrue(fakeRes.getSanitizedText().contains("@synthetic.invalid"));

        TokenRestorer restorer = new TokenRestorer(vault);
        assertEquals(withEmail, restorer.restore("s2", fakeRes.getSanitizedText()));

        // 3. WARN policy denied vs approved
        profile.getCategoryPolicies().put(EntityCategory.IP_ADDRESS, PolicyAction.WARN);
        String withIp = "Ping 10.24.1.8";
        List<DetectedEntity> ipEntities = detection.detect(withIp, profile);

        policyEngine.setWarnCallback((ents, p) -> false);
        SanitizationResult warnDenied = policyEngine.enforce("s3", withIp, ipEntities, profile, vault);
        assertTrue(warnDenied.isBlocked());
        assertTrue(warnDenied.isWarnTriggered());

        policyEngine.setWarnCallback((ents, p) -> true);
        SanitizationResult warnApproved = policyEngine.enforce("s3", withIp, ipEntities, profile, vault);
        assertFalse(warnApproved.isBlocked());
        assertTrue(warnApproved.getSanitizedText().contains("[IP_001]"));
    }
}
