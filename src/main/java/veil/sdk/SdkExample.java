package veil.sdk;

import veil.models.SanitizationResult;
import veil.streaming.StreamingTokenRestorer;

import java.nio.file.Path;

/**
 * Minimal runnable example demonstrating the VEIL Java SDK without JavaFX or external API keys.
 */
public final class SdkExample {

    private SdkExample() {
    }

    public static void main(String[] args) {
        VeilClient client = new VeilClient(Path.of(System.getProperty("user.home"), ".veil-sdk-example"));
        String session = client.createSession();

        String prompt = "Please check server 192.168.1.42 and email rahul@example.com about the outage.";
        SanitizationResult result = client.sanitize(session, prompt);

        System.out.println("Blocked:   " + result.isBlocked());
        System.out.println("Sanitized: " + result.getSanitizedText());

        // Simulated fragmented stream from a provider referencing the tokens
        StreamingTokenRestorer restorer = client.createStreamingRestorer(session);
        StringBuilder restored = new StringBuilder();
        restored.append(restorer.processChunk("Investigated host [IP_"));
        restored.append(restorer.processChunk("001] and notified [EMAIL_0"));
        restored.append(restorer.processChunk("01] successfully."));
        restored.append(restorer.flush());

        System.out.println("Restored:  " + restored);
        client.clearSession(session);
    }
}
