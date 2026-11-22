package veil.gateway;

import com.sun.net.httpserver.HttpExchange;
import veil.models.GatewayConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Enforces local gateway access controls:
 * - Constant-time Bearer token verification (when local auth is enabled)
 * - Sliding-window rate limiting to prevent local resource exhaustion
 * - Maximum request body size validation
 */
public class GatewaySecurityFilter {

    private final Deque<Long> requestTimestampsMs = new ConcurrentLinkedDeque<>();

    public boolean isAuthorized(HttpExchange exchange, GatewayConfig config) {
        if (!config.isRequireLocalAuth()) {
            return true;
        }
        String expected = config.getGatewayAccessToken();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String xVeilToken = exchange.getRequestHeaders().getFirst("X-Veil-Token");

        String candidate = null;
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            candidate = authHeader.substring(7).trim();
        } else if (xVeilToken != null && !xVeilToken.isBlank()) {
            candidate = xVeilToken.trim();
        }
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    public boolean allowRateLimit(GatewayConfig config) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - 60_000L;
        while (!requestTimestampsMs.isEmpty()) {
            Long head = requestTimestampsMs.peekFirst();
            if (head != null && head < windowStart) {
                requestTimestampsMs.pollFirst();
            } else {
                break;
            }
        }
        if (requestTimestampsMs.size() >= config.getRateLimitPerMinute()) {
            return false;
        }
        requestTimestampsMs.addLast(now);
        return true;
    }

    public boolean isContentLengthAllowed(HttpExchange exchange, GatewayConfig config) {
        String lenHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lenHeader == null) {
            return true;
        }
        try {
            long len = Long.parseLong(lenHeader.trim());
            return len >= 0 && len <= config.getMaxRequestBodyBytes();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
