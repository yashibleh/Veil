package veil.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import veil.models.SanitizationResult;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VeilClientTest {

    @Test
    void sanitizesAndRestoresThroughJavaSdk(@TempDir Path tempDir) {
        VeilClient client = new VeilClient(tempDir);
        String session = client.createSession();

        SanitizationResult res = client.sanitize(session, "Ping 10.1.2.3 and email ops@example.com");
        assertFalse(res.isBlocked());
        assertTrue(res.getSanitizedText().contains("[IP_001]"));
        assertTrue(res.getSanitizedText().contains("[EMAIL_001]"));

        String restored = client.restore(session, "Host [IP_001] acknowledged by [EMAIL_001].");
        assertEquals("Host 10.1.2.3 acknowledged by ops@example.com.", restored);

        client.clearSession(session);
        assertEquals("Host [IP_001]", client.restore(session, "Host [IP_001]"));
    }
}
