package io.meridian.notification.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Sandbox provider for every channel (SMS, PUSH, WHATSAPP, IN_APP, USSD, VOICE, and EMAIL without SMTP): records
 * the delivery and returns a synthetic provider message id. Provider config {@code "simulate"} forces an outcome
 * for testing failover and retries: {@code ERROR}, {@code TIMEOUT} (transient) or {@code REJECT} (permanent).
 */
@Component
@Order(Integer.MAX_VALUE)
public class SandboxChannelAdapter implements ChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(SandboxChannelAdapter.class);

    @Override
    public boolean supports(Provider provider) {
        return true;
    }

    @Override
    public Result send(Provider provider, Message message) {
        String simulate = provider.config().path("simulate").asString("").toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adapter", "sandbox");
        response.put("provider", provider.providerName());
        response.put("channel", message.channel());
        switch (simulate) {
            case "ERROR" -> {
                response.put("error", "simulated provider error");
                return new Result("ERROR", null, "simulated_error", response);
            }
            case "TIMEOUT" -> {
                response.put("error", "simulated timeout");
                return new Result("TIMEOUT", null, "timeout", response);
            }
            case "REJECT" -> {
                response.put("error", "simulated rejection");
                return new Result("REJECTED", null, "rejected", response);
            }
            default -> {
                String id = "sbx_" + UUID.randomUUID();
                response.put("message_id", id);
                log.info("Sandbox {} delivery via {} to {} (notification {})", message.channel(),
                        provider.providerName(), mask(message.destination()), message.notificationId());
                return new Result("ACCEPTED", id, null, response);
            }
        }
    }

    static String mask(String d) {
        if (d == null || d.length() <= 4) {
            return "****";
        }
        return "****" + d.substring(d.length() - 4);
    }
}
