package io.meridian.reconciliation.application;

import io.meridian.reconciliation.infrastructure.ReconStore;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Reconciliation quality metrics over the runs of a date range (business date of EOD runs, else the start date):
 * auto-match rate overall, per source, per rule and per run, manual / accepted matches, and the ageing of the open
 * breaks (0-1, 2-7, 8-30, 31+ days) by reason, with the unassigned count and the oldest age.
 */
@Service
public class MetricsService {
    private static final int MAX_RANGE_DAYS = 366;
    private static final String RUNS = " " + """
            from recon_run r where r.status = 'SUCCEEDED'
              and coalesce(r.business_date, (r.started_at at time zone 'UTC')::date) between ? and ?
              and (?::uuid is null or r.recon_source_id = ?::uuid)""";

    private final ReconStore store;

    public MetricsService(ReconStore store) {
        this.store = store;
    }

    public Map<String, Object> metrics(LocalDate from, LocalDate to, UUID sourceId) {
        if (to.isBefore(from)) {
            throw io.meridian.common.error.ApiException.unprocessable("invalid_date_range", "to_date must not be before from_date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw io.meridian.common.error.ApiException.unprocessable("date_range_too_long",
                    "Metrics cover at most " + MAX_RANGE_DAYS + " days");
        }
        Object[] p = {from, to, sourceId, sourceId};
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from_date", from.toString());
        out.put("to_date", to.toString());
        if (sourceId != null) {
            out.put("recon_source_id", sourceId.toString());
        }
        JsonNode totals = store.row("""
                select json_build_object('runs', count(*), 'external_count', coalesce(sum(r.external_count), 0),
                       'auto_matched', coalesce(sum(r.matched_count), 0), 'suggested', coalesce(sum(r.suggested_count), 0),
                       'breaks_raised', coalesce(sum(r.exception_count), 0))::text""" + RUNS, p).orElseThrow();
        Map<String, Object> t = new LinkedHashMap<>();
        totals.properties().forEach(e -> t.put(e.getKey(), e.getValue().asLong()));
        t.put("auto_match_rate", rate(totals.get("auto_matched").asLong(), totals.get("external_count").asLong()));
        out.put("totals", t);

        List<Map<String, Object>> bySource = new ArrayList<>();
        for (JsonNode r : store.rows("""
                select json_build_object('recon_source_id', r.recon_source_id, 'name', min(s.name), 'runs', count(*),
                       'external_count', coalesce(sum(r.external_count), 0), 'auto_matched', coalesce(sum(r.matched_count), 0),
                       'suggested', coalesce(sum(r.suggested_count), 0))::text
                from recon_run r join recon_source s on s.id = r.recon_source_id
                where r.status = 'SUCCEEDED'
                  and coalesce(r.business_date, (r.started_at at time zone 'UTC')::date) between ? and ?
                  and (?::uuid is null or r.recon_source_id = ?::uuid)
                group by r.recon_source_id order by min(s.name)""", p)) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.properties().forEach(e -> m.put(e.getKey(), e.getValue().isNumber() ? e.getValue().asLong() : e.getValue().asString()));
            m.put("auto_match_rate", rate(r.get("auto_matched").asLong(), r.get("external_count").asLong()));
            bySource.add(m);
        }
        out.put("by_source", bySource);

        List<Map<String, Object>> byRule = new ArrayList<>();
        long matchedLines = 0;
        List<JsonNode> ruleRows = store.rows("""
                select json_build_object('rule', x.rule, 'manual', x.manual, 'matches', x.matches,
                       'external_matched', x.external_matched, 'avg_confidence', x.avg_confidence)::text
                from (select coalesce(m.rule_code, m.recon_rule_id::text,
                             case when m.confirmed_by is null then 'auto' else 'manual' end) as rule,
                         m.confirmed_by is not null as manual, count(*) as matches,
                         coalesce(sum(case when jsonb_typeof(m.external_record_ids) = 'array'
                             then jsonb_array_length(m.external_record_ids) else 0 end), 0) as external_matched,
                         round(avg(m.confidence)::numeric, 4) as avg_confidence
                      from recon_match m join recon_run r on r.id = m.recon_run_id
                      where m.unmatched_at is null and r.status = 'SUCCEEDED'
                        and coalesce(r.business_date, (r.started_at at time zone 'UTC')::date) between ? and ?
                        and (?::uuid is null or r.recon_source_id = ?::uuid)
                      group by 1, 2) x
                order by x.matches desc, x.rule""", p);
        for (JsonNode r : ruleRows) {
            matchedLines += r.get("external_matched").asLong();
        }
        long considered = totals.get("external_count").asLong();
        for (JsonNode r : ruleRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", r.get("rule").asString());
            m.put("manual", r.get("manual").asBoolean());
            m.put("matches", r.get("matches").asLong());
            m.put("external_matched", r.get("external_matched").asLong());
            m.put("share_of_records", rate(r.get("external_matched").asLong(), considered));
            m.put("avg_confidence", r.get("avg_confidence").isNull() ? null : r.get("avg_confidence").asDouble());
            byRule.add(m);
        }
        out.put("by_rule", byRule);
        out.put("matched_records", matchedLines);

        List<Map<String, Object>> byRun = new ArrayList<>();
        for (JsonNode r : store.rows("""
                select json_build_object('id', r.id, 'recon_source_id', r.recon_source_id, 'business_date', r.business_date,
                       'from_date', r.from_date, 'to_date', r.to_date, 'started_at', r.started_at,
                       'external_count', r.external_count, 'matched_count', r.matched_count,
                       'suggested_count', r.suggested_count, 'exception_count', r.exception_count,
                       'auto_match_rate', r.auto_match_rate, 'recon_rule_set_id', r.recon_rule_set_id)::text
                """ + RUNS + " order by r.started_at desc limit 100", p)) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.properties().forEach(e -> m.put(e.getKey(), e.getValue().isNull() ? null
                    : e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asString()));
            byRun.add(m);
        }
        out.put("by_run", byRun);
        out.put("open_breaks", ageing(sourceId));
        return out;
    }

    /** Open breaks (OPEN / INVESTIGATING) by age bucket and reason. */
    private Map<String, Object> ageing(UUID sourceId) {
        Object[] p = {sourceId, sourceId};
        String scope = """
                from recon_exception e join recon_run r on r.id = e.recon_run_id
                where e.status in ('OPEN', 'INVESTIGATING') and (?::uuid is null or r.recon_source_id = ?::uuid)""";
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (String b : List.of("0-1", "2-7", "8-30", "31+")) {
            buckets.put(b, 0L);
        }
        Map<String, Map<String, Long>> byReason = new LinkedHashMap<>();
        long total = 0;
        for (JsonNode r : store.rows("""
                select json_build_object('reason', x.reason, 'bucket', x.bucket, 'n', x.n)::text
                from (select e.reason, case
                             when current_date - e.created_at::date <= 1 then '0-1'
                             when current_date - e.created_at::date <= 7 then '2-7'
                             when current_date - e.created_at::date <= 30 then '8-30' else '31+' end as bucket,
                         count(*) as n""" + " " + scope + " group by 1, 2) x", p)) {
            long n = r.get("n").asLong();
            total += n;
            buckets.merge(r.get("bucket").asString(), n, Long::sum);
            byReason.computeIfAbsent(r.get("reason").asString(), k -> new LinkedHashMap<>())
                    .merge(r.get("bucket").asString(), n, Long::sum);
        }
        out.put("total", total);
        out.put("by_age", buckets);
        out.put("by_reason", byReason);
        out.put("unassigned", store.count("select count(*) " + scope + " and e.assigned_to is null", p));
        out.put("oldest_days", store.count("select coalesce(max(current_date - e.created_at::date), 0) " + scope, p));
        return out;
    }

    private static Double rate(long n, long d) {
        return d == 0 ? null : Math.round(n * 1_000_000.0 / d) / 1_000_000.0;
    }
}
