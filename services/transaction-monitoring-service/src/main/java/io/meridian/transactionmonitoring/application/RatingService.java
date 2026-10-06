package io.meridian.transactionmonitoring.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.transactionmonitoring.domain.RiskRating;
import io.meridian.transactionmonitoring.infrastructure.Db;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Customer risk ratings: calculated from factor inputs plus monitoring behaviour, recalculated on demand, and
 * overridden only with a second user's approval (the effective rating changes when the override is approved).
 */
@Service
public class RatingService {
    static final Set<String> FACTOR_KEYS = Set.of("country_risk", "product_risk", "channel_risk", "occupation_risk", "pep",
            "adverse_media", "sanctioned");

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public RatingService(Db db, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(JsonNode body) {
        UUID customer = Db.uuid(body, "customer_id");
        if (db.one("select 1::text from customer_risk_rating where customer_id = ?", customer).isPresent()) {
            throw ApiException.conflict("rating_exists", "Customer " + customer + " already has a risk rating; recalculate it");
        }
        Map<String, Object> inputs = inputs(body.get("factors"));
        RiskRating.Result r = RiskRating.rate(inputs, behaviour(customer), today());
        ObjectNode v = crud.toColumns("customer_risk_rating", body);
        apply(v, r);
        if (!v.hasNonNull("rated_by")) {
            v.put("rated_by", "SYSTEM");
        }
        String row = crud.insert("customer_risk_rating", v);
        JsonNode n = json.readTree(row);
        outbox.publish("risk.customer_risk_rating.changed", "customer_risk_rating", UUID.fromString(n.get("id").asString()), n);
        return row;
    }

    /** PATCH: new factor inputs are re-rated immediately. */
    @Transactional
    public String update(UUID id, JsonNode changes, int expectedVersion) {
        JsonNode cur = db.require("customer_risk_rating", id, true);
        if (cur.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        ObjectNode v = crud.toColumns("customer_risk_rating", changes);
        v.remove("customer_id");
        if (v.has("factors")) {
            RiskRating.Result r = RiskRating.rate(inputs(v.get("factors")), behaviour(UUID.fromString(cur.get("customer_id").asString())), today());
            apply(v, r);
            v.put("effective_rating", effective(cur, r.rating()));
        }
        String row = crud.updateRow("customer_risk_rating", Map.of("id", id), v, expectedVersion).orElseThrow();
        publishIfChanged(cur, json.readTree(row));
        return row;
    }

    @Transactional
    public String recalculate(UUID id) {
        JsonNode cur = db.require("customer_risk_rating", id, true);
        JsonNode stored = cur.get("factors");
        JsonNode inputs = stored != null && stored.has("inputs") ? stored.get("inputs") : stored;
        RiskRating.Result r = RiskRating.rate(inputs(inputs), behaviour(UUID.fromString(cur.get("customer_id").asString())), today());
        String effective = effective(cur, r.rating());
        db.update("""
                update customer_risk_rating set rating = ?, score = ?, factors = cast(? as jsonb), model_version = ?,
                    next_review_date = ?, effective_rating = ?, rated_at = now(), rated_by = ?, updated_by = ?
                where id = ?""", r.rating(), r.score(), json.writeValueAsString(r.factors()), RiskRating.MODEL_VERSION,
                RiskRating.nextReview(effective, today()), effective, TenantContext.principal(), TenantContext.principal(), id);
        JsonNode row = db.require("customer_risk_rating", id, false);
        publishIfChanged(cur, row);
        return db.text(row);
    }

    @Transactional
    public String requestOverride(UUID id, String rating, String reason) {
        JsonNode cur = db.require("customer_risk_rating", id, true);
        if ("PENDING_APPROVAL".equals(Db.str(cur, "override_status"))) {
            throw ApiException.conflict("override_pending", "An override is already awaiting approval");
        }
        if (reason == null || reason.strip().length() < 10) {
            throw ApiException.unprocessable("justification_required", "Give a justification of at least 10 characters");
        }
        db.update("""
                update customer_risk_rating set override_rating = ?, override_reason = ?, override_status = 'PENDING_APPROVAL',
                    override_requested_by = ?, override_approved_by = null, updated_by = ? where id = ?""",
                rating, reason, TenantContext.principal(), TenantContext.principal(), id);
        JsonNode row = db.require("customer_risk_rating", id, false);
        outbox.publish("risk.customer_risk_rating.override_requested", "customer_risk_rating", id, row);
        return db.text(row);
    }

    @Transactional
    public String approveOverride(UUID id) {
        JsonNode cur = pending(id);
        if (TenantContext.principal().equals(Db.str(cur, "override_requested_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The requester cannot approve their own override");
        }
        String rating = cur.get("override_rating").asString();
        db.update("""
                update customer_risk_rating set override_status = 'APPROVED', override_approved_by = ?, effective_rating = ?,
                    next_review_date = ?, updated_by = ? where id = ?""",
                TenantContext.principal(), rating, RiskRating.nextReview(rating, today()), TenantContext.principal(), id);
        JsonNode row = db.require("customer_risk_rating", id, false);
        outbox.publish("risk.customer_risk_rating.changed", "customer_risk_rating", id, row);
        return db.text(row);
    }

    @Transactional
    public String rejectOverride(UUID id, String reason) {
        pending(id);
        db.update("""
                update customer_risk_rating set override_status = 'REJECTED', override_approved_by = ?,
                    override_reason = override_reason || ' | rejected: ' || ?, updated_by = ? where id = ?""",
                TenantContext.principal(), reason, TenantContext.principal(), id);
        JsonNode row = db.require("customer_risk_rating", id, false);
        outbox.publish("risk.customer_risk_rating.override_rejected", "customer_risk_rating", id, row);
        return db.text(row);
    }

    private JsonNode pending(UUID id) {
        JsonNode cur = db.require("customer_risk_rating", id, true);
        if (!"PENDING_APPROVAL".equals(Db.str(cur, "override_status"))) {
            throw ApiException.conflict("invalid_transition", "No override is awaiting approval");
        }
        return cur;
    }

    /** Monitoring behaviour of the last 12 months: alerts, escalations and cash share of turnover. */
    RiskRating.Behaviour behaviour(UUID customer) {
        JsonNode b = db.one("""
                select json_build_object(
                  'alerts', (select count(*) from tm_alert where customer_id = ? and created_at > now() - interval '365 days'),
                  'escalated', (select count(*) from tm_alert where customer_id = ? and created_at > now() - interval '365 days'
                                and status = 'CLOSED_CASE_OPENED'),
                  'cash', (select coalesce(sum(amount) filter (where is_cash), 0) from tm_transaction
                           where customer_id = ? and occurred_at > now() - interval '365 days'),
                  'total', (select coalesce(sum(amount), 0) from tm_transaction
                            where customer_id = ? and occurred_at > now() - interval '365 days'))::text""",
                customer, customer, customer, customer).orElseThrow();
        double total = b.get("total").asDouble();
        return new RiskRating.Behaviour(b.get("alerts").asInt(), b.get("escalated").asInt(),
                total <= 0 ? 0.0 : b.get("cash").asDouble() / total);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> inputs(JsonNode factors) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (factors == null || !factors.isObject()) {
            return m;
        }
        Map<String, Object> all = json.convertValue(factors, LinkedHashMap.class);
        all.forEach((k, v) -> {
            if (FACTOR_KEYS.contains(k)) {
                m.put(k, v);
            }
        });
        return m;
    }

    private void apply(ObjectNode v, RiskRating.Result r) {
        v.put("rating", r.rating());
        v.put("score", r.score());
        v.set("factors", json.valueToTree(r.factors()));
        v.put("model_version", RiskRating.MODEL_VERSION);
        v.put("next_review_date", r.nextReview().toString());
        if (!v.has("effective_rating")) {
            v.put("effective_rating", r.rating());
        }
    }

    private static String effective(JsonNode cur, String calculated) {
        return "APPROVED".equals(Db.str(cur, "override_status")) && cur.hasNonNull("override_rating")
                ? cur.get("override_rating").asString() : calculated;
    }

    private void publishIfChanged(JsonNode before, JsonNode after) {
        if (!String.valueOf(Db.str(before, "effective_rating")).equals(Db.str(after, "effective_rating"))
                || !String.valueOf(Db.str(before, "rating")).equals(Db.str(after, "rating"))) {
            outbox.publish("risk.customer_risk_rating.changed", "customer_risk_rating",
                    UUID.fromString(after.get("id").asString()), after);
        }
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
