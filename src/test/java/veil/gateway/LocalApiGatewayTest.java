package veil.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import veil.core.VeilCoreEngine;
import veil.models.ProviderType;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LocalApiGateway using a clearly labelled LOCAL TEST FIXTURE
 * HTTP server mimicking an OpenAI-compatible upstream provider (no external API calls).
 */
class LocalApiGatewayTest {

    private HttpServer mockProviderServer;
    private VeilCoreEngine core;
    private LocalApiGateway gateway;
    private int gatewayPort;
    private String gatewayToken;
    private final AtomicReference<String> lastProviderReceivedBody = new AtomicReference<>();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        int mockPort = findFreePort();
        gatewayPort = findFreePort();

        // Local Test Fixture simulating an OpenAI upstream endpoint
        mockProviderServer = HttpServer.create(new InetSocketAddress("127.0.0.1", mockPort), 0);
        mockProviderServer.createContext("/v1/chat/completions", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastProviderReceivedBody.set(reqBody);
            boolean stream = reqBody.contains("\"stream\":true");
            if (stream) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    // Split [EMAIL_001] across two SSE chunks to test end-to-end streaming restoration
                    os.write("data: {\"choices\":[{\"delta\":{\"content\":\"Notified [EMAIL_\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                    os.write("data: {\"choices\":[{\"delta\":{\"content\":\"001] about [IP_001].\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
            } else {
                String respJson = """
                        {
                          "id": "chatcmpl-test-fixture",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "test-fixture-model",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "Checked host [IP_001] and emailed [EMAIL_001]."
                              },
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """;
                byte[] bytes = respJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        mockProviderServer.start();

        core = new VeilCoreEngine(tempDir);
        core.getConfigManager().getConfig().getGateway().setPort(gatewayPort);
        core.getConfigManager().getConfig().getGateway().setActiveProvider(ProviderType.OPENAI);
        core.getConfigManager().getConfig().getGateway().setActiveProfileId("developer");
        core.getConfigManager().getConfig().getProviderConfig(ProviderType.OPENAI)
                .setBaseUrl("http://127.0.0.1:" + mockPort + "/v1");
        core.getCredentialStore().saveKey(ProviderType.OPENAI, "sk-test-fixture-key-1234567890abcdef");
        gatewayToken = core.getConfigManager().getConfig().getGateway().getGatewayAccessToken();

        gateway = new LocalApiGateway(core);
        gateway.start();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.stop();
        if (mockProviderServer != null) mockProviderServer.stop(0);
    }

    @Test
    void rejectsUnauthorizedRequestsWith401() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
    }

    @Test
    void sanitizesOutgoingAndRestoresIncomingResponse() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"Email rahul@example.com about 192.168.1.12\"}]}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + gatewayToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        // Verify raw PII never reached the upstream provider
        assertFalse(lastProviderReceivedBody.get().contains("rahul@example.com"));
        assertFalse(lastProviderReceivedBody.get().contains("192.168.1.12"));
        assertTrue(lastProviderReceivedBody.get().contains("[EMAIL_001]"));
        assertTrue(lastProviderReceivedBody.get().contains("[IP_001]"));
        // Verify original values were restored in client response
        assertTrue(resp.body().contains("rahul@example.com"));
        assertTrue(resp.body().contains("192.168.1.12"));
    }

    @Test
    void blocksApiKeyUnderDeveloperProfileWithoutCallingProvider() throws Exception {
        lastProviderReceivedBody.set(null);
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"My key is sk-proj-9kLmN8pQrStUvWxYz1234567890AbCdEf\"}]}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + gatewayToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode());
        assertNull(lastProviderReceivedBody.get(), "Blocked request must never reach provider");
    }

    @Test
    void streamsAndRestoresFragmentedTokensOverSse() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Notify rahul@example.com on 192.168.1.12\"}]}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + gatewayToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("rahul@example.com"));
        assertTrue(resp.body().contains("192.168.1.12"));
        assertTrue(resp.body().contains("data: [DONE]"));
    }

    private int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
