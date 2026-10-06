package io.meridian.notification.infrastructure;

import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A delivery provider integration for one or more channels (SMTP, SMS gateway, push service ...). */
public interface ChannelAdapter {

    /** Provider row as needed for sending. {@code config} is the provider's JSON options (never null). */
    record Provider(UUID id, String channel, String providerName, String senderId, JsonNode config, String status) {}

    record Message(UUID notificationId, String channel, String destination, String subject, String body) {}

    /**
     * @param status ACCEPTED, REJECTED (permanent for this provider), TIMEOUT or ERROR (transient, retryable)
     */
    record Result(String status, String providerMessageId, String errorCode, Map<String, Object> response) {
        public boolean accepted() {
            return "ACCEPTED".equals(status);
        }

        public boolean retryable() {
            return "TIMEOUT".equals(status) || "ERROR".equals(status);
        }
    }

    /** True if this adapter handles the given provider. Adapters are consulted in order; the sandbox is last. */
    boolean supports(Provider provider);

    /** Sends the message; must not throw (failures are reported in the result). */
    Result send(Provider provider, Message message);
}
