package io.meridian.reconciliation.domain.matching;

import io.meridian.reconciliation.domain.StatementLine.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * External side of a match (statement / settlement line) as the engine sees it. The direction is already from the
 * internal account's point of view (sources that report the counterparty's view are inverted by the caller).
 * {@code remittance} is the remittance / narrative text; {@code charges} and {@code originalAmount} /
 * {@code originalCurrency} are stated by some formats (camt Chrgs, InstdAmt; settlement fee / gross columns).
 */
public record ExternalLine(UUID id, LocalDate valueDate, Direction direction, BigDecimal amount, String currency,
                           String bankRef, String endToEndId, String instructionId, String uetr, String batchRef,
                           String counterpartyName, String counterpartyAccount, String remittance, boolean reversal,
                           BigDecimal charges, BigDecimal originalAmount, String originalCurrency) {

    public static Builder builder(UUID id, LocalDate valueDate, Direction direction, BigDecimal amount, String currency) {
        return new Builder(id, valueDate, direction, amount, currency);
    }

    /** Key value by name: uetr, end_to_end_id, instruction_id, bank_ref, batch_ref. */
    public String key(String name) {
        return switch (name) {
            case "uetr" -> uetr;
            case "end_to_end_id" -> endToEndId;
            case "instruction_id" -> instructionId;
            case "bank_ref" -> bankRef;
            case "batch_ref" -> batchRef;
            default -> null;
        };
    }

    public static final class Builder {
        private final UUID id;
        private final LocalDate valueDate;
        private final Direction direction;
        private final BigDecimal amount;
        private final String currency;
        private String bankRef;
        private String endToEndId;
        private String instructionId;
        private String uetr;
        private String batchRef;
        private String counterpartyName;
        private String counterpartyAccount;
        private String remittance;
        private boolean reversal;
        private BigDecimal charges;
        private BigDecimal originalAmount;
        private String originalCurrency;

        private Builder(UUID id, LocalDate valueDate, Direction direction, BigDecimal amount, String currency) {
            this.id = id;
            this.valueDate = valueDate;
            this.direction = direction;
            this.amount = amount;
            this.currency = currency;
        }

        public Builder bankRef(String v) {
            bankRef = v;
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

        public Builder remittance(String v) {
            remittance = v;
            return this;
        }

        public Builder reversal(boolean v) {
            reversal = v;
            return this;
        }

        public Builder charges(BigDecimal v) {
            charges = v;
            return this;
        }

        public Builder original(BigDecimal value, String ccy) {
            originalAmount = value;
            originalCurrency = ccy;
            return this;
        }

        public ExternalLine build() {
            return new ExternalLine(id, valueDate, direction, amount, currency, bankRef, endToEndId, instructionId,
                    uetr, batchRef, counterpartyName, counterpartyAccount, remittance, reversal, charges,
                    originalAmount, originalCurrency);
        }
    }
}
