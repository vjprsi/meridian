package io.meridian.limits.application;

import io.meridian.common.id.Ids;
import io.meridian.limits.domain.LimitModel.Rule;
import io.meridian.limits.infrastructure.Db;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * WE-LIMIT-1: compiles ACTIVE Transfer Control Profiles into synthetic {@link Rule}s that the existing
 * {@code LimitEngine} evaluates and {@code limit_usage} counts, so the two authoring tiers reuse one engine and one
 * counter store rather than forking a parallel path.
 *
 * <p>Every synthetic rule gets a <b>deterministic</b> id (UUIDv5 of a stable key built from the authoring row id and
 * the window/measure), so the compiled rule — and therefore its {@code limit_usage} counters, which are keyed by
 * {@code (limit_rule_id, subject_id, period_start)} — survives a cache refresh or a service restart unchanged.
 *
 * <p>Cumulative (Rank-1) profiles compile to {@code REGULATORY_PROFILE}-scoped, regulatory (never-superseded) windowed
 * rules counted against the regulatory-profile id, so usage aggregates across a customer's account groups. Instrument
 * (Rank-2) profiles compile to {@code INSTRUMENT_GRADE}-scoped rules counted per instrument; they apply alongside the
 * cumulative rules (both tiers apply) and are never allowed to supersede the regulatory cumulative rules.
 */
public final class TcpCompiler {
    private TcpCompiler() {}

    public static List<Rule> compile(List<JsonNode> cumulative, List<JsonNode> instrument) {
        List<Rule> out = new ArrayList<>();
        for (JsonNode p : cumulative) {
            out.addAll(compileCumulative(p));
        }
        for (JsonNode p : instrument) {
            out.addAll(compileInstrument(p));
        }
        return out;
    }

    /** One cumulative profile → windowed amount/count rules plus an optional max-balance rule. */
    public static List<Rule> compileCumulative(JsonNode p) {
        List<Rule> out = new ArrayList<>();
        UUID profileId = Db.uuid(p, "id");
        String code = Db.str(p, "code");
        String direction = Db.str(p, "direction");
        String ref = Db.str(p, "regulatory_profile_code");
        String currency = Db.str(p, "currency");
        String domain = Db.str(p, "domain_code");
        String category = Db.str(p, "category_code");
        String action = Db.str(p, "breach_action");
        int priority = Db.integer(p, "priority") != null ? Db.integer(p, "priority") : 100;
        boolean regulatory = Db.bool(p, "is_regulatory");
        boolean yearly = Db.bool(p, "yearly_enabled");
        String[][] windows = {{"daily", "DAILY"}, {"weekly", "WEEKLY"}, {"monthly", "MONTHLY"}};
        for (String[] w : windows) {
            addAmount(out, p, profileId, code, "REGULATORY_PROFILE", ref, direction, domain, category, null, null,
                    w[1], w[0] + "_amount", currency, action, priority, regulatory);
            addCount(out, p, profileId, code, "REGULATORY_PROFILE", ref, direction, domain, category, null, null,
                    w[1], w[0] + "_count", currency, action, priority, regulatory);
        }
        if (yearly) {
            addAmount(out, p, profileId, code, "REGULATORY_PROFILE", ref, direction, domain, category, null, null,
                    "YEARLY", "yearly_amount", currency, action, priority, regulatory);
            addCount(out, p, profileId, code, "REGULATORY_PROFILE", ref, direction, domain, category, null, null,
                    "YEARLY", "yearly_count", currency, action, priority, regulatory);
        }
        BigDecimal maxBalance = Db.dec(p, "max_balance");
        if (maxBalance != null) {
            out.add(new Rule(det(profileId, "BALANCE"), code + ":BAL", "REGULATORY_PROFILE", ref, null, null,
                    "PER_TRANSACTION", "BALANCE", currency, maxBalance, null, action, priority, regulatory,
                    direction, domain, category, null, null, null, null, null, null, null));
        }
        return out;
    }

    /** One instrument profile → a per-transaction rule (per-txn min/max, %, multiple, min-remaining) plus windowed and
     * max-balance rules. */
    public static List<Rule> compileInstrument(JsonNode p) {
        List<Rule> out = new ArrayList<>();
        UUID profileId = Db.uuid(p, "id");
        String code = Db.str(p, "code");
        String direction = Db.str(p, "direction"); // null = both sides
        String instrument = Db.str(p, "payment_instrument");
        String grade = Db.str(p, "grade_code");
        String domain = Db.str(p, "domain_code");
        String category = Db.str(p, "category_code");
        String bearer = Db.str(p, "bearer");
        String service = Db.str(p, "service_type");
        String currency = Db.str(p, "currency");
        String action = Db.str(p, "breach_action");
        int priority = Db.integer(p, "priority") != null ? Db.integer(p, "priority") : 100;
        BigDecimal perTxnMax = Db.dec(p, "per_txn_max");
        BigDecimal perTxnMin = Db.dec(p, "per_txn_min");
        BigDecimal minRemaining = Db.dec(p, "min_remaining_balance");
        BigDecimal maxPct = Db.dec(p, "max_transfer_pct");
        BigDecimal multiple = Db.dec(p, "amount_multiple");
        if (perTxnMax != null || perTxnMin != null || minRemaining != null || maxPct != null || multiple != null) {
            out.add(new Rule(det(profileId, "PERTXN"), code + ":TXN", "INSTRUMENT_GRADE", instrument, service, null,
                    "PER_TRANSACTION", "AMOUNT", currency, perTxnMax, null, action, priority, false,
                    direction, domain, category, grade, instrument, bearer, maxPct, multiple, minRemaining, perTxnMin));
        }
        addAmount(out, p, profileId, code, "INSTRUMENT_GRADE", instrument, direction, domain, category, grade, instrument,
                "DAILY", "daily_amount", currency, action, priority, false, bearer, service);
        addCount(out, p, profileId, code, "INSTRUMENT_GRADE", instrument, direction, domain, category, grade, instrument,
                "DAILY", "daily_count", currency, action, priority, false, bearer, service);
        addAmount(out, p, profileId, code, "INSTRUMENT_GRADE", instrument, direction, domain, category, grade, instrument,
                "MONTHLY", "monthly_amount", currency, action, priority, false, bearer, service);
        addCount(out, p, profileId, code, "INSTRUMENT_GRADE", instrument, direction, domain, category, grade, instrument,
                "MONTHLY", "monthly_count", currency, action, priority, false, bearer, service);
        BigDecimal maxBalance = Db.dec(p, "max_balance");
        if (maxBalance != null) {
            out.add(new Rule(det(profileId, "BALANCE"), code + ":BAL", "INSTRUMENT_GRADE", instrument, service, null,
                    "PER_TRANSACTION", "BALANCE", currency, maxBalance, null, action, priority, false,
                    direction, domain, category, grade, instrument, bearer, null, null, null, null));
        }
        return out;
    }

    private static void addAmount(List<Rule> out, JsonNode p, UUID id, String code, String scope, String ref,
                                  String direction, String domain, String category, String grade, String instrument,
                                  String period, String field, String currency, String action, int priority,
                                  boolean regulatory) {
        addAmount(out, p, id, code, scope, ref, direction, domain, category, grade, instrument, period, field, currency,
                action, priority, regulatory, null, null);
    }

    private static void addAmount(List<Rule> out, JsonNode p, UUID id, String code, String scope, String ref,
                                  String direction, String domain, String category, String grade, String instrument,
                                  String period, String field, String currency, String action, int priority,
                                  boolean regulatory, String bearer, String service) {
        BigDecimal v = Db.dec(p, field);
        if (v == null) {
            return;
        }
        out.add(new Rule(det(id, period + ":AMOUNT"), code + ":" + period + ":A", scope, ref, service, null, period,
                "AMOUNT", currency, v, null, action, priority, regulatory, direction, domain, category, grade,
                instrument, bearer, null, null, null, null));
    }

    private static void addCount(List<Rule> out, JsonNode p, UUID id, String code, String scope, String ref,
                                 String direction, String domain, String category, String grade, String instrument,
                                 String period, String field, String currency, String action, int priority,
                                 boolean regulatory) {
        addCount(out, p, id, code, scope, ref, direction, domain, category, grade, instrument, period, field, currency,
                action, priority, regulatory, null, null);
    }

    private static void addCount(List<Rule> out, JsonNode p, UUID id, String code, String scope, String ref,
                                 String direction, String domain, String category, String grade, String instrument,
                                 String period, String field, String currency, String action, int priority,
                                 boolean regulatory, String bearer, String service) {
        Integer v = Db.integer(p, field);
        if (v == null) {
            return;
        }
        out.add(new Rule(det(id, period + ":COUNT"), code + ":" + period + ":C", scope, ref, service, null, period,
                "COUNT", currency, null, v, action, priority, regulatory, direction, domain, category, grade,
                instrument, bearer, null, null, null, null));
    }

    /** Deterministic synthetic rule id: the authoring row id plus a window/measure discriminator. */
    static UUID det(UUID profileId, String discriminator) {
        return Ids.deterministic("tcp:" + profileId + ":" + discriminator);
    }
}
