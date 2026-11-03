package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.config.AgentNetworkProperties;
import io.meridian.agentnetwork.domain.StockRules;
import io.meridian.agentnetwork.infrastructure.Downstream;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-FLOAT-1 (MQ-STK-6): auto-deallocation sweeps. {@link #plan} builds a PENDING_APPROVAL run of float wallets whose
 * balance exceeds their minimum residual (precedence: plan value, {@code agent_float.min_balance}, config default). A
 * checker approves and each candidate is swept float&rarr;trust (2700) as its own immutable, per-wallet-idempotent line;
 * money never moves unattended, even when the scheduler built the plan. An unknown (5xx) outcome keeps the run RUNNING
 * for a retry that skips already-swept lines.
 */
@Service
public class StockSweepService {
    static final String RUN = "stock_sweep_run";
    static final String LINE = "stock_sweep_line";
    /** Approval resource type in workflow-service (SEC-03 owner = agent-network-service). */
    public static final String APPROVAL_RESOURCE = "stock_sweep_run";

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final AgentNetworkProperties props;
    private final TransactionTemplate tx;
    private final Approvals approvals;

    public StockSweepService(Rows rows, Downstream downstream, Events events, AgentNetworkProperties props,
                             TransactionTemplate tx, Approvals approvals) {
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.props = props;
        this.tx = tx;
        this.approvals = approvals;
    }

    /** Builds a PENDING_APPROVAL run (triggered_by MANUAL unless {@code scheduled}). Returns null when no wallet qualifies. */
    public ObjectNode plan(JsonNode req, boolean scheduled) {
        String currency = Rows.str(rows.columns(RUN, req), "currency");
        if (currency == null) {
            throw ApiException.unprocessable("currency_required", "currency is required");
        }
        BigDecimal planResidual = Rows.dec(rows.columns(RUN, req), "min_residual");
        return tx.execute(s -> {
            List<Candidate> candidates = candidates(currency, planResidual);
            if (candidates.isEmpty()) {
                return null;
            }
            BigDecimal total = candidates.stream().map(Candidate::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            ObjectNode v = rows.object();
            v.put("currency", currency);
            if (planResidual != null) {
                v.put("min_residual", planResidual.toPlainString());
            }
            v.put("triggered_by", scheduled ? "SCHEDULER" : "MANUAL");
            v.put("status", "PENDING_APPROVAL");
            v.put("candidate_count", candidates.size());
            v.put("total_amount", total.toPlainString());
            ObjectNode run = rows.insert(RUN, v);
            requestApproval(run);
            return run;
        });
    }

    private void requestApproval(ObjectNode run) {
        UUID id = Rows.uuid(run, "id");
        BigDecimal total = Rows.decOr0(run, "total_amount");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currency", Rows.str(run, "currency"));
        payload.put("candidate_count", Rows.integer(run, "candidate_count"));
        payload.put("total_amount", total.toPlainString());
        payload.put("triggered_by", Rows.str(run, "triggered_by"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                        "Auto-deallocation sweep " + total.toPlainString() + " " + Rows.str(run, "currency")
                                + " (" + Rows.integer(run, "candidate_count") + " wallets)", payload)
                        .withAmount(total, Rows.str(run, "currency")),
                "submit:" + id);
    }

    public boolean awaitsApproval(UUID id) {
        return rows.find(RUN, id).filter(r -> "PENDING_APPROVAL".equals(Rows.str(r, "status"))).isPresent();
    }

    /**
     * Approves (checker other than the maker) and sweeps each candidate float wallet to the trust account. Re-reads
     * balances so stale plan amounts never move; each line is idempotent per wallet (stable transfer key + a skip of
     * already-swept lines), so a retry after a 5xx completes the run without double-moving.
     */
    public ObjectNode approve(UUID id) {
        UUID trust = trust();
        ObjectNode start = tx.execute(s -> {
            ObjectNode run = rows.lock(RUN, id);
            String status = Rows.str(run, "status");
            if ("RUNNING".equals(status)) {
                return run; // a retry: resume
            }
            if (!"PENDING_APPROVAL".equals(status)) {
                throw ApiException.conflict("invalid_transition", "The run is " + status + ", not PENDING_APPROVAL");
            }
            if (TenantContext.principal().equals(Rows.str(run, "created_by"))) {
                throw ApiException.unprocessable("four_eyes_violation", "The run must be approved by a different user than its maker");
            }
            ObjectNode v = rows.object();
            v.put("status", "RUNNING");
            v.put("approved_by", TenantContext.principal());
            v.put("trust_ledger_account_id", trust.toString());
            rows.update(RUN, id, v);
            rows.execute("update stock_sweep_run set approved_at = now(), started_at = now() where id = ?", id);
            return rows.get(RUN, id);
        });
        String currency = Rows.str(start, "currency");
        BigDecimal planResidual = Rows.dec(start, "min_residual");
        for (Candidate c : candidates(currency, planResidual)) {
            if (rows.scalar("select count(*) from stock_sweep_line where stock_sweep_run_id = ? and float_wallet_id = ? and line_status = 'SWEPT'",
                    Long.class, id, c.walletId()).orElse(0L) > 0) {
                continue; // already swept on a prior attempt
            }
            JsonNode txn;
            try {
                txn = downstream.walletToGl("ssr-" + id + "-" + c.walletId(), "ADJUSTMENT", c.walletId(), true, trust,
                        c.amount(), currency, c.agentId(), "auto-deallocation sweep " + id);
            } catch (ApiException e) {
                if (e.status() >= 500) {
                    throw e; // unknown outcome: keep RUNNING, the listener retries
                }
                insertLine(id, c, currency, "FAILED", null, truncate(e.code() + ": " + e.getMessage()));
                continue;
            }
            insertLine(id, c, currency, "SWEPT", txn.path("id").asString(), null);
        }
        return finish(id);
    }

    private void insertLine(UUID runId, Candidate c, String currency, String status, String txnId, String failure) {
        tx.execute(s -> {
            ObjectNode l = rows.object();
            l.put("stock_sweep_run_id", runId.toString());
            l.put("float_wallet_id", c.walletId().toString());
            if (c.agentId() != null) {
                l.put("agent_id", c.agentId().toString());
            }
            l.put("balance_before", c.balance().toPlainString());
            l.put("min_residual", c.residual().toPlainString());
            l.put("amount", c.amount().toPlainString());
            l.put("currency", currency);
            l.put("line_status", status);
            if (txnId != null) {
                l.put("wallet_transaction_id", txnId);
            }
            if (failure != null) {
                l.put("failure_reason", failure);
            }
            return rows.insert(LINE, l);
        });
    }

    private ObjectNode finish(UUID id) {
        return tx.execute(s -> {
            rows.lock(RUN, id);
            long swept = rows.scalar("select count(*) from stock_sweep_line where stock_sweep_run_id = ? and line_status = 'SWEPT'", Long.class, id).orElse(0L);
            long failed = rows.scalar("select count(*) from stock_sweep_line where stock_sweep_run_id = ? and line_status = 'FAILED'", Long.class, id).orElse(0L);
            BigDecimal sweptAmount = rows.scalar("select coalesce(sum(amount), 0) from stock_sweep_line where stock_sweep_run_id = ? and line_status = 'SWEPT'", BigDecimal.class, id).orElse(BigDecimal.ZERO);
            ObjectNode v = rows.object();
            v.put("status", StockRules.runStatus((int) (swept + failed), (int) swept, (int) failed));
            v.put("swept_count", (int) swept);
            v.put("failed_count", (int) failed);
            v.put("swept_amount", sweptAmount.toPlainString());
            rows.update(RUN, id, v);
            rows.execute("update stock_sweep_run set finished_at = now() where id = ?", id);
            ObjectNode run = rows.get(RUN, id);
            events.publish("wallets.stock_sweep_run.completed", RUN, run);
            return run;
        });
    }

    public ObjectNode reject(UUID id, String reason) {
        return tx.execute(s -> {
            ObjectNode run = rows.lock(RUN, id);
            if (!"PENDING_APPROVAL".equals(Rows.str(run, "status"))) {
                throw ApiException.conflict("invalid_transition", "The run is " + Rows.str(run, "status") + ", not PENDING_APPROVAL");
            }
            ObjectNode v = rows.object();
            v.put("status", "CANCELLED");
            v.put("cancel_reason", reason == null ? "rejected" : reason);
            ObjectNode after = rows.update(RUN, id, v);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
            return after;
        });
    }

    public List<ObjectNode> lines(UUID runId) {
        rows.get(RUN, runId);
        return rows.query("select row_to_json(l)::text from stock_sweep_line l where l.stock_sweep_run_id = ? order by l.created_at, l.id", runId);
    }

    // ------------------------------------------------------------------ candidate discovery

    private record Candidate(UUID walletId, UUID agentId, BigDecimal balance, BigDecimal residual, BigDecimal amount) {}

    private List<Candidate> candidates(String currency, BigDecimal planResidual) {
        List<ObjectNode> floats = rows.query(
                "select row_to_json(f)::text from agent_float f join agent a on a.id = f.agent_id "
                        + "where f.currency = ? and a.status = 'ACTIVE'", currency);
        java.util.ArrayList<Candidate> out = new java.util.ArrayList<>();
        for (ObjectNode f : floats) {
            UUID wallet = Rows.uuid(f, "wallet_id");
            BigDecimal balance = downstream.balance(wallet);
            BigDecimal residual = StockRules.effectiveResidual(planResidual, Rows.dec(f, "min_balance"),
                    props.sweep().defaultMinResidual());
            BigDecimal amount = StockRules.sweepable(balance, residual);
            if (amount.signum() > 0) {
                out.add(new Candidate(wallet, Rows.uuid(f, "agent_id"), balance, residual, amount));
            }
        }
        return out;
    }

    private UUID trust() {
        UUID a = props.gl().trust();
        if (a == null) {
            throw new ApiException(422, "trust_not_configured",
                    "No trust ledger account is configured for this tenant (config-service ledger/agent-network-service.gl.trust)");
        }
        return a;
    }

    private static String truncate(String s) {
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
