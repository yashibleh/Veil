package veil.integrations;

import veil.models.GatewayConfig;

import java.util.List;

/**
 * Honest integration guidance and configuration snippets for supported OpenAI-compatible clients
 * plus documented API contracts for future IDE/browser extensions.
 */
public record IntegrationDescriptor(
        String name,
        String status,
        String mechanism,
        String instructions,
        String snippet
) {
    public static List<IntegrationDescriptor> buildDescriptors(GatewayConfig cfg) {
        String baseUrl = cfg.getBaseUrl();
        String endpoint = cfg.getChatCompletionsUrl();
        String tokenHint = cfg.isRequireLocalAuth() ? "<YOUR_VEIL_GATEWAY_TOKEN>" : "not-required";

        return List.of(
                new IntegrationDescriptor(
                        "OpenAI-Compatible AI Coding Assistants (Continue, Cline, Aider, Cursor Custom Base URL)",
                        "Supported via Local Gateway",
                        "Set custom OpenAI Base URL to VEIL",
                        "Configure your IDE extension or CLI tool to use VEIL's local base URL and local gateway token instead of calling the external provider directly.",
                        "OPENAI_BASE_URL=" + baseUrl + "\nOPENAI_API_KEY=" + tokenHint
                ),
                new IntegrationDescriptor(
                        "cURL / HTTP Client Verification",
                        "Supported via Local Gateway",
                        "Direct POST to /v1/chat/completions",
                        "Verify sanitization, blocking, and restoration directly from your terminal.",
                        "curl -X POST " + endpoint + " \\\n"
                                + "  -H \"Content-Type: application/json\" \\\n"
                                + "  -H \"Authorization: Bearer " + tokenHint + "\" \\\n"
                                + "  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"Email rahul@example.com about 192.168.1.12\"}]}'"
                ),
                new IntegrationDescriptor(
                        "VEIL Java Developer SDK",
                        "Supported (Included)",
                        "In-process Java library (veil.sdk.VeilClient)",
                        "Embed VEIL directly inside Java services without JavaFX dependencies.",
                        "VeilClient client = new VeilClient(Path.of(System.getProperty(\"user.home\"), \".veil\"));\n"
                                + "String session = client.createSession();\n"
                                + "SanitizationResult res = client.sanitize(session, prompt);"
                ),
                new IntegrationDescriptor(
                        "Browser Extension Bridge (Future Integration)",
                        "API Ready — Extension Not Installed",
                        "Local REST endpoints: POST /v1/veil/sanitize & POST /v1/veil/restore",
                        "VEIL exposes authenticated local sanitize/restore endpoints so a future browser extension can protect selected web AI interfaces without bypassing site security or transmitting raw secrets to third-party servers.",
                        "POST http://" + cfg.getBindAddress() + ":" + cfg.getPort() + "/v1/veil/sanitize\n"
                                + "Body: {\"session_id\":\"tab-1\",\"text\":\"...\"}"
                )
        );
    }
}
