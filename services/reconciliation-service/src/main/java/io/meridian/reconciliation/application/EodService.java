package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.infrastructure.ReconStore;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-tenant end-of-day reconciliation (business-day-service standard pack job RECON_DAILY): for every active source
 * linked to a ledger account, a run over its look-back window ending on the business date
 * ({@code parser_config.eod_lookback_days}, default {@value #DEFAULT_LOOKBACK}, so late value dates and date
 * shifts still pair). Idempotent per business date and source: a source that already has a SUCCEEDED run for the
 * date is skipped; a source without any statement line in the window is reported as {@code statement_missing}
 * instead of raising a break for every ledger entry. The summary carries {@code records_processed} (statement lines
 * considered) and {@code records_failed} (sources whose run failed) for the EOD job record, plus the auto-match rate.
 */
@Service
public class EodService {
    static final int DEFAULT_LOOKBACK = 3;
    private static final Logger log = LoggerFactory.getLogger(EodService.class);

    private final ReconStore store;
    private final RunService runs;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public EodService(ReconStore store, RunService runs, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json) {
        this.store = store;
        this.runs = runs;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    public Map<String, Object> endOfDay(LocalDate businessDate) {
        if (businessDate == null) {
            throw Fields.invalid("business_date", "required", "business_date is required");
        }
        List<JsonNode> sources = store.rows("""
                select row_to_json(t)::text from recon_source t
                where t.is_active and t.ledger_account_id is not null order by t.name, t.id""");
        long processed = 0;
        long matched = 0;
        long suggested = 0;
        long breaks = 0;
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        int missing = 0;
        List<Map<String, Object>> perSource = new ArrayList<>();
        for (JsonNode source : sources) {
            UUID sourceId = UUID.fromString(source.get("id").asString());
            int lookback = Math.clamp(source.path("parser_config").path("eod_lookback_days").asInt(DEFAULT_LOOKBACK), 0, 31);
            LocalDate from = businessDate.minusDays(lookback);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("recon_source_id", sourceId.toString());
            s.put("name", source.get("name").asString());
            s.put("from_date", from.toString());
            s.put("to_date", businessDate.toString());
            var done = store.row("""
                    select row_to_json(t)::text from recon_run t
                    where t.recon_source_id = ? and t.business_date = ? and t.status = 'SUCCEEDED'
                    order by t.created_at desc limit 1""", sourceId, businessDate);
            if (done.isPresent()) {
                s.put("status", "ALREADY_DONE");
                s.put("recon_run_id", done.get().get("id").asString());
                skipped++;
                perSource.add(s);
                continue;
            }
            if (store.count("select count(*) from recon_record where recon_source_id = ? and value_date between ? and ?",
                    sourceId, from, businessDate) == 0) {
                s.put("status", "STATEMENT_MISSING");
                missing++;
                perSource.add(s);
                continue;
            }
            try {
                JsonNode run = json.readTree(runs.run(source, from, businessDate, businessDate));
                s.put("recon_run_id", run.get("id").asString());
                s.put("status", run.get("status").asString());
                if ("SUCCEEDED".equals(run.get("status").asString())) {
                    succeeded++;
                    long considered = run.path("external_count").asLong(0);
                    long m = run.path("matched_count").asLong(0);
                    long sg = run.path("suggested_count").asLong(0);
                    long b = run.path("exception_count").asLong(0);
                    processed += considered;
                    matched += m;
                    suggested += sg;
                    breaks += b;
                    s.put("records", considered);
                    s.put("auto_matched", m);
                    s.put("suggested", sg);
                    s.put("breaks_raised", b);
                    s.put("auto_match_rate", run.path("auto_match_rate").isNull() ? null
                            : run.path("auto_match_rate").asDouble());
                } else {
                    failed++;
                }
            } catch (ApiException e) {
                log.warn("EOD reconciliation of source {} failed: {}", sourceId, e.getMessage());
                s.put("status", "FAILED");
                s.put("error", e.getMessage());
                failed++;
            }
            perSource.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_date", businessDate.toString());
        out.put("sources", sources.size());
        out.put("runs_succeeded", succeeded);
        out.put("runs_failed", failed);
        out.put("runs_skipped", skipped);
        out.put("statements_missing", missing);
        out.put("records_processed", processed);
        out.put("records_failed", (long) failed);
        out.put("auto_matched", matched);
        out.put("suggested", suggested);
        out.put("breaks_raised", breaks);
        out.put("auto_match_rate", processed == 0 ? null : Math.round(matched * 1_000_000.0 / processed) / 1_000_000.0);
        out.put("open_breaks", store.count("select count(*) from recon_exception where status in ('OPEN', 'INVESTIGATING')"));
        out.put("per_source", perSource);
        UUID eventId = UUID.nameUUIDFromBytes(("recon-eod:" + TenantContext.require() + ":" + businessDate)
                .getBytes(StandardCharsets.UTF_8));
        tx.executeWithoutResult(t -> outbox.publish("ledger.recon_eod.completed", "recon_run", eventId, json.valueToTree(out)));
        return out;
    }
}
