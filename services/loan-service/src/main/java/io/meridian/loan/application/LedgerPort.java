package io.meridian.loan.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Everything loan-service needs from ledger-service. Every write carries a deterministic idempotency key derived from
 * the business operation, so a retried action replays the original posting instead of posting twice.
 */
public interface LedgerPort {

    /** One posting line. {@code direction} is DEBIT or CREDIT. Zero lines are dropped. */
    record Leg(UUID account, String direction, BigDecimal amount, String description) {
        public static Leg debit(UUID account, BigDecimal amount, String description) {
            return new Leg(account, "DEBIT", amount, description);
        }

        public static Leg credit(UUID account, BigDecimal amount, String description) {
            return new Leg(account, "CREDIT", amount, description);
        }
    }

    record Posting(String key, String entryType, String description, String currency, String sourceType,
                   UUID sourceId, LocalDate valueDate, List<Leg> legs) {

        /** True when every leg is zero (nothing to post). */
        public boolean empty() {
            return legs.stream().allMatch(l -> l.amount() == null || l.amount().signum() == 0);
        }
    }

    /** Builder for postings with many legs. */
    final class Legs {
        private final List<Leg> legs = new ArrayList<>();

        public Legs debit(UUID account, BigDecimal amount, String description) {
            if (amount != null && amount.signum() > 0) {
                legs.add(Leg.debit(account, amount, description));
            }
            return this;
        }

        public Legs credit(UUID account, BigDecimal amount, String description) {
            if (amount != null && amount.signum() > 0) {
                legs.add(Leg.credit(account, amount, description));
            }
            return this;
        }

        public List<Leg> list() {
            return List.copyOf(legs);
        }
    }

    /** Posts a balanced journal entry; returns its id. */
    UUID post(Posting posting);

    /** Reverses a posted journal entry with a mirrored entry; returns the reversal's id. */
    UUID reverse(UUID journalEntryId, String reason, String key);

    /** Opens a debit-normal asset account (loan or credit-line receivable) and returns its id. */
    UUID openReceivable(String key, String code, String name, String currency, String ownerType, UUID ownerId);

    /** Closes a ledger account (balance must be zero). */
    void close(UUID ledgerAccountId, String reason, String key);

    JsonNode balance(UUID ledgerAccountId);

    static BigDecimal available(JsonNode balance) {
        return new BigDecimal(balance.path("available_balance").asString("0"));
    }

    /**
     * Ledger idempotency key {@code <operation>:<entity id>[:<discriminator>]}; hashed when it would exceed the
     * ledger's 128-character limit.
     */
    static String key(String operation, UUID entityId, String discriminator) {
        String raw = operation + ":" + entityId + (discriminator == null ? "" : ":" + discriminator);
        if (raw.length() <= 128) {
            return raw;
        }
        try {
            String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
            return (operation.length() > 40 ? operation.substring(0, 40) : operation) + ":" + h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
