package io.meridian.commission.application;

import io.meridian.commission.config.CommissionProperties;
import io.meridian.commission.domain.CommissionEngine;
import io.meridian.commission.domain.ShareLedger;
import io.meridian.commission.infrastructure.Downstream;
import io.meridian.commission.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(AccrualService.class);
    static final String TABLE = "commission_earning";
    static final String PENDING = "pending_commission_reward";

    private final Rows rows;
    private final Hierarchy hierarchy;
    private final PlanService plans;
    private final Events events;
    private final TransactionTemplate tx;
    private final Downstream downstream;
    private final CommissionProperties props;

    public AccrualService(Rows rows, Hierarchy hierarchy, PlanService plans, Events events, TransactionTemplate tx,
                          Downstream downstream, CommissionProperties props) {
        this.rows = rows;
        this.hierarchy = hierarchy;
        this.plans = plans;
        this.events = events;
        this.tx = tx;
        this.downstream = downstream;
        this.props = props;
    }

    /**
     * One completed (or reversed) wallet transaction, as received from the API or an event payload. WE-PRICE-1 added
     * the cross-service fields (remoteTxnType..customerInactiveDays): commission-service has no wallet_db, so
     * wallet-service supplies the credited customer's identity/inactivity and the counting wallet's windowed counts
     * and cumulative volume on the event / request. Missing fields leave the engine on its pre-WE-PRICE-1 path.
     */
    public record TxnEvent(UUID walletTransactionId, String txnType, String status, BigDecimal amount, BigDecimal fee,
                           String currency, String channel, Integer kycTier, UUID agentId, UUID merchantId, UUID billerId,
                           OffsetDateTime completedAt, String remoteTxnType, Integer countDaily, Integer countWeekly,
                           Integer countMonthly, Integer countLifetime, BigDecimal cumulativeBefore, UUID payeeWalletId,
                           Integer payeeKycTier, UUID customerWalletId, UUID openedByAgentId, Integer customerInactiveDays) {

        public static TxnEvent from(JsonNode n, String idField) {
            BigDecimal amount = Rows.dec(n, "amount");
            if (Rows.uuid(n, idField) == null || Rows.str(n, "txn_type") == null || amount == null
                    || Rows.str(n, "currency") == null) {
                throw ApiException.unprocessable("invalid_request", "wallet_transaction_id, txn_type, amount and currency are required");
            }
            return new TxnEvent(Rows.uuid(n, idField), Rows.str(n, "txn_type"),
                    Rows.str(n, "status") == null ? "COMPLETED" : Rows.str(n, "status"), amount, Rows.dec(n, "fee_amount"),
                    Rows.str(n, "currency"), Rows.str(n, "channel"), Rows.integer(n, "kyc_tier"), Rows.uuid(n, "agent_id"),
                    Rows.uuid(n, "merchant_id"), Rows.uuid(n, "biller_id"), Rows.ts(n, "completed_at"),
                    Rows.str(n, "remote_txn_type"), Rows.integer(n, "txn_count_daily"), Rows.integer(n, "txn_count_weekly"),
                    Rows.integer(n, "txn_count_monthly"), Rows.integer(n, "txn_count_lifetime"),
                    Rows.dec(n, "cumulative_amount_before"), Rows.uuid(n, "payee_wallet_id"), Rows.integer(n, "payee_kyc_tier"),
                    Rows.uuid(n, "customer_wallet_id"), Rows.uuid(n, "opened_by_agent_id"),
                    Rows.integer(n, "customer_inactive_days"));
        }
    }

    public ObjectNode accrue(TxnEvent t) {
        if (!Set.of("COMPLETED", "REVERSED").contains(t.status())) {
            throw ApiException.unprocessable("invalid_status", "Only COMPLETED or REVERSED transactions accrue commission");
        }
        if ("REVERSED".equals(t.status())) {
            // PE-14: reverse the whole commission-sharing CP/CR journal (once per share group) before clawing back
            for (ObjectNode j : rows.query("""
                    select distinct json_build_object('g', share_group_id, 'e', ledger_entry_id)::text
                    from commission_earning where wallet_transaction_id = ? and not is_clawback
                      and share_group_id is not null and ledger_entry_id is not null""", t.walletTransactionId())) {
                try {
                    downstream.reverseJournal("share:" + t.walletTransactionId() + ":reverse", Rows.uuid(j, "e"),
                            "commission sharing clawback " + t.walletTransactionId());
                } catch (RuntimeException e) {
                    log.warn("commission sharing reversal for {} failed: {}", t.walletTransactionId(), e.getMessage());
                }
            }
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
                ctx.territoryId(), t.billerId(), t.amount(), t.fee(), t.remoteTxnType(), t.cumulativeBefore(),
                t.countDaily(), t.countWeekly(), t.countMonthly(), t.countLifetime(), t.customerInactiveDays());
        List<CommissionEngine.Selected> selected = CommissionEngine.evaluate(plans.rules(Rows.uuid(plan, "id")), txn);
        // resolve beneficiaries and partition (outside the DB transaction)
        List<Accrual> accruals = new ArrayList<>();
        for (CommissionEngine.Selected sel : selected) {
            Hierarchy.Beneficiary b = hierarchy.beneficiary(sel.rule(), ctx, t.merchantId());
            if (b != null) {
                accruals.add(new Accrual(sel, b));
            }
        }
        boolean shared = accruals.stream().anyMatch(a -> a.sel.rule().shareEnabled());
        UUID shareGroupId = shared ? ShareLedger.shareGroupId(t.walletTransactionId()) : null;
        // PE-14: one balanced CP/CR journal recognises the whole shared split (best-effort, idempotent, isolated)
        UUID shareJournalId = shared ? postShareJournal(t, accruals, shareGroupId) : null;
        return result(t, tx.execute(s -> {
            rows.advisoryLock("accrual:" + t.walletTransactionId());
            List<ObjectNode> again = earnings(t.walletTransactionId(), false);
            if (!again.isEmpty()) {
                return again;
            }
            for (Accrual a : accruals) {
                CommissionEngine.Rule rule = a.sel.rule();
                if (rule.inactiveCustomerOnly() && rule.rewardTrigger() != null
                        && !"IMMEDIATE".equals(rule.rewardTrigger())) {
                    insertPending(t, plan, a, earnedOn);   // PE-13: deferred release
                    continue;
                }
                insertEarning(t, plan, a, earnedOn, shareGroupId, shareJournalId);
            }
            return earnings(t.walletTransactionId(), false);
        }));
    }

    /** A selected rule together with its resolved beneficiary. */
    private record Accrual(CommissionEngine.Selected sel, Hierarchy.Beneficiary b) {}

    private void insertEarning(TxnEvent t, ObjectNode plan, Accrual a, LocalDate earnedOn, UUID shareGroupId,
                               UUID shareJournalId) {
        CommissionEngine.Rule rule = a.sel.rule();
        ObjectNode v = rows.object();
        v.put("commission_plan_id", Rows.str(plan, "id"));
        v.put("commission_rule_id", rule.id().toString());
        v.put("wallet_transaction_id", t.walletTransactionId().toString());
        v.put("txn_type", t.txnType());
        v.put("beneficiary", rule.beneficiary());
        if (a.b.agentId() != null) {
            v.put("beneficiary_agent_id", a.b.agentId().toString());
        }
        if (a.b.merchantId() != null) {
            v.put("beneficiary_merchant_id", a.b.merchantId().toString());
        }
        v.put("beneficiary_wallet_id", a.b.walletId().toString());
        v.put("base_amount", a.sel.amounts().base().toPlainString());
        v.put("commission_amount", a.sel.amounts().commission().toPlainString());
        v.put("tax_amount", a.sel.amounts().tax().toPlainString());
        v.put("net_amount", a.sel.amounts().net().toPlainString());
        v.put("currency", t.currency());
        v.put("payout_frequency", Rows.str(plan, "payout_frequency"));
        v.put("is_clawback", false);
        if (rule.shareEnabled() && shareGroupId != null) {
            v.put("share_group_id", shareGroupId.toString());
            v.put("share_role", shareRole(rule.beneficiary()));
            if (shareJournalId != null) {
                v.put("ledger_entry_id", shareJournalId.toString());
            }
        }
        v.put("earned_at", (t.completedAt() == null ? io.meridian.common.time.Clocks.now() : t.completedAt()).toString());
        v.put("payout_due_on", CommissionEngine.payoutDueOn(Rows.str(plan, "payout_frequency"), earnedOn).toString());
        ObjectNode row = rows.insert(TABLE, v);
        events.publish("wallets.commission_earning.accrued", TABLE, row);
    }

    /** PE-13: records a deferred inactive-customer reward instead of an earning; released later by release-due. */
    private void insertPending(TxnEvent t, ObjectNode plan, Accrual a, LocalDate earnedOn) {
        CommissionEngine.Rule rule = a.sel.rule();
        if (rows.scalar("select count(*) from pending_commission_reward where wallet_transaction_id = ? and commission_rule_id = ? and beneficiary_wallet_id = ?",
                Long.class, t.walletTransactionId(), rule.id(), a.b.walletId()).orElse(0L) > 0) {
            return;
        }
        ObjectNode v = rows.object();
        v.put("wallet_transaction_id", t.walletTransactionId().toString());
        v.put("commission_plan_id", Rows.str(plan, "id"));
        v.put("commission_rule_id", rule.id().toString());
        if (t.customerWalletId() != null) {
            v.put("customer_wallet_id", t.customerWalletId().toString());
        }
        v.put("beneficiary", rule.beneficiary());
        if (a.b.agentId() != null) {
            v.put("beneficiary_agent_id", a.b.agentId().toString());
        }
        v.put("beneficiary_wallet_id", a.b.walletId().toString());
        v.put("base_amount", a.sel.amounts().base().toPlainString());
        v.put("commission_amount", a.sel.amounts().commission().toPlainString());
        v.put("tax_amount", a.sel.amounts().tax().toPlainString());
        v.put("net_amount", a.sel.amounts().net().toPlainString());
        v.put("currency", t.currency());
        v.put("payout_frequency", Rows.str(plan, "payout_frequency"));
        v.put("reward_trigger", rule.rewardTrigger());
        if ("AFTER_DURATION".equals(rule.rewardTrigger())) {
            int delay = rule.rewardDelayDays() == null ? 0 : rule.rewardDelayDays();
            v.put("release_due_on", earnedOn.plusDays(delay).toString());
        }
        v.put("status", "PENDING");
        rows.insert(PENDING, v);
    }

    private UUID postShareJournal(TxnEvent t, List<Accrual> accruals, UUID shareGroupId) {
        BigDecimal total = accruals.stream().filter(a -> a.sel.rule().shareEnabled())
                .map(a -> a.sel.amounts().commission()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            return null;
        }
        if (props.ledgerId() == null || props.commissionReceivableAccountId() == null
                || props.commissionPayableAccountId() == null) {
            log.warn("commission sharing for {}: CP/CR ledger accounts not configured, accruing without a journal",
                    t.walletTransactionId());
            return null;
        }
        List<Downstream.Line> lines = ShareLedger.cpcr(props.commissionReceivableAccountId(),
                        props.commissionPayableAccountId(), total).stream()
                .map(l -> new Downstream.Line(l.account(), l.direction(), l.amount(), "Commission sharing " + t.walletTransactionId()))
                .toList();
        try {
            return downstream.postJournal("share:" + t.walletTransactionId(), props.ledgerId(),
                    "Commission sharing split for " + t.walletTransactionId(), t.currency(),
                    shareGroupId.toString(), "COMMISSION_SHARE", t.walletTransactionId(), lines);
        } catch (RuntimeException e) {
            log.warn("commission sharing CP/CR journal for {} failed: {}", t.walletTransactionId(), e.getMessage());
            return null;
        }
    }

    static String shareRole(String beneficiary) {
        return switch (beneficiary) {
            case "AGGREGATOR" -> "AGGREGATOR";
            case "SERVICE_PROVIDER" -> "SERVICE_PROVIDER";
            default -> "MERCHANT";
        };
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
                    "base_amount", "currency", "payout_frequency", "share_group_id", "share_role", "ledger_entry_id")) {
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
