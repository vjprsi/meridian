package io.meridian.alm.application;

import io.meridian.alm.domain.Assumption;
import io.meridian.alm.domain.Assumption.AssumptionType;
import io.meridian.alm.domain.Behaviour;
import io.meridian.alm.domain.Curve;
import io.meridian.alm.domain.FtpPricing;
import io.meridian.alm.domain.FtpPricing.ExposureType;
import io.meridian.alm.domain.FtpPricing.Method;
import io.meridian.alm.domain.FtpPricing.Rule;
import io.meridian.alm.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Funds transfer pricing: curves (frozen once published, maker-checker publication), curve points, interpolation,
 * pricing rules, period assignments and single-exposure quotes.
 */
@Service
public class FtpService {
    static final String CURVE = "ftp_curve";
    static final String POINT = "ftp_curve_point";
    static final String RULE = "ftp_rule";
    static final String ASSIGNMENT = "ftp_assignment";
    private static final Pattern CCY = Pattern.compile("[A-Z]{3}");
    private static final BigDecimal MAX_RATE = BigDecimal.ONE;
    private static final BigDecimal MIN_RATE = new BigDecimal("-0.05");

    /** Approval resource type for FTP curve publication submissions in workflow-service (G11). */
    public static final String CURVE_APPROVAL = "ftp_curve";
    private static final String CLEAR_SUBMISSION = "submitted_by = null, submitted_at = null";

    private final Rows rows;
    private final Events events;
    private final AssumptionService assumptions;
    private final Approvals approvals;

    public FtpService(Rows rows, Events events, AssumptionService assumptions, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.assumptions = assumptions;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ curves

    /** PATCH guard: published curves are frozen. */
    @Transactional(readOnly = true)
    public void checkCurveEditable(UUID curveId) {
        ObjectNode curve = rows.get(CURVE, curveId);
        if (Rows.bool(curve, "is_published")) {
            throw ApiException.conflict("curve_published", "Curve " + Rows.str(curve, "code")
                    + " is published and cannot change; create a curve with a new effective_date");
        }
    }

    /**
     * Maker step: submits the unpublished curve and its points for ALCO approval. In workflow mode this opens an
     * approval request whose payload carries the points and a checksum; {@link FtpCurveApprovalHandler} publishes or
     * rejects. A new submission replaces a pending one.
     */
    @Transactional
    public JsonNode submit(UUID id, String comment) {
        ObjectNode curve = rows.lock(CURVE, id);
        if (Rows.bool(curve, "is_published")) {
            throw ApiException.conflict("already_published", "The curve is already published");
        }
        List<ObjectNode> points = curvePoints(id);
        if (points.isEmpty()) {
            throw ApiException.unprocessable("curve_has_no_points", "A curve needs at least one point to be published");
        }
        OffsetDateTime at = Clocks.now();
        String maker = TenantContext.principal();
        ObjectNode submitted = rows.queryOne("""
                update ftp_curve t set submitted_by = ?, submitted_at = ?, rejected_by = null, rejection_reason = null,
                updated_by = ? where id = ? returning row_to_json(t)::text""", maker, at, maker, id).orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Rows.str(curve, "code"));
        payload.put("currency", Rows.str(curve, "currency"));
        payload.put("curve_type", Rows.str(curve, "curve_type"));
        payload.put("effective_date", Rows.str(curve, "effective_date"));
        List<Map<String, Object>> pts = new ArrayList<>();
        for (ObjectNode p : points) {
            pts.add(Map.of("tenor_days", Rows.integer(p, "tenor_days"), "tenor_label", Rows.str(p, "tenor_label"),
                    "rate", Rows.str(p, "rate")));
        }
        payload.put("points", pts);
        payload.put("checksum", checksum(curve, points));
        if (comment != null) {
            payload.put("comment", comment);
        }
        UUID requestId = Approvals.idOf(approvals.request(ApprovalSpec.of(CURVE_APPROVAL, id, "publish",
                "FTP curve " + Rows.str(curve, "code") + " " + Rows.str(curve, "currency") + " "
                        + Rows.str(curve, "effective_date"), payload), "ftp-curve:" + id + ":" + at));
        if (requestId != null) {
            approvals.cancelOpenExcept(CURVE_APPROVAL, id, null, requestId, "superseded");
        }
        events.publish("treasury.ftp_curve.submitted", CURVE, submitted);
        return submitted;
    }

    /** True while the curve is submitted and unpublished with the submitted content (checksum) unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String checksum) {
        return rows.find(CURVE, id)
                .filter(c -> !Rows.bool(c, "is_published") && c.hasNonNull("submitted_at"))
                .filter(c -> checksum != null && checksum.equals(checksum(c, curvePoints(id))))
                .isPresent();
    }

    @Transactional
    public JsonNode publish(UUID id, String comment) {
        ObjectNode curve = rows.lock(CURVE, id);
        if (Rows.bool(curve, "is_published")) {
            throw ApiException.conflict("already_published", "The curve is already published");
        }
        String approver = TenantContext.principal();
        if (approver.equals(Rows.str(curve, "created_by")) || approver.equals(Rows.str(curve, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker or submitter of a curve cannot publish it");
        }
        int points = rows.scalar("select count(*)::int from ftp_curve_point where ftp_curve_id = ?", Integer.class, id)
                .orElse(0);
        if (points == 0) {
            throw ApiException.unprocessable("curve_has_no_points", "A curve needs at least one point to be published");
        }
        ObjectNode published = rows.queryOne("""
                update ftp_curve t set is_published = true, approved_by = ?, published_at = ?, updated_by = ?,
                submitted_by = null, submitted_at = null
                where id = ? returning row_to_json(t)::text""", approver, Clocks.now(), approver, id).orElseThrow();
        events.publish("treasury.ftp_curve.published", CURVE, published);
        approvals.cancelOpen(CURVE_APPROVAL, id, null, "published directly");
        return published;
    }

    /** Checker step: rejects the submitted curve; it stays unpublished (and editable) for rework. */
    @Transactional
    public JsonNode reject(UUID id, String reason) {
        ObjectNode curve = rows.lock(CURVE, id);
        if (Rows.bool(curve, "is_published")) {
            throw ApiException.conflict("already_published", "The curve is already published");
        }
        if (!curve.hasNonNull("submitted_at")) {
            throw ApiException.conflict("no_pending_submission", "The curve has not been submitted for approval");
        }
        String checker = TenantContext.principal();
        if (checker.equals(Rows.str(curve, "created_by")) || checker.equals(Rows.str(curve, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker or submitter of a curve cannot reject it");
        }
        ObjectNode rejected = rows.queryOne("update ftp_curve t set rejected_by = ?, rejection_reason = ?, updated_by = ?, "
                + CLEAR_SUBMISSION + " where id = ? returning row_to_json(t)::text", checker, reason, checker, id).orElseThrow();
        events.publish("treasury.ftp_curve.rejected", CURVE, rejected);
        approvals.cancelOpen(CURVE_APPROVAL, id, null, "rejected directly");
        return rejected;
    }

    private List<ObjectNode> curvePoints(UUID curveId) {
        return rows.query("select row_to_json(t)::text from ftp_curve_point t where ftp_curve_id = ? order by tenor_days",
                curveId);
    }

    /** SHA-256 over the curve identity and its points (tenor and rate), so an edit after submission is detected. */
    private static String checksum(JsonNode curve, List<ObjectNode> points) {
        StringBuilder sb = new StringBuilder();
        sb.append(Rows.str(curve, "code")).append('|').append(Rows.str(curve, "currency")).append('|')
                .append(Rows.str(curve, "curve_type")).append('|').append(Rows.str(curve, "effective_date")).append('\n');
        for (ObjectNode p : points) {
            BigDecimal rate = Rows.dec(p, "rate");
            sb.append(Rows.integer(p, "tenor_days")).append('|')
                    .append(rate == null ? "" : rate.stripTrailingZeros().toPlainString()).append('\n');
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(readOnly = true)
    public JsonNode rate(UUID id, Integer tenorDays) {
        ObjectNode curveRow = rows.get(CURVE, id);
        if (tenorDays == null || tenorDays < 0) {
            throw ApiException.unprocessable("invalid_tenor", "tenor_days must be zero or positive");
        }
        Curve curve = curve(id).orElseThrow(() -> ApiException.unprocessable("curve_has_no_points",
                "The curve has no points"));
        Curve.Interpolation i = curve.interpolate(tenorDays);
        ObjectNode out = rows.object();
        out.put("curve_id", id.toString());
        out.put("code", Rows.str(curveRow, "code"));
        out.put("currency", Rows.str(curveRow, "currency"));
        out.put("effective_date", Rows.str(curveRow, "effective_date"));
        out.put("is_published", Rows.bool(curveRow, "is_published"));
        out.put("tenor_days", tenorDays);
        out.put("rate", FtpPricingRate.of(i.rate()));
        out.put("method", i.method());
        out.set("lower", point(i.lower()));
        out.set("upper", point(i.upper()));
        return out;
    }

    private ObjectNode point(Curve.Point p) {
        ObjectNode n = rows.object();
        n.put("tenor_days", p.tenorDays());
        n.put("rate", p.rate());
        return n;
    }

    /** Points of a curve as a domain curve (empty when it has none). */
    Optional<Curve> curve(UUID curveId) {
        List<Curve.Point> pts = rows.query("select row_to_json(t)::text from ftp_curve_point t where ftp_curve_id = ?",
                curveId).stream().map(p -> new Curve.Point(Rows.integer(p, "tenor_days"), Rows.dec(p, "rate"))).toList();
        return pts.isEmpty() ? Optional.empty() : Optional.of(new Curve(pts));
    }

    /** Latest published curve with points by code (or type) and currency effective on or before a date. */
    Optional<Curve> published(String code, String curveType, String currency, LocalDate date) {
        Optional<ObjectNode> row = rows.queryOne("""
                select row_to_json(t)::text from ftp_curve t
                where is_published and currency = ? and effective_date <= ?
                  and (?::text is null or code = ?) and (?::text is null or curve_type = ?)
                  and exists (select 1 from ftp_curve_point p where p.ftp_curve_id = t.id)
                order by effective_date desc, published_at desc limit 1""",
                currency, date, code, code, curveType, curveType);
        return row.flatMap(r -> curve(Rows.uuid(r, "id")));
    }

    // ------------------------------------------------------------------ points

    @Transactional(readOnly = true)
    public void checkPoint(UUID curveId, Integer tenorDays, String label, BigDecimal rate) {
        if (curveId != null) {
            ObjectNode curve = rows.find(CURVE, curveId).orElseThrow(() -> ApiException.unprocessable("curve_not_found",
                    "ftp_curve " + curveId + " does not exist"));
            if (Rows.bool(curve, "is_published")) {
                throw ApiException.conflict("curve_published", "Points of a published curve cannot change");
            }
        }
        if (tenorDays != null && (tenorDays < 1 || tenorDays > 365 * 50)) {
            throw ApiException.unprocessable("invalid_tenor", "tenor_days must be between 1 and 18250");
        }
        if (label != null && label.isBlank()) {
            throw ApiException.unprocessable("invalid_tenor_label", "tenor_label must not be blank");
        }
        if (rate != null && (rate.compareTo(MIN_RATE) < 0 || rate.compareTo(MAX_RATE) > 0)) {
            throw ApiException.unprocessable("invalid_rate", "rate must be a decimal between -0.05 and 1");
        }
    }

    @Transactional(readOnly = true)
    public UUID curveOfPoint(UUID pointId) {
        return Rows.uuid(rows.get(POINT, pointId), "ftp_curve_id");
    }

    // ------------------------------------------------------------------ rules

    /** Validates a rule (create body, or PATCH merged over the stored row). */
    @Transactional(readOnly = true)
    public void checkRule(UUID existingId, Object body) {
        ObjectNode merged = existingId == null ? rows.object() : rows.get(RULE, existingId).deepCopy();
        merged.setAll(rows.columns(RULE, body));
        Method method;
        try {
            method = Method.valueOf(Rows.str(merged, "method"));
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("invalid_method", "unknown method " + Rows.str(merged, "method"));
        }
        if (method == Method.FIXED_SPREAD && Rows.dec(merged, "fixed_spread") == null) {
            throw ApiException.unprocessable("fixed_spread_required", "FIXED_SPREAD rules need fixed_spread");
        }
        Integer pool = Rows.integer(merged, "pool_tenor_days");
        if (pool != null && pool < 1) {
            throw ApiException.unprocessable("invalid_tenor", "pool_tenor_days must be positive");
        }
        Integer priority = Rows.integer(merged, "priority");
        if (priority != null && priority < 0) {
            throw ApiException.unprocessable("invalid_priority", "priority must not be negative");
        }
        LocalDate from = Rows.date(merged, "valid_from");
        LocalDate to = Rows.date(merged, "valid_to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
    }

    List<Rule> rules() {
        return rows.query("select row_to_json(t)::text from ftp_rule t").stream().map(r -> new Rule(
                Rows.uuid(r, "id"), Rows.uuid(r, "product_id"), Rows.str(r, "segment"), Rows.str(r, "currency"),
                Method.valueOf(Rows.str(r, "method")), Rows.str(r, "base_curve_code"),
                Rows.str(r, "liquidity_curve_code"), Rows.dec(r, "fixed_spread"), Rows.integer(r, "pool_tenor_days"),
                Rows.integer(r, "priority") == null ? 100 : Rows.integer(r, "priority"), Rows.date(r, "valid_from"),
                Rows.date(r, "valid_to"))).toList();
    }

    // ------------------------------------------------------------------ pricing

    /** One exposure to price. */
    record Exposure(ExposureType type, UUID exposureId, UUID productId, String segment, String currency,
                    BigDecimal averageBalance, BigDecimal customerRate, Integer termDays, UUID depositAccountId,
                    UUID loanId) {}

    /** Outcome of pricing one exposure on a date. */
    record Priced(Rule rule, FtpPricing.Pricing pricing, BigDecimal margin) {}

    Priced price(Exposure e, LocalDate date, List<Rule> rules, List<Assumption> approved) {
        Rule rule = FtpPricing.resolve(rules, e.productId(), e.segment(), e.currency(), date)
                .orElseThrow(() -> ApiException.unprocessable("no_ftp_rule", "No FTP rule applies to product "
                        + e.productId() + ", segment " + e.segment() + ", currency " + e.currency() + " on " + date));
        Integer behaviouralDays = null;
        if (rule.method() == Method.BEHAVIOURAL) {
            behaviouralDays = Assumption.best(approved, AssumptionType.DEPOSIT_DECAY, e.productId(), e.segment(),
                    e.currency(), date).map(a -> Behaviour.averageLifeDays(a.parameters())).orElse(null);
        }
        int term;
        try {
            term = FtpPricing.pricingTerm(rule, e.termDays(), behaviouralDays);
        } catch (IllegalArgumentException ex) {
            throw ApiException.unprocessable("term_days_required", ex.getMessage());
        }
        Curve base = published(rule.baseCurveCode(), null, e.currency(), date)
                .orElseThrow(() -> ApiException.unprocessable("curve_not_found", "No published curve "
                        + rule.baseCurveCode() + " in " + e.currency() + " effective on " + date));
        Curve liquidity = null;
        if (rule.liquidityCurveCode() != null) {
            liquidity = published(rule.liquidityCurveCode(), null, e.currency(), date)
                    .orElseThrow(() -> ApiException.unprocessable("curve_not_found", "No published curve "
                            + rule.liquidityCurveCode() + " in " + e.currency() + " effective on " + date));
        }
        FtpPricing.Pricing pricing = FtpPricing.price(rule, base, liquidity, term);
        return new Priced(rule, pricing, FtpPricing.margin(e.type(), e.customerRate(), pricing.ftpRate()));
    }

    @Transactional
    public JsonNode run(LocalDate start, LocalDate end, JsonNode items) {
        if (start == null || end == null || end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        List<Exposure> exposures = exposures(items, true);
        List<Rule> rules = rules();
        List<Assumption> approved = assumptions.approved();
        int computed = 0;
        int existing = 0;
        ArrayNode assigned = rows.json().createArrayNode();
        ArrayNode kept = rows.json().createArrayNode();
        ArrayNode failed = rows.json().createArrayNode();
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (int i = 0; i < exposures.size(); i++) {
            Exposure e = exposures.get(i);
            Optional<ObjectNode> prior = rows.queryOne("""
                    select row_to_json(t)::text from ftp_assignment t where exposure_id = ? and period_start = ?""",
                    e.exposureId(), start);
            if (prior.isPresent()) {
                existing++;
                kept.add(Rows.str(prior.get(), "id"));
                continue;
            }
            Priced p;
            try {
                p = price(e, start, rules, approved);
            } catch (ApiException ex) {
                ObjectNode f = failed.addObject();
                f.put("index", i);
                f.put("exposure_id", e.exposureId().toString());
                f.put("code", ex.code());
                f.put("message", ex.getMessage());
                continue;
            }
            BigDecimal amount = FtpPricing.charge(e.averageBalance(), p.pricing().ftpRate(), start, end);
            ObjectNode v = rows.object();
            v.put("period_start", start.toString());
            v.put("period_end", end.toString());
            v.put("exposure_type", e.type().name());
            v.put("exposure_id", e.exposureId().toString());
            if (e.depositAccountId() != null) {
                v.put("deposit_account_id", e.depositAccountId().toString());
            }
            if (e.loanId() != null) {
                v.put("loan_id", e.loanId().toString());
            }
            if (e.productId() != null) {
                v.put("product_id", e.productId().toString());
            }
            v.put("ftp_rule_id", p.rule().id().toString());
            v.put("term_days", p.pricing().termDays());
            v.put("average_balance", e.averageBalance());
            v.put("currency", e.currency());
            v.put("customer_rate", e.customerRate());
            v.put("ftp_rate", p.pricing().ftpRate());
            v.put("net_margin", p.margin());
            v.put("ftp_charge_credit", amount);
            ObjectNode row = rows.insert(ASSIGNMENT, v);
            events.publish("treasury.ftp_assignment.computed", ASSIGNMENT, row);
            assigned.add(Rows.str(row, "id"));
            computed++;
            if (e.type().isAsset()) {
                charges = charges.add(amount);
            } else {
                credits = credits.add(amount);
            }
        }
        ObjectNode out = rows.object();
        out.put("period_start", start.toString());
        out.put("period_end", end.toString());
        out.put("exposures", exposures.size());
        out.put("computed", computed);
        out.put("existing", existing);
        out.put("failed_count", failed.size());
        out.put("total_ftp_charges", charges);
        out.put("total_ftp_credits", credits);
        out.set("assignment_ids", assigned);
        out.set("existing_assignment_ids", kept);
        out.set("failed", failed);
        return out;
    }

    @Transactional(readOnly = true)
    public JsonNode quote(JsonNode req) {
        List<Exposure> one = exposures(rows.json().createArrayNode().add(req), false);
        LocalDate date = Rows.date(req, "as_of_date");
        Exposure e = one.getFirst();
        Priced p = price(e, date, rules(), assumptions.approved());
        ObjectNode out = rows.object();
        out.put("exposure_type", e.type().name());
        out.put("currency", e.currency());
        out.put("as_of_date", date.toString());
        out.put("ftp_rule_id", p.rule().id().toString());
        out.put("method", p.rule().method().name());
        out.put("base_curve_code", p.rule().baseCurveCode());
        if (p.rule().liquidityCurveCode() != null) {
            out.put("liquidity_curve_code", p.rule().liquidityCurveCode());
        }
        out.put("term_days", p.pricing().termDays());
        out.put("base_rate", p.pricing().baseRate());
        out.put("liquidity_premium", p.pricing().liquidityPremium());
        out.put("spread", p.pricing().spread());
        out.put("ftp_rate", p.pricing().ftpRate());
        if (e.customerRate() != null) {
            out.put("customer_rate", e.customerRate());
            out.put("net_margin", p.margin());
        }
        BigDecimal amount = Rows.dec(req, "amount");
        if (amount != null) {
            out.put("amount", amount);
            out.put("annual_ftp_amount", amount.multiply(p.pricing().ftpRate()).setScale(2, java.math.RoundingMode.HALF_EVEN));
        }
        return out;
    }

    private List<Exposure> exposures(JsonNode items, boolean forRun) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw ApiException.unprocessable("exposures_required", "exposures must be a non-empty list");
        }
        List<Exposure> out = new ArrayList<>();
        int i = 0;
        for (JsonNode n : items) {
            String where = forRun ? "exposures[" + i + "]: " : "";
            try {
                String type = Inputs.text(n, "exposure_type");
                if (type == null) {
                    throw new IllegalArgumentException("exposure_type is required");
                }
                ExposureType t = ExposureType.valueOf(type.toUpperCase(Locale.ROOT));
                String ccy = Inputs.text(n, "currency");
                if (ccy == null || !CCY.matcher(ccy).matches()) {
                    throw new IllegalArgumentException("currency must be an ISO 4217 code");
                }
                UUID exposureId = Inputs.uuid(n, "exposure_id");
                BigDecimal balance = Inputs.decimal(n, "average_balance");
                BigDecimal customer = Inputs.decimal(n, "customer_rate");
                if (forRun) {
                    if (exposureId == null) {
                        throw new IllegalArgumentException("exposure_id is required");
                    }
                    if (balance == null || balance.signum() < 0) {
                        throw new IllegalArgumentException("average_balance is required and must not be negative");
                    }
                    if (customer == null) {
                        throw new IllegalArgumentException("customer_rate is required");
                    }
                }
                Integer term = n.hasNonNull("term_days") ? n.get("term_days").asInt() : null;
                if (term != null && term <= 0) {
                    throw new IllegalArgumentException("term_days must be positive");
                }
                out.add(new Exposure(t, exposureId, Inputs.uuid(n, "product_id"), Inputs.text(n, "segment"), ccy,
                        balance, customer, term, Inputs.uuid(n, "deposit_account_id"), Inputs.uuid(n, "loan_id")));
            } catch (IllegalArgumentException ex) {
                String m = ex.getMessage();
                if (m != null && m.startsWith("No enum constant")) {
                    m = "unknown exposure_type " + m.substring(m.lastIndexOf('.') + 1);
                }
                throw ApiException.unprocessable("invalid_exposure", where + m);
            }
            i++;
        }
        return out;
    }

    /** Rates in responses at 10 decimals. */
    static final class FtpPricingRate {
        private FtpPricingRate() {}

        static BigDecimal of(BigDecimal r) {
            return r.setScale(10, java.math.RoundingMode.HALF_EVEN);
        }
    }
}
