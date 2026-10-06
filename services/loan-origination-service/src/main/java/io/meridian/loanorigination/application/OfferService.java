package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.dec;
import static io.meridian.loanorigination.infrastructure.Rows.integer;
import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loanorigination.config.OriginationProperties;
import io.meridian.loanorigination.domain.Currencies;
import io.meridian.loanorigination.domain.Lifecycles;
import io.meridian.loanorigination.domain.OfferPricing;
import io.meridian.loanorigination.infrastructure.ProductCatalogClient;
import io.meridian.loanorigination.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Priced offers (schedule preview, APR, expiry on the database clock), acceptance and indicative quotes. */
@Service
public class OfferService {
    static final String OFFER = "loan_offer";
    private static final Set<String> OFFERABLE = Set.of("APPROVED", "COUNTER_OFFERED");

    private final Support support;
    private final Rows rows;
    private final Events events;
    private final ProductCatalogClient catalog;
    private final OriginationProperties props;
    private final SignatureService signatures;

    public OfferService(Support support, ProductCatalogClient catalog, OriginationProperties props,
                        SignatureService signatures) {
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
        this.catalog = catalog;
        this.props = props;
        this.signatures = signatures;
    }

    /** Issues the offer of an approving decision (locked application). */
    ObjectNode issue(ObjectNode app, ObjectNode decision) {
        ProductTerms terms = catalog.terms(uuid(app, "product_id"), uuid(app, "product_version_id"));
        BigDecimal amount = dec(decision, "approved_amount") != null ? dec(decision, "approved_amount") : dec(app, "requested_amount");
        Integer term = integer(decision, "approved_term_months") != null ? integer(decision, "approved_term_months")
                : integer(app, "requested_term_months");
        BigDecimal rate = dec(decision, "approved_rate");
        if (rate == null) {
            rate = baseRate(terms).add(latestAdjustment(uuid(app, "id"))).max(BigDecimal.ZERO);
        }
        String type = str(app, "requested_schedule_type") == null || "CUSTOM".equals(str(app, "requested_schedule_type"))
                ? "ANNUITY" : str(app, "requested_schedule_type");
        String frequency = str(app, "requested_frequency") == null ? "MONTHLY" : str(app, "requested_frequency");
        BigDecimal fees = terms.originationFee(amount, Currencies.digits(str(app, "currency")));
        ObjectNode v = rows.object();
        v.put("loan_application_id", str(app, "id"));
        v.put("credit_decision_id", str(decision, "id"));
        v.put("offer_amount", amount.toPlainString());
        v.put("currency", str(app, "currency"));
        v.put("term_months", term == null ? 12 : term);
        v.put("repayment_frequency", frequency);
        v.put("schedule_type", type);
        v.put("interest_rate", rate.toPlainString());
        v.put("upfront_fees", fees.toPlainString());
        if ("BALLOON".equals(type)) {
            v.put("balloon_amount", Currencies.round(amount.multiply(new BigDecimal("0.30")), str(app, "currency")).toPlainString());
        }
        return persist(app, v, validityDays(terms), null);
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(OFFER, body);
        UUID appId = uuid(v, "loan_application_id");
        if (rows.find(Support.APP, appId).isEmpty()) {
            throw ApiException.unprocessable("application_not_found", "loan_application_id " + appId + " does not exist");
        }
        ObjectNode app = support.lockApp(appId);
        if (!OFFERABLE.contains(str(app, "status"))) {
            throw ApiException.conflict("invalid_transition", "Application " + appId + " is " + str(app, "status")
                    + "; offers are made on APPROVED or COUNTER_OFFERED applications");
        }
        ObjectNode decision = rows.queryOne("""
                select row_to_json(d)::text from credit_decision d where id = ? and loan_application_id = ?""",
                uuid(v, "credit_decision_id"), appId).orElseThrow(() -> ApiException.unprocessable("decision_not_found",
                "credit_decision_id does not reference a decision of this application"));
        ObjectNode latest = latestDecision(appId);
        if (!str(latest, "id").equals(str(decision, "id"))) {
            throw ApiException.unprocessable("decision_superseded", "Offers must be based on the latest decision " + str(latest, "id"));
        }
        if (!Set.of("APPROVED", "COUNTER_OFFER").contains(str(decision, "outcome"))) {
            throw ApiException.unprocessable("decision_not_approving", "Decision " + str(decision, "id") + " is "
                    + str(decision, "outcome"));
        }
        if (!str(app, "currency").equals(str(v, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "Offer currency must be " + str(app, "currency"));
        }
        BigDecimal approved = dec(decision, "approved_amount");
        if (approved != null && dec(v, "offer_amount").compareTo(approved) > 0) {
            throw ApiException.unprocessable("amount_exceeds_approval", "offer_amount exceeds the approved amount "
                    + approved.toPlainString());
        }
        if (dec(v, "interest_rate").signum() < 0) {
            throw ApiException.unprocessable("invalid_rate", "interest_rate must not be negative");
        }
        if ("CUSTOM".equals(str(v, "schedule_type"))) {
            throw ApiException.unprocessable("unsupported_schedule_type", "CUSTOM schedules are agreed in loan servicing");
        }
        OffsetDateTime expires = Rows.ts(v, "expires_at");
        if (expires != null && !expires.isAfter(support.dbNow())) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
        }
        ProductTerms terms = catalog.terms(uuid(app, "product_id"), uuid(app, "product_version_id"));
        ApplicationService.validateRequest(terms, dec(v, "offer_amount"), integer(v, "term_months"), str(app, "currency"));
        return persist(app, v, validityDays(terms), expires);
    }

    @Transactional
    public ObjectNode accept(UUID offerId, String provider) {
        ObjectNode offer = rows.lock(OFFER, offerId);
        Support.require(Lifecycles.OFFER, "Offer", str(offer, "status"), "ACCEPTED");
        if (!"ISSUED".equals(str(offer, "status"))) {
            throw ApiException.conflict("invalid_transition", "Offer " + offerId + " is " + str(offer, "status"));
        }
        if (!rows.scalar("select expires_at > now() from loan_offer where id = ?", Boolean.class, offerId).orElse(false)) {
            throw ApiException.conflict("offer_expired", "Offer " + offerId + " expired at " + str(offer, "expires_at"));
        }
        ObjectNode app = support.lockApp(uuid(offer, "loan_application_id"));
        support.transition(app, "OFFER_ACCEPTED");
        ObjectNode v = rows.object();
        v.put("status", "ACCEPTED");
        v.put("accepted_at", support.dbNow().toString());
        ObjectNode accepted = rows.update(OFFER, offerId, v);
        events.publish("lending.loan_offer.accepted", OFFER, accepted);
        return signatures.createAndSend(accepted, provider);
    }

    @Transactional
    public ObjectNode decline(UUID offerId, String reason) {
        return close(offerId, "DECLINED", reason == null ? "Declined by the applicant" : reason, "lending.loan_offer.declined");
    }

    @Transactional
    public ObjectNode withdraw(UUID offerId, String reason) {
        return close(offerId, "WITHDRAWN", reason, "lending.loan_offer.withdrawn");
    }

    @Transactional(readOnly = true)
    public ObjectNode preview(UUID offerId) {
        ObjectNode o = rows.get(OFFER, offerId);
        ObjectNode out = rows.object();
        out.put("loan_offer_id", offerId.toString());
        out.put("status", str(o, "status"));
        out.put("currency", str(o, "currency"));
        out.put("offer_amount", str(o, "offer_amount"));
        out.put("term_months", integer(o, "term_months"));
        out.put("repayment_frequency", str(o, "repayment_frequency"));
        out.put("schedule_type", str(o, "schedule_type"));
        out.put("interest_rate", str(o, "interest_rate"));
        out.put("apr", str(o, "apr"));
        out.put("upfront_fees", str(o, "upfront_fees"));
        out.put("instalment_amount", str(o, "instalment_amount"));
        out.put("total_interest", str(o, "total_interest"));
        out.put("total_repayable", str(o, "total_repayable"));
        out.put("expires_at", str(o, "expires_at"));
        out.set("installments", o.get("schedule_preview"));
        return out;
    }

    /** Indicative price without persistence or bureau pull (rate = product rate + risk-grade adjustment). */
    @Transactional(readOnly = true)
    public ObjectNode quickQuote(JsonNode req) {
        UUID productId = Json.uuid(req, "product_id");
        ProductTerms terms = catalog.terms(productId, Json.uuid(req, "product_version_id"));
        String currency = Json.text(req, "currency");
        if (terms.currency() != null && !terms.currency().equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch", "Product " + productId + " is sold in " + terms.currency());
        }
        BigDecimal amount = Json.decimal(req, "amount");
        Integer term = Json.integer(req, "term_months");
        ApplicationService.validateRequest(terms, amount, term, currency);
        String grade = Json.text(req, "risk_grade");
        BigDecimal adjustment = grade == null ? BigDecimal.ZERO : gradeAdjustment(grade);
        BigDecimal rate = baseRate(terms).add(adjustment).max(BigDecimal.ZERO);
        String type = Json.text(req, "schedule_type") == null ? "ANNUITY" : Json.text(req, "schedule_type");
        if ("CUSTOM".equals(type)) {
            throw ApiException.unprocessable("unsupported_schedule_type", "CUSTOM schedules cannot be quoted");
        }
        String frequency = Json.text(req, "repayment_frequency") == null ? "MONTHLY" : Json.text(req, "repayment_frequency");
        BigDecimal fees = terms.originationFee(amount, Currencies.digits(currency));
        LocalDate start = support.dbToday();
        OfferPricing.Quote q = price(amount, rate, term, frequency, type, fees, null, start, null, currency);
        ObjectNode out = rows.object();
        out.put("indicative", true);
        out.put("product_id", productId.toString());
        out.put("product_version_id", terms.productVersionId().toString());
        out.put("amount", amount.toPlainString());
        out.put("currency", currency);
        out.put("term_months", term);
        out.put("repayment_frequency", frequency);
        out.put("schedule_type", type);
        if (grade != null) {
            out.put("risk_grade", grade);
        }
        out.put("interest_rate", rate.toPlainString());
        out.put("apr", q.apr().toPlainString());
        out.put("instalment_amount", q.instalment().toPlainString());
        out.put("installment_count", q.installments().size());
        out.put("total_interest", q.totalInterest().toPlainString());
        out.put("upfront_fees", fees.toPlainString());
        out.put("total_repayable", q.totalRepayable().toPlainString());
        out.put("first_repayment_date", q.installments().getFirst().dueDate().toString());
        out.put("quoted_on", start.toString());
        return out;
    }

    // ------------------------------------------------------------------ internals

    private ObjectNode persist(ObjectNode app, ObjectNode v, int validityDays, OffsetDateTime expires) {
        String currency = str(app, "currency");
        BigDecimal fees = dec(v, "upfront_fees") == null ? BigDecimal.ZERO : dec(v, "upfront_fees");
        if (fees.signum() < 0 || fees.compareTo(dec(v, "offer_amount")) >= 0) {
            throw ApiException.unprocessable("invalid_fees", "upfront_fees must be between 0 and the offer amount");
        }
        String type = str(v, "schedule_type") == null ? "ANNUITY" : str(v, "schedule_type");
        OfferPricing.Quote q;
        try {
            q = price(dec(v, "offer_amount"), dec(v, "interest_rate"), integer(v, "term_months"),
                    str(v, "repayment_frequency"), type, fees, dec(v, "balloon_amount"), support.dbToday(),
                    Rows.date(v, "first_repayment_date"), currency);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_offer", e.getMessage());
        }
        // a new offer supersedes the ones still open
        for (ObjectNode open : rows.query("""
                select row_to_json(o)::text from loan_offer o where loan_application_id = ? and status = 'ISSUED' for update""",
                uuid(app, "id"))) {
            ObjectNode w = rows.object();
            w.put("status", "WITHDRAWN");
            w.put("status_reason", "Superseded by a new offer");
            events.publish("lending.loan_offer.withdrawn", OFFER, rows.update(OFFER, uuid(open, "id"), w));
        }
        v.put("schedule_type", type);
        v.put("upfront_fees", fees.toPlainString());
        v.put("apr", q.apr().toPlainString());
        v.put("instalment_amount", q.instalment().toPlainString());
        v.put("total_interest", q.totalInterest().toPlainString());
        v.put("total_repayable", q.totalRepayable().toPlainString());
        v.put("first_repayment_date", q.installments().getFirst().dueDate().toString());
        v.set("schedule_preview", schedule(q));
        v.put("status", "ISSUED");
        v.put("expires_at", (expires != null ? expires : support.dbNow().plusDays(validityDays)).toString());
        ObjectNode row = rows.insert(OFFER, v);
        events.publish("lending.loan_offer.issued", OFFER, row);
        return row;
    }

    private ObjectNode close(UUID offerId, String to, String reason, String event) {
        ObjectNode offer = rows.lock(OFFER, offerId);
        Support.require(Lifecycles.OFFER, "Offer", str(offer, "status"), to);
        ObjectNode app = support.lockApp(uuid(offer, "loan_application_id"));
        if (Set.of("SIGNED", "BOOKED", "DISBURSED").contains(str(app, "status"))) {
            throw ApiException.conflict("offer_signed", "The agreement of application " + str(app, "id") + " is already signed");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        if (reason != null) {
            v.put("status_reason", reason);
        }
        ObjectNode updated = rows.update(OFFER, offerId, v);
        events.publish(event, OFFER, updated);
        if ("ACCEPTED".equals(str(offer, "status"))) {
            signatures.voidOpenEnvelopes(offerId, "Offer " + to.toLowerCase());
            if ("OFFER_ACCEPTED".equals(str(app, "status"))) {
                support.transition(app, "APPROVED");
            }
        }
        return updated;
    }

    static OfferPricing.Quote price(BigDecimal amount, BigDecimal rate, Integer term, String frequency, String type,
                                    BigDecimal fees, BigDecimal balloon, LocalDate start, LocalDate first, String currency) {
        return OfferPricing.price(new OfferPricing.Request(amount, rate, term == null ? 12 : term,
                OfferPricing.Frequency.parse(frequency), OfferPricing.ScheduleType.parse(type), fees, balloon, start,
                first, currency));
    }

    private ArrayNode schedule(OfferPricing.Quote q) {
        ArrayNode a = rows.json().createArrayNode();
        for (OfferPricing.Installment i : q.installments()) {
            ObjectNode n = a.addObject();
            n.put("installment_no", i.no());
            n.put("due_date", i.dueDate().toString());
            n.put("principal", i.principal().toPlainString());
            n.put("interest", i.interest().toPlainString());
            n.put("payment", i.payment().toPlainString());
            n.put("balance_after", i.balanceAfter().toPlainString());
        }
        return a;
    }

    private BigDecimal baseRate(ProductTerms terms) {
        return terms.baseRate() == null ? props.defaultRate() : terms.baseRate();
    }

    private int validityDays(ProductTerms terms) {
        return terms.offerValidityDays() == null ? props.offerValidityDays() : terms.offerValidityDays();
    }

    private BigDecimal latestAdjustment(UUID appId) {
        return rows.scalar("""
                select rate_adjustment from credit_score where loan_application_id = ? order by created_at desc, id desc limit 1""",
                BigDecimal.class, appId).orElse(BigDecimal.ZERO);
    }

    /** Pricing adjustment of a risk grade in the champion application scorecard's cut-offs (0 when unknown). */
    private BigDecimal gradeAdjustment(String grade) {
        for (ObjectNode v : rows.query("""
                select row_to_json(v)::text from scorecard_version v join scorecard s on s.id = v.scorecard_id
                where s.scorecard_type = 'APPLICATION' and s.is_active and v.status = 'CHAMPION'""")) {
            for (JsonNode c : v.path("cutoffs")) {
                if (grade.equals(c.path("risk_grade").asString("")) && c.hasNonNull("rate_adjustment")) {
                    return new BigDecimal(c.get("rate_adjustment").asString());
                }
            }
        }
        return BigDecimal.ZERO;
    }

    ObjectNode latestDecision(UUID appId) {
        return rows.queryOne("""
                select row_to_json(d)::text from credit_decision d where loan_application_id = ?
                order by created_at desc, id desc limit 1""", appId)
                .orElseThrow(() -> ApiException.unprocessable("decision_not_found", "Application " + appId + " has no decision"));
    }
}
