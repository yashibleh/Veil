package veil.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import veil.core.VeilCoreEngine;
import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
import veil.models.GatewayConfig;
import veil.models.ProviderType;
import veil.models.RequestEvent;
import veil.models.SanitizationResult;
import veil.providers.ProviderException;
import veil.utils.JsonUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP Privacy Gateway exposing an OpenAI-compatible API:
 * - POST /v1/chat/completions  (standard JSON and Server-Sent Events streaming)
 * - GET  /v1/models
 * - POST /v1/veil/sanitize     (integration endpoint for future browser/IDE plugins)
 * - POST /v1/veil/restore      (integration endpoint for future browser/IDE plugins)
 * - GET  /health
 */
public class LocalApiGateway {

    private final VeilCoreEngine core;
    private final GatewaySecurityFilter securityFilter = new GatewaySecurityFilter();
    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean running = false;
    private volatile Instant startedAt;
    private volatile String lastError;

    public LocalApiGateway(VeilCoreEngine core) {
        this.core = core;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        try {
            InetSocketAddress address = new InetSocketAddress(cfg.getBindAddress(), cfg.getPort());
            HttpServer httpServer = HttpServer.create(address, 64);
            this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
            httpServer.setExecutor(this.executor);

            httpServer.createContext("/health", this::handleHealth);
            httpServer.createContext("/v1/models", this::handleModels);
            httpServer.createContext("/v1/chat/completions", this::handleChatCompletions);
            httpServer.createContext("/v1/veil/sanitize", this::handleSanitize);
            httpServer.createContext("/v1/veil/restore", this::handleRestore);

            httpServer.start();
            this.server = httpServer;
            this.running = true;
            this.startedAt = Instant.now();
            this.lastError = null;
            core.notifyGatewayState(true, "Gateway listening on " + cfg.getChatCompletionsUrl());
            core.notifyAll("INFO", "Gateway Started", "Listening on " + cfg.getChatCompletionsUrl());
        } catch (IOException e) {
            this.running = false;
            this.lastError = e.getMessage();
            core.notifyGatewayState(false, "Failed to start gateway: " + e.getMessage());
            core.notifyAll("ERROR", "Gateway Start Failed", e.getMessage());
            throw e;
        }
    }

    public synchronized void stop() {
        if (!running && server == null) {
            return;
        }
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        running = false;
        startedAt = null;
        core.notifyGatewayState(false, "Gateway stopped");
        core.notifyAll("INFO", "Gateway Stopped", "Local privacy gateway is no longer listening.");
    }

    public synchronized void restart() throws IOException {
        stop();
        start();
    }

    public GatewayStatus getStatus() {
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        return new GatewayStatus(running, cfg.getBindAddress(), cfg.getPort(), startedAt, lastError);
    }

    public boolean isRunning() {
        return running;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonError(exchange, 405, "method_not_allowed", "Use GET for /health");
            return;
        }
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("gateway", "VEIL Local Privacy Gateway");
        body.put("provider", cfg.getActiveProvider().name());
        body.put("local_provider", cfg.getActiveProvider().isLocalOnly());
        body.put("profile", core.getActiveProfile().getName());
        body.put("vault_locked", core.getTokenVault().isLocked());
        body.put("auth_required", cfg.isRequireLocalAuth());
        sendJson(exchange, 200, body);
    }

    private void handleModels(HttpExchange exchange) throws IOException {
        if (!preFlightChecks(exchange, "GET")) {
            return;
        }
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        String activeModel = core.getConfigManager().getConfig()
                .getProviderConfig(cfg.getActiveProvider()).getDefaultModel();
        Map<String, Object> modelObj = new LinkedHashMap<>();
        modelObj.put("id", activeModel);
        modelObj.put("object", "model");
        modelObj.put("owned_by", cfg.getActiveProvider().getDisplayName());
        sendJson(exchange, 200, Map.of("object", "list", "data", List.of(modelObj)));
    }

    private void handleChatCompletions(HttpExchange exchange) throws IOException {
        if (!preFlightChecks(exchange, "POST")) {
            return;
        }
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        byte[] rawBody = readLimitedBody(exchange, cfg.getMaxRequestBodyBytes());
        if (rawBody == null) {
            sendJsonError(exchange, 413, "payload_too_large", "Request exceeds configured maximum size.");
            return;
        }

        ChatCompletionRequest request;
        try {
            request = JsonUtil.mapper().readValue(rawBody, ChatCompletionRequest.class);
        } catch (Exception e) {
            recordValidationError(cfg, "Malformed JSON payload");
            sendJsonError(exchange, 400, "invalid_request_error", "Malformed JSON request body.");
            return;
        }

        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            recordValidationError(cfg, "Missing required 'messages' array");
            sendJsonError(exchange, 400, "invalid_request_error", "Field 'messages' must be a non-empty array.");
            return;
        }

        String sessionId = exchange.getRequestHeaders().getFirst("X-Veil-Session");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "gw-" + UUID.randomUUID().toString().substring(0, 8);
        }

        ProviderType providerOverride = parseProviderHeader(exchange.getRequestHeaders().getFirst("X-Veil-Provider"));
        boolean wantsStream = request.isStreaming() && cfg.isStreamingAllowed();

        if (wantsStream) {
            handleStreamingCompletion(exchange, request, providerOverride, sessionId);
        } else {
            try {
                ChatCompletionResponse response = core.processChatCompletion(request, providerOverride, sessionId, "GATEWAY");
                exchange.getResponseHeaders().set("X-Veil-Session", sessionId);
                sendJson(exchange, 200, response);
            } catch (IllegalStateException lockedEx) {
                sendJsonError(exchange, 423, "vault_locked", lockedEx.getMessage());
            } catch (ProviderException pe) {
                sendJsonError(exchange, pe.getHttpStatus(),
                        pe.getHttpStatus() == 403 ? "privacy_policy_blocked" : "provider_error",
                        pe.getMessage());
            } catch (Exception e) {
                sendJsonError(exchange, 500, "internal_error", "Unexpected gateway error while processing request.");
            }
        }
    }

    private void handleStreamingCompletion(HttpExchange exchange,
                                           ChatCompletionRequest request,
                                           ProviderType providerOverride,
                                           String sessionId) throws IOException {
        String streamId = "chatcmpl-veil-" + UUID.randomUUID().toString().substring(0, 10);
        long created = Instant.now().getEpochSecond();
        String model = request.getModel() != null ? request.getModel()
                : core.getConfigManager().getConfig().getProviderConfig(
                providerOverride != null ? providerOverride : core.getConfigManager().getConfig().getGateway().getActiveProvider()
        ).getDefaultModel();

        // Buffer headers until first chunk or error so policy blocks still return clean HTTP 403 JSON
        final boolean[] headersSent = {false};
        try {
            core.processStreamingChatCompletion(request, providerOverride, sessionId, "GATEWAY", restoredDelta -> {
                try {
                    if (!headersSent[0]) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                        exchange.getResponseHeaders().set("X-Veil-Session", sessionId);
                        exchange.sendResponseHeaders(200, 0);
                        headersSent[0] = true;
                    }
                    Map<String, Object> chunkPayload = new LinkedHashMap<>();
                    chunkPayload.put("id", streamId);
                    chunkPayload.put("object", "chat.completion.chunk");
                    chunkPayload.put("created", created);
                    chunkPayload.put("model", model);
                    chunkPayload.put("choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of("content", restoredDelta),
                            "finish_reason", ""
                    )));
                    String sse = "data: " + JsonUtil.mapper().writeValueAsString(chunkPayload) + "\n\n";
                    OutputStream os = exchange.getResponseBody();
                    os.write(sse.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {
                }
            });

            if (!headersSent[0]) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("X-Veil-Session", sessionId);
                exchange.sendResponseHeaders(200, 0);
                headersSent[0] = true;
            }
            OutputStream os = exchange.getResponseBody();
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        } catch (IllegalStateException lockedEx) {
            if (!headersSent[0]) {
                sendJsonError(exchange, 423, "vault_locked", lockedEx.getMessage());
            } else {
                exchange.getResponseBody().close();
            }
        } catch (ProviderException pe) {
            if (!headersSent[0]) {
                sendJsonError(exchange, pe.getHttpStatus(),
                        pe.getHttpStatus() == 403 ? "privacy_policy_blocked" : "provider_error",
                        pe.getMessage());
            } else {
                exchange.getResponseBody().close();
            }
        }
    }

    /**
     * Local integration endpoint for future browser extensions or IDE plugins to sanitize text.
     */
    private void handleSanitize(HttpExchange exchange) throws IOException {
        if (!preFlightChecks(exchange, "POST")) {
            return;
        }
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        byte[] rawBody = readLimitedBody(exchange, cfg.getMaxRequestBodyBytes());
        if (rawBody == null) {
            sendJsonError(exchange, 413, "payload_too_large", "Payload too large.");
            return;
        }
        try {
            JsonNode node = JsonUtil.mapper().readTree(rawBody);
            String text = node.path("text").asText("");
            String sessionId = node.path("session_id").asText("ext-" + UUID.randomUUID().toString().substring(0, 8));
            SanitizationResult res = core.sanitizeText(sessionId, text, core.getActiveProfile());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("session_id", res.getSessionId());
            out.put("blocked", res.isBlocked());
            out.put("block_reason", res.getBlockReason());
            out.put("sanitized_text", res.getSanitizedText());
            out.put("detected_count", res.getDetectedEntities().size());
            out.put("masked_count", res.getMaskedCount());
            sendJson(exchange, res.isBlocked() ? 403 : 200, out);
        } catch (Exception e) {
            sendJsonError(exchange, 400, "invalid_request_error", "Invalid sanitize request.");
        }
    }

    /**
     * Local integration endpoint for future browser extensions or IDE plugins to restore tokens.
     */
    private void handleRestore(HttpExchange exchange) throws IOException {
        if (!preFlightChecks(exchange, "POST")) {
            return;
        }
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        byte[] rawBody = readLimitedBody(exchange, cfg.getMaxRequestBodyBytes());
        if (rawBody == null) {
            sendJsonError(exchange, 413, "payload_too_large", "Payload too large.");
            return;
        }
        try {
            JsonNode node = JsonUtil.mapper().readTree(rawBody);
            String text = node.path("text").asText("");
            String sessionId = node.path("session_id").asText("");
            String restored = core.restoreText(sessionId, text);
            sendJson(exchange, 200, Map.of("session_id", sessionId, "restored_text", restored));
        } catch (Exception e) {
            sendJsonError(exchange, 400, "invalid_request_error", "Invalid restore request.");
        }
    }

    private boolean preFlightChecks(HttpExchange exchange, String expectedMethod) throws IOException {
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonError(exchange, 405, "method_not_allowed", "Expected HTTP " + expectedMethod);
            return false;
        }
        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        if (!securityFilter.isAuthorized(exchange, cfg)) {
            RequestEvent authEvent = new RequestEvent();
            authEvent.setSource("GATEWAY");
            authEvent.setProvider(cfg.getActiveProvider());
            authEvent.setProfileName(core.getActiveProfile().getName());
            authEvent.setStatus(RequestEvent.Status.AUTH_REJECTED);
            authEvent.setSummaryNote("Rejected unauthorized local gateway request");
            core.recordAndNotify(authEvent);
            sendJsonError(exchange, 401, "unauthorized", "Missing or invalid VEIL local gateway access token.");
            return false;
        }
        if (!securityFilter.allowRateLimit(cfg)) {
            sendJsonError(exchange, 429, "rate_limit_exceeded", "Local gateway rate limit exceeded.");
            return false;
        }
        if (!securityFilter.isContentLengthAllowed(exchange, cfg)) {
            sendJsonError(exchange, 413, "payload_too_large", "Request body exceeds maximum allowed size.");
            return false;
        }
        return true;
    }

    private byte[] readLimitedBody(HttpExchange exchange, int maxBytes) throws IOException {
        try (InputStream is = exchange.getRequestBody(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int total = 0;
            int read;
            while ((read = is.read(buf)) != -1) {
                total += read;
                if (total > maxBytes) {
                    return null;
                }
                baos.write(buf, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private ProviderType parseProviderHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return ProviderType.valueOf(header.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void recordValidationError(GatewayConfig cfg, String note) {
        RequestEvent ev = new RequestEvent();
        ev.setSource("GATEWAY");
        ev.setProvider(cfg.getActiveProvider());
        ev.setProfileName(core.getActiveProfile().getName());
        ev.setStatus(RequestEvent.Status.VALIDATION_ERROR);
        ev.setSummaryNote(note);
        core.recordAndNotify(ev);
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JsonUtil.mapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonError(HttpExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> error = Map.of(
                "error", Map.of(
                        "type", code,
                        "message", message == null ? "Gateway error" : message
                )
        );
        sendJson(exchange, status, error);
    }
}
