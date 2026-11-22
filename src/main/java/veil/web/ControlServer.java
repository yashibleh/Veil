package veil.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import veil.core.PrivacyEventListener;
import veil.core.VeilCoreEngine;
import veil.detection.Detector;
import veil.gateway.GatewayStatus;
import veil.gateway.LocalApiGateway;
import veil.integrations.IntegrationDescriptor;
import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
import veil.models.ChatMessage;
import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.GatewayConfig;
import veil.models.PolicyAction;
import veil.models.PrivacyProfile;
import veil.models.ProviderConfig;
import veil.models.ProviderType;
import veil.models.RequestEvent;
import veil.models.SanitizationResult;
import veil.models.VeilConfig;
import veil.analytics.AnalyticsSnapshot;
import veil.providers.ProviderException;
import veil.utils.JsonUtil;
import veil.vault.TokenMapping;
import veil.vault.TokenVault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Loopback-only control surface for the VEIL interface.
 *
 * Serves the static web UI from the classpath (/web) and a small JSON API under /api, plus a
 * Server-Sent-Events stream (/api/stream) that pushes genuine gateway activity to the UI.
 *
 * Hardening: bound to 127.0.0.1 only, Host-header allow-list (anti DNS-rebinding), a random
 * per-launch UI token exchanged for an HttpOnly SameSite=Strict cookie, a custom-header +
 * same-origin requirement on every mutating call, and a strict Content-Security-Policy.
 */
public final class ControlServer {

    private static final int MAX_BODY = 1_048_576;
    private static final int MAX_TEXT = 200_000;
    private static final String COOKIE = "veil_ui";

    private final VeilCoreEngine core;
    private final LocalApiGateway gateway;
    private final Runnable onQuit;
    private final String uiToken;
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Boolean>> pendingWarns = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private ScheduledExecutorService heartbeat;
    private HttpServer server;
    private int port;

    public ControlServer(VeilCoreEngine core, LocalApiGateway gateway, Runnable onQuit) {
        this.core = core;
        this.gateway = gateway;
        this.onQuit = onQuit;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        this.uiToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    public synchronized void start() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        IOException last = null;
        for (int candidate = 8787; candidate <= 8797 && server == null; candidate++) {
            try {
                server = HttpServer.create(new InetSocketAddress(loopback, candidate), 32);
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(loopback, 0), 32);
        }
        this.port = server.getAddress().getPort();
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "veil-ui-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();

        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "veil-ui-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> clients.forEach(c -> c.comment("hb")), 15, 15, TimeUnit.SECONDS);

        core.addListener(new PrivacyEventListener() {
            @Override
            public void onRequestEvent(RequestEvent event) {
                broadcast("request", event);
            }

            @Override
            public void onGatewayStateChanged(boolean running, String message) {
                broadcast("gateway", map("running", running, "message", message));
            }

            @Override
            public void onNotification(String level, String title, String message) {
                broadcast("notify", map("level", level, "title", title, "message", message));
            }
        });

        core.setWarnCallback((entities, profile) -> {
            if (clients.isEmpty()) {
                return false; // fail safe: nobody to ask
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            CompletableFuture<Boolean> decision = new CompletableFuture<>();
            pendingWarns.put(id, decision);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (DetectedEntity e : entities) {
                counts.merge(e.getCategory().getDisplayName(), 1, Integer::sum);
            }
            broadcast("warn", map("id", id, "profile", profile.getName(), "count", entities.size(), "categories", counts));
            try {
                return decision.get(90, TimeUnit.SECONDS);
            } catch (Exception e) {
                return false;
            } finally {
                pendingWarns.remove(id);
            }
        });
        if (last != null && port != 8787) {
            System.err.println("[VEIL] Port 8787 busy; control surface moved to " + port);
        }
    }

    public synchronized void stop() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        clients.forEach(SseClient::close);
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public int getPort() {
        return port;
    }

    public String getLaunchUrl() {
        return "http://127.0.0.1:" + port + "/?t=" + uiToken;
    }

    // ───────────────────────────── request pipeline ─────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!hostAllowed(ex)) {
                text(ex, 403, "Forbidden host");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);

            if (("/".equals(path) || "/index.html".equals(path)) && tokenInQuery(ex)) {
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + uiToken + "; Path=/; HttpOnly; SameSite=Strict");
                ex.getResponseHeaders().set("Location", "/");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(302, -1);
                return;
            }
            if (!authorized(ex)) {
                text(ex, 401, "VEIL control surface. Open it from the VEIL launcher window.");
                return;
            }
            if (path.startsWith("/api/")) {
                if (!"GET".equals(method) && !"HEAD".equals(method) && !sameOriginMutation(ex)) {
                    json(ex, 403, map("error", "Cross-origin request refused"));
                    return;
                }
                api(ex, method, path);
            } else {
                serveStatic(ex, path, method);
            }
        } catch (ApiException e) {
            json(ex, e.status, map("error", e.getMessage()));
        } catch (Exception e) {
            json(ex, 500, map("error", String.valueOf(e.getMessage())));
        } finally {
            if (ex.getAttribute("keep") == null) {
                ex.close();
            }
        }
    }

    private boolean hostAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && (host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port));
    }

    private boolean tokenInQuery(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return false;
        }
        for (String part : q.split("&")) {
            if (part.startsWith("t=") && constEq(part.substring(2), uiToken)) {
                return true;
            }
        }
        return false;
    }

    private boolean authorized(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return false;
        }
        for (String header : cookies) {
            for (String c : header.split(";")) {
                String s = c.trim();
                if (s.startsWith(COOKIE + "=") && constEq(s.substring(COOKIE.length() + 1), uiToken)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sameOriginMutation(HttpExchange ex) {
        if (!"1".equals(ex.getRequestHeaders().getFirst("X-Veil-UI"))) {
            return false;
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        return origin.equals("http://" + host);
    }

    private static boolean constEq(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ───────────────────────────── static files ─────────────────────────────

    private void serveStatic(HttpExchange ex, String path, String method) throws IOException {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            text(ex, 405, "Method not allowed");
            return;
        }
        String rel = "/".equals(path) ? "index.html" : path.substring(1);
        if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/") || rel.isEmpty()) {
            text(ex, 404, "Not found");
            return;
        }
        try (InputStream in = ControlServer.class.getResourceAsStream("/web/" + rel)) {
            if (in == null) {
                text(ex, 404, "Not found");
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(rel));
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
                            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            if ("HEAD".equals(method)) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String contentType(String rel) {
        String r = rel.toLowerCase(Locale.ROOT);
        if (r.endsWith(".html")) return "text/html; charset=utf-8";
        if (r.endsWith(".css")) return "text/css; charset=utf-8";
        if (r.endsWith(".js") || r.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (r.endsWith(".json")) return "application/json; charset=utf-8";
        if (r.endsWith(".svg")) return "image/svg+xml";
        if (r.endsWith(".png")) return "image/png";
        if (r.endsWith(".ico")) return "image/x-icon";
        if (r.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // ───────────────────────────── API routing ─────────────────────────────

    private void api(HttpExchange ex, String method, String path) throws Exception {
        VeilConfig cfg = core.getConfigManager().getConfig();
        GatewayConfig gw = cfg.getGateway();
        String key = method + " " + path;

        switch (key) {
            case "GET /api/state":
                json(ex, 200, state());
                return;
            case "GET /api/stream":
                stream(ex);
                return;
            case "GET /api/events":
                json(ex, 200, eventsList());
                return;
            case "GET /api/analytics":
                json(ex, 200, analyticsMap());
                return;
            case "POST /api/analytics/clear":
                core.getAnalyticsService().clearAllHistory();
                json(ex, 200, map("ok", true));
                return;
            case "GET /api/integrations":
                json(ex, 200, IntegrationDescriptor.buildDescriptors(gw));
                return;

            // ── gateway ──
            case "POST /api/gateway/start":
                try {
                    gateway.start();
                } catch (IOException e) {
                    throw new ApiException(409, "Could not start the gateway: " + e.getMessage());
                }
                json(ex, 200, gatewayMap());
                return;
            case "POST /api/gateway/stop":
                gateway.stop();
                json(ex, 200, gatewayMap());
                return;
            case "POST /api/gateway/restart":
                try {
                    gateway.restart();
                } catch (IOException e) {
                    throw new ApiException(409, "Could not restart the gateway: " + e.getMessage());
                }
                json(ex, 200, gatewayMap());
                return;
            case "POST /api/gateway/config": {
                JsonNode b = body(ex);
                int oldPort = gw.getPort();
                if (b.has("port")) gw.setPort(range(b.get("port").asInt(), 1, 65535, "port"));
                if (b.has("requestTimeoutSeconds")) gw.setRequestTimeoutSeconds(range(b.get("requestTimeoutSeconds").asInt(), 5, 600, "timeout"));
                if (b.has("rateLimitPerMinute")) gw.setRateLimitPerMinute(range(b.get("rateLimitPerMinute").asInt(), 1, 100_000, "rate limit"));
                if (b.has("maxRequestBodyBytes")) gw.setMaxRequestBodyBytes(range(b.get("maxRequestBodyBytes").asInt(), 1024, 50 * 1024 * 1024, "max body"));
                if (b.has("requireLocalAuth")) gw.setRequireLocalAuth(b.get("requireLocalAuth").asBoolean());
                if (b.has("streamingAllowed")) gw.setStreamingAllowed(b.get("streamingAllowed").asBoolean());
                core.getConfigManager().save();
                if (gateway.isRunning() && gw.getPort() != oldPort) {
                    try {
                        gateway.restart();
                    } catch (IOException e) {
                        throw new ApiException(409, "Saved, but the gateway could not rebind: " + e.getMessage());
                    }
                }
                json(ex, 200, map("gateway", gatewayMap(), "config", gatewayConfigMap()));
                return;
            }
            case "GET /api/gateway/token":
                json(ex, 200, map("token", gw.getGatewayAccessToken()));
                return;
            case "POST /api/gateway/token/rotate":
                json(ex, 200, map("token", core.getConfigManager().rotateGatewayToken()));
                return;
            case "POST /api/gateway/health": {
                try {
                    HttpResponse<String> r = httpClient.send(HttpRequest.newBuilder(
                                    URI.create("http://" + gw.getBindAddress() + ":" + gw.getPort() + "/health"))
                            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    json(ex, 200, map("ok", r.statusCode() == 200, "status", r.statusCode(), "body", r.body()));
                } catch (Exception e) {
                    json(ex, 200, map("ok", false, "message", "Gateway did not answer: " + e.getMessage()));
                }
                return;
            }

            // ── providers ──
            case "POST /api/provider/active": {
                ProviderType t = providerOf(body(ex).path("type").asText(""));
                gw.setActiveProvider(t);
                core.getConfigManager().save();
                json(ex, 200, map("active", t.name()));
                return;
            }

            // ── profiles ──
            case "POST /api/profile": {
                PrivacyProfile incoming = JsonUtil.mapper().treeToValue(body(ex), PrivacyProfile.class);
                if (incoming.getId() == null || incoming.getId().isBlank()) {
                    throw new ApiException(400, "Profile id missing");
                }
                core.getProfileManager().getProfile(incoming.getId()).ifPresent(old -> incoming.setBuiltIn(old.isBuiltIn()));
                if (incoming.getName() == null || incoming.getName().isBlank()) {
                    throw new ApiException(400, "A mask needs a name");
                }
                core.getProfileManager().saveProfile(incoming);
                json(ex, 200, incoming);
                return;
            }
            case "POST /api/profile/active": {
                String id = body(ex).path("id").asText("");
                if (core.getProfileManager().getProfile(id).isEmpty()) {
                    throw new ApiException(404, "Unknown profile");
                }
                gw.setActiveProfileId(id);
                core.getConfigManager().save();
                json(ex, 200, map("active", id));
                return;
            }

            // ── vault ──
            case "GET /api/vault":
                json(ex, 200, vaultMap());
                return;
            case "GET /api/vault/session": {
                String id = query(ex, "id");
                boolean reveal = "1".equals(query(ex, "reveal"));
                TokenVault v = core.getTokenVault();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (TokenMapping m : v.getSessionMappings(id)) {
                    rows.add(map("token", m.getToken(), "category", m.getCategory().name(),
                            "categoryName", m.getCategory().getDisplayName(), "action", m.getAction().name(),
                            "createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString(),
                            "preview", m.getRedactedPreview(), "original", reveal ? m.getOriginalValue() : null));
                }
                json(ex, 200, map("id", id, "mappings", rows));
                return;
            }
            case "POST /api/vault/lock":
                try {
                    core.getTokenVault().lock();
                    core.notifyAll("WARN", "Vault locked", "Token mappings were cleared from memory.");
                } catch (Exception e) {
                    throw new ApiException(400, "Lock failed: " + e.getMessage());
                }
                json(ex, 200, vaultMap());
                return;
            case "POST /api/vault/unlock":
                try {
                    core.getTokenVault().unlock(body(ex).path("passphrase").asText("").toCharArray());
                } catch (Exception e) {
                    throw new ApiException(400, "Could not unlock (check the passphrase): " + e.getMessage());
                }
                json(ex, 200, vaultMap());
                return;
            case "POST /api/vault/persist":
                try {
                    core.getTokenVault().enablePersistence(body(ex).path("passphrase").asText("").toCharArray());
                    cfg.setPersistentVaultEnabled(true);
                    core.getConfigManager().save();
                } catch (Exception e) {
                    throw new ApiException(400, e.getMessage());
                }
                json(ex, 200, vaultMap());
                return;
            case "POST /api/vault/wipe":
                core.getTokenVault().secureWipeAll();
                json(ex, 200, vaultMap());
                return;
            case "POST /api/vault/clear":
                core.getTokenVault().clearSession(body(ex).path("id").asText(""));
                json(ex, 200, vaultMap());
                return;

            // ── atelier ──
            case "POST /api/detect": {
                String text = textOf(body(ex));
                PrivacyProfile p = core.getActiveProfile();
                List<DetectedEntity> found = core.getDetectionEngine().detect(text, p);
                json(ex, 200, map("profile", p.getName(), "entities", entities(found, p), "text", text.length()));
                return;
            }
            case "POST /api/sanitize": {
                String text = textOf(body(ex));
                PrivacyProfile p = core.getActiveProfile();
                String sid = "pg-" + UUID.randomUUID().toString().substring(0, 8);
                try {
                    SanitizationResult r = core.sanitizeText(sid, text, p);
                    json(ex, 200, map("session", sid, "blocked", r.isBlocked(), "reason", r.getBlockReason(),
                            "sanitized", r.getSanitizedText(), "masked", r.getMaskedCount(),
                            "entities", entities(r.getDetectedEntities(), p)));
                } catch (IllegalStateException locked) {
                    json(ex, 200, map("session", sid, "blocked", true, "reason", locked.getMessage(), "entities", List.of()));
                }
                return;
            }
            case "POST /api/send": {
                JsonNode b = body(ex);
                String text = textOf(b);
                ProviderType t = b.hasNonNull("provider") ? providerOf(b.get("provider").asText()) : null;
                String sid = "pg-" + UUID.randomUUID().toString().substring(0, 8);
                try {
                    ChatCompletionRequest req = new ChatCompletionRequest();
                    req.setMessages(List.of(new ChatMessage("user", text)));
                    ChatCompletionResponse resp = core.processChatCompletion(req, t, sid, "PLAYGROUND");
                    json(ex, 200, map("ok", true, "session", sid, "reply", resp.getFirstAssistantContent()));
                } catch (ProviderException pe) {
                    json(ex, 200, map("ok", false, "blocked", pe.getHttpStatus() == 403, "status", pe.getHttpStatus(), "message", pe.getMessage()));
                } catch (IllegalStateException locked) {
                    json(ex, 200, map("ok", false, "status", 423, "message", locked.getMessage()));
                }
                return;
            }

            // ── settings ──
            case "POST /api/settings": {
                JsonNode b = body(ex);
                if (b.has("enabledDetectors") && b.get("enabledDetectors").isObject()) {
                    Map<String, Boolean> states = new LinkedHashMap<>(cfg.getEnabledDetectors());
                    b.get("enabledDetectors").fields().forEachRemaining(en -> states.put(en.getKey(), en.getValue().asBoolean()));
                    cfg.setEnabledDetectors(states);
                }
                if (b.has("sessionTtlMinutes")) cfg.setSessionTtlMinutes(range(b.get("sessionTtlMinutes").asInt(), 1, 10_080, "session lifetime"));
                if (b.has("logRetentionDays")) cfg.setLogRetentionDays(range(b.get("logRetentionDays").asInt(), 1, 365, "retention"));
                if (b.has("reducedMotion")) cfg.setReducedMotion(b.get("reducedMotion").asBoolean());
                if (b.has("minimizeToTray")) cfg.setMinimizeToTray(b.get("minimizeToTray").asBoolean());
                if (b.has("desktopNotifications")) cfg.setDesktopNotifications(b.get("desktopNotifications").asBoolean());
                if (b.has("logPromptPreviews")) cfg.setLogPromptPreviews(b.get("logPromptPreviews").asBoolean());
                core.getConfigManager().save();
                core.applyConfigToRuntime();
                json(ex, 200, settingsMap());
                return;
            }
            case "POST /api/quit":
                json(ex, 200, map("ok", true));
                new Thread(onQuit, "veil-quit").start();
                return;
            default:
                break;
        }

        // ── parameterised routes ──
        if (path.startsWith("/api/provider/")) {
            String[] seg = path.substring("/api/provider/".length()).split("/");
            ProviderType t = providerOf(seg[0]);
            String sub = seg.length > 1 ? seg[1] : "";
            if ("POST".equals(method) && sub.isEmpty()) {
                JsonNode b = body(ex);
                ProviderConfig pc = cfg.getProviderConfig(t);
                if (b.has("baseUrl")) {
                    String url = b.get("baseUrl").asText("").trim();
                    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                        throw new ApiException(400, "Base URL must start with http:// or https://");
                    }
                    pc.setBaseUrl(url);
                }
                if (b.has("defaultModel")) pc.setDefaultModel(b.get("defaultModel").asText("").trim());
                if (b.has("timeoutSeconds")) pc.setTimeoutSeconds(range(b.get("timeoutSeconds").asInt(), 5, 600, "timeout"));
                if (b.has("streamingEnabled")) pc.setStreamingEnabled(b.get("streamingEnabled").asBoolean());
                core.getConfigManager().save();
                String apiKey = b.path("apiKey").asText("").trim();
                if (!apiKey.isEmpty()) {
                    try {
                        core.getCredentialStore().saveKey(t, apiKey);
                    } catch (Exception e) {
                        throw new ApiException(500, "Could not store the key: " + e.getMessage());
                    }
                }
                json(ex, 200, providerMap(t));
                return;
            }
            if ("DELETE".equals(method) && "key".equals(sub)) {
                try {
                    core.getCredentialStore().deleteKey(t);
                } catch (Exception e) {
                    throw new ApiException(500, "Could not delete the key: " + e.getMessage());
                }
                json(ex, 200, providerMap(t));
                return;
            }
            if ("POST".equals(method) && "test".equals(sub)) {
                try {
                    boolean ok = core.testProviderConnection(t);
                    json(ex, 200, map("ok", ok, "message", ok ? "The line is open." : "The provider answered, but not happily."));
                } catch (ProviderException e) {
                    json(ex, 200, map("ok", false, "message", e.getMessage()));
                }
                return;
            }
        }
        if (path.startsWith("/api/profile/")) {
            String[] seg = path.substring("/api/profile/".length()).split("/");
            String id = seg[0];
            if ("POST".equals(method) && seg.length > 1 && "duplicate".equals(seg[1])) {
                String name = body(ex).path("name").asText("").trim();
                PrivacyProfile src = core.getProfileManager().getProfile(id).orElseThrow(() -> new ApiException(404, "Unknown profile"));
                json(ex, 200, core.getProfileManager().duplicateProfile(id, name.isEmpty() ? src.getName() + " (copy)" : name));
                return;
            }
            if ("DELETE".equals(method) && seg.length == 1) {
                boolean ok = core.getProfileManager().deleteCustomProfile(id);
                if (!ok) {
                    throw new ApiException(400, "Built-in masks cannot be deleted. Duplicate one instead.");
                }
                if (id.equals(gw.getActiveProfileId())) {
                    gw.setActiveProfileId("developer");
                    core.getConfigManager().save();
                }
                json(ex, 200, map("ok", true));
                return;
            }
        }
        if (path.startsWith("/api/warn/") && "POST".equals(method)) {
            String id = path.substring("/api/warn/".length());
            CompletableFuture<Boolean> f = pendingWarns.get(id);
            if (f == null) {
                throw new ApiException(404, "That question has already been answered or timed out.");
            }
            f.complete(body(ex).path("approve").asBoolean(false));
            json(ex, 200, map("ok", true));
            return;
        }
        throw new ApiException(404, "No such route: " + method + " " + path);
    }

    // ───────────────────────────── view models ─────────────────────────────

    private Map<String, Object> state() {
        VeilConfig cfg = core.getConfigManager().getConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gateway", gatewayMap());
        m.put("gatewayConfig", gatewayConfigMap());
        m.put("settings", settingsMap());
        List<Map<String, Object>> detectors = new ArrayList<>();
        for (Detector d : core.getDetectionEngine().getDetectors()) {
            detectors.add(map("name", d.getName(), "description", d.getDescription(),
                    "enabled", core.getDetectionEngine().isDetectorEnabled(d.getName())));
        }
        m.put("detectors", detectors);
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ProviderType t : ProviderType.values()) {
            providers.add(providerMap(t));
        }
        m.put("providers", providers);
        m.put("profiles", new ArrayList<>(core.getProfileManager().getAllProfiles()));
        List<Map<String, Object>> cats = new ArrayList<>();
        for (EntityCategory c : EntityCategory.values()) {
            cats.add(map("name", c.name(), "prefix", c.getTokenPrefix(), "display", c.getDisplayName()));
        }
        m.put("categories", cats);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (PolicyAction a : PolicyAction.values()) {
            actions.add(map("name", a.name(), "description", a.getDescription()));
        }
        m.put("actions", actions);
        m.put("vault", vaultMap());
        m.put("analytics", analyticsMap());
        m.put("events", eventsList());
        m.put("persistentVaultEnabled", cfg.isPersistentVaultEnabled());
        return m;
    }

    private Map<String, Object> gatewayMap() {
        GatewayStatus st = gateway.getStatus();
        return map("running", st.isRunning(), "bindAddress", st.getBindAddress(), "port", st.getPort(),
                "endpoint", st.getEndpointUrl(), "startedAt", st.getStartedAt() == null ? null : st.getStartedAt().toString(),
                "lastError", st.getLastError());
    }

    private Map<String, Object> gatewayConfigMap() {
        GatewayConfig g = core.getConfigManager().getConfig().getGateway();
        return map("bindAddress", g.getBindAddress(), "port", g.getPort(), "requireLocalAuth", g.isRequireLocalAuth(),
                "maxRequestBodyBytes", g.getMaxRequestBodyBytes(), "rateLimitPerMinute", g.getRateLimitPerMinute(),
                "requestTimeoutSeconds", g.getRequestTimeoutSeconds(), "streamingAllowed", g.isStreamingAllowed(),
                "activeProvider", g.getActiveProvider().name(), "activeProfileId", g.getActiveProfileId(),
                "baseUrl", g.getBaseUrl(), "endpoint", g.getChatCompletionsUrl());
    }

    private Map<String, Object> settingsMap() {
        VeilConfig c = core.getConfigManager().getConfig();
        Map<String, Boolean> det = new LinkedHashMap<>();
        for (Detector d : core.getDetectionEngine().getDetectors()) {
            det.put(d.getName(), core.getDetectionEngine().isDetectorEnabled(d.getName()));
        }
        return map("sessionTtlMinutes", c.getSessionTtlMinutes(), "logRetentionDays", c.getLogRetentionDays(),
                "logPromptPreviews", c.isLogPromptPreviews(), "reducedMotion", c.isReducedMotion(),
                "minimizeToTray", c.isMinimizeToTray(), "desktopNotifications", c.isDesktopNotifications(),
                "enabledDetectors", det);
    }

    private Map<String, Object> providerMap(ProviderType t) {
        ProviderConfig pc = core.getConfigManager().getConfig().getProviderConfig(t);
        return map("type", t.name(), "name", t.getDisplayName(), "local", t.isLocalOnly(),
                "requiresKey", t.isRequiresApiKey(), "baseUrl", pc.getBaseUrl(), "model", pc.getDefaultModel(),
                "timeoutSeconds", pc.getTimeoutSeconds(), "streaming", pc.isStreamingEnabled(),
                "hasKey", core.getCredentialStore().hasKey(t), "keyMasked", core.getCredentialStore().getMaskedDisplay(t));
    }

    private Map<String, Object> vaultMap() {
        TokenVault v = core.getTokenVault();
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String id : v.getActiveSessionIds()) {
            sessions.add(map("id", id, "count", v.getSessionMappings(id).size()));
        }
        return map("locked", v.isLocked(), "persistent", v.isPersistentEnabled(), "hasFile", v.hasEncryptedVaultFile(),
                "total", v.getTotalMappingCount(), "sessions", sessions, "ttlMinutes", v.getSessionTtl().toMinutes());
    }

    private Map<String, Object> analyticsMap() {
        AnalyticsSnapshot s = core.getAnalyticsService().computeSnapshot();
        Map<String, Long> cats = new LinkedHashMap<>();
        s.getCategoryDistribution().forEach((k, v) -> cats.put(k.name(), v));
        Map<String, Long> provs = new LinkedHashMap<>();
        s.getProviderUsage().forEach((k, v) -> provs.put(k.name(), v));
        return map("total", s.getTotalRequests(), "forwarded", s.getForwardedRequests(), "blocked", s.getBlockedRequests(),
                "errors", s.getErrorRequests(), "detected", s.getTotalEntitiesDetected(), "masked", s.getTotalEntitiesMasked(),
                "avgMs", s.getAverageDurationMs(), "categories", cats, "providers", provs);
    }

    private List<RequestEvent> eventsList() {
        List<RequestEvent> all = new ArrayList<>(core.getAnalyticsService().getRecentEvents());
        all.sort(Comparator.comparing(RequestEvent::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return all.size() > 300 ? new ArrayList<>(all.subList(0, 300)) : all;
    }

    private List<Map<String, Object>> entities(List<DetectedEntity> found, PrivacyProfile p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DetectedEntity e : found) {
            out.add(map("category", e.getCategory().name(), "prefix", e.getCategory().getTokenPrefix(),
                    "categoryName", e.getCategory().getDisplayName(), "label", e.getRuleLabel(),
                    "detector", e.getDetectorName(), "start", e.getStartIndex(), "end", e.getEndIndex(),
                    "confidence", e.getConfidence(), "action", p.getPolicyFor(e.getCategory()).name()));
        }
        return out;
    }

    // ───────────────────────────── SSE ─────────────────────────────

    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        ex.setAttribute("keep", Boolean.TRUE);
        SseClient c = new SseClient(ex);
        clients.add(c);
        c.send("hello", JsonUtil.mapper().writeValueAsString(map("at", System.currentTimeMillis())));
    }

    private void broadcast(String event, Object payload) {
        if (clients.isEmpty()) {
            return;
        }
        String data;
        try {
            data = JsonUtil.mapper().writeValueAsString(payload);
        } catch (IOException e) {
            return;
        }
        for (SseClient c : clients) {
            if (!c.send(event, data)) {
                clients.remove(c);
            }
        }
    }

    private static final class SseClient {
        private final HttpExchange ex;
        private final OutputStream os;
        private volatile boolean open = true;

        SseClient(HttpExchange ex) {
            this.ex = ex;
            this.os = ex.getResponseBody();
        }

        synchronized boolean send(String event, String data) {
            if (!open) {
                return false;
            }
            try {
                os.write(("event: " + event + "\ndata: " + data.replace("\n", "") + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        synchronized void comment(String text) {
            if (!open) {
                return;
            }
            try {
                os.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                close();
            }
        }

        synchronized void close() {
            open = false;
            try {
                ex.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static final class ApiException extends RuntimeException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static int range(int v, int lo, int hi, String what) {
        if (v < lo || v > hi) {
            throw new ApiException(400, "The " + what + " must be between " + lo + " and " + hi + ".");
        }
        return v;
    }

    private static ProviderType providerOf(String s) {
        try {
            return ProviderType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new ApiException(400, "Unknown provider: " + s);
        }
    }

    private static String textOf(JsonNode b) {
        String t = b.path("text").asText("");
        if (t.length() > MAX_TEXT) {
            throw new ApiException(413, "That is more text than the atelier will hold.");
        }
        return t;
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return "";
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BODY) {
                    throw new ApiException(413, "Request body too large");
                }
                bos.write(buf, 0, n);
            }
            if (total == 0) {
                return JsonNodeFactory.instance.objectNode();
            }
            return JsonUtil.mapper().readTree(bos.toByteArray());
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JsonUtil.mapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void text(HttpExchange ex, int status, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
