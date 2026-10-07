package io.meridian.commission.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.commission.config.CommissionProperties;
import io.meridian.commission.infrastructure.Downstream;
import io.meridian.commission.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Commission payout runs. A run covers the unpaid earnings (incl. clawbacks) due in its period for one currency and
 * payout frequency; it is created PENDING_APPROVAL with a preview, approved by a different principal, then each
 * beneficiary wallet with a positive net is paid by a COMMISSION_PAYOUT wallet transaction from the commission pool.
 * Every attempt appends an immutable line (PAID / FAILED); failed beneficiaries can be retried with a new attempt.
 * Beneficiaries whose net is zero or negative (clawbacks) are carried forward to a later run.
 */
@Service
public class PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    static final String RUN = "commission_payout_run";
    static final String LINE = "commission_payout_line";
    static final Set<String> FREQUENCIES = Set.of("REALTIME", "DAILY", "WEEKLY", "MONTHLY");

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final TransactionTemplate tx;
    private final CommissionProperties props;
    private final Approvals approvals;

    /** Approval resource type for commission payout runs in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "commission_payout_run";

    public PayoutService(Rows rows, Downstream downstream, Events events, TransactionTemplate tx, CommissionProperties props,
                         Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.tx = tx;
        this.props = props;
    }

    /** Unpaid earnings of a beneficiary group. */
    record Group(UUID walletId, UUID agentId, List<String> earningIds, BigDecimal gross, BigDecimal tax, BigDecimal net) {}

    List<Group> unpaid(String frequency, String currency, LocalDate start, LocalDate end) {
        List<ObjectNode> found = rows.query("""
                select json_build_object('wallet', e.beneficiary_wallet_id, 'agent', min(e.beneficiary_agent_id::text),
                       'ids', json_agg(e.id order by e.id), 'gross', sum(e.commission_amount)::text,
                       'tax', sum(e.tax_amount)::text, 'net', sum(e.net_amount)::text)::text
                from commission_earning e
                where e.currency = ? and e.payout_frequency = ? and e.payout_due_on between ? and ?
                  and not exists (select 1 from commission_payout_line l where l.payout_status = 'PAID'
                                  and l.earning_ids @> jsonb_build_array(e.id::text))
                group by e.beneficiary_wallet_id order by e.beneficiary_wallet_id""", currency, frequency, start, end);
        List<Group> out = new ArrayList<>();
        for (ObjectNode g : found) {
            List<String> ids = new ArrayList<>();
            g.get("ids").forEach(i -> ids.add(i.asString()));
            out.add(new Group(Rows.uuid(g, "wallet"), Rows.uuid(g, "agent"), ids, Rows.dec(g, "gross"), Rows.dec(g, "tax"),
                    Rows.dec(g, "net")));
        }
        return out;
    }

    public ObjectNode create(JsonNode req) {
        return tx.execute(s -> {
            ObjectNode v = rows.columns(RUN, req);
            String freq = Rows.str(v, "payout_frequency");
            String ccy = Rows.str(v, "currency");
            LocalDate start = Rows.date(v, "period_start");
            LocalDate end = Rows.date(v, "period_end");
            if (!FREQUENCIES.contains(freq)) {
                throw ApiException.unprocessable("invalid_frequency", "payout_frequency must be one of " + FREQUENCIES);
            }
            if (end.isBefore(start)) {
                throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
            }
            rows.advisoryLock("payout-run:" + ccy + ":" + freq);
            if (rows.scalar("""
                    select count(*) from commission_payout_run where currency = ? and payout_frequency = ?
                      and status in ('PENDING_APPROVAL', 'RUNNING')""", Long.class, ccy, freq).orElse(0L) > 0) {
                throw ApiException.conflict("run_in_progress",
                        "Another " + freq + " " + ccy + " payout run is awaiting approval or running");
            }
            List<Group> groups = unpaid(freq, ccy, start, end);
            if (groups.isEmpty()) {
                throw ApiException.unprocessable("nothing_to_pay", "No unpaid commission is due in the period");
            }
            int earnings = groups.stream().mapToInt(g -> g.earningIds().size()).sum();
            List<Group> payable = groups.stream().filter(g -> g.net().signum() > 0).toList();
            v.put("status", "PENDING_APPROVAL");
            v.put("earning_count", earnings);
            v.put("beneficiary_count", payable.size());
            BigDecimal total = payable.stream().map(Group::net).reduce(BigDecimal.ZERO, BigDecimal::add);
            v.put("total_amount", total.toPlainString());
            ObjectNode row = rows.insert(RUN, v);
            // Central maker-checker: the checker decides in the workflow inbox; PayoutRunApprovalHandler applies it.
            UUID id = Rows.uuid(row, "id");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("payout_frequency", freq);
            payload.put("currency", ccy);
            payload.put("period_start", start.toString());
            payload.put("period_end", end.toString());
            payload.put("earning_count", earnings);
            payload.put("beneficiary_count", payable.size());
            payload.put("total_amount", Rows.str(row, "total_amount"));
            payload.put("created_by", Rows.str(row, "created_by"));
            approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                            "Commission payout run " + freq + " " + ccy + " " + start + ".." + end, payload)
                    .withAmount(total, ccy), "create:" + id);
            return row;
        });
    }

    /** True while the run is PENDING_APPROVAL with the previewed total unchanged. */
    public boolean awaitsApproval(UUID id, String totalAmount) {
        return rows.find(RUN, id)
                .filter(r -> "PENDING_APPROVAL".equals(Rows.str(r, "status")))
                .filter(r -> totalAmount == null || new BigDecimal(totalAmount).compareTo(Rows.dec(r, "total_amount")) == 0)
                .isPresent();
    }

    /** Checker approval, then execution. */
    public ObjectNode approve(UUID id) {
        ObjectNode run = tx.execute(s -> {
            ObjectNode r = rows.lock(RUN, id);
            if (!"PENDING_APPROVAL".equals(Rows.str(r, "status"))) {
                throw ApiException.conflict("invalid_transition", "The run is " + Rows.str(r, "status"));
            }
            if (TenantContext.principal().equals(Rows.str(r, "created_by"))) {
                throw ApiException.unprocessable("four_eyes_violation", "The payout run must be approved by a different user than its maker");
            }
            ObjectNode v = rows.object();
            v.put("status", "RUNNING");
            v.put("approved_by", TenantContext.principal());
            rows.update(RUN, id, v);
            rows.execute("update commission_payout_run set approved_at = now(), started_at = now() where id = ?", id);
            ObjectNode after = rows.get(RUN, id);
            events.publish("wallets.commission_payout_run.approved", RUN, after);
            return after;
        });
        for (Group g : unpaid(Rows.str(run, "payout_frequency"), Rows.str(run, "currency"),
                Rows.date(run, "period_start"), Rows.date(run, "period_end"))) {
            if (g.net().signum() > 0) {
                pay(run, g);
            }
        }
        ObjectNode done = finish(id);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return done;
    }

    public ObjectNode cancel(UUID id, String reason) {
        return tx.execute(s -> {
            ObjectNode r = rows.lock(RUN, id);
            if (!"PENDING_APPROVAL".equals(Rows.str(r, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only a run awaiting approval can be cancelled");
            }
            ObjectNode v = rows.object();
            v.put("status", "CANCELLED");
            v.put("cancel_reason", reason == null ? "Cancelled" : reason);
            rows.update(RUN, id, v);
            rows.execute("update commission_payout_run set finished_at = now() where id = ?", id);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "cancelled directly");
            return rows.get(RUN, id);
        });
    }

    /** Pays again every beneficiary whose latest attempt failed (earnings since paid elsewhere are excluded). */
    public ObjectNode retryFailed(UUID id) {
        ObjectNode run = tx.execute(s -> {
            ObjectNode r = rows.lock(RUN, id);
            if (!Set.of("PARTIALLY_SUCCEEDED", "FAILED").contains(Rows.str(r, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only a partially succeeded or failed run can be retried");
            }
            rows.execute("update commission_payout_run set status = 'RUNNING', finished_at = null where id = ?", id);
            return rows.get(RUN, id);
        });
        for (ObjectNode failed : rows.query("""
                select row_to_json(l)::text from commission_payout_line l
                where l.commission_payout_run_id = ? and l.payout_status = 'FAILED'
                  and l.attempt = (select max(x.attempt) from commission_payout_line x
                                   where x.commission_payout_run_id = l.commission_payout_run_id
                                     and x.beneficiary_wallet_id = l.beneficiary_wallet_id)
                order by l.beneficiary_wallet_id""", id)) {
            List<String> ids = new ArrayList<>();
            failed.get("earning_ids").forEach(e -> ids.add(e.asString()));
            ObjectNode totals = rows.queryOne("""
                    select json_build_object('ids', coalesce(json_agg(e.id order by e.id), '[]'::json),
                           'gross', coalesce(sum(e.commission_amount), 0)::text, 'tax', coalesce(sum(e.tax_amount), 0)::text,
                           'net', coalesce(sum(e.net_amount), 0)::text)::text
                    from commission_earning e where e.id::text in (select jsonb_array_elements_text(?::jsonb))
                      and not exists (select 1 from commission_payout_line l where l.payout_status = 'PAID'
                                      and l.earning_ids @> jsonb_build_array(e.id::text))""",
                    rows.json().writeValueAsString(ids)).orElseThrow();
            List<String> still = new ArrayList<>();
            totals.get("ids").forEach(e -> still.add(e.asString()));
            Group g = new Group(Rows.uuid(failed, "beneficiary_wallet_id"), Rows.uuid(failed, "beneficiary_agent_id"), still,
                    Rows.dec(totals, "gross"), Rows.dec(totals, "tax"), Rows.dec(totals, "net"));
            if (!still.isEmpty() && g.net().signum() > 0) {
                pay(run, g);
            }
        }
        return finish(id);
    }

    private void pay(ObjectNode run, Group g) {
        UUID runId = Rows.uuid(run, "id");
        int attempt = rows.scalar("""
                select coalesce(max(attempt), 0) + 1 from commission_payout_line
                where commission_payout_run_id = ? and beneficiary_wallet_id = ?""", Integer.class, runId, g.walletId())
                .orElse(1);
        String status;
        String txnId = null;
        String failure = null;
        try {
            UUID source = props.payoutSourceWalletId();
            if (source == null) {
                throw ApiException.unprocessable("payout_source_not_configured", "No commission payout wallet is configured "
                        + "for this tenant (config-service ledger/commission-service.payout_source_wallet_id or "
                        + "meridian.commission.payout-source-wallet-id)");
            }
            JsonNode t = downstream.payout("cpr-" + runId + "-" + g.walletId() + "-" + attempt, source,
                    g.walletId(), g.agentId(), g.net(), Rows.str(run, "currency"), runId);
            status = "PAID";
            txnId = t.path("id").asString(null);
        } catch (ApiException e) {
            log.warn("commission payout to {} failed: {} {}", g.walletId(), e.code(), e.getMessage());
            status = "FAILED";
            failure = (e.code() + ": " + e.getMessage());
            failure = failure.length() > 255 ? failure.substring(0, 255) : failure;
        }
        ObjectNode line = rows.object();
        line.put("commission_payout_run_id", runId.toString());
        line.put("beneficiary_wallet_id", g.walletId().toString());
        if (g.agentId() != null) {
            line.put("beneficiary_agent_id", g.agentId().toString());
        }
        line.put("attempt", attempt);
        line.put("earning_count", g.earningIds().size());
        ArrayNode ids = line.putArray("earning_ids");
        g.earningIds().forEach(ids::add);
        line.put("gross_amount", g.gross().toPlainString());
        line.put("tax_amount", g.tax().toPlainString());
        line.put("net_amount", g.net().toPlainString());
        line.put("currency", Rows.str(run, "currency"));
        line.put("payout_status", status);
        if (txnId != null) {
            line.put("wallet_transaction_id", txnId);
        }
        if (failure != null) {
            line.put("failure_reason", failure);
        }
        tx.executeWithoutResult(s -> rows.insert(LINE, line));
    }

    /** Totals from the lines (latest attempt per beneficiary) and the final status. */
    private ObjectNode finish(UUID id) {
        return tx.execute(s -> {
            rows.lock(RUN, id);
            ObjectNode t = rows.queryOne("""
                    select json_build_object(
                      'paid', count(*) filter (where l.payout_status = 'PAID'),
                      'failed', count(*) filter (where l.payout_status = 'FAILED'),
                      'paid_amount', coalesce(sum(l.net_amount) filter (where l.payout_status = 'PAID'), 0)::text)::text
                    from (select distinct on (beneficiary_wallet_id) * from commission_payout_line
                          where commission_payout_run_id = ? order by beneficiary_wallet_id, attempt desc) l""", id)
                    .orElseThrow();
            int paid = Rows.integer(t, "paid");
            int failed = Rows.integer(t, "failed");
            String status = failed == 0 ? "SUCCEEDED" : paid == 0 ? "FAILED" : "PARTIALLY_SUCCEEDED";
            ObjectNode v = rows.object();
            v.put("status", status);
            v.put("paid_count", paid);
            v.put("failed_count", failed);
            v.put("paid_amount", Rows.str(t, "paid_amount"));
            rows.update(RUN, id, v);
            rows.execute("update commission_payout_run set finished_at = now() where id = ?", id);
            ObjectNode after = rows.get(RUN, id);
            events.publish("wallets.commission_payout_run.completed", RUN, after);
            return after;
        });
    }
}
