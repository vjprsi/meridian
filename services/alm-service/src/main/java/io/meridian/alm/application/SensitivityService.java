package io.meridian.alm.application;

import io.meridian.alm.domain.Assumption;
import io.meridian.alm.domain.Assumption.AssumptionType;
import io.meridian.alm.domain.Curve;
import io.meridian.alm.domain.IrrbbShocks;
import io.meridian.alm.domain.IrrbbShocks.ShockType;
import io.meridian.alm.domain.Position;
import io.meridian.alm.domain.SensitivityCalculator;
import io.meridian.alm.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * NII / EVE / EaR sensitivity runs under IRRBB shocks, computed synchronously on create, and the six Basel standard
 * shocks in one call with the EVE outlier test (decline beyond 15% of Tier 1).
 */
@Service
public class SensitivityService {
    static final String TABLE = "alm_sensitivity_run";
    static final BigDecimal OUTLIER_THRESHOLD = new BigDecimal("0.15");
    private static final int[] CURVE_TENORS = {30, 90, 180, 365, 730, 1825, 3650, 7300};

    private final Rows rows;
    private final Events events;
    private final AssumptionService assumptions;
    private final FtpService ftp;

    public SensitivityService(Rows rows, Events events, AssumptionService assumptions, FtpService ftp) {
        this.rows = rows;
        this.events = events;
        this.assumptions = assumptions;
        this.ftp = ftp;
    }

    /** Inputs shared by one run and the standard-shocks batch. */
    record Inputs(String legalEntityId, LocalDate asOf, String currency, List<Position> positions, Curve baseCurve,
                  BigDecimal tier1, int horizonMonths) {}

    @Transactional
    public JsonNode create(Object body, JsonNode req) {
        ObjectNode values = rows.columns(TABLE, body);
        String measure = Rows.str(values, "measure");
        ShockType shock = ShockType.valueOf(Rows.str(values, "shock_type"));
        Integer bps = Rows.integer(values, "shock_bps");
        if (bps != null && (bps <= 0 || bps > 1000)) {
            throw ApiException.unprocessable("invalid_shock", "shock_bps must be 1-1000 (the direction comes from shock_type)");
        }
        Curve custom = io.meridian.alm.application.Inputs.curve(req.get("custom_shock"), "custom_shock", "bps");
        if (shock == ShockType.CUSTOM && custom == null) {
            throw ApiException.unprocessable("custom_shock_required", "CUSTOM shocks need custom_shock [{tenor_days, bps}]");
        }
        Inputs in = inputs(req, "EVE".equals(measure));
        values.put("horizon_months", in.horizonMonths());
        return compute(values, in, measure, shock, IrrbbShocks.Params.standard(bps, custom));
    }

    @Transactional
    public JsonNode standardShocks(JsonNode req) {
        Inputs in = inputs(req, true);
        ArrayNode runs = rows.json().createArrayNode();
        BigDecimal worstEve = null;
        BigDecimal worstNii = null;
        boolean outlier = false;
        for (String measure : List.of("NII", "EVE")) {
            for (ShockType shock : IrrbbShocks.STANDARD) {
                ObjectNode values = rows.object();
                values.put("legal_entity_id", in.legalEntityId());
                values.put("as_of_date", in.asOf().toString());
                values.put("currency", in.currency());
                values.put("measure", measure);
                values.put("shock_type", shock.name());
                if (shock == ShockType.PARALLEL_UP || shock == ShockType.PARALLEL_DOWN) {
                    values.put("shock_bps", 200);
                }
                values.put("horizon_months", in.horizonMonths());
                JsonNode run = compute(values, in, measure, shock, IrrbbShocks.Params.standard(200, null));
                runs.add(run);
                BigDecimal delta = Rows.dec(run, "delta");
                if ("EVE".equals(measure)) {
                    worstEve = worstEve == null || delta.compareTo(worstEve) < 0 ? delta : worstEve;
                    outlier |= Rows.bool(run, "outlier");
                } else {
                    worstNii = worstNii == null || delta.compareTo(worstNii) < 0 ? delta : worstNii;
                }
            }
        }
        ObjectNode out = rows.object();
        out.put("legal_entity_id", in.legalEntityId());
        out.put("as_of_date", in.asOf().toString());
        out.put("currency", in.currency());
        out.put("horizon_months", in.horizonMonths());
        out.put("worst_eve_delta", worstEve);
        out.put("worst_nii_delta", worstNii);
        if (in.tier1() != null) {
            out.put("tier1_capital", in.tier1());
            out.put("worst_eve_delta_pct_tier1", ratio(worstEve, in.tier1()));
        }
        out.put("outlier", outlier);
        out.set("runs", runs);
        return out;
    }

    private Inputs inputs(JsonNode req, boolean needsCurve) {
        List<Position> positions = io.meridian.alm.application.Inputs.positions(req.get("positions"), "positions");
        String currency = Rows.str(req, "currency");
        LocalDate asOf = Rows.date(req, "as_of_date");
        Integer horizon = Rows.integer(req, "horizon_months");
        if (horizon != null && (horizon < 1 || horizon > 120)) {
            throw ApiException.unprocessable("invalid_horizon", "horizon_months must be 1-120");
        }
        BigDecimal tier1 = Rows.dec(req, "tier1_capital");
        if (tier1 != null && tier1.signum() <= 0) {
            throw ApiException.unprocessable("invalid_tier1_capital", "tier1_capital must be positive");
        }
        Curve curve = io.meridian.alm.application.Inputs.curve(req.get("base_curve"), "base_curve", "rate");
        if (curve == null && needsCurve) {
            curve = ftp.published(null, "BASE", currency, asOf).orElseThrow(() -> ApiException.unprocessable(
                    "curve_required", "EVE needs base_curve or a published BASE FTP curve in " + currency
                            + " effective on " + asOf));
        }
        List<Position> inCurrency = positions.stream()
                .filter(p -> p.currency() == null || p.currency().equals(currency)).toList();
        return new Inputs(Rows.str(req, "legal_entity_id"), asOf, currency, inCurrency, curve, tier1,
                horizon == null ? 12 : horizon);
    }

    private JsonNode compute(ObjectNode values, Inputs in, String measure, ShockType shock, IrrbbShocks.Params params) {
        SensitivityCalculator.Result r;
        try {
            if ("EVE".equals(measure)) {
                r = SensitivityCalculator.eve(in.asOf(), in.positions(), in.baseCurve(), shock, params);
            } else {
                List<Assumption> approved = assumptions.approved();
                r = SensitivityCalculator.nii(in.asOf(), in.positions(), in.horizonMonths(), shock, params,
                        p -> passThrough(approved, p, in.asOf()));
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_position", e.getMessage());
        }
        values.put("base_value", r.base());
        values.put("shocked_value", r.shocked());
        values.put("delta", r.delta());
        boolean outlier = false;
        if (in.tier1() != null) {
            BigDecimal pct = ratio(r.delta(), in.tier1());
            values.put("delta_pct_tier1", pct);
            outlier = "EVE".equals(measure) && r.delta().signum() < 0 && pct.abs().compareTo(OUTLIER_THRESHOLD) > 0;
        }
        values.put("outlier", outlier);
        values.put("status", "SUCCEEDED");
        ObjectNode results = values.putObject("results");
        ArrayNode ps = results.putArray("positions");
        for (SensitivityCalculator.PositionResult p : r.positions()) {
            ObjectNode n = ps.addObject();
            n.put("code", p.code());
            n.put("base", p.base());
            n.put("shocked", p.shocked());
            n.put("delta", p.delta());
        }
        ArrayNode curve = results.putArray("shock");
        for (int days : CURVE_TENORS) {
            double t = days / 365.0;
            ObjectNode n = curve.addObject();
            n.put("tenor_days", days);
            BigDecimal delta = IrrbbShocks.delta(shock, t, params);
            n.put("shock_bps", delta.multiply(BigDecimal.valueOf(10000)).setScale(2, RoundingMode.HALF_EVEN));
            if (in.baseCurve() != null) {
                BigDecimal base = in.baseCurve().rate(days);
                n.put("base_rate", base.setScale(10, RoundingMode.HALF_EVEN));
                n.put("shocked_rate", IrrbbShocks.shocked(base, delta, t).setScale(10, RoundingMode.HALF_EVEN));
            }
        }
        ObjectNode row = rows.insert(TABLE, values);
        events.publish("treasury.alm_sensitivity.completed", TABLE, row);
        if (outlier) {
            events.publish("treasury.alm_sensitivity.outlier_breached", TABLE, row);
        }
        return row;
    }

    private static BigDecimal passThrough(List<Assumption> approved, Position p, LocalDate asOf) {
        if (!p.nonMaturity()) {
            return BigDecimal.ONE;
        }
        Optional<Assumption> a = Assumption.best(approved, AssumptionType.DEPOSIT_DECAY, p.productId(), p.segment(),
                p.currency(), asOf);
        if (a.isEmpty()) {
            a = Assumption.best(approved, AssumptionType.NON_MATURITY_REPRICING, p.productId(), p.segment(),
                    p.currency(), asOf);
        }
        return a.map(x -> x.param("pass_through", BigDecimal.ONE)).orElse(BigDecimal.ONE);
    }

    private static BigDecimal ratio(BigDecimal delta, BigDecimal tier1) {
        return delta.divide(tier1, MathContext.DECIMAL128).setScale(10, RoundingMode.HALF_EVEN);
    }
}
