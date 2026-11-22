package veil.providers;

import com.fasterxml.jackson.databind.JsonNode;
import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
import veil.models.ChatMessage;
import veil.models.ProviderConfig;
import veil.models.ProviderType;
import veil.utils.JsonUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Adapter translating VEIL OpenAI-compatible requests to Anthropic Messages API (/v1/messages).
 */
public class AnthropicAdapter implements AIProviderAdapter {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.ANTHROPIC;
    }

    @Override
    public ChatCompletionResponse sendChat(ChatCompletionRequest request, ProviderConfig config, String apiKey)
            throws ProviderException {
        requireKey(apiKey);
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/messages";
            Map<String, Object> body = buildAnthropicPayload(request, config, false);
            String json = JsonUtil.mapper().writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("Anthropic API returned HTTP " + response.statusCode(), response.statusCode());
            }

            JsonNode root = JsonUtil.mapper().readTree(response.body());
            StringBuilder text = new StringBuilder();
            JsonNode contentArray = root.path("content");
            if (contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        text.append(block.path("text").asText(""));
                    }
                }
            }
            int inputTokens = root.path("usage").path("input_tokens").asInt(0);
            int outputTokens = root.path("usage").path("output_tokens").asInt(0);
            String model = root.path("model").asText(config.getDefaultModel());
            return ChatCompletionResponse.simple(model, text.toString(), inputTokens, outputTokens);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Failed to communicate with Anthropic endpoint: " + e.getMessage(), e);
        }
    }

    @Override
    public void streamChat(ChatCompletionRequest request, ProviderConfig config, String apiKey, Consumer<String> deltaConsumer)
            throws ProviderException {
        requireKey(apiKey);
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/messages";
            Map<String, Object> body = buildAnthropicPayload(request, config, true);
            String json = JsonUtil.mapper().writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("Anthropic streaming API returned HTTP " + response.statusCode(), response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonNode root = JsonUtil.mapper().readTree(data);
                    if ("content_block_delta".equals(root.path("type").asText())) {
                        String text = root.path("delta").path("text").asText("");
                        if (!text.isEmpty()) {
                            deltaConsumer.accept(text);
                        }
                    }
                }
            }
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Anthropic streaming failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(ProviderConfig config, String apiKey) throws ProviderException {
        requireKey(apiKey);
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/models";
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.min(15, config.getTimeoutSeconds())))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return true;
            }
            throw new ProviderException("Anthropic connectivity test failed with HTTP " + resp.statusCode(), resp.statusCode());
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Anthropic connectivity test failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildAnthropicPayload(ChatCompletionRequest req, ProviderConfig cfg, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        String model = (req.getModel() != null && !req.getModel().isBlank()) ? req.getModel() : cfg.getDefaultModel();
        body.put("model", model);
        body.put("max_tokens", req.getMaxTokens() != null ? req.getMaxTokens() : 1024);
        body.put("stream", stream);
        if (req.getTemperature() != null) {
            body.put("temperature", req.getTemperature());
        }

        StringBuilder systemPrompt = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : req.getMessages()) {
            if ("system".equalsIgnoreCase(m.getRole())) {
                if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
                systemPrompt.append(m.getContent() == null ? "" : m.getContent());
            } else {
                String role = "assistant".equalsIgnoreCase(m.getRole()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", m.getContent() == null ? "" : m.getContent()));
            }
        }
        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }
        body.put("messages", messages);
        return body;
    }

    private void requireKey(String apiKey) throws ProviderException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException("Anthropic API key is not configured.", 401);
        }
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
