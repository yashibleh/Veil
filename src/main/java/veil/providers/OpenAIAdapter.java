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
 * Adapter for OpenAI and OpenAI-compatible HTTP chat completion endpoints.
 */
public class OpenAIAdapter implements AIProviderAdapter {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.OPENAI;
    }

    @Override
    public ChatCompletionResponse sendChat(ChatCompletionRequest request, ProviderConfig config, String apiKey)
            throws ProviderException {
        requireKey(apiKey);
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/chat/completions";
            Map<String, Object> body = buildPayload(request, config, false);
            String json = JsonUtil.mapper().writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("OpenAI API returned HTTP " + response.statusCode(), response.statusCode());
            }
            return JsonUtil.mapper().readValue(response.body(), ChatCompletionResponse.class);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Failed to communicate with OpenAI endpoint: " + e.getMessage(), e);
        }
    }

    @Override
    public void streamChat(ChatCompletionRequest request, ProviderConfig config, String apiKey, Consumer<String> deltaConsumer)
            throws ProviderException {
        requireKey(apiKey);
        try {
            String url = trimTrailingSlash(config.getBaseUrl()) + "/chat/completions";
            Map<String, Object> body = buildPayload(request, config, true);
            String json = JsonUtil.mapper().writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("OpenAI streaming API returned HTTP " + response.statusCode(), response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode root = JsonUtil.mapper().readTree(data);
                    JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String text = delta.asText();
                        if (!text.isEmpty()) {
                            deltaConsumer.accept(text);
                        }
                    }
                }
            }
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("OpenAI streaming failed: " + e.getMessage(), e);
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
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return true;
            }
            throw new ProviderException("OpenAI connectivity test failed with HTTP " + resp.statusCode(), resp.statusCode());
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("OpenAI connectivity test failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildPayload(ChatCompletionRequest req, ProviderConfig cfg, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        String model = (req.getModel() != null && !req.getModel().isBlank()) ? req.getModel() : cfg.getDefaultModel();
        body.put("model", model);
        body.put("messages", req.getMessages().stream().map(m -> m.toMap()).toList());
        body.put("stream", stream);
        if (req.getTemperature() != null) body.put("temperature", req.getTemperature());
        if (req.getTopP() != null) body.put("top_p", req.getTopP());
        if (req.getMaxTokens() != null) body.put("max_tokens", req.getMaxTokens());
        if (req.getStop() != null) body.put("stop", req.getStop());
        if (req.getPresencePenalty() != null) body.put("presence_penalty", req.getPresencePenalty());
        if (req.getFrequencyPenalty() != null) body.put("frequency_penalty", req.getFrequencyPenalty());
        if (req.getUser() != null) body.put("user", req.getUser());
        body.putAll(req.getExtraFields());
        return body;
    }

    private void requireKey(String apiKey) throws ProviderException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException("OpenAI API key is not configured.", 401);
        }
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
