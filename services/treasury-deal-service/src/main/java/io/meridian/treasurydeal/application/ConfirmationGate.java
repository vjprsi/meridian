package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * Confirmation precondition of the deal confirm actions: a MATCHED inbound confirmation must exist, or the user
 * gives a manual reference (phone / platform confirmation), recorded as a MANUAL matched confirmation for the audit trail.
 */
@Component
public class ConfirmationGate {
    private final Rows rows;
    private final Events events;

    public ConfirmationGate(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    public void requireMatchedOrManual(String dealType, UUID dealId, String dealRef, String manualReference) {
        boolean matched = rows.scalar("""
                select count(*) from treasury_deal_confirmation
                where deal_id = ? and direction = 'INBOUND' and status = 'MATCHED'""", Integer.class, dealId).orElse(0) > 0;
        if (matched) {
            return;
        }
        if (manualReference == null || manualReference.isBlank()) {
            throw ApiException.unprocessable("confirmation_not_matched",
                    "No matched inbound confirmation for the deal; match the counterparty's confirmation or give a manual_reference");
        }
        ObjectNode v = rows.object();
        v.put("deal_type", dealType);
        v.put("deal_id", dealId.toString());
        v.put("channel", "MANUAL");
        v.put("our_reference", dealRef);
        v.put("their_reference", manualReference.length() > 35 ? manualReference.substring(0, 35) : manualReference);
        v.put("direction", "INBOUND");
        v.put("status", "MATCHED");
        v.putArray("mismatch_fields");
        v.put("matched_at", rows.now());
        ObjectNode c = rows.insert(ConfirmationService.TABLE, v);
        events.publish("treasury.confirmation.matched", ConfirmationService.TABLE, c);
    }
}
