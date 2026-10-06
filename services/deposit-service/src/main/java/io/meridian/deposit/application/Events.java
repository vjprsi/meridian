package io.meridian.deposit.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the accounts domain events (names exactly as declared in spec/domains/accounts.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "accounts.deposit_account.opened", "accounts.deposit_account.activated",
            "accounts.deposit_account.status_changed", "accounts.deposit_account.product_changed",
            "accounts.deposit_account.closed", "accounts.account_holder.added", "accounts.account_holder.removed",
            "accounts.account_block.placed", "accounts.account_block.lifted", "accounts.overdraft_facility.approved",
            "accounts.overdraft_facility.limit_exceeded", "accounts.interest.capitalised", "accounts.fee.charged",
            "accounts.fee.refunded", "accounts.term_deposit.placed", "accounts.term_deposit.maturing",
            "accounts.term_deposit.matured", "accounts.term_deposit.broken", "accounts.savings_pot.goal_reached",
            "accounts.standing_order.executed", "accounts.standing_order.failed", "accounts.dormancy.marked_dormant",
            "accounts.cheque.returned", "accounts.virtual_account.created");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the accounts domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
