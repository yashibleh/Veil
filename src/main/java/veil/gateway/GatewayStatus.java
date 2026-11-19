package veil.gateway;

import java.time.Instant;

/**
 * Genuine runtime status of the VEIL Local API Gateway.
 */
public final class GatewayStatus {
    private final boolean running;
    private final String bindAddress;
    private final int port;
    private final Instant startedAt;
    private final String lastError;

    public GatewayStatus(boolean running, String bindAddress, int port, Instant startedAt, String lastError) {
        this.running = running;
        this.bindAddress = bindAddress;
        this.port = port;
        this.startedAt = startedAt;
        this.lastError = lastError;
    }

    public boolean isRunning() {
        return running;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getEndpointUrl() {
        return "http://" + bindAddress + ":" + port + "/v1/chat/completions";
    }
}
