package veil.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import veil.models.EntityCategory;
import veil.models.PolicyAction;
import veil.vault.TokenVault;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StreamingTokenRestorerTest {

    private TokenVault vault;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        vault = new TokenVault(tempDir.resolve("vault.enc"));
        vault.tokenize("s1", "rahul@example.com", EntityCategory.EMAIL, PolicyAction.MASK, "");
        vault.tokenize("s1", "192.168.1.12", EntityCategory.IP_ADDRESS, PolicyAction.MASK, "");
    }

    @Test
    void restoresCompleteTokenInSingleChunk() {
        StreamingTokenRestorer r = new StreamingTokenRestorer("s1", vault);
        String out = r.processChunk("Contact [EMAIL_001] immediately.") + r.flush();
        assertEquals("Contact rahul@example.com immediately.", out);
    }

    @Test
    void restoresTokenSplitAcrossTwoChunks() {
        StreamingTokenRestorer r = new StreamingTokenRestorer("s1", vault);
        StringBuilder sb = new StringBuilder();
        sb.append(r.processChunk("Email sent to [EMAIL_"));
        sb.append(r.processChunk("001] today."));
        sb.append(r.flush());
        assertEquals("Email sent to rahul@example.com today.", sb.toString());
    }

    @Test
    void restoresMultipleTokensSplitCharacterByCharacter() {
        StreamingTokenRestorer r = new StreamingTokenRestorer("s1", vault);
        String streamed = "Host [IP_001] alerted [EMAIL_001] twice: [IP_001].";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < streamed.length(); i++) {
            sb.append(r.processChunk(String.valueOf(streamed.charAt(i))));
        }
        sb.append(r.flush());
        assertEquals("Host 192.168.1.12 alerted rahul@example.com twice: 192.168.1.12.", sb.toString());
    }

    @Test
    void preservesMalformedAndUnrelatedBracketTextOnInterruptedStream() {
        StreamingTokenRestorer r = new StreamingTokenRestorer("s1", vault);
        StringBuilder sb = new StringBuilder();
        sb.append(r.processChunk("Unrelated [EMAIL_999] and partial [IP_"));
        sb.append(r.flush()); // simulated interrupted stream
        assertEquals("Unrelated [EMAIL_999] and partial [IP_", sb.toString());
    }
}
