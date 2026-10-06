package io.meridian.campaign.application;

import static io.meridian.campaign.application.Json.text;

import io.meridian.campaign.domain.CampaignMath;
import io.meridian.campaign.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Batch jobs of the current tenant for one business date, called by business-day-service. Every execution is
 * recorded in {@code campaign_job_run} (one row per tenant, job and date): a COMPLETED job is replayed
 * ({@code replayed=true}) and never executed twice; COMPLETED_WITH_ERRORS may be re-run (each step is idempotent);
 * a RUNNING job is refused with 409 unless stale.
 */
@Component
public class CampaignJobs {
    private static final Logger log = LoggerFactory.getLogger(CampaignJobs.class);
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    public enum Job { LIFECYCLE, METRICS_ROLLUP, OFFER_EXPIRY }

    private final Rows rows;
    private final CampaignService campaigns;
    private final OfferService offers;
    private final TransactionTemplate tx;

    public CampaignJobs(Rows rows, CampaignService campaigns, OfferService offers, TransactionTemplate tx) {
        this.rows = rows;
        this.campaigns = campaigns;
        this.offers = offers;
        this.tx = tx;
    }

    public ObjectNode run(Job job, LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = tx.execute(s -> claim(job, businessDate));
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("""
                    select row_to_json(r)::text from campaign_job_run r where job = ? and business_date = ?""",
                    job.name(), businessDate).orElseThrow();
            if ("RUNNING".equals(text(existing, "status"))) {
                throw ApiException.conflict("job_running", job + " for " + businessDate + " is already running");
            }
            return response(existing, true);
        }
        UUID runId = Json.uuid(claimed.get(), "id");
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            switch (job) {
                case LIFECYCLE -> lifecycle(counts, errors);
                case METRICS_ROLLUP -> rollup(businessDate, counts, errors);
                case OFFER_EXPIRY -> {
                    int[] r = tx.execute(s -> offers.expireDue());
                    counts.put("offers_expired", r[0]);
                    counts.put("assignments_expired", r[1]);
                }
            }
        } catch (RuntimeException e) {
            log.error("campaign job {} for {} failed", job, businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = tx.execute(s -> rows.queryOne("""
                update campaign_job_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(campaign_job_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow());
        return response(done, false);
    }

    private void lifecycle(Map<String, Integer> counts, List<String> errors) {
        int launched = 0;
        for (ObjectNode c : rows.query("""
                select row_to_json(t)::text from campaign t where t.status = 'SCHEDULED' and t.start_at <= now()
                and (t.end_at is null or t.end_at > now()) and t.audience_built_at is not null order by t.start_at""")) {
            UUID id = Json.uuid(c, "id");
            launched += guarded("launch " + id, errors, () -> {
                campaigns.launch(id);
                return 1;
            });
        }
        int completed = 0;
        for (ObjectNode c : rows.query("""
                select row_to_json(t)::text from campaign t where t.status in ('RUNNING', 'PAUSED') and t.end_at <= now()""")) {
            UUID id = Json.uuid(c, "id");
            completed += guarded("complete " + id, errors, () -> {
                campaigns.complete(id);
                return 1;
            });
        }
        counts.put("launched", launched);
        counts.put("completed", completed);
    }

    /**
     * One immutable metric row per variant and one campaign total (variant empty) for the day: counts of the day,
     * cumulative rates through the end of the day, uplift of treated members over the control group.
     */
    private void rollup(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        OffsetDateTime from = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(1);
        int campaignsRolled = 0;
        int metricRows = 0;
        for (ObjectNode c : rows.query("""
                select row_to_json(t)::text from campaign t where exists (select 1 from campaign_audience_member m
                where m.campaign_id = t.id and m.created_at < ?) order by t.id""", to)) {
            UUID id = Json.uuid(c, "id");
            int n = guarded("rollup " + id, errors, () -> tx.execute(s -> rollupCampaign(c, from, to, date)));
            metricRows += n;
            campaignsRolled++;
        }
        counts.put("campaigns", campaignsRolled);
        counts.put("metric_rows", metricRows);
    }

    private int rollupCampaign(ObjectNode c, OffsetDateTime from, OffsetDateTime to, LocalDate date) {
        UUID id = Json.uuid(c, "id");
        BigDecimal cpm = Json.decimal(c, "cost_per_message");
        long[] control = groupCounts(id, "is_control", from, to);
        List<String[]> variants = new ArrayList<>();
        rows.jdbc().sql("""
                select distinct campaign_variant_id::text, variant_code from campaign_audience_member
                where campaign_id = ? and not is_control and campaign_variant_id is not null order by 2""")
                .param(id).query((rs, i) -> variants.add(new String[] {rs.getString(1), rs.getString(2)})).list();
        int written = 0;
        long[] treated = groupCounts(id, "not is_control", from, to);
        for (String[] v : variants) {
            long[] g = groupCounts(id, "not is_control and campaign_variant_id = '" + UUID.fromString(v[0]) + "'::uuid", from, to);
            written += insertMetric(id, UUID.fromString(v[0]), v[1], date, g, control, cpm, 0);
        }
        int impressions = rows.count("""
                select count(*) from offer_assignment where campaign_id = ? and shown_at >= ? and shown_at < ?""", id, from, to);
        long[] total = treated.clone();
        total[0] += control[0];
        written += insertMetric(id, null, null, date, total, control, cpm, impressions, treated);
        return written;
    }

    private int insertMetric(UUID campaignId, UUID variantId, String code, LocalDate date, long[] g, long[] control,
                             BigDecimal cpm, int impressions) {
        return insertMetric(campaignId, variantId, code, date, g, control, cpm, impressions, g);
    }

    /**
     * g = [audience, sentDay, openedDay, clickedDay, convertedDay, unsubscribedDay, revenueDay(1e-8), sentCum,
     * clickedCum, convertedCum]; {@code rates} supplies the treated cumulative figures for rates and uplift.
     */
    private int insertMetric(UUID campaignId, UUID variantId, String code, LocalDate date, long[] g, long[] control,
                             BigDecimal cpm, int impressions, long[] rates) {
        boolean exists = rows.count(variantId == null
                ? "select count(*) from campaign_metric where campaign_id = ? and campaign_variant_id is null and metric_date = ?"
                : "select count(*) from campaign_metric where campaign_id = ? and campaign_variant_id = '" + variantId
                        + "'::uuid and metric_date = ?", campaignId, date) > 0;
        if (exists) {
            return 0;
        }
        ObjectNode m = rows.object();
        m.put("campaign_id", campaignId.toString());
        if (variantId != null) {
            m.put("campaign_variant_id", variantId.toString());
            m.put("variant_code", code);
        }
        m.put("metric_date", date.toString());
        m.put("audience", g[0]);
        m.put("sent", g[1]);
        m.put("delivered", g[1]);
        m.put("impressions", impressions);
        m.put("opened", g[2]);
        m.put("clicked", g[3]);
        m.put("converted", g[4]);
        m.put("unsubscribed", g[5]);
        m.put("revenue", BigDecimal.valueOf(g[6], 8).toPlainString());
        m.put("cost", cpm.multiply(BigDecimal.valueOf(g[1])).toPlainString());
        long treatedMembers = rates == g ? g[0] : rates[0];
        AudienceService.putDecimal(m, "ctr", CampaignMath.rate(rates[8], rates[7]));
        AudienceService.putDecimal(m, "conversion_rate", CampaignMath.rate(rates[9], treatedMembers));
        m.put("control_audience", control[0]);
        m.put("control_converted", control[9]);
        AudienceService.putDecimal(m, "uplift_pct", CampaignMath.uplift(rates[9], treatedMembers, control[9], control[0]));
        rows.insert("campaign_metric", m);
        return 1;
    }

    /** Counts of one member group; {@code predicate} is an internal SQL fragment (never user input). */
    private long[] groupCounts(UUID campaignId, String predicate, OffsetDateTime from, OffsetDateTime to) {
        return rows.jdbc().sql("select count(*) filter (where created_at < :to),"
                        + " count(*) filter (where sent_at >= :from and sent_at < :to),"
                        + " count(*) filter (where opened_at >= :from and opened_at < :to),"
                        + " count(*) filter (where clicked_at >= :from and clicked_at < :to),"
                        + " count(*) filter (where converted_at >= :from and converted_at < :to),"
                        + " count(*) filter (where unsubscribed_at >= :from and unsubscribed_at < :to),"
                        + " coalesce(sum(revenue) filter (where converted_at >= :from and converted_at < :to), 0)::text,"
                        + " count(*) filter (where sent_at < :to),"
                        + " count(*) filter (where clicked_at < :to),"
                        + " count(*) filter (where converted_at < :to)"
                        + " from campaign_audience_member where campaign_id = :id and " + predicate)
                .param("from", from).param("to", to).param("id", campaignId)
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), new BigDecimal(rs.getString(7)).movePointRight(8).longValue(), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10)})
                .single();
    }

    private Optional<ObjectNode> claim(Job job, LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into campaign_job_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
                                                   created_by, updated_by)
                values (?, ?, ?, ?, 'RUNNING', 1, now(), ?, ?)
                on conflict (tenant_id, job, business_date) do update
                  set status = 'RUNNING', attempts = r.attempts + 1, started_at = now(), completed_at = null,
                      result = null, updated_at = now(), updated_by = excluded.updated_by
                  where r.status = 'COMPLETED_WITH_ERRORS'
                     or (r.status = 'RUNNING' and r.started_at < now() - make_interval(hours => ?))
                returning row_to_json(r)::text""",
                Ids.newId(), TenantContext.require(), job.name(), date, principal, principal, STALE_HOURS);
    }

    private ObjectNode response(ObjectNode run, boolean replayed) {
        ObjectNode out = rows.object();
        out.put("run_id", text(run, "id"));
        out.put("job", text(run, "job"));
        out.put("business_date", text(run, "business_date"));
        out.put("status", text(run, "status"));
        out.put("attempts", run.path("attempts").asInt(1));
        out.put("replayed", replayed);
        out.put("started_at", text(run, "started_at"));
        out.put("completed_at", text(run, "completed_at"));
        if (run.path("result").isObject()) {
            out.setAll((ObjectNode) run.get("result"));
        }
        return out;
    }

    private static int guarded(String what, List<String> errors, IntSupplier step) {
        try {
            return step.getAsInt();
        } catch (RuntimeException e) {
            log.error("campaign job step '{}' failed", what, e);
            errors.add(what + ": " + describe(e));
            return 0;
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }
}
