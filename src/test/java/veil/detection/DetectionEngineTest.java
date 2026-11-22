package veil.detection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import veil.models.CustomRule;
import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PrivacyProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DetectionEngineTest {

    private DetectionEngine engine;
    private PrivacyProfile profile;

    @BeforeEach
    void setUp() {
        engine = new DetectionEngine();
        profile = new PrivacyProfile("test", "Test", "Test profile", false);
        profile.setCodeIdentifiers(List.of("ACME_INTERNAL_LEDGER", "PROD_CLUSTER_MASTER"));
        profile.setCustomRules(List.of(
                new CustomRule("Project Codename", "Project Obsidian", false, false, EntityCategory.CUSTOM)
        ));
    }

    @Test
    void detectsEmailAndIpv4AndName() {
        String text = "Employee: Priya Nair reported an outage on 192.168.1.42; reply to priya.nair@example.com.";
        List<DetectedEntity> found = engine.detect(text, profile);

        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.PERSONAL_NAME && e.getOriginalValue().equals("Priya Nair")));
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.IP_ADDRESS && e.getOriginalValue().equals("192.168.1.42")));
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.EMAIL && e.getOriginalValue().equals("priya.nair@example.com")));
    }

    @Test
    void validatesAadhaarWithVerhoeffChecksumAndRejectsInvalid() {
        // 2341 2341 2346 is a mathematically Verhoeff-valid 12-digit test sequence
        assertTrue(ValidationUtils.isValidAadhaar("2341 2341 2346"));
        assertFalse(ValidationUtils.isValidAadhaar("2341 2341 2347"));
        assertFalse(ValidationUtils.isValidAadhaar("1234 5678 9012")); // starts with 1

        List<DetectedEntity> valid = engine.detect("Aadhaar: 2341 2341 2346", profile);
        assertTrue(valid.stream().anyMatch(e -> e.getCategory() == EntityCategory.AADHAAR));

        List<DetectedEntity> invalid = engine.detect("Order reference: 2341 2341 2347", profile);
        assertTrue(invalid.stream().noneMatch(e -> e.getCategory() == EntityCategory.AADHAAR));
    }

    @Test
    void validatesPanAndLuhnCreditCard() {
        String text = "PAN ABCDE1234F and card 4532-0151-1283-0366";
        List<DetectedEntity> found = engine.detect(text, profile);
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.PAN && e.getOriginalValue().equals("ABCDE1234F")));
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.CREDIT_CARD));

        // Invalid Luhn digit
        List<DetectedEntity> badCard = engine.detect("Tracking 4532-0151-1283-0367", profile);
        assertTrue(badCard.stream().noneMatch(e -> e.getCategory() == EntityCategory.CREDIT_CARD));
    }

    @Test
    void detectsSecretsAndIgnoresPlaceholders() {
        String live = "api_key=sk-proj-9kLmN8pQrStUvWxYz1234567890AbCdEf and db_password=K9#vL2!mP8xQ";
        List<DetectedEntity> found = engine.detect(live, profile);
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.API_KEY));
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.PASSWORD));

        String placeholder = "api_key=YOUR_API_KEY_PLACEHOLDER and password=changeme";
        List<DetectedEntity> none = engine.detect(placeholder, profile);
        assertTrue(none.stream().noneMatch(e -> e.getCategory() == EntityCategory.API_KEY));
        assertTrue(none.stream().noneMatch(e -> e.getCategory() == EntityCategory.PASSWORD));
    }

    @Test
    void detectsConfiguredCodeIdentifiersAndCustomRules() {
        String text = "Query ACME_INTERNAL_LEDGER for Project Obsidian on prod-db-01.corp.internal";
        List<DetectedEntity> found = engine.detect(text, profile);
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.CODE_IDENTIFIER && e.getOriginalValue().equals("ACME_INTERNAL_LEDGER")));
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.CUSTOM && e.getOriginalValue().equals("Project Obsidian")));
        assertTrue(found.stream().anyMatch(e -> e.getCategory() == EntityCategory.CODE_IDENTIFIER && e.getOriginalValue().equals("prod-db-01.corp.internal")));
    }

    @Test
    void resolvesOverlappingSpansWithoutCorruption() {
        String text = "Connect via postgres://admin:Sup3rS3cret!@10.0.0.15:5432/prod";
        List<DetectedEntity> found = engine.detect(text, profile);
        assertEquals(1, found.size());
        assertEquals(EntityCategory.DB_CREDENTIAL, found.get(0).getCategory());
    }

    @Test
    void avoidsFalsePositivesOnOrdinaryTechnicalText() {
        String benign = "Refactor the Spring Boot controller to return HTTP 200 OK for version v1.2.3.4 with max_retries = 5.";
        List<DetectedEntity> found = engine.detect(benign, profile);
        assertTrue(found.isEmpty(), "Expected no detections on benign text, got: " + found.size());
    }
}
