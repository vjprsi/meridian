package io.meridian.transactionmonitoring.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.transactionmonitoring.infrastructure.Db;
import io.meridian.web.crud.CrudEngine;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Batch runs and backtests over a date range: QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED, processed in the
 * background customer by customer (each customer in its own transaction; cancellation is checked between customers).
 * Batch runs raise alerts (de-duplicated against existing ones); backtests only count would-be alerts per scenario.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    static final Duration MAX_RANGE = Duration.ofDays(400);

    private final MonitoringService monitoring;
    private final ScenarioService scenarios;
    private final ScenarioCatalog catalog;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Db db;
    private final JsonMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(2);

    public RunService(MonitoringService monitoring, ScenarioService scenarios, ScenarioCatalog catalog, CrudEngine crud,
                      OutboxWriter outbox, TransactionTemplate tx, Db db, JsonMapper json) {
        this.monitoring = monitoring;
        this.scenarios = scenarios;
        this.catalog = catalog;
        this.crud = crud;
        this.outbox = outbox;
        this.tx = tx;
        this.db = db;
        this.json = json;
    }

    /** Creates a BATCH or BACKTEST run and starts it after commit. */
    public String create(JsonNode body) {
        UUID tenant = TenantContext.require();
        String mode = Db.str(body, "mode");
        if ("STREAMING".equals(mode)) {
            throw ApiException.unprocessable("invalid_mode", "Streaming windows are recorded by the platform; create BATCH or BACKTEST runs");
        }
        OffsetDateTime from = OffsetDateTime.parse(body.get("from_ts").asString());
        OffsetDateTime to = OffsetDateTime.parse(body.get("to_ts").asString());
        if (!to.isAfter(from)) {
            throw ApiException.unprocessable("invalid_period", "to_ts must be after from_ts");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.unprocessable("period_too_long", "A run covers at most " + MAX_RANGE.toDays() + " days");
        }
        UUID versionId = Db.uuid(body, "tm_scenario_version_id");
        if ("BACKTEST".equals(mode) && versionId == null) {
            throw ApiException.unprocessable("version_required", "A backtest needs tm_scenario_version_id");
        }
        if (versionId != null) {
            db.require("tm_scenario_version", versionId, false);
        }
        ObjectNode v = crud.toColumns("tm_run", body);
        v.put("status", "QUEUED");
        String row = tx.execute(s -> crud.insert("tm_run", v));
        UUID id = UUID.fromString(json.readTree(row).get("id").asString());
        String principal = TenantContext.principal();
        executor.submit(() -> {
            try {
                slots.acquire();
                try {
                    TenantContext.callAs(tenant, principal, () -> {
                        execute(id);
                        return null;
                    });
                } finally {
                    slots.release();
                }
            } catch (Exception e) {
                log.error("tm run {} crashed", id, e);
            }
        });
        return row;
    }

    public String cancel(UUID id, String reason) {
        return tx.execute(s -> {
            JsonNode run = db.require("tm_run", id, true);
            String st = run.get("status").asString();
            if (!"QUEUED".equals(st) && !"RUNNING".equals(st)) {
                throw ApiException.conflict("invalid_transition", "Cannot cancel a run that is " + st);
            }
            db.update("update tm_run set status = 'CANCELLED', cancel_reason = ?, finished_at = now(), updated_by = ? where id = ?",
                    reason, TenantContext.principal(), id);
            JsonNode row = db.require("tm_run", id, false);
            outbox.publish("risk.tm_run.cancelled", "tm_run", id, row);
            return db.text(row);
        });
    }

    void execute(UUID id) {
        int claimed = db.update("update tm_run set status = 'RUNNING', started_at = now() where id = ? and status = 'QUEUED'", id);
        if (claimed == 0) {
            return; // cancelled before start
        }
        JsonNode run = db.require("tm_run", id, false);
        boolean backtest = "BACKTEST".equals(run.get("mode").asString());
        try {
            List<ScenarioCatalog.Active> list = run.hasNonNull("tm_scenario_version_id")
                    ? List.of(scenarios.asActive(UUID.fromString(run.get("tm_scenario_version_id").asString())))
                    : catalog.active();
            var from = OffsetDateTime.parse(run.get("from_ts").asString()).toInstant();
            var to = OffsetDateTime.parse(run.get("to_ts").asString()).toInstant();
            List<UUID> customers = db.jdbc().sql("""
                    select distinct customer_id from tm_transaction where occurred_at >= ? and occurred_at <= ?""")
                    .params(OffsetDateTime.parse(run.get("from_ts").asString()), OffsetDateTime.parse(run.get("to_ts").asString()))
                    .query(UUID.class).list();
            long txCount = db.jdbc().sql("select count(*) from tm_transaction where occurred_at >= ? and occurred_at <= ?")
                    .params(OffsetDateTime.parse(run.get("from_ts").asString()), OffsetDateTime.parse(run.get("to_ts").asString()))
                    .query(Long.class).single();
            Map<String, Integer> perScenario = new TreeMap<>();
            int alerts = 0;
            int evaluated = 0;
            for (UUID c : customers) {
                if (cancelled(id)) {
                    return;
                }
                MonitoringService.CustomerResult r = tx.execute(s -> monitoring.evaluateCustomer(c, list, from, to, id, backtest));
                for (MonitoringService.Raised raised : r.raised()) {
                    perScenario.merge(raised.scenarioCode(), 1, Integer::sum);
                }
                r.wouldAlert().forEach((k, n) -> perScenario.merge(k, n, Integer::sum));
                alerts += backtest ? r.wouldAlert().values().stream().mapToInt(Integer::intValue).sum() : r.raised().size();
                evaluated++;
            }
            Map<String, Object> results = new LinkedHashMap<>();
            results.put(backtest ? "would_alert" : "alerts", perScenario);
            results.put("scenarios", list.stream().map(a -> a.code() + "@v" + a.version()).toList());
            int a = alerts;
            int e = evaluated;
            tx.executeWithoutResult(s -> {
                db.update("""
                        update tm_run set status = 'SUCCEEDED', finished_at = now(), transactions_evaluated = ?,
                            alerts_generated = ?, customers_evaluated = ?, results = cast(? as jsonb)
                        where id = ? and status = 'RUNNING'""", txCount, a, e, json.writeValueAsString(results), id);
                outbox.publish("risk.tm_run.completed", "tm_run", id, db.require("tm_run", id, false));
            });
        } catch (RuntimeException ex) {
            log.error("tm run {} failed", id, ex);
            tx.executeWithoutResult(s -> {
                db.update("update tm_run set status = 'FAILED', finished_at = now(), error = ? where id = ? and status = 'RUNNING'",
                        String.valueOf(ex.getMessage()), id);
                outbox.publish("risk.tm_run.completed", "tm_run", id, db.require("tm_run", id, false));
            });
        }
    }

    private boolean cancelled(UUID id) {
        return "CANCELLED".equals(db.require("tm_run", id, false).get("status").asString());
    }

    /** Backtest of one scenario version (POST /scenarios/{id}/backtest). */
    public String backtest(UUID scenarioId, int version, String from, String to) {
        ScenarioCatalog.Active a = scenarios.asActive(scenarioId, version);
        ObjectNode body = json.createObjectNode();
        body.put("mode", "BACKTEST");
        body.put("tm_scenario_version_id", a.versionId().toString());
        body.put("from_ts", from);
        body.put("to_ts", to);
        return create(body);
    }
}
