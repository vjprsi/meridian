package io.meridian.payment.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ledger-service over HTTP. Payments never move balances themselves: funds are reserved with a PAYMENT hold,
 * settlement captures the hold into a journal entry, returns and credits post balanced journal entries. Every
 * write carries a deterministic idempotency key so a retried business action replays instead of posting twice.
 */
@Component
public class LedgerClient {
    static final String LEDGER = "ledger-service";

    public record Leg(UUID account, String direction, BigDecimal amount, String description) {
        public static Leg debit(UUID account, BigDecimal amount, String description) {
            return new Leg(account, "DEBIT", amount, description);
        }

        public static Leg credit(UUID account, BigDecimal amount, String description) {
            return new Leg(account, "CREDIT", amount, description);
        }
    }

    private final ServiceClient client;
    private final PaymentProperties props;
    private final JsonMapper json;

    public LedgerClient(ServiceClient client, PaymentProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    public JsonNode placeHold(String key, UUID ledgerAccountId, BigDecimal amount, String currency, String description,
                              UUID paymentId) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_account_id", ledgerAccountId.toString());
        body.put("hold_type", "PAYMENT");
        body.put("amount", amount.toPlainString());
        body.put("currency", currency);
        body.put("description", trim(description, 500));
        body.put("source_type", "PAYMENT");
        body.put("source_id", paymentId.toString());
        return client.post(LEDGER, "/v1/holds", body, key(key));
    }

    /**
     * Payment references of a posting ({@code external_refs}): the payment's ISO 20022 ids, the clearing-system and rail
     * references, the bulk payment it belongs to, the purpose and the counterparty (the creditor of an outbound or book
     * transfer, the debtor of an inbound payment). ledger-service stores them on the journal entry and every ledger
     * entry (the counterparty account only as a keyed pseudonym + last 4), so reconciliation of the settlement / nostro
     * account matches statement lines on UETR, end-to-end id and bank reference (RECON-REAL-REFS).
     */
    public ObjectNode refs(JsonNode payment, boolean counterpartyIsCreditor) {
        ObjectNode r = json.createObjectNode();
        putRef(r, "uetr", payment, "uetr", 36);
        putRef(r, "end_to_end_id", payment, "end_to_end_id", 35);
        putRef(r, "instruction_id", payment, "instruction_id", 35);
        putRef(r, "bank_reference", payment, "clearing_system_ref", 64);
        putRef(r, "scheme_reference", payment, "transaction_id", 64);
        putRef(r, "batch_id", payment, "bulk_payment_id", 64);
        String party = counterpartyIsCreditor ? "creditor" : "debtor";
        putRef(r, "counterparty_name", payment, party + "_name", 140);
        putRef(r, "counterparty_account", payment, party + "_account_number", 64);
        putRef(r, "purpose_code", payment, payment.hasNonNull("purpose_code") ? "purpose_code" : "category_purpose", 35);
        return r;
    }

    /** Sets {@code key} from {@code source.field} (trimmed to the ledger column length) when present. */
    public static void putRef(ObjectNode refs, String key, JsonNode source, String field, int max) {
        if (source != null && source.hasNonNull(field) && !source.get(field).asString().isBlank()) {
            refs.put(key, trim(source.get(field).asString().strip(), max));
        }
    }

    /** Captures part or all of a hold: debit the held account, credit {@code counterAccount}. */
    public JsonNode capture(UUID holdId, BigDecimal amount, UUID counterAccount, String description,
                            boolean releaseRemainder, String key) {
        return capture(holdId, amount, counterAccount, description, releaseRemainder, key, null, null);
    }

    /** Capture whose journal entry carries the payment reference and {@link #refs payment references}. */
    public JsonNode capture(UUID holdId, BigDecimal amount, UUID counterAccount, String description,
                            boolean releaseRemainder, String key, String reference, JsonNode externalRefs) {
        if (counterAccount == null) {
            throw new ApiException(422, "ledger_account_not_configured",
                    "A ledger account required for settlement is not configured");
        }
        ObjectNode body = json.createObjectNode();
        body.put("amount", amount.toPlainString());
        body.put("counter_account_id", counterAccount.toString());
        body.put("description", trim(description, 500));
        body.put("release_remainder", releaseRemainder);
        if (reference != null) {
            body.put("reference", trim(reference, 128));
        }
        if (externalRefs != null && !externalRefs.isEmpty()) {
            body.set("external_refs", externalRefs);
        }
        return client.post(LEDGER, "/v1/holds/" + holdId + "/capture", body, key(key));
    }

    public void releaseHold(UUID holdId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", trim(reason == null ? "released" : reason, 255));
        client.post(LEDGER, "/v1/holds/" + holdId + "/release", body, key(key));
    }

    public JsonNode hold(UUID holdId) {
        return client.get(LEDGER, "/v1/holds/" + holdId);
    }

    /** Journal entry created by the latest capture of a hold (from the hold's event history). */
    public UUID captureJournalEntry(UUID holdId) {
        JsonNode page = client.get(LEDGER, "/v1/holds/" + holdId + "/events?limit=50");
        UUID found = null;
        for (JsonNode e : page.path("data")) {
            String type = e.path("event_type").asString("");
            String je = e.path("journal_entry_id").asString(null);
            if (je != null && (type.equals("CAPTURED") || type.equals("PARTIALLY_CAPTURED"))) {
                found = UUID.fromString(je);
                break; // events are returned newest first
            }
        }
        return found;
    }

    /** Posts a balanced journal entry and returns its id. */
    public UUID post(String key, String entryType, String description, String currency, String sourceType,
                     UUID sourceId, List<Leg> legs) {
        return post(key, entryType, description, currency, sourceType, sourceId, null, null, legs);
    }

    /** Posts a balanced journal entry carrying the payment reference and {@link #refs payment references}. */
    public UUID post(String key, String entryType, String description, String currency, String sourceType,
                     UUID sourceId, String reference, JsonNode externalRefs, List<Leg> legs) {
        if (props.ledgerId() == null) {
            throw new ApiException(422, "ledger_not_configured", "meridian.payment.ledger-id is not configured");
        }
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", props.ledgerId().toString());
        body.put("entry_type", entryType);
        body.put("description", trim(description, 500));
        body.put("source_type", sourceType);
        body.put("source_id", sourceId.toString());
        if (reference != null) {
            body.put("reference", trim(reference, 128));
        }
        if (externalRefs != null && !externalRefs.isEmpty()) {
            body.set("external_refs", externalRefs);
        }
        ArrayNode entries = body.putArray("entries");
        for (Leg leg : legs) {
            if (leg.account() == null) {
                throw new ApiException(422, "ledger_account_not_configured",
                        "A ledger account required for this posting is not configured");
            }
            if (leg.amount().signum() == 0) {
                continue;
            }
            ObjectNode e = entries.addObject();
            e.put("ledger_account_id", leg.account().toString());
            e.put("direction", leg.direction());
            e.put("amount", leg.amount().toPlainString());
            e.put("currency", currency);
            if (leg.description() != null) {
                e.put("description", trim(leg.description(), 500));
            }
        }
        return id(client.post(LEDGER, "/v1/journal-entries", body, key(key)));
    }

    public UUID reverse(UUID journalEntryId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", trim(reason, 255));
        return id(client.post(LEDGER, "/v1/journal-entries/" + journalEntryId + "/reverse", body, key(key)));
    }

    public JsonNode balance(UUID ledgerAccountId) {
        return client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance");
    }

    /** Ledger idempotency keys are limited to 128 characters; longer keys are hashed. */
    public static String key(String raw) {
        if (raw.length() <= 128) {
            return raw;
        }
        try {
            return "h:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static UUID id(JsonNode node) {
        String id = node.path("id").asString(null);
        if (id == null) {
            throw new ApiException(502, "upstream_error", "ledger-service returned no id");
        }
        return UUID.fromString(id);
    }
}
