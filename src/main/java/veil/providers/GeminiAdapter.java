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
 * Adapter translating VEIL requests to the Google Gemini generateContent / streamGenerateContent API.
 */
public class GeminiAdapter implements AIProviderAdapter {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.GEMINI;
    }

    @Override
    public ChatCompletionResponse sendChat(ChatCompletionRequest request, ProviderConfig config, String apiKey)
            throws ProviderException {
        requireKey(apiKey);
        try {
            String model = resolveModel(request, config);
            String url = trimTrailingSlash(config.getBaseUrl()) + "/models/" + model + ":generateContent";
            String json = JsonUtil.mapper().writeValueAsString(buildGeminiPayload(request));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("Gemini API returned HTTP " + response.statusCode(), response.statusCode());
            }

            JsonNode root = JsonUtil.mapper().readTree(response.body());
            String text = extractText(root);
            int promptTokens = root.path("usageMetadata").path("promptTokenCount").asInt(0);
            int candidateTokens = root.path("usageMetadata").path("candidatesTokenCount").asInt(0);
            return ChatCompletionResponse.simple(model, text, promptTokens, candidateTokens);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Failed to communicate with Gemini endpoint: " + e.getMessage(), e);
        }
    }

    @Override
    public void streamChat(ChatCompletionRequest request, ProviderConfig config, String apiKey, Consumer<String> deltaConsumer)
            throws ProviderException {
        requireKey(apiKey);
        try {
            String model = resolveModel(request, config);
            String url = trimTrailingSlash(config.getBaseUrl()) + "/models/" + model + ":streamGenerateContent?alt=sse";
            String json = JsonUtil.mapper().writeValueAsString(buildGeminiPayload(request));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("Gemini streaming API returned HTTP " + response.statusCode(), response.statusCode());
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
                    String chunk = extractText(root);
                    if (!chunk.isEmpty()) {
                        deltaConsumer.accept(chunk);
                    }
                }
            }
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Gemini streaming failed: " + e.getMessage(), e);
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
                    .header("x-goog-api-key", apiKey)
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return true;
            }
            throw new ProviderException("Gemini connectivity test failed with HTTP " + resp.statusCode(), resp.statusCode());
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Gemini connectivity test failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildGeminiPayload(ChatCompletionRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        StringBuilder systemInstruction = new StringBuilder();

        for (ChatMessage m : req.getMessages()) {
            String text = m.getContent() == null ? "" : m.getContent();
            if ("system".equalsIgnoreCase(m.getRole())) {
                if (!systemInstruction.isEmpty()) systemInstruction.append("\n");
                systemInstruction.append(text);
            } else {
                String role = "assistant".equalsIgnoreCase(m.getRole()) ? "model" : "user";
                contents.add(Map.of("role", role, "parts", List.of(Map.of("text", text))));
            }
        }
        payload.put("contents", contents);
        if (!systemInstruction.isEmpty()) {
            payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction.toString()))));
        }

        Map<String, Object> genConfig = new LinkedHashMap<>();
        if (req.getTemperature() != null) genConfig.put("temperature", req.getTemperature());
        if (req.getTopP() != null) genConfig.put("topP", req.getTopP());
        if (req.getMaxTokens() != null) genConfig.put("maxOutputTokens", req.getMaxTokens());
        if (!genConfig.isEmpty()) {
            payload.put("generationConfig", genConfig);
        }
        return payload;
    }

    private String extractText(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (parts.isArray()) {
            for (JsonNode p : parts) {
                sb.append(p.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private String resolveModel(ChatCompletionRequest req, ProviderConfig cfg) {
        return (req.getModel() != null && !req.getModel().isBlank()) ? req.getModel() : cfg.getDefaultModel();
    }

    private void requireKey(String apiKey) throws ProviderException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException("Google Gemini API key is not configured.", 401);
        }
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
