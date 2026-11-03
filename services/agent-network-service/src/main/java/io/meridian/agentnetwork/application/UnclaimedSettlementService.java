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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-FLOAT-1 (MQ-STK-13): unclaimed-money settlement. {@link #plan} ages e-money and builds a PENDING_APPROVAL run; a
 * checker approves and balanced GL&lt;-&gt;GL journals post via ledger-service: suspense (2710) &rarr; unclaimed (2720)
 * for funds older than {@code suspense_age_days}, then unclaimed (2720) &rarr; capital (IND01) or income (IND03) for
 * funds older than {@code unclaimed_age_days}. Aged amounts are the posted balances at the age cut-offs, capped at the
 * current balance. Each stage is one immutable, idempotent line.
 */
@Service
public class UnclaimedSettlementService {
    static final String RUN = "unclaimed_settlement_run";
    static final String LINE = "unclaimed_settlement_line";
    /** Approval resource type in workflow-service (SEC-03 owner = agent-network-service). */
    public static final String APPROVAL_RESOURCE = "unclaimed_settlement";

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final AgentNetworkProperties props;
    private final TransactionTemplate tx;
    private final Approvals approvals;

    public UnclaimedSettlementService(Rows rows, Downstream downstream, Events events, AgentNetworkProperties props,
                                      TransactionTemplate tx, Approvals approvals) {
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.props = props;
        this.tx = tx;
        this.approvals = approvals;
    }

    private record Stage(String stage, UUID source, UUID target, BigDecimal amount) {}

    /** Builds a PENDING_APPROVAL run. Returns null when nothing has aged. */
    public ObjectNode plan(tools.jackson.databind.JsonNode req) {
        ObjectNode v = rows.columns(RUN, req);
        String currency = Rows.str(v, "currency");
        if (currency == null) {
            throw ApiException.unprocessable("currency_required", "currency is required");
        }
        int suspenseAge = Rows.integer(v, "suspense_age_days") == null ? 0 : Rows.integer(v, "suspense_age_days");
        int unclaimedAge = Rows.integer(v, "unclaimed_age_days") == null ? 0 : Rows.integer(v, "unclaimed_age_days");
        String settleTo = Rows.str(v, "settle_to") == null ? "CAPITAL" : Rows.str(v, "settle_to");
        if (!"CAPITAL".equalsIgnoreCase(settleTo) && !"INCOME".equalsIgnoreCase(settleTo)) {
            throw ApiException.unprocessable("invalid_settle_to", "settle_to must be CAPITAL or INCOME");
        }
        return tx.execute(s -> {
            List<Stage> stages = stages(suspenseAge, unclaimedAge, settleTo);
            if (stages.isEmpty()) {
                return null;
            }
            BigDecimal total = stages.stream().map(Stage::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            v.put("suspense_age_days", suspenseAge);
            v.put("unclaimed_age_days", unclaimedAge);
            v.put("settle_to", settleTo.toUpperCase());
            v.put("status", "PENDING_APPROVAL");
            v.put("line_count", stages.size());
            v.put("total_amount", total.toPlainString());
            v.put("suspense_ledger_account_id", account("gl.suspense", props.gl().suspense()).toString());
            v.put("unclaimed_ledger_account_id", account("gl.unclaimed", props.gl().unclaimed()).toString());
            v.put("capital_ledger_account_id", props.gl().capital() == null ? null : props.gl().capital().toString());
            v.put("income_ledger_account_id", props.gl().income() == null ? null : props.gl().income().toString());
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
        payload.put("line_count", Rows.integer(run, "line_count"));
        payload.put("total_amount", total.toPlainString());
        payload.put("settle_to", Rows.str(run, "settle_to"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                        "Unclaimed settlement " + total.toPlainString() + " " + Rows.str(run, "currency"), payload)
                        .withAmount(total, Rows.str(run, "currency")),
                "submit:" + id);
    }

    public boolean awaitsApproval(UUID id) {
        return rows.find(RUN, id).filter(r -> "PENDING_APPROVAL".equals(Rows.str(r, "status"))).isPresent();
    }

    /** Approves (checker other than the maker) and posts the aging journals; idempotent per stage on retry. */
    public ObjectNode approve(UUID id) {
        UUID ledger = ledger();
        ObjectNode start = tx.execute(s -> {
            ObjectNode run = rows.lock(RUN, id);
            String status = Rows.str(run, "status");
            if ("RUNNING".equals(status)) {
                return run;
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
            rows.update(RUN, id, v);
            rows.execute("update unclaimed_settlement_run set approved_at = now(), started_at = now() where id = ?", id);
            return rows.get(RUN, id);
        });
        String currency = Rows.str(start, "currency");
        List<Stage> stages = stages(Rows.integer(start, "suspense_age_days"), Rows.integer(start, "unclaimed_age_days"),
                Rows.str(start, "settle_to"));
        for (Stage st : stages) {
            if (rows.scalar("select count(*) from unclaimed_settlement_line where unclaimed_settlement_run_id = ? and stage = ? and line_status = 'SWEPT'",
                    Long.class, id, st.stage()).orElse(0L) > 0) {
                continue;
            }
            StockRules.requireBalanced(st.amount(), st.amount());
            UUID journalId;
            try {
                journalId = UUID.fromString(downstream.postJournal("usr-" + id + "-" + st.stage(), ledger,
                        "UNCLAIMED_SETTLEMENT", id, "unclaimed settlement " + id + " " + st.stage(),
                        st.source(), st.target(), st.amount(), currency).path("id").asString());
            } catch (ApiException e) {
                if (e.status() >= 500) {
                    throw e;
                }
                insertLine(id, st, currency, "FAILED", null, truncate(e.code() + ": " + e.getMessage()));
                continue;
            }
            insertLine(id, st, currency, "SWEPT", journalId.toString(), null);
        }
        return finish(id);
    }

    private void insertLine(UUID runId, Stage st, String currency, String status, String journalId, String failure) {
        tx.execute(s -> {
            ObjectNode l = rows.object();
            l.put("unclaimed_settlement_run_id", runId.toString());
            l.put("stage", st.stage());
            l.put("source_ledger_account_id", st.source().toString());
            l.put("target_ledger_account_id", st.target().toString());
            l.put("amount", st.amount().toPlainString());
            l.put("currency", currency);
            l.put("line_status", status);
            if (journalId != null) {
                l.put("journal_entry_id", journalId);
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
            long settled = rows.scalar("select count(*) from unclaimed_settlement_line where unclaimed_settlement_run_id = ? and line_status = 'SWEPT'", Long.class, id).orElse(0L);
            long failed = rows.scalar("select count(*) from unclaimed_settlement_line where unclaimed_settlement_run_id = ? and line_status = 'FAILED'", Long.class, id).orElse(0L);
            BigDecimal settledAmount = rows.scalar("select coalesce(sum(amount), 0) from unclaimed_settlement_line where unclaimed_settlement_run_id = ? and line_status = 'SWEPT'", BigDecimal.class, id).orElse(BigDecimal.ZERO);
            ObjectNode v = rows.object();
            v.put("status", StockRules.runStatus((int) (settled + failed), (int) settled, (int) failed));
            v.put("settled_count", (int) settled);
            v.put("failed_count", (int) failed);
            v.put("settled_amount", settledAmount.toPlainString());
            rows.update(RUN, id, v);
            rows.execute("update unclaimed_settlement_run set finished_at = now() where id = ?", id);
            ObjectNode run = rows.get(RUN, id);
            events.publish("wallets.unclaimed_settlement.completed", RUN, run);
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
        return rows.query("select row_to_json(l)::text from unclaimed_settlement_line l where l.unclaimed_settlement_run_id = ? order by l.created_at, l.id", runId);
    }

    // ------------------------------------------------------------------ aging

    /** The aged stages to post (empty when nothing has aged). Amount = posted balance at the cut-off, capped at current. */
    private List<Stage> stages(Integer suspenseAgeDays, Integer unclaimedAgeDays, String settleTo) {
        UUID suspense = account("gl.suspense", props.gl().suspense());
        UUID unclaimed = account("gl.unclaimed", props.gl().unclaimed());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Stage> out = new ArrayList<>();
        BigDecimal suspenseAged = aged(suspense, now.minusDays(suspenseAgeDays == null ? 0 : suspenseAgeDays));
        if (suspenseAged.signum() > 0) {
            out.add(new Stage("SUSPENSE_TO_UNCLAIMED", suspense, unclaimed, suspenseAged));
        }
        BigDecimal unclaimedAged = aged(unclaimed, now.minusDays(unclaimedAgeDays == null ? 0 : unclaimedAgeDays));
        if (unclaimedAged.signum() > 0) {
            UUID target = "INCOME".equalsIgnoreCase(settleTo)
                    ? account("gl.income", props.gl().income())
                    : account("gl.capital", props.gl().capital());
            out.add(new Stage(StockRules.finalStage(settleTo), unclaimed, target, unclaimedAged));
        }
        return out;
    }

    /** Posted balance that has sat on the account since {@code cutoff}, capped at the current balance (never negative). */
    private BigDecimal aged(UUID account, OffsetDateTime cutoff) {
        BigDecimal atCutoff = downstream.ledgerBalanceAt(account, cutoff);
        BigDecimal now = downstream.ledgerBalance(account);
        BigDecimal capped = atCutoff.min(now);
        return capped.signum() > 0 ? capped : BigDecimal.ZERO;
    }

    private UUID ledger() {
        UUID l = props.ledgerId();
        if (l == null) {
            throw new ApiException(422, "ledger_not_configured",
                    "No ledger book is configured for this tenant (config-service ledger/agent-network-service.ledger_id or the default book)");
        }
        return l;
    }

    private static UUID account(String key, UUID value) {
        if (value == null) {
            throw new ApiException(422, "ledger_account_not_configured",
                    "No ledger account is configured for this tenant (config-service ledger/agent-network-service." + key + ")");
        }
        return value;
    }

    private static String truncate(String s) {
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
