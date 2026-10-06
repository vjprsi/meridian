package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.dec;
import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loanorigination.config.OriginationProperties;
import io.meridian.loanorigination.domain.AffordabilityCalculator;
import io.meridian.loanorigination.domain.ScorecardEngine;
import io.meridian.loanorigination.infrastructure.ProductCatalogClient;
import io.meridian.loanorigination.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Scoring (explainable scorecard on applicant, bureau and declared finances) and affordability assessment. */
@Service
public class AssessmentService {
    static final MathContext MC = MathContext.DECIMAL128;

    private final Support support;
    private final Rows rows;
    private final Events events;
    private final BureauService bureau;
    private final ScorecardService scorecards;
    private final ProductCatalogClient catalog;
    private final OriginationProperties props;

    public AssessmentService(Support support, BureauService bureau, ScorecardService scorecards,
                             ProductCatalogClient catalog, OriginationProperties props) {
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
        this.bureau = bureau;
        this.scorecards = scorecards;
        this.catalog = catalog;
        this.props = props;
    }

    @Transactional
    public ObjectNode score(UUID appId) {
        ObjectNode app = support.lockApp(appId);
        requireAssessable(app);
        return score(app);
    }

    ObjectNode score(ObjectNode app) {
        UUID appId = uuid(app, "id");
        ObjectNode primary = requirePrimary(appId);
        ObjectNode report = bureau.latest(uuid(primary, "id")).orElse(null);
        String type = "NANO".equals(str(app, "loan_type")) ? "WALLET_TRANSACTIONAL" : "APPLICATION";
        ScorecardService.Selected selected = scorecards.select(type, appId);
        if (selected == null && !"APPLICATION".equals(type)) {
            selected = scorecards.select("APPLICATION", appId);
        }
        if (selected == null) {
            throw ApiException.unprocessable("no_champion_scorecard",
                    "No active champion scorecard of type " + type + " is configured");
        }
        long started = System.nanoTime();
        Map<String, Object> inputs = features(app, primary, report, items(appId), support.dbToday());
        ScorecardEngine.Result r = ScorecardEngine.score(ScorecardService.model(selected.version()), inputs);
        ObjectNode v = resultJson(rows, r);
        v.remove("probability_of_default");
        v.put("probability_of_default", r.probabilityOfDefault().toPlainString());
        v.put("loan_application_id", appId.toString());
        v.put("loan_applicant_id", str(primary, "id"));
        v.put("scorecard_version_id", str(selected.version(), "id"));
        v.put("routing", selected.routing());
        v.set("inputs", rows.json().valueToTree(inputs));
        v.put("latency_ms", (int) ((System.nanoTime() - started) / 1_000_000));
        ObjectNode row = rows.insert("credit_score", v);
        events.publish("lending.credit_score.calculated", "credit_score", row);
        ObjectNode extra = rows.object();
        extra.put("risk_grade", r.band().riskGrade());
        support.transition(app, "SUBMITTED".equals(str(app, "status")) ? "SCORED" : str(app, "status"), extra);
        return row;
    }

    @Transactional
    public ObjectNode affordability(UUID appId) {
        ObjectNode app = support.lockApp(appId);
        requireAssessable(app);
        return affordability(app);
    }

    ObjectNode affordability(ObjectNode app) {
        UUID appId = uuid(app, "id");
        ObjectNode primary = requirePrimary(appId);
        ObjectNode report = bureau.latest(uuid(primary, "id")).orElse(null);
        ProductTerms terms = catalog.terms(uuid(app, "product_id"), uuid(app, "product_version_id"));
        BigDecimal rate = (terms.baseRate() == null ? props.defaultRate() : terms.baseRate()).add(latestAdjustment(appId));
        AffordabilityCalculator.Result r = AffordabilityCalculator.assess(input(app, primary, report, rate),
                AffordabilityCalculator.Policy.standard());
        ObjectNode v = rows.object();
        v.put("loan_application_id", appId.toString());
        v.put("verified_monthly_income", r.monthlyIncome().toPlainString());
        v.put("monthly_commitments", r.commitments().toPlainString());
        v.put("living_costs", r.livingCosts().toPlainString());
        v.put("proposed_instalment", r.instalment().toPlainString());
        v.put("stressed_instalment", r.stressedInstalment().toPlainString());
        v.put("stress_rate", r.stressRate().toPlainString());
        v.put("debt_to_income", r.dti().toPlainString());
        v.put("debt_service_ratio", r.dsr().toPlainString());
        v.put("disposable_income", r.residualIncome().toPlainString());
        v.put("max_affordable_amount", r.maxAffordableAmount().toPlainString());
        v.put("result", r.result());
        v.set("reason_codes", rows.json().valueToTree(r.reasonCodes()));
        v.put("currency", str(app, "currency"));
        v.put("policy_version", r.policyVersion());
        ObjectNode row = rows.insert("affordability_assessment", v);
        events.publish("lending.affordability_assessment.completed", "affordability_assessment", row);
        return row;
    }

    // ------------------------------------------------------------------ features

    AffordabilityCalculator.Input input(ObjectNode app, ObjectNode primary, ObjectNode report, BigDecimal rate) {
        UUID appId = uuid(app, "id");
        List<AffordabilityCalculator.Item> items = new ArrayList<>();
        for (ObjectNode i : items(appId)) {
            items.add(new AffordabilityCalculator.Item(str(i, "item_type"), dec(i, "amount"), str(i, "frequency"),
                    Rows.bool(i, "is_verified")));
        }
        BigDecimal declared = rows.scalar("""
                select sum(monthly_net_income) from loan_applicant where loan_application_id = ? and role <> 'GUARANTOR'""",
                BigDecimal.class, appId).orElse(null);
        int dependants = primary.path("dependants").asInt(0);
        return new AffordabilityCalculator.Input(items, declared, dependants,
                report == null ? null : dec(report, "monthly_debt_service"), report == null ? null : dec(report, "total_debt"),
                dec(app, "requested_amount"), Rows.integer(app, "requested_term_months") == null ? 12
                : Rows.integer(app, "requested_term_months"), rate, str(app, "currency"));
    }

    /** Scorecard feature values; absent facts are left out (the scorecard applies missing_points). */
    static Map<String, Object> features(ObjectNode app, ObjectNode primary, ObjectNode report, List<ObjectNode> items,
                                        LocalDate today) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (report != null && "RECEIVED".equals(str(report, "status"))) {
            put(f, "bureau_score", Rows.integer(report, "bureau_score"));
            put(f, "delinquent_accounts", Rows.integer(report, "delinquent_accounts"));
            put(f, "worst_dpd_24m", Rows.integer(report, "worst_dpd_24m"));
            put(f, "inquiries_6m", Rows.integer(report, "inquiries_6m"));
            put(f, "open_accounts", Rows.integer(report, "open_accounts"));
        }
        LocalDate dob = Rows.date(primary, "date_of_birth");
        if (dob != null) {
            f.put("age", Period.between(dob, today).getYears());
        }
        LocalDate employed = Rows.date(primary, "employment_start_date");
        if (employed != null) {
            f.put("months_employed", (int) ChronoUnit.MONTHS.between(employed, today));
        }
        put(f, "residential_status", str(primary, "residential_status"));
        put(f, "employment_status", str(primary, "employment_status"));
        put(f, "dependants", Rows.integer(primary, "dependants"));
        BigDecimal income = BigDecimal.ZERO;
        boolean incomeItem = false;
        for (ObjectNode i : items) {
            if (AffordabilityCalculator.INCOME_TYPES.contains(str(i, "item_type"))) {
                incomeItem = true;
                income = income.add(AffordabilityCalculator.monthly(dec(i, "amount"), str(i, "frequency")));
            }
        }
        if (!incomeItem && dec(primary, "monthly_net_income") != null) {
            income = dec(primary, "monthly_net_income");
        }
        BigDecimal requested = dec(app, "requested_amount");
        if (income.signum() > 0) {
            f.put("monthly_income", income.setScale(2, RoundingMode.HALF_EVEN));
            BigDecimal annual = income.multiply(BigDecimal.valueOf(12));
            f.put("loan_to_income", requested.divide(annual, MC).setScale(4, RoundingMode.HALF_UP));
            BigDecimal debt = report == null || dec(report, "total_debt") == null ? BigDecimal.ZERO : dec(report, "total_debt");
            f.put("dti", debt.add(requested).divide(annual, MC).setScale(4, RoundingMode.HALF_UP));
        }
        return f;
    }

    static ObjectNode resultJson(Rows rows, ScorecardEngine.Result r) {
        ObjectNode out = rows.object();
        out.put("score", r.score());
        out.put("probability_of_default", r.probabilityOfDefault());
        out.put("risk_grade", r.band().riskGrade());
        out.put("cutoff_outcome", r.band().outcome());
        out.put("rate_adjustment", r.band().rateAdjustment().toPlainString());
        ArrayNode reasons = out.putArray("reason_codes");
        int rank = 0;
        for (ScorecardEngine.ReasonCode c : r.reasons()) {
            ObjectNode n = reasons.addObject();
            n.put("rank", ++rank);
            n.put("code", c.code());
            n.put("feature", c.feature());
            n.put("points_lost", c.pointsLost());
            n.put("description", c.description());
        }
        ArrayNode contributions = out.putArray("contributions");
        for (ScorecardEngine.Contribution c : r.contributions()) {
            ObjectNode n = contributions.addObject();
            n.put("feature", c.feature());
            n.set("value", rows.json().valueToTree(c.value()));
            n.put("bin", c.bin());
            n.put("points", c.points());
            n.put("max_points", c.maxPoints());
        }
        out.put("inputs_hash", r.inputsHash());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    BigDecimal latestAdjustment(UUID appId) {
        return rows.scalar("""
                select rate_adjustment from credit_score where loan_application_id = ? order by created_at desc, id desc limit 1""",
                BigDecimal.class, appId).orElse(BigDecimal.ZERO);
    }

    List<ObjectNode> items(UUID appId) {
        return rows.query("""
                select row_to_json(i)::text from loan_financial_item i join loan_applicant a on a.id = i.loan_applicant_id
                where a.loan_application_id = ? and a.role <> 'GUARANTOR' order by i.created_at""", appId);
    }

    ObjectNode requirePrimary(UUID appId) {
        ObjectNode primary = support.primaryApplicant(appId);
        if (primary == null) {
            throw ApiException.unprocessable("applicant_required", "The application has no PRIMARY applicant");
        }
        return primary;
    }

    static void requireAssessable(ObjectNode app) {
        String status = str(app, "status");
        if ("BUREAU_PENDING".equals(status)) {
            throw ApiException.conflict("bureau_pending", "A bureau report is still pending for application " + str(app, "id"));
        }
        if (!BureauService.ASSESSABLE.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Application " + str(app, "id") + " is " + status
                    + "; scoring and affordability run on submitted applications before the decision");
        }
    }

    private static void put(Map<String, Object> f, String k, Object v) {
        if (v != null) {
            f.put(k, v);
        }
    }
}
