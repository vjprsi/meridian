package io.meridian.alm.application;

import io.meridian.alm.domain.Assumption;
import io.meridian.alm.domain.GapCalculator;
import io.meridian.alm.domain.Position;
import io.meridian.alm.infrastructure.Rows;
import io.meridian.common.id.Ids;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Repricing gap runs, computed synchronously on create: positions are bucketed by repricing date with the approved
 * behavioural assumptions (when behavioural), buckets stored append-only, the run completes or fails with a reason.
 */
@Service
public class GapService {
    static final String TABLE = "alm_gap_run";

    private final Rows rows;
    private final Events events;
    private final AssumptionService assumptions;

    public GapService(Rows rows, Events events, AssumptionService assumptions) {
        this.rows = rows;
        this.events = events;
        this.assumptions = assumptions;
    }

    @Transactional
    public JsonNode run(Object body, JsonNode req) {
        List<Position> all = Inputs.positions(req.get("positions"), "positions");
        ObjectNode values = rows.columns(TABLE, body);
        boolean behavioural = !values.has("behavioural") || values.get("behavioural").asBoolean();
        values.put("behavioural", behavioural);
        String currency = Rows.str(values, "currency");
        LocalDate asOf = Rows.date(values, "as_of_date");
        List<Position> positions = all.stream()
                .filter(p -> currency == null || p.currency() == null || currency.equals(p.currency())).toList();
        values.put("status", "RUNNING");
        ObjectNode run = rows.insert(TABLE, values);
        rows.execute("update alm_gap_run set started_at = now() where id = ?", Rows.uuid(run, "id"));
        UUID id = Rows.uuid(run, "id");
        List<Assumption> approved = behavioural ? assumptions.approved() : List.of();
        GapCalculator.Result result;
        try {
            result = GapCalculator.compute(asOf, positions, behavioural, approved);
        } catch (GapCalculator.UnbucketablePosition e) {
            ObjectNode failed = rows.queryOne("""
                    update alm_gap_run t set status = 'FAILED', error_message = ?, finished_at = now()
                    where id = ? returning row_to_json(t)::text""", truncate(e.getMessage()), id).orElseThrow();
            events.publish("treasury.alm_gap_run.failed", TABLE, failed);
            return failed;
        }
        List<ObjectNode> buckets = new ArrayList<>();
        for (GapCalculator.BucketResult b : result.buckets()) {
            ObjectNode r = rows.object();
            r.put("id", Ids.newId().toString());
            r.put("alm_gap_run_id", id.toString());
            r.put("bucket_code", b.def().code());
            r.put("bucket_order", b.def().order());
            r.put("rate_sensitive_assets", b.assets());
            r.put("rate_sensitive_liabilities", b.liabilities());
            r.put("off_balance_net", b.offBalance());
            r.put("gap", b.gap());
            r.put("cumulative_gap", b.cumulativeGap());
            ObjectNode bd = r.putObject("breakdown");
            b.breakdown().forEach(bd::put);
            buckets.add(r);
        }
        rows.insertMany("alm_gap_bucket", buckets);
        ArrayNode used = rows.json().createArrayNode();
        result.assumptionsUsed().forEach(u -> used.add(u.toString()));
        ObjectNode done = rows.queryOne("""
                update alm_gap_run t set status = 'SUCCEEDED', total_assets = ?, total_liabilities = ?,
                       assumptions_used = ?::jsonb, finished_at = now()
                where id = ? returning row_to_json(t)::text""",
                result.totalAssets(), result.totalLiabilities(), used.toString(), id).orElseThrow();
        events.publish("treasury.alm_gap_run.completed", TABLE, done);
        return done;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
