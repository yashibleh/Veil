package veil.core;

import veil.models.RequestEvent;

/**
 * Observer interface for real-time privacy gateway events and notifications.
 */
public interface PrivacyEventListener {
    void onRequestEvent(RequestEvent event);

    default void onGatewayStateChanged(boolean running, String message) {
    }

    default void onNotification(String level, String title, String message) {
    }
}
