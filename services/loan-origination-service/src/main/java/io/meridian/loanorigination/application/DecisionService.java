package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.dec;
import static io.meridian.loanorigination.infrastructure.Rows.integer;
import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.config.OriginationProperties;
import io.meridian.loanorigination.domain.DecisionPolicy;
import io.meridian.loanorigination.domain.Lifecycles;
import io.meridian.loanorigination.infrastructure.ProductCatalogClient;
import io.meridian.loanorigination.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Credit decisions. Automatic: bureau (if missing), score, affordability and policy rules in one transaction, with
 * an offer issued for approvals and counter-offers. Manual: an underwriter proposes (maker) and a second user
 * approves (checker) before the decision is recorded.
 */
@Service
public class DecisionService {
    static final String DECISION = "credit_decision";
    static final String REQUEST = "credit_decision_request";
    private static final Map<String, String> APP_STATUS = Map.of("APPROVED", "APPROVED", "DECLINED", "DECLINED",
            "REFER", "REFERRED", "COUNTER_OFFER", "COUNTER_OFFERED");

    private final Support support;
    private final Rows rows;
    private final Events events;
    private final BureauService bureau;
    private final AssessmentService assessment;
    private final OfferService offers;
    private final ProductCatalogClient catalog;
    private final OriginationProperties props;

    public DecisionService(Support support, BureauService bureau, AssessmentService assessment, OfferService offers,
                           ProductCatalogClient catalog, OriginationProperties props) {
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
        this.bureau = bureau;
        this.assessment = assessment;
        this.offers = offers;
        this.catalog = catalog;
        this.props = props;
    }

    @Transactional
    public ObjectNode autoDecide(UUID appId) {
        ObjectNode app = support.lockApp(appId);
        if ("BUREAU_PENDING".equals(str(app, "status"))) {
            throw ApiException.conflict("bureau_pending", "A bureau report is still pending for application " + appId);
        }
        if (!Lifecycles.DECIDABLE.contains(str(app, "status"))) {
            throw ApiException.conflict("invalid_transition", "Application " + appId + " is " + str(app, "status")
                    + "; automatic decisioning runs on submitted applications");
        }
        if (pendingRequest(appId).isPresent()) {
            throw ApiException.conflict("decision_request_pending", "A manual decision awaits approval");
        }
        ObjectNode primary = assessment.requirePrimary(appId);
        boolean nano = "NANO".equals(str(app, "loan_type"));
        ObjectNode report = bureau.latest(uuid(primary, "id")).orElse(null);
        if (report == null && !nano) {
            report = bureau.pull(app, uuid(primary, "id"), null, "HARD", false);
            if ("REQUESTED".equals(str(report, "status"))) {
                throw ApiException.conflict("bureau_pending", "The bureau answers asynchronously; decide again when the report arrives");
            }
            app = support.lockApp(appId);
        }
        ObjectNode score = assessment.score(app);
        app = support.lockApp(appId);
        ObjectNode aff = assessment.affordability(app);
        ProductTerms terms = catalog.terms(uuid(app, "product_id"), uuid(app, "product_version_id"));
        LocalDate dob = Rows.date(primary, "date_of_birth");
        DecisionPolicy.Outcome outcome = DecisionPolicy.evaluate(new DecisionPolicy.Facts(str(app, "loan_type"),
                dob == null ? null : Period.between(dob, support.dbToday()).getYears(),
                report == null ? null : str(report, "status"), report == null ? null : integer(report, "worst_dpd_24m"),
                report == null ? null : integer(report, "delinquent_accounts"),
                report == null ? null : integer(report, "inquiries_6m"), str(score, "cutoff_outcome"), str(aff, "result"),
                dec(aff, "max_affordable_amount"), dec(app, "requested_amount"), terms.minAmount(),
                props.counterOfferStep()));

        ObjectNode v = rows.object();
        v.put("loan_application_id", appId.toString());
        v.put("outcome", outcome.outcome());
        v.put("method", "AUTOMATIC");
        v.put("risk_grade", str(score, "risk_grade"));
        v.put("credit_score_id", str(score, "id"));
        v.put("affordability_assessment_id", str(aff, "id"));
        if (outcome.approvedAmount() != null) {
            BigDecimal base = terms.baseRate() == null ? props.defaultRate() : terms.baseRate();
            v.put("approved_amount", outcome.approvedAmount().toPlainString());
            v.put("approved_term_months", integer(app, "requested_term_months"));
            v.put("approved_rate", base.add(dec(score, "rate_adjustment") == null ? BigDecimal.ZERO
                    : dec(score, "rate_adjustment")).max(BigDecimal.ZERO).toPlainString());
        }
        ArrayNode fired = v.putArray("policy_rules_fired");
        for (DecisionPolicy.Rule r : outcome.rules()) {
            fired.addObject().put("rule", r.code()).put("result", r.severity().name()).put("detail", r.detail());
        }
        ArrayNode reasons = v.putArray("reason_codes");
        for (DecisionPolicy.Rule r : outcome.failed()) {
            reasons.addObject().put("code", r.code()).put("source", "POLICY").put("severity", r.severity().name())
                    .put("description", r.detail());
        }
        if (!"APPROVED".equals(outcome.outcome())) {
            for (JsonNode sr : score.path("reason_codes")) {
                reasons.addObject().put("code", sr.path("code").asString()).put("source", "SCORECARD")
                        .put("points_lost", sr.path("points_lost").asInt()).put("description", sr.path("description").asString());
            }
        }
        if ("COUNTER_OFFER".equals(outcome.outcome())) {
            reasons.addObject().put("code", "COUNTER_OFFER_AMOUNT").put("source", "AFFORDABILITY")
                    .put("description", "Largest affordable amount " + outcome.approvedAmount().toPlainString());
        }
        if ("DECLINED".equals(outcome.outcome())) {
            ArrayNode adverse = v.putArray("decline_reasons");
            int n = 0;
            for (JsonNode r : reasons) {
                if (n++ < 4) {
                    adverse.addObject().put("code", r.get("code").asString()).put("description", r.get("description").asString());
                }
            }
        }
        v.set("conditions", conditions(app));
        return record(app, v);
    }

    // ------------------------------------------------------------------ manual decisions (maker-checker)

    @Transactional
    public ObjectNode propose(UUID appId, JsonNode req) {
        ObjectNode app = support.lockApp(appId);
        if (!Lifecycles.MANUALLY_DECIDABLE.contains(str(app, "status"))) {
            throw ApiException.conflict("invalid_transition", "Application " + appId + " is " + str(app, "status")
                    + "; manual decisions are taken before an offer is accepted");
        }
        if (pendingRequest(appId).isPresent()) {
            throw ApiException.conflict("decision_request_pending", "Another manual decision awaits approval");
        }
        String outcome = Json.text(req, "outcome");
        BigDecimal amount = Json.decimal(req, "approved_amount");
        Integer term = Json.integer(req, "approved_term_months");
        BigDecimal rate = Json.decimal(req, "approved_rate");
        if (Set.of("APPROVED", "COUNTER_OFFER").contains(outcome)) {
            if (amount == null) {
                amount = "APPROVED".equals(outcome) ? dec(app, "requested_amount") : null;
            }
            if (amount == null || amount.signum() <= 0) {
                throw ApiException.unprocessable("approved_amount_required", "A counter-offer needs a positive approved_amount");
            }
            if (term == null) {
                term = integer(app, "requested_term_months");
            }
            ProductTerms terms = catalog.terms(uuid(app, "product_id"), uuid(app, "product_version_id"));
            ApplicationService.validateRequest(terms, amount, term, str(app, "currency"));
            if (rate != null && rate.signum() < 0) {
                throw ApiException.unprocessable("invalid_rate", "approved_rate must not be negative");
            }
        }
        JsonNode reasonCodes = req.get("reason_codes");
        if ("DECLINED".equals(outcome) && (reasonCodes == null || !reasonCodes.isArray() || reasonCodes.isEmpty())) {
            throw ApiException.unprocessable("reason_codes_required", "A manual decline needs adverse-action reason_codes");
        }
        Optional<ObjectNode> last = rows.queryOne("""
                select row_to_json(d)::text from credit_decision d where loan_application_id = ?
                order by created_at desc, id desc limit 1""", appId);
        ObjectNode v = rows.object();
        v.put("loan_application_id", appId.toString());
        v.put("outcome", outcome);
        if (amount != null) {
            v.put("approved_amount", amount.toPlainString());
        }
        if (term != null) {
            v.put("approved_term_months", term);
        }
        if (rate != null) {
            v.put("approved_rate", rate.toPlainString());
        }
        if (req.get("conditions") != null && req.get("conditions").isArray()) {
            v.set("conditions", req.get("conditions"));
        }
        if (reasonCodes != null && reasonCodes.isArray()) {
            v.set("reason_codes", reasonCodes);
        }
        if (Json.text(req, "notes") != null) {
            v.put("notes", Json.text(req, "notes"));
        }
        v.put("is_override", last.isPresent() && !outcome.equals(str(last.get(), "outcome")));
        v.put("status", "PENDING_APPROVAL");
        v.put("requested_by", TenantContext.principal());
        ObjectNode row = rows.insert(REQUEST, v);
        events.publish("lending.credit_decision_request.created", REQUEST, row);
        return row;
    }

    @Transactional
    public ObjectNode approve(UUID requestId, String comment) {
        ObjectNode req = rows.lock(REQUEST, requestId);
        requirePending(req);
        Support.fourEyes(str(req, "requested_by"), "A manual credit decision");
        UUID appId = uuid(req, "loan_application_id");
        ObjectNode app = support.lockApp(appId);
        if (!Lifecycles.MANUALLY_DECIDABLE.contains(str(app, "status"))) {
            throw ApiException.conflict("invalid_transition", "Application " + appId + " is " + str(app, "status"));
        }
        ObjectNode v = rows.object();
        v.put("loan_application_id", appId.toString());
        v.put("outcome", str(req, "outcome"));
        v.put("method", "MANUAL");
        v.put("decided_by", str(req, "requested_by"));
        v.put("approved_by", TenantContext.principal());
        for (String f : List.of("approved_amount", "approved_term_months", "approved_rate")) {
            if (req.hasNonNull(f)) {
                v.set(f, req.get(f));
            }
        }
        v.put("risk_grade", str(app, "risk_grade"));
        rows.scalar("select id from credit_score where loan_application_id = ? order by created_at desc, id desc limit 1",
                UUID.class, appId).ifPresent(id -> v.put("credit_score_id", id.toString()));
        rows.scalar("select id from affordability_assessment where loan_application_id = ? order by created_at desc, id desc limit 1",
                UUID.class, appId).ifPresent(id -> v.put("affordability_assessment_id", id.toString()));
        v.set("conditions", req.hasNonNull("conditions") ? req.get("conditions") : conditions(app));
        if (req.hasNonNull("reason_codes")) {
            v.set("reason_codes", req.get("reason_codes"));
            if ("DECLINED".equals(str(req, "outcome"))) {
                v.set("decline_reasons", req.get("reason_codes"));
            }
        }
        v.put("decision_request_id", requestId.toString());
        if (req.hasNonNull("notes") || comment != null) {
            v.put("notes", (str(req, "notes") == null ? "" : str(req, "notes"))
                    + (comment == null ? "" : (str(req, "notes") == null ? "" : "\n") + "Checker: " + comment));
        }
        ObjectNode decision = record(app, v);
        ObjectNode r = rows.object();
        r.put("status", "APPROVED");
        r.put("reviewed_by", TenantContext.principal());
        r.put("reviewed_at", support.dbNow().toString());
        if (comment != null) {
            r.put("review_comment", comment);
        }
        r.put("credit_decision_id", str(decision, "id"));
        rows.update(REQUEST, requestId, r);
        return decision;
    }

    @Transactional
    public ObjectNode reject(UUID requestId, String reason) {
        ObjectNode req = rows.lock(REQUEST, requestId);
        requirePending(req);
        Support.fourEyes(str(req, "requested_by"), "Reviewing a manual credit decision");
        ObjectNode r = rows.object();
        r.put("status", "REJECTED");
        r.put("reviewed_by", TenantContext.principal());
        r.put("reviewed_at", support.dbNow().toString());
        r.put("review_comment", reason);
        ObjectNode updated = rows.update(REQUEST, requestId, r);
        events.publish("lending.credit_decision_request.rejected", REQUEST, updated);
        return updated;
    }

    // ------------------------------------------------------------------ internals

    /** Stores the decision, moves the application and issues the offer of an approval or counter-offer. */
    private ObjectNode record(ObjectNode app, ObjectNode v) {
        ObjectNode decision = rows.insert(DECISION, v);
        events.publish("lending.credit_decision.made", DECISION, decision);
        String outcome = str(decision, "outcome");
        ObjectNode extra = rows.object();
        extra.put("decided_at", support.dbNow().toString());
        ObjectNode updated = support.transition(app, APP_STATUS.get(outcome), extra);
        // a new decision supersedes the open offers of the previous one
        for (ObjectNode open : rows.query("""
                select row_to_json(o)::text from loan_offer o where loan_application_id = ? and status = 'ISSUED' for update""",
                uuid(app, "id"))) {
            ObjectNode w = rows.object();
            w.put("status", "WITHDRAWN");
            w.put("status_reason", "Superseded by decision " + str(decision, "id"));
            events.publish("lending.loan_offer.withdrawn", OfferService.OFFER, rows.update(OfferService.OFFER, uuid(open, "id"), w));
        }
        if (Set.of("APPROVED", "COUNTER_OFFER").contains(outcome)) {
            offers.issue(updated, decision);
        }
        return decision;
    }

    /** Conditions precedent: mandatory documents not yet verified, collateral for secured loan types. */
    private ArrayNode conditions(ObjectNode app) {
        ArrayNode c = rows.json().createArrayNode();
        for (ObjectNode d : rows.query("""
                select row_to_json(d)::text from loan_application_document d
                where loan_application_id = ? and is_mandatory and status <> 'VERIFIED' order by created_at""", uuid(app, "id"))) {
            c.addObject().put("type", "DOCUMENT").put("document_type", str(d, "document_type"))
                    .put("loan_application_document_id", str(d, "id")).put("status", str(d, "status"));
        }
        if (Set.of("MORTGAGE", "VEHICLE").contains(str(app, "loan_type"))) {
            c.addObject().put("type", "COLLATERAL").put("description", "Pledge of the financed asset before disbursement");
        }
        return c;
    }

    private Optional<ObjectNode> pendingRequest(UUID appId) {
        return rows.queryOne("""
                select row_to_json(r)::text from credit_decision_request r
                where loan_application_id = ? and status = 'PENDING_APPROVAL' limit 1""", appId);
    }

    private static void requirePending(ObjectNode req) {
        if (!"PENDING_APPROVAL".equals(str(req, "status"))) {
            throw ApiException.conflict("invalid_transition", "Decision request " + str(req, "id") + " is " + str(req, "status"));
        }
    }
}
