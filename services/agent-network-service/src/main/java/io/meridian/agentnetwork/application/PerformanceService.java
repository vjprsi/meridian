package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.infrastructure.Downstream;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-of-day agent KPIs: one append-only row per agent (all outlets, with commission earned) and per outlet with
 * activity, from wallet-service transaction summaries and commission-service earnings. Idempotent per business date:
 * existing rows are kept.
 */
@Service
public class PerformanceService {
    private static final Logger log = LoggerFactory.getLogger(PerformanceService.class);

    private final Rows rows;
    private final Downstream downstream;
    private final TransactionTemplate tx;

    public PerformanceService(Rows rows, Downstream downstream, TransactionTemplate tx) {
        this.rows = rows;
        this.downstream = downstream;
        this.tx = tx;
    }

    public ObjectNode aggregate(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(1);
        List<ObjectNode> agents = rows.query("""
                select row_to_json(a)::text from agent a
                where a.float_wallet_id is not null and a.onboarded_at < ?::timestamptz
                  and (a.terminated_at is null or a.terminated_at >= ?::timestamptz)
                order by a.agent_code""", to.toString(), from.toString());
        int written = 0;
        int skipped = 0;
        ArrayNode failed = rows.json().createArrayNode();
        for (ObjectNode a : agents) {
            UUID agentId = Rows.uuid(a, "id");
            try {
                if (exists(agentId, null, date)) {
                    skipped++;
                } else {
                    JsonNode summary = downstream.summary(agentId, null, from, to);
                    BigDecimal commission = commission(agentId, date);
                    write(agentId, null, date, Rows.str(a, "currency"), summary, commission);
                    written++;
                }
                for (ObjectNode o : rows.query("select row_to_json(o)::text from agent_outlet o where o.agent_id = ?", agentId)) {
                    UUID outletId = Rows.uuid(o, "id");
                    if (exists(agentId, outletId, date)) {
                        skipped++;
                        continue;
                    }
                    JsonNode summary = downstream.summary(null, outletId, from, to);
                    if (summary.path("groups").isEmpty()) {
                        continue;
                    }
                    write(agentId, outletId, date, Rows.str(a, "currency"), summary, BigDecimal.ZERO);
                    written++;
                }
            } catch (ApiException e) {
                log.warn("performance of agent {} for {} not aggregated: {}", agentId, date, e.getMessage());
                failed.add(agentId.toString());
            }
        }
        ObjectNode out = rows.object();
        out.put("business_date", date.toString());
        out.put("agents", agents.size());
        out.put("rows_written", written);
        out.put("rows_existing", skipped);
        out.set("failed_agent_ids", failed);
        return out;
    }

    private boolean exists(UUID agent, UUID outlet, LocalDate date) {
        return rows.scalar("""
                select count(*) from agent_performance_daily
                where agent_id = ? and agent_outlet_id is not distinct from ? and business_date = ?""",
                Long.class, agent, outlet, date).orElse(0L) > 0;
    }

    private BigDecimal commission(UUID agent, LocalDate date) {
        try {
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode g : downstream.commissionSummary(agent, date, date).path("groups")) {
                total = total.add(new BigDecimal(g.path("net").asString(g.path("earned").asString("0"))));
            }
            return total;
        } catch (ApiException e) {
            log.warn("commission summary of agent {} unavailable: {}", agent, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private void write(UUID agent, UUID outlet, LocalDate date, String currency, JsonNode summary, BigDecimal commission) {
        ObjectNode r = rows.object();
        r.put("agent_id", agent.toString());
        if (outlet != null) {
            r.put("agent_outlet_id", outlet.toString());
        }
        r.put("business_date", date.toString());
        r.put("currency", currency);
        r.put("cash_in_count", OutletService.count(summary, "CASH_IN", "count"));
        r.put("cash_in_amount", OutletService.sum(summary, "CASH_IN", currency).toPlainString());
        r.put("cash_out_count", OutletService.count(summary, "CASH_OUT", "count"));
        r.put("cash_out_amount", OutletService.sum(summary, "CASH_OUT", currency).toPlainString());
        r.put("bill_pay_count", OutletService.count(summary, "BILL_PAYMENT", "count")
                + OutletService.count(summary, "AIRTIME", "count"));
        r.put("onboarding_count", 0);
        r.put("commission_earned", commission.toPlainString());
        // customers served: cash-in recipients (one per cash-in) plus distinct cash-out payers (upper bound)
        r.put("active_customers", OutletService.count(summary, "CASH_IN", "count")
                + OutletService.count(summary, "CASH_OUT", "distinct_payers"));
        tx.executeWithoutResult(s -> rows.insert("agent_performance_daily", r));
    }
}
