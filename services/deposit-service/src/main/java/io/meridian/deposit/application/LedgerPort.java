package io.meridian.deposit.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Everything deposit-service needs from ledger-service. All money movement goes through here; every write carries
 * a deterministic idempotency key so a retried business action replays the original ledger posting instead of
 * posting twice.
 */
public interface LedgerPort {

    /** One posting line. {@code direction} is DEBIT or CREDIT. */
    record Leg(UUID account, String direction, BigDecimal amount, String description) {
        public static Leg debit(UUID account, BigDecimal amount, String description) {
            return new Leg(account, "DEBIT", amount, description);
        }

        public static Leg credit(UUID account, BigDecimal amount, String description) {
            return new Leg(account, "CREDIT", amount, description);
        }
    }

    record Posting(String key, String entryType, String description, String currency, String sourceType,
                   UUID sourceId, LocalDate valueDate, List<Leg> legs) {}

    /** Posts a balanced journal entry; returns its id. */
    UUID post(Posting posting);

    /** Reverses a posted journal entry with a mirrored entry; returns the reversal's id. */
    UUID reverse(UUID journalEntryId, String reason, String key);

    /** Opens a customer liability account (credit-normal) and returns its id. */
    UUID openAccount(String key, String code, String name, String currency, BigDecimal overdraftLimit, UUID parentId,
                     String ownerType, UUID ownerId);

    JsonNode balance(UUID ledgerAccountId);

    /** Posted balance as booked at {@code at}. */
    BigDecimal balanceAt(UUID ledgerAccountId, OffsetDateTime at);

    JsonNode entries(UUID ledgerAccountId, OffsetDateTime from, OffsetDateTime to, Integer limit, String cursor);

    UUID placeHold(String key, UUID ledgerAccountId, BigDecimal amount, String currency, String holdType,
                   String description, String sourceType, UUID sourceId);

    void releaseHold(UUID holdId, String reason, String key);

    /** Sets the ledger posting restriction: ACTIVE, FROZEN, FROZEN_DEBIT, FROZEN_CREDIT or CLOSED. */
    void restrict(UUID ledgerAccountId, String fromStatus, String toStatus, String reason, String key);

    void setOverdraftLimit(UUID ledgerAccountId, BigDecimal limit);

    /** Posted balance from a balance document. */
    static BigDecimal posted(JsonNode balance) {
        return new BigDecimal(balance.path("posted_balance").asString("0"));
    }

    static BigDecimal available(JsonNode balance) {
        return new BigDecimal(balance.path("available_balance").asString("0"));
    }

    /**
     * Ledger idempotency key {@code <operation>:<entity id>:<client key>}; hashed when it would exceed the
     * ledger's 128-character limit.
     */
    static String key(String operation, UUID entityId, String clientKey) {
        String raw = operation + ":" + entityId + (clientKey == null ? "" : ":" + clientKey);
        if (raw.length() <= 128) {
            return raw;
        }
        try {
            String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(clientKey.getBytes(StandardCharsets.UTF_8)));
            return (operation + ":" + entityId + ":" + h).substring(0, Math.min(128, operation.length() + 38 + 64));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
