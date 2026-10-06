package io.meridian.liquidity.application;

import io.meridian.common.error.ApiException;
import io.meridian.liquidity.domain.Amounts;
import io.meridian.liquidity.domain.CashFlow;
import io.meridian.liquidity.domain.MaturityLadder;
import io.meridian.liquidity.domain.MaturityLadder.Bucket;
import io.meridian.liquidity.infrastructure.FlowSources;
import io.meridian.liquidity.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maturity ladder runs: computed synchronously from the request items (or, when none are given, the contractual
 * flows of live treasury deals) into the standard buckets.
 */
@Service
public class LadderService {
    static final String RUN = "liquidity_ladder_run";
    static final String BUCKET = "liquidity_bucket";
    /** Horizon of treasury flows pulled when the request carries no items. */
    static final int TREASURY_HORIZON_DAYS = 3650;

    private final Rows rows;
    private final Events events;
    private final FlowSources sources;

    public LadderService(Rows rows, Events events, FlowSources sources) {
        this.rows = rows;
        this.events = events;
        this.sources = sources;
    }

    @Transactional
    public ObjectNode create(Object body) {
        JsonNode req = rows.json().valueToTree(body);
        ObjectNode v = rows.columns(RUN, body);
        LocalDate asOf = Rows.date(v, "as_of_date");
        UUID entity = Rows.uuid(v, "legal_entity_id");
        String currency = Rows.str(v, "currency");
        boolean behavioural = !v.has("behavioural") || v.get("behavioural").asBoolean();
        List<JsonNode> raw = Inputs.list(req, "items");
        List<MaturityLadder.Item> items = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            parseItem(raw.get(i), i, asOf, currency).ifPresent(items::add);
        }
        // validate before persisting anything: a malformed request creates no run
        if (!raw.isEmpty()) {
            Inputs.domain(() -> MaturityLadder.build(items, behavioural));
        }
        v.put("status", "RUNNING");
        v.put("behavioural", behavioural);
        v.put("started_at", rows.dbNow());
        ObjectNode run = rows.insert(RUN, v);
        UUID runId = Rows.uuid(run, "id");
        if (raw.isEmpty()) {
            try {
                for (CashFlow f : sources.treasuryFlows(asOf, asOf.plusDays(TREASURY_HORIZON_DAYS), entity)) {
                    if (currency == null || currency.equals(f.currency())) {
                        items.add(new MaturityLadder.Item(f.category(), f.inflow(), f.amount(),
                                ChronoUnit.DAYS.between(asOf, f.valueDate()), List.of()));
                    }
                }
            } catch (ApiException e) {
                ObjectNode failed = rows.object();
                failed.put("status", "FAILED");
                failed.put("error_message", truncate("treasury flows unavailable: " + e.getMessage(), 500));
                failed.put("finished_at", rows.dbNow());
                return rows.update(RUN, runId, failed);
            }
        }
        List<MaturityLadder.Row> ladder = Inputs.domain(() -> MaturityLadder.build(items, behavioural));
        List<ObjectNode> buckets = new ArrayList<>();
        BigDecimal totalIn = BigDecimal.ZERO;
        BigDecimal totalOut = BigDecimal.ZERO;
        for (MaturityLadder.Row r : ladder) {
            ObjectNode b = rows.object();
            b.put("id", io.meridian.common.id.Ids.newId().toString());
            b.put("liquidity_ladder_run_id", runId.toString());
            b.put("bucket_code", r.bucket().code);
            b.put("bucket_order", r.bucket().order());
            b.put("start_day", r.bucket().startDay);
            if (r.bucket().endDay == null) {
                b.putNull("end_day");
            } else {
                b.put("end_day", r.bucket().endDay);
            }
            b.put("inflows", r.inflows().toPlainString());
            b.put("outflows", r.outflows().toPlainString());
            b.put("net_gap", r.netGap().toPlainString());
            b.put("cumulative_gap", r.cumulativeGap().toPlainString());
            ObjectNode breakdown = b.putObject("breakdown");
            r.breakdown().forEach((cat, cell) -> {
                ObjectNode c = breakdown.putObject(cat);
                c.put("inflows", Amounts.money(cell[0]).toPlainString());
                c.put("outflows", Amounts.money(cell[1]).toPlainString());
            });
            buckets.add(b);
            totalIn = totalIn.add(r.inflows());
            totalOut = totalOut.add(r.outflows());
        }
        rows.crudInsertMany(BUCKET, buckets);
        ObjectNode done = rows.object();
        done.put("status", "SUCCEEDED");
        done.put("total_inflows", totalIn.toPlainString());
        done.put("total_outflows", totalOut.toPlainString());
        Bucket negative = MaturityLadder.firstNegative(ladder);
        if (negative == null) {
            done.putNull("negative_gap_bucket");
        } else {
            done.put("negative_gap_bucket", negative.code);
        }
        done.put("finished_at", rows.dbNow());
        ObjectNode result = rows.update(RUN, runId, done);
        events.publish("treasury.liquidity_ladder.completed", RUN, result);
        return result;
    }

    private java.util.Optional<MaturityLadder.Item> parseItem(JsonNode n, int i, LocalDate asOf, String runCurrency) {
        String ctx = "items[" + i + "]";
        String ccy = Inputs.currency(n, "currency", ctx, false);
        String cat = Inputs.text(n, "category");
        String category = cat == null ? "OTHER" : cat.toUpperCase(Locale.ROOT);
        if (!CashFlow.isCategory(category)) {
            throw ApiException.unprocessable("invalid_category", ctx + ": category must be one of " + CashFlow.CATEGORIES);
        }
        String direction = Inputs.requiredText(n, "direction", ctx).toUpperCase(Locale.ROOT);
        if (!direction.equals("IN") && !direction.equals("OUT")) {
            throw ApiException.unprocessable("invalid_direction", ctx + ": direction must be IN or OUT");
        }
        BigDecimal amount = Inputs.decimal(n, "amount", ctx, true);
        LocalDate maturity = Inputs.date(n, "maturity_date", ctx);
        Integer days = Inputs.integer(n, "days", ctx);
        if (days != null && days < 0) {
            throw ApiException.unprocessable("invalid_days", ctx + ": days must not be negative");
        }
        Long residual = maturity != null ? Long.valueOf(ChronoUnit.DAYS.between(asOf, maturity))
                : days == null ? null : Long.valueOf(days);
        List<MaturityLadder.Share> profile = new ArrayList<>();
        for (JsonNode s : Inputs.list(n, "behavioural_profile")) {
            String code = Inputs.requiredText(s, "bucket_code", ctx + ".behavioural_profile");
            Bucket b = Bucket.ofCode(code);
            if (b == null) {
                throw ApiException.unprocessable("invalid_behavioural_profile", ctx + ": unknown bucket_code " + code);
            }
            profile.add(new MaturityLadder.Share(b, Inputs.decimal(s, "share", ctx + ".behavioural_profile", true)));
        }
        if (runCurrency != null && ccy != null && !runCurrency.equals(ccy)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new MaturityLadder.Item(category, direction.equals("IN"), amount, residual, profile));
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
