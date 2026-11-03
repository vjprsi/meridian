package io.meridian.commission.application;

import io.meridian.commission.infrastructure.Downstream;
import io.meridian.commission.infrastructure.Rows;
import io.meridian.common.time.Clocks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-PRICE-1 (PE-11): joining commission. Rewards the business user who registered a new customer at three fire-once
 * milestones — REGISTER (first wallet activation), FIRST_CASH_IN and FIRST_DEBIT. Each milestone fires at most once per
 * customer wallet: a per-wallet advisory lock serialises concurrent events and a {@code customer_milestone_state} row
 * records the first occurrence (so a later event, or a redelivery, never pays twice). The reward is a commission
 * earning credited to the registering agent's commission wallet, paid out by the normal payout runs.
 */
@Service
public class MilestoneService {
    private static final Logger log = LoggerFactory.getLogger(MilestoneService.class);
    static final String STATE = "customer_milestone_state";
    static final String EARNING = "commission_earning";

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final TransactionTemplate tx;

    public MilestoneService(Rows rows, Downstream downstream, Events events, TransactionTemplate tx) {
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.tx = tx;
    }

    /** The three milestones and their state columns / amount fields. */
    public enum Milestone {
        REGISTER("registered_at", "register_earning_id", "register_amount"),
        FIRST_CASH_IN("first_cash_in_at", "first_cash_in_earning_id", "first_cash_in_amount"),
        FIRST_DEBIT("first_debit_at", "first_debit_earning_id", "first_debit_amount");

        final String atColumn;
        final String earningColumn;
        final String amountField;

        Milestone(String atColumn, String earningColumn, String amountField) {
            this.atColumn = atColumn;
            this.earningColumn = earningColumn;
            this.amountField = amountField;
        }
    }

    /**
     * Fires a milestone for a customer wallet (idempotent / fire-once). When an active joining-commission configuration
     * exists for the customer and the milestone amount is positive, an earning is created for the registering agent's
     * commission wallet; either way the milestone is marked as seen so it never fires again.
     */
    public void fire(Milestone milestone, UUID customerWalletId, String currency, UUID gradeId, UUID categoryId,
                     UUID registeringAgentId, UUID walletTransactionId, String txnType, OffsetDateTime at) {
        if (customerWalletId == null || currency == null || registeringAgentId == null) {
            return;
        }
        tx.executeWithoutResult(s -> {
            rows.advisoryLock("milestone:" + customerWalletId);
            ObjectNode state = ensureState(customerWalletId, registeringAgentId);
            if (state.hasNonNull(milestone.atColumn)) {
                return; // already fired (fire-once)
            }
            ObjectNode jc = selectJoining(currency, gradeId, categoryId);
            UUID earningId = null;
            if (jc != null) {
                BigDecimal amount = Rows.decOr0(jc, milestone.amountField);
                if (amount.signum() > 0) {
                    earningId = payout(jc, milestone, amount, currency, registeringAgentId, walletTransactionId, txnType, at);
                }
            }
            ObjectNode v = rows.object();
            v.put(milestone.atColumn, Clocks.now().toString());
            if (earningId != null) {
                v.put(milestone.earningColumn, earningId.toString());
            }
            if (jc != null && !state.hasNonNull("joining_commission_id")) {
                v.put("joining_commission_id", Rows.str(jc, "id"));
            }
            rows.update(STATE, Rows.uuid(state, "id"), v);
        });
    }

    private UUID payout(ObjectNode jc, Milestone milestone, BigDecimal amount, String currency, UUID agentId,
                        UUID walletTransactionId, String txnType, OffsetDateTime at) {
        UUID commissionWallet;
        try {
            commissionWallet = Rows.uuid(downstream.agent(agentId), "commission_wallet_id");
        } catch (RuntimeException e) {
            log.warn("joining commission {}: cannot resolve registering agent {}: {}", milestone, agentId, e.getMessage());
            return null;
        }
        if (commissionWallet == null) {
            return null;
        }
        BigDecimal rate = Rows.dec(jc, "tax_withholding_rate");
        BigDecimal tax = rate == null ? BigDecimal.ZERO.setScale(2) : amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal net = amount.subtract(tax);
        ObjectNode v = rows.object();
        v.put("beneficiary", Rows.str(jc, "beneficiary") == null ? "AGENT" : Rows.str(jc, "beneficiary"));
        v.put("beneficiary_agent_id", agentId.toString());
        v.put("beneficiary_wallet_id", commissionWallet.toString());
        if (walletTransactionId != null) {
            v.put("wallet_transaction_id", walletTransactionId.toString());
        }
        if (txnType != null) {
            v.put("txn_type", txnType);
        }
        v.put("base_amount", amount.toPlainString());
        v.put("commission_amount", amount.toPlainString());
        v.put("tax_amount", tax.toPlainString());
        v.put("net_amount", net.toPlainString());
        v.put("currency", currency);
        v.put("payout_frequency", "DAILY");
        v.put("is_clawback", false);
        v.put("joining_commission_id", Rows.str(jc, "id"));
        v.put("joining_milestone", milestone.name());
        v.put("earned_at", (at == null ? Clocks.now() : at).toString());
        v.put("payout_due_on", rows.today().toString());
        ObjectNode row = rows.insert(EARNING, v);
        events.publish("wallets.commission_earning.accrued", EARNING, row);
        return Rows.uuid(row, "id");
    }

    private ObjectNode ensureState(UUID customerWalletId, UUID registeringAgentId) {
        ObjectNode existing = rows.queryOne("select row_to_json(t)::text from customer_milestone_state t where customer_wallet_id = ?",
                customerWalletId).orElse(null);
        if (existing != null) {
            return existing;
        }
        ObjectNode v = rows.object();
        v.put("customer_wallet_id", customerWalletId.toString());
        v.put("registering_agent_id", registeringAgentId.toString());
        return rows.insert(STATE, v);
    }

    /** The most specific active joining-commission configuration for a customer (grade / category win over any). */
    private ObjectNode selectJoining(String currency, UUID gradeId, UUID categoryId) {
        java.time.LocalDate on = rows.today();
        return rows.queryOne("""
                select row_to_json(j)::text from joining_commission j
                where j.status = 'ACTIVE' and j.currency = ? and j.valid_from <= ?
                  and (j.valid_to is null or j.valid_to >= ?)
                  and (j.applies_to_grade_id is null or j.applies_to_grade_id = ?)
                  and (j.applies_to_category_id is null or j.applies_to_category_id = ?)
                order by (j.applies_to_grade_id is not null) desc, (j.applies_to_category_id is not null) desc,
                         j.valid_from desc limit 1""", currency, on, on, gradeId, categoryId).orElse(null);
    }
}
