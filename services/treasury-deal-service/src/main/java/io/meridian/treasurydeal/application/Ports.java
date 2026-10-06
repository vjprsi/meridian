package io.meridian.treasurydeal.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound ports of treasury-deal-service (implemented in infrastructure over ServiceClient). */
public final class Ports {
    private Ports() {}

    /** One posting line: a ledger account role in a currency (resolved from configuration). */
    public record Line(String currency, String role, String direction, BigDecimal amount, String description) {
        public static Line debit(String currency, String role, BigDecimal amount) {
            return new Line(currency, role, "DEBIT", amount, null);
        }

        public static Line credit(String currency, String role, BigDecimal amount) {
            return new Line(currency, role, "CREDIT", amount, null);
        }
    }

    /** A balanced journal entry for ledger-service; {@code key} makes the posting idempotent. */
    public record Posting(String key, String entryType, String description, String sourceType, UUID sourceId,
                          LocalDate valueDate, String reference, List<Line> lines) {}

    public interface Ledger {
        /** Posts the entry (zero lines skipped); returns the journal entry id. */
        UUID post(Posting posting);
    }

    /** Outgoing payment to a counterparty SSI. */
    public record PaymentOrder(String key, String creditorName, String creditorAccount, String creditorBic,
                               String intermediaryBic, String countryCode, BigDecimal amount, String currency,
                               LocalDate executionDate, String endToEndId, String reference, UUID debtorLedgerAccountId) {}

    public interface Payments {
        UUID create(PaymentOrder order);
    }

    public interface MarketData {
        /** Latest rate (quote units per base unit) of the given type, if the reference data has one. */
        Optional<BigDecimal> rate(String base, String quote, String type);
    }
}
