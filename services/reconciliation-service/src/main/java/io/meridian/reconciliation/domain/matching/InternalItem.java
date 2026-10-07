package io.meridian.reconciliation.domain.matching;

import io.meridian.reconciliation.domain.StatementLine.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal side of a match: a ledger entry (or a payment / clearing record) with whatever references it carries.
 * {@code reference} is the generic reference copied from the journal entry (end-to-end id, bank or scheme
 * reference, invoice number); {@code batchRef} groups the items of one batch / bulk payment or card settlement;
 * {@code bankRef} (clearing-system / account servicer reference) and {@code schemeRef} (card scheme or rail reference)
 * come from the ledger entry's payment references and match a statement line's bank reference;
 * {@code counterpartyAccount} is the ledger's keyed pseudonym of the account ({@code h1:...}), compared with the
 * statement account pseudonymised the same way.
 */
public record InternalItem(UUID id, LocalDate valueDate, Direction direction, BigDecimal amount, String currency,
                           String reference, String endToEndId, String instructionId, String uetr, String batchRef,
                           String counterpartyName, String counterpartyAccount, String narrative, boolean reversal,
                           String bankRef, String schemeRef) {

    public static InternalItem of(UUID id, LocalDate valueDate, Direction direction, BigDecimal amount, String currency,
                                  String reference, String narrative) {
        return new InternalItem(id, valueDate, direction, amount, currency, reference, null, null, null, null, null,
                null, narrative, false, null, null);
    }

    public static Builder builder(UUID id, LocalDate valueDate, Direction direction, BigDecimal amount, String currency) {
        return new Builder(id, valueDate, direction, amount, currency);
    }

    public static final class Builder {
        private final UUID id;
        private final LocalDate valueDate;
        private final Direction direction;
        private final BigDecimal amount;
        private final String currency;
        private String reference;
        private String endToEndId;
        private String instructionId;
        private String uetr;
        private String batchRef;
        private String counterpartyName;
        private String counterpartyAccount;
        private String narrative;
        private boolean reversal;
        private String bankRef;
        private String schemeRef;

        private Builder(UUID id, LocalDate valueDate, Direction direction, BigDecimal amount, String currency) {
            this.id = id;
            this.valueDate = valueDate;
            this.direction = direction;
            this.amount = amount;
            this.currency = currency;
        }

        public Builder reference(String v) {
            reference = v;
            return this;
        }

        public Builder endToEndId(String v) {
            endToEndId = v;
            return this;
        }

        public Builder instructionId(String v) {
            instructionId = v;
            return this;
        }

        public Builder uetr(String v) {
            uetr = v;
            return this;
        }

        public Builder batchRef(String v) {
            batchRef = v;
            return this;
        }

        public Builder counterparty(String name, String account) {
            counterpartyName = name;
            counterpartyAccount = account;
            return this;
        }

        public Builder narrative(String v) {
            narrative = v;
            return this;
        }

        public Builder reversal(boolean v) {
            reversal = v;
            return this;
        }

        public Builder bankRef(String v) {
            bankRef = v;
            return this;
        }

        public Builder schemeRef(String v) {
            schemeRef = v;
            return this;
        }

        public InternalItem build() {
            return new InternalItem(id, valueDate, direction, amount, currency, reference, endToEndId, instructionId,
                    uetr, batchRef, counterpartyName, counterpartyAccount, narrative, reversal, bankRef, schemeRef);
        }
    }
}
