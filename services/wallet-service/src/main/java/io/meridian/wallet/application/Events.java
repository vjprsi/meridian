package io.meridian.wallet.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes the wallet-service events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "wallets.wallet.created", "wallets.wallet.status_changed", "wallets.wallet.tier_upgraded",
            "wallets.wallet.pin_changed", "wallets.wallet.pin_locked",
            "wallets.wallet_transaction.initiated", "wallets.wallet_transaction.completed",
            "wallets.wallet_transaction.failed", "wallets.wallet_transaction.cancelled",
            "wallets.wallet_transaction.reversal_requested", "wallets.wallet_transaction.reversed",
            "wallets.wallet_bank_link.verified", "wallets.wallet_bank_link.removed",
            "wallets.wallet_pin_reset.requested", "wallets.wallet_pin_reset.completed");

    /** Columns never published (hashes and internal counters). */
    private static final Set<String> SECRET = Set.of("pin_hash", "pin_failed_attempts", "confirmation_otp_hash",
            "verification_code_hash", "otp_hash", "tenant_id");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the wallets domain spec");
        }
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        SECRET.forEach(payload::remove);
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), payload);
    }
}
