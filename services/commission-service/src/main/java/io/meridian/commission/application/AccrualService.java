package io.meridian.commission.application;

import io.meridian.commission.domain.CommissionEngine;
import io.meridian.commission.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accrues commissions for completed wallet transactions (and claws them back for reversed ones). Idempotent per
 * transaction: a transaction already accrued returns its earnings; a clawback happens once (unique per original
 * earning). Driven by the explicit API and by the wallets.wallet_transaction.completed / reversed events.
 */
@Service
public class AccrualService {
    static final String TABLE = "commission_earning";

    private final Rows rows;
    private final Hierarchy hierarchy;
    private final PlanService plans;
    private final Events events;
    private final TransactionTemplate tx;

    public AccrualService(Rows rows, Hierarchy hierarchy, PlanService plans, Events events, TransactionTemplate tx) {
        this.rows = rows;
        this.hierarchy = hierarchy;
        this.plans = plans;
        this.events = events;
        this.tx = tx;
    }

    /** One completed (or reversed) wallet transaction, as received from the API or an event payload. */
    public record TxnEvent(UUID walletTransactionId, String txnType, String status, BigDecimal amount, BigDecimal fee,
                           String currency, String channel, Integer kycTier, UUID agentId, UUID merchantId, UUID billerId,
                           OffsetDateTime completedAt) {

        public static TxnEvent from(JsonNode n, String idField) {
            BigDecimal amount = Rows.dec(n, "amount");
            if (Rows.uuid(n, idField) == null || Rows.str(n, "txn_type") == null || amount == null
                    || Rows.str(n, "currency") == null) {
                throw ApiException.unprocessable("invalid_request", "wallet_transaction_id, txn_type, amount and currency are required");
            }
            return new TxnEvent(Rows.uuid(n, idField), Rows.str(n, "txn_type"),
                    Rows.str(n, "status") == null ? "COMPLETED" : Rows.str(n, "status"), amount, Rows.dec(n, "fee_amount"),
                    Rows.str(n, "currency"), Rows.str(n, "channel"), Rows.integer(n, "kyc_tier"), Rows.uuid(n, "agent_id"),
                    Rows.uuid(n, "merchant_id"), Rows.uuid(n, "biller_id"), Rows.ts(n, "completed_at"));
        }
    }

    public ObjectNode accrue(TxnEvent t) {
        if (!Set.of("COMPLETED", "REVERSED").contains(t.status())) {
            throw ApiException.unprocessable("invalid_status", "Only COMPLETED or REVERSED transactions accrue commission");
        }
        if ("REVERSED".equals(t.status())) {
            return result(t, tx.execute(s -> clawback(t)));
        }
        List<ObjectNode> existing = earnings(t.walletTransactionId(), false);
        if (!existing.isEmpty()) {
            return result(t, existing);
        }
        // resolve outside the transaction (HTTP), then insert under a per-transaction lock
        Hierarchy.Context ctx = hierarchy.resolve(t.agentId());
        // the transaction's own date when known, else today's business date
        LocalDate earnedOn = t.completedAt() == null ? rows.today()
                : t.completedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        ObjectNode plan = selectPlan(t, ctx, earnedOn);
        if (plan == null) {
            return result(t, List.of());
        }
        CommissionEngine.Txn txn = new CommissionEngine.Txn(t.txnType(), t.channel(), t.kycTier(), ctx.agentLevel(),
                ctx.territoryId(), t.billerId(), t.amount(), t.fee());
        List<CommissionEngine.Selected> selected = CommissionEngine.evaluate(plans.rules(Rows.uuid(plan, "id")), txn);
        return result(t, tx.execute(s -> {
            rows.advisoryLock("accrual:" + t.walletTransactionId());
            List<ObjectNode> again = earnings(t.walletTransactionId(), false);
            if (!again.isEmpty()) {
                return again;
            }
            for (CommissionEngine.Selected sel : selected) {
                Hierarchy.Beneficiary b = hierarchy.beneficiary(sel.rule(), ctx, t.merchantId());
                if (b == null) {
                    continue;
                }
                ObjectNode v = rows.object();
                v.put("commission_plan_id", Rows.str(plan, "id"));
                v.put("commission_rule_id", sel.rule().id().toString());
                v.put("wallet_transaction_id", t.walletTransactionId().toString());
                v.put("txn_type", t.txnType());
                v.put("beneficiary", sel.rule().beneficiary());
                if (b.agentId() != null) {
                    v.put("beneficiary_agent_id", b.agentId().toString());
                }
                if (b.merchantId() != null) {
                    v.put("beneficiary_merchant_id", b.merchantId().toString());
                }
                v.put("beneficiary_wallet_id", b.walletId().toString());
                v.put("base_amount", sel.amounts().base().toPlainString());
                v.put("commission_amount", sel.amounts().commission().toPlainString());
                v.put("tax_amount", sel.amounts().tax().toPlainString());
                v.put("net_amount", sel.amounts().net().toPlainString());
                v.put("currency", t.currency());
                v.put("payout_frequency", Rows.str(plan, "payout_frequency"));
                v.put("is_clawback", false);
                v.put("earned_at", (t.completedAt() == null ? io.meridian.common.time.Clocks.now() : t.completedAt()).toString());
                v.put("payout_due_on", CommissionEngine.payoutDueOn(Rows.str(plan, "payout_frequency"), earnedOn).toString());
                ObjectNode row = rows.insert(TABLE, v);
                events.publish("wallets.commission_earning.accrued", TABLE, row);
            }
            return earnings(t.walletTransactionId(), false);
        }));
    }

    /** Negative rows for every original earning of the transaction that has not been clawed back yet. */
    private List<ObjectNode> clawback(TxnEvent t) {
        rows.advisoryLock("accrual:" + t.walletTransactionId());
        for (ObjectNode e : rows.query("""
                select row_to_json(e)::text from commission_earning e
                where e.wallet_transaction_id = ? and not e.is_clawback
                  and not exists (select 1 from commission_earning c where c.clawback_of_id = e.id)
                order by e.id""", t.walletTransactionId())) {
            ObjectNode v = rows.object();
            for (String f : List.of("commission_plan_id", "commission_rule_id", "wallet_transaction_id", "txn_type",
                    "beneficiary", "beneficiary_agent_id", "beneficiary_merchant_id", "beneficiary_wallet_id",
                    "base_amount", "currency", "payout_frequency")) {
                if (e.hasNonNull(f)) {
                    v.set(f, e.get(f));
                }
            }
            v.put("commission_amount", Rows.dec(e, "commission_amount").negate().toPlainString());
            v.put("tax_amount", Rows.dec(e, "tax_amount").negate().toPlainString());
            v.put("net_amount", Rows.dec(e, "net_amount").negate().toPlainString());
            v.put("is_clawback", true);
            v.put("clawback_of_id", Rows.str(e, "id"));
            v.put("earned_at", rows.scalar("select now()::text", String.class).orElseThrow());
            v.put("payout_due_on", rows.today().toString());
            ObjectNode row = rows.insert(TABLE, v);
            events.publish("wallets.commission_earning.accrued", TABLE, row);
        }
        return earnings(t.walletTransactionId(), true);
    }

    /**
     * The agent's own ACTIVE plan in the currency, else the ACTIVE plan that applies to this kind of transaction
     * (MERCHANT / BILLER / AGENT, then ALL) valid on the earning date; newest valid_from wins.
     */
    ObjectNode selectPlan(TxnEvent t, Hierarchy.Context ctx, LocalDate on) {
        UUID own = ctx.commissionPlanId();
        if (own != null) {
            var p = rows.queryOne("""
                    select row_to_json(p)::text from commission_plan p where p.id = ? and p.status = 'ACTIVE' and p.currency = ?
                      and p.valid_from <= ? and (p.valid_to is null or p.valid_to >= ?)""", own, t.currency(), on, on);
            if (p.isPresent()) {
                return p.get();
            }
        }
        String kind = t.merchantId() != null ? "MERCHANT" : t.billerId() != null ? "BILLER" : "AGENT";
        return rows.queryOne("""
                select row_to_json(p)::text from commission_plan p
                where p.status = 'ACTIVE' and p.currency = ? and p.applies_to in (?, 'ALL')
                  and p.valid_from <= ? and (p.valid_to is null or p.valid_to >= ?)
                order by (p.applies_to = ?) desc, p.valid_from desc, p.version desc limit 1""",
                t.currency(), kind, on, on, kind).orElse(null);
    }

    private List<ObjectNode> earnings(UUID txn, boolean all) {
        return rows.query("select row_to_json(e)::text from commission_earning e where e.wallet_transaction_id = ?"
                + (all ? "" : " and not e.is_clawback") + " order by e.is_clawback, e.id", txn);
    }

    private ObjectNode result(TxnEvent t, List<ObjectNode> earnings) {
        ObjectNode out = rows.object();
        out.put("wallet_transaction_id", t.walletTransactionId().toString());
        out.put("status", t.status());
        ArrayNode arr = out.putArray("earnings");
        earnings.forEach(arr::add);
        BigDecimal total = BigDecimal.ZERO;
        for (ObjectNode e : earnings) {
            total = total.add(Rows.dec(e, "commission_amount"));
        }
        out.put("total_commission", total.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString());
        return out;
    }

    /** Earned / clawed back / unpaid totals per beneficiary wallet and currency. */
    public ObjectNode summary(UUID agentId, UUID walletId, LocalDate from, LocalDate to) {
        return rows.queryOne("""
                select json_build_object('groups', coalesce(json_agg(g order by g.beneficiary_wallet_id, g.currency), '[]'::json))::text
                from (
                  select e.beneficiary_wallet_id, e.currency,
                         coalesce(sum(e.net_amount) filter (where not e.is_clawback), 0)::text as earned,
                         (-coalesce(sum(e.net_amount) filter (where e.is_clawback), 0))::text as clawed_back,
                         sum(e.net_amount)::text as net,
                         coalesce(sum(e.net_amount) filter (where not exists (
                           select 1 from commission_payout_line l where l.payout_status = 'PAID'
                             and l.earning_ids @> jsonb_build_array(e.id::text))), 0)::text as unpaid,
                         count(*) as earning_count
                  from commission_earning e
                  where (?::uuid is null or e.beneficiary_agent_id = ?) and (?::uuid is null or e.beneficiary_wallet_id = ?)
                    and (?::date is null or e.earned_at >= ?::date) and (?::date is null or e.earned_at < (?::date + 1))
                  group by e.beneficiary_wallet_id, e.currency) g""",
                agentId, agentId, walletId, walletId, from, from, to, to).orElseThrow();
    }
}
