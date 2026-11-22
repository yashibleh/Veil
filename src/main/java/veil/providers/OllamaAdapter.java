package veil.providers;

import com.fasterxml.jackson.databind.JsonNode;
import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Adapter for local Ollama instances (/api/chat).
 * Requests are routed locally without contacting an external cloud AI provider.
 */
public class OllamaAdapter implements AIProviderAdapter {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.OLLAMA;
    }

    @Override
    public ChatCompletionResponse sendChat(ChatCompletionRequest request, ProviderConfig config, String apiKey)
            throws ProviderException {
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/api/chat";
            Map<String, Object> body = buildOllamaPayload(request, config, false);
            String json = JsonUtil.mapper().writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("Local Ollama returned HTTP " + response.statusCode(), response.statusCode());
            }

            JsonNode root = JsonUtil.mapper().readTree(response.body());
            String content = root.path("message").path("content").asText("");
            int promptTokens = root.path("prompt_eval_count").asInt(0);
            int evalTokens = root.path("eval_count").asInt(0);
            String model = root.path("model").asText(config.getDefaultModel());
            return ChatCompletionResponse.simple(model, content, promptTokens, evalTokens);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Unable to reach local Ollama server at " + config.getBaseUrl() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void streamChat(ChatCompletionRequest request, ProviderConfig config, String apiKey, Consumer<String> deltaConsumer)
            throws ProviderException {
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/api/chat";
            Map<String, Object> body = buildOllamaPayload(request, config, true);
            String json = JsonUtil.mapper().writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("Local Ollama streaming returned HTTP " + response.statusCode(), response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode root = JsonUtil.mapper().readTree(line);
                    String chunk = root.path("message").path("content").asText("");
                    if (!chunk.isEmpty()) {
                        deltaConsumer.accept(chunk);
                    }
                    if (root.path("done").asBoolean(false)) {
                        break;
                    }
                }
            }
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Local Ollama streaming failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(ProviderConfig config, String apiKey) throws ProviderException {
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/api/tags";
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return true;
            }
            throw new ProviderException("Local Ollama check failed with HTTP " + resp.statusCode(), resp.statusCode());
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Local Ollama is not reachable at " + config.getBaseUrl(), e);
        }
    }

    private Map<String, Object> buildOllamaPayload(ChatCompletionRequest req, ProviderConfig cfg, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        String model = (req.getModel() != null && !req.getModel().isBlank()) ? req.getModel() : cfg.getDefaultModel();
        body.put("model", model);
        body.put("messages", req.getMessages().stream().map(m -> m.toMap()).toList());
        body.put("stream", stream);
        if (req.getTemperature() != null) {
            body.put("options", Map.of("temperature", req.getTemperature()));
        }
        return body;
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
