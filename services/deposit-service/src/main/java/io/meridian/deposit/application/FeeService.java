package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.bool;
import static io.meridian.deposit.infrastructure.Rows.decOr0;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.FeeCalculator;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fee charging and refunds. A charge posts debit customer (fee + tax), credit fee income (fee) and credit tax
 * payable (tax); a full refund reverses that journal entry in the ledger, a partial refund posts the mirrored
 * legs for the refunded share (tax pro rata). Fee rows are append-only: refunds are new rows with negative amounts.
 */
@Service
public class FeeService {
    private static final Set<String> CHARGEABLE = Set.of("ACTIVE", "BLOCKED_CREDIT", "INACTIVE", "DORMANT", "CLOSING",
            "FROZEN", "BLOCKED_DEBIT", "BLOCKED");

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final DepositProperties props;

    public FeeService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events, DepositProperties props) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
    }

    /** Manual fee: amount from the request, else computed from the product's fee definition. */
    @Transactional
    public ObjectNode charge(UUID accountId, String feeCode, BigDecimal amount, String reason, String clientKey) {
        ObjectNode acc = accounts.lock(accountId);
        return chargeLocked(acc, feeCode, amount, null, "MANUAL", null, reason, clientKey);
    }

    ObjectNode chargeLocked(ObjectNode acc, String feeCode, BigDecimal amount, BigDecimal base, String triggerType,
                            UUID triggerRef, String reason, String keySeed) {
        UUID accountId = uuid(acc, "id");
        UUID ledgerAccount = accounts.requireLedger(acc);
        String status = str(acc, "status");
        if (!CHARGEABLE.contains(status)) {
            throw ApiException.conflict("account_not_chargeable", "Fees cannot be charged on an account in status " + status);
        }
        String ccy = str(acc, "currency");
        Optional<FeeCalculator.FeeDefinition> def = ProductSnapshot.of(acc).fee(feeCode);
        FeeCalculator.Charge charge;
        if (amount != null) {
            if (amount.signum() <= 0) {
                throw ApiException.unprocessable("invalid_amount", "amount must be positive");
            }
            if (!Currencies.fitsMinorUnit(amount, ccy)) {
                throw ApiException.unprocessable("invalid_amount", "amount has more decimals than " + ccy + " allows");
            }
            charge = FeeCalculator.withTax(amount, def.map(FeeCalculator.FeeDefinition::taxRate).orElse(BigDecimal.ZERO), ccy);
        } else {
            FeeCalculator.FeeDefinition d = def.orElseThrow(() -> ApiException.unprocessable("fee_not_defined",
                    "Fee " + feeCode + " is not defined for this product; give an explicit amount"));
            charge = FeeCalculator.compute(d, base, ccy);
        }
        if (charge.amount().signum() == 0) {
            throw ApiException.unprocessable("zero_fee", "The computed fee is zero");
        }
        String key = LedgerPort.key("fee", accountId, feeCode + ":" + keySeed);
        String narrative = reason == null ? "Fee " + feeCode : reason;
        UUID je = ledger.post(new LedgerPort.Posting(key, "FEE", narrative, ccy, "FEE_CHARGE", accountId, null, List.of(
                LedgerPort.Leg.debit(ledgerAccount, charge.total(), narrative),
                LedgerPort.Leg.credit(props.gl().feeIncome(), charge.amount(), "Fee income " + feeCode),
                LedgerPort.Leg.credit(props.gl().taxPayable(), charge.tax(), "Tax on fee " + feeCode))));
        ObjectNode row = rows.object();
        row.put("deposit_account_id", accountId.toString());
        row.put("fee_code", feeCode);
        row.put("trigger_type", triggerType);
        if (triggerRef != null) {
            row.put("trigger_ref_id", triggerRef.toString());
        }
        if (base != null) {
            row.put("base_amount", base.toPlainString());
        }
        row.put("amount", charge.amount().toPlainString());
        row.put("tax_amount", charge.tax().toPlainString());
        row.put("currency", ccy);
        row.put("journal_entry_id", je.toString());
        row.put("charged_on", accounts.today().toString());
        ObjectNode fee = rows.insert("fee_charge", row);
        events.publish("accounts.fee.charged", "fee_charge", fee);
        return fee;
    }

    @Transactional
    public ObjectNode refund(UUID feeId, BigDecimal amount, String reason, String clientKey) {
        ObjectNode original = rows.lock("fee_charge", feeId);
        if (str(original, "refund_of_id") != null || decOr0(original, "amount").signum() <= 0) {
            throw ApiException.unprocessable("not_a_charge", "Fee row " + feeId + " is a refund and cannot be refunded");
        }
        if (bool(original, "waived")) {
            throw ApiException.unprocessable("fee_waived", "Fee " + feeId + " was waived; nothing to refund");
        }
        ObjectNode acc = accounts.lock(uuid(original, "deposit_account_id"));
        UUID ledgerAccount = accounts.requireLedger(acc);
        accounts.requireNotClosed(acc);
        String ccy = str(original, "currency");
        Optional<FeeCalculator.FeeDefinition> def = ProductSnapshot.of(acc).fee(str(original, "fee_code"));
        if (def.isPresent() && !def.get().refundable()) {
            throw ApiException.unprocessable("fee_not_refundable", "Fee " + str(original, "fee_code") + " is not refundable");
        }
        BigDecimal charged = decOr0(original, "amount");
        BigDecimal refunded = rows.scalar("select coalesce(-sum(amount), 0) from fee_charge where refund_of_id = ?",
                BigDecimal.class, feeId).orElse(BigDecimal.ZERO);
        BigDecimal remaining = charged.subtract(refunded);
        if (remaining.signum() <= 0) {
            throw ApiException.conflict("already_refunded", "Fee " + feeId + " was already refunded in full");
        }
        BigDecimal refund = amount == null ? remaining : amount;
        if (refund.signum() <= 0 || refund.compareTo(remaining) > 0) {
            throw ApiException.unprocessable("invalid_refund_amount", "Refund must be positive and at most the "
                    + "refundable remainder " + remaining.toPlainString());
        }
        if (!Currencies.fitsMinorUnit(refund, ccy)) {
            throw ApiException.unprocessable("invalid_amount", "amount has more decimals than " + ccy + " allows");
        }
        BigDecimal taxRefunded = rows.scalar("select coalesce(-sum(tax_amount), 0) from fee_charge where refund_of_id = ?",
                BigDecimal.class, feeId).orElse(BigDecimal.ZERO);
        BigDecimal taxRefund = FeeCalculator.refundTax(charged, decOr0(original, "tax_amount"), refund, refunded,
                taxRefunded, ccy);
        String key = LedgerPort.key("fee-refund", feeId, clientKey);
        UUID je;
        UUID originalJe = uuid(original, "journal_entry_id");
        if (refund.compareTo(charged) == 0 && refunded.signum() == 0 && originalJe != null) {
            je = ledger.reverse(originalJe, reason, key);
        } else {
            List<LedgerPort.Leg> legs = new ArrayList<>();
            legs.add(LedgerPort.Leg.debit(props.gl().feeIncome(), refund, "Fee refund " + str(original, "fee_code")));
            legs.add(LedgerPort.Leg.debit(props.gl().taxPayable(), taxRefund, "Tax refund"));
            legs.add(LedgerPort.Leg.credit(ledgerAccount, refund.add(taxRefund), reason));
            je = ledger.post(new LedgerPort.Posting(key, "FEE", "Refund of fee " + feeId + ": " + reason, ccy,
                    "FEE_REFUND", feeId, null, legs));
        }
        ObjectNode row = rows.object();
        row.put("deposit_account_id", str(original, "deposit_account_id"));
        row.put("fee_code", str(original, "fee_code"));
        if (str(original, "fee_definition_id") != null) {
            row.put("fee_definition_id", str(original, "fee_definition_id"));
        }
        row.put("trigger_type", "REFUND");
        row.put("trigger_ref_id", feeId.toString());
        row.put("amount", refund.negate().toPlainString());
        row.put("tax_amount", taxRefund.negate().toPlainString());
        row.put("currency", ccy);
        row.put("refund_of_id", feeId.toString());
        row.put("journal_entry_id", je.toString());
        row.put("charged_on", accounts.today().toString());
        ObjectNode fee = rows.insert("fee_charge", row);
        events.publish("accounts.fee.refunded", "fee_charge", fee);
        return fee;
    }
}
