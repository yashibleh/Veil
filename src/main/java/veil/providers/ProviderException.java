package veil.providers;

/**
 * Exception thrown when communicating with an external or local AI provider fails.
 * Messages must never contain raw API keys or sensitive user prompt values.
 */
public class ProviderException extends Exception {
    private final int httpStatus;

    public ProviderException(String message) {
        super(message);
        this.httpStatus = 502;
    }

    public ProviderException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 502;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
