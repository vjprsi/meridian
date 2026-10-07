package io.meridian.campaign.application;

import static io.meridian.campaign.application.Json.text;

import io.meridian.campaign.api.generated.model.CampaignOffer;
import io.meridian.campaign.api.generated.model.OfferAssignment;
import io.meridian.campaign.domain.Eligibility;
import io.meridian.campaign.domain.Lifecycles;
import io.meridian.campaign.infrastructure.ExternalServices;
import io.meridian.campaign.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Personalised offers and their assignments. An assignment needs an ACTIVE offer within its validity, eligibility
 * (segments, score band), room under the offer's assignment cap and a recorded marketing consent snapshot; the
 * customer's responses move it ASSIGNED -> SHOWN -> CLICKED -> ACCEPTED | DECLINED, and anything still open past
 * its expiry becomes EXPIRED.
 */
@Service
public class OfferService {
    static final String T = "campaign_offer";
    static final String A = "offer_assignment";
    static final int MAX_CUSTOMERS = 5_000;

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final ExternalServices external;
    private final AudienceService audience;
    private final TransactionTemplate tx;

    public OfferService(Rows rows, CrudEngine crud, Events events, ExternalServices external, AudienceService audience,
                        TransactionTemplate tx) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.external = external;
        this.audience = audience;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ offers

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        if (text(v, "code") != null) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        validate(v);
        v.put("status", "DRAFT");
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if (!Set.of("DRAFT", "PAUSED").contains(text(current, "status"))) {
            throw ApiException.conflict("offer_not_editable", "Only DRAFT or PAUSED offers can be changed");
        }
        ObjectNode v = crud.toColumns(T, body);
        if (v.has("code")) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    private void validate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        OffsetDateTime from = Json.ts(v, "valid_from");
        OffsetDateTime to = Json.ts(v, "valid_to");
        if (from != null && to != null && !to.isAfter(from)) {
            errors.add(new FieldError("valid_to", "before_valid_from", "valid_to must be after valid_from"));
        }
        BigDecimal max = Json.decimal(v, "max_amount");
        if (max != null && (max.signum() <= 0 || text(v, "currency") == null)) {
            errors.add(new FieldError("max_amount", "invalid", "max_amount must be > 0 and needs a currency"));
        }
        if (v.hasNonNull("max_assignments") && v.get("max_assignments").asInt() < 1) {
            errors.add(new FieldError("max_assignments", "out_of_range", "max_assignments must be >= 1"));
        }
        JsonNode rules = v.get("eligibility_rules");
        if (rules != null && rules.isObject()) {
            try {
                Eligibility.Rules r = rules(rules);
                if (r.minScore() != null && r.maxScore() != null && r.minScore().compareTo(r.maxScore()) > 0) {
                    errors.add(new FieldError("eligibility_rules.min_score", "out_of_range", "min_score exceeds max_score"));
                }
            } catch (RuntimeException e) {
                errors.add(new FieldError("eligibility_rules", "invalid", "segment_ids / exclusion_segment_ids must be id lists and scores numbers"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_offer", "Offer is invalid", errors);
        }
    }

    static Eligibility.Rules rules(JsonNode r) {
        if (r == null || !r.isObject()) {
            return new Eligibility.Rules(List.of(), List.of(), null, null);
        }
        return new Eligibility.Rules(Json.uuids(r, "segment_ids"), Json.uuids(r, "exclusion_segment_ids"),
                Json.decimal(r, "min_score"), Json.decimal(r, "max_score"));
    }

    @Transactional
    public ObjectNode activate(UUID id) {
        ObjectNode o = rows.lock(T, id);
        String to = Lifecycles.OFFER.apply("activate", text(o, "status"));
        if (!Json.ts(o, "valid_to").isAfter(rows.now())) {
            throw ApiException.unprocessable("offer_expired", "The offer's validity has ended");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.putNull("status_reason");
        return publish("engagement.campaign_offer.activated", rows.update(T, id, v));
    }

    @Transactional
    public ObjectNode pause(UUID id, String reason) {
        ObjectNode o = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.OFFER.apply("pause", text(o, "status")));
        v.put("status_reason", Json.truncate(reason, 255));
        return publish("engagement.campaign_offer.paused", rows.update(T, id, v));
    }

    /** Withdraws the offer and every open assignment of it. */
    @Transactional
    public ObjectNode withdraw(UUID id, String reason) {
        ObjectNode o = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.OFFER.apply("withdraw", text(o, "status")));
        v.put("status_reason", Json.truncate(reason, 255));
        ObjectNode row = publish("engagement.campaign_offer.withdrawn", rows.update(T, id, v));
        for (ObjectNode a : rows.query("""
                select row_to_json(t)::text from offer_assignment t where t.campaign_offer_id = ?
                and t.status in ('ASSIGNED', 'SHOWN', 'CLICKED') for update""", id)) {
            transition(a, "withdraw", "engagement.offer.withdrawn", null);
        }
        return row;
    }

    /** Offers past valid_to become EXPIRED (with their open assignments); returns {offers, assignments}. */
    public int[] expireDue() {
        int offers = 0;
        for (ObjectNode o : rows.query("""
                select row_to_json(t)::text from campaign_offer t where t.status in ('ACTIVE', 'PAUSED')
                and t.valid_to <= now() for update""")) {
            ObjectNode v = rows.object();
            v.put("status", Lifecycles.OFFER.apply("expire", text(o, "status")));
            publish("engagement.campaign_offer.expired", rows.update(T, Json.uuid(o, "id"), v));
            offers++;
        }
        int assignments = 0;
        for (ObjectNode a : rows.query("""
                select row_to_json(t)::text from offer_assignment t where t.status in ('ASSIGNED', 'SHOWN', 'CLICKED')
                and (t.expires_at <= now() or exists (select 1 from campaign_offer o where o.id = t.campaign_offer_id
                     and o.status = 'EXPIRED')) for update""")) {
            transition(a, "expire", "engagement.offer.expired", null);
            assignments++;
        }
        return new int[] {offers, assignments};
    }

    // ------------------------------------------------------------------ assign

    @Transactional
    public ObjectNode assign(UUID id, JsonNode req) {
        ObjectNode o = rows.lock(T, id);
        if (!"ACTIVE".equals(text(o, "status"))) {
            throw ApiException.conflict("offer_not_active", "Only ACTIVE offers can be assigned (offer is " + text(o, "status") + ")");
        }
        OffsetDateTime now = rows.now();
        OffsetDateTime validTo = Json.ts(o, "valid_to");
        if (now.isBefore(Json.ts(o, "valid_from")) || !now.isBefore(validTo)) {
            throw ApiException.unprocessable("offer_not_valid", "The offer is outside its validity period");
        }
        Set<UUID> customers = new LinkedHashSet<>(Json.uuids(req, "customer_ids"));
        if (customers.isEmpty() || customers.size() > MAX_CUSTOMERS) {
            throw ApiException.badRequest("validation_failed", "customer_ids must hold 1-" + MAX_CUSTOMERS + " customers");
        }
        UUID campaignId = Json.uuid(req, "campaign_id");
        if (campaignId != null && rows.find("campaign", campaignId).isEmpty()) {
            throw ApiException.unprocessable("campaign_not_found", "Campaign " + campaignId + " does not exist");
        }
        OffsetDateTime expires = Json.ts(req, "expires_at");
        if (expires == null || expires.isAfter(validTo)) {
            expires = validTo;
        }
        if (!expires.isAfter(now)) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
        }
        BigDecimal score = Json.decimal(req, "score");
        JsonNode terms = req.get("personalised_terms");
        Eligibility.Rules rules = rules(o.get("eligibility_rules"));
        Integer cap = o.hasNonNull("max_assignments") ? o.get("max_assignments").asInt() : null;
        int assignedCount = o.path("assigned_count").asInt(0);

        ObjectNode out = rows.object();
        ArrayNode assigned = out.putArray("assignment_ids");
        ArrayNode skipped = out.putArray("skipped");
        for (UUID customer : customers) {
            String reason = null;
            if (rows.count("select count(*) from offer_assignment where campaign_offer_id = ? and customer_id = ?", id, customer) > 0) {
                reason = "already_assigned";
            } else if (cap != null && assignedCount >= cap) {
                reason = "offer_cap_reached";
            } else {
                Set<UUID> segments = Set.of();
                if (rules.needsSegments()) {
                    try {
                        segments = external.activeSegments(customer, rows.today());
                    } catch (RuntimeException e) {
                        reason = "segment_check_failed";
                    }
                }
                if (reason == null) {
                    reason = Eligibility.offer(rules, segments, score);
                }
            }
            if (reason != null) {
                skip(skipped, customer, reason);
                continue;
            }
            ExternalServices.Consent consent = external.marketingConsent(customer, now);
            ObjectNode s = rows.object();
            s.put("customer_id", customer.toString());
            if (consent.consentId() != null) {
                s.put("consent_id", consent.consentId().toString());
            }
            if (campaignId != null) {
                s.put("campaign_id", campaignId.toString());
            }
            s.put("campaign_offer_id", id.toString());
            s.put("channel", "IN_APP");
            s.put("is_permitted", consent.permitted());
            if (consent.reason() != null) {
                s.put("reason", consent.reason());
            }
            s.put("checked_at", now.toString());
            ObjectNode snapshot = rows.insert("marketing_consent_snapshot", s);
            if (!consent.permitted()) {
                skip(skipped, customer, consent.reason());
                continue;
            }
            ObjectNode a = rows.object();
            a.put("campaign_offer_id", id.toString());
            if (campaignId != null) {
                a.put("campaign_id", campaignId.toString());
            }
            a.put("customer_id", customer.toString());
            a.put("status", "ASSIGNED");
            if (terms != null && terms.isObject()) {
                a.set("personalised_terms", terms);
            }
            if (score != null) {
                a.put("score", score.toPlainString());
            }
            a.put("consent_snapshot_id", text(snapshot, "id"));
            a.put("assigned_at", now.toString());
            a.put("expires_at", expires.toString());
            ObjectNode row = rows.insert(A, a);
            events.publish("engagement.offer.assigned", A, row, OfferAssignment.class);
            assigned.add(text(row, "id"));
            assignedCount++;
        }
        ObjectNode v = rows.object();
        v.put("assigned_count", assignedCount);
        rows.update(T, id, v);
        out.put("campaign_offer_id", id.toString());
        out.put("assigned", assigned.size());
        out.put("skipped_count", skipped.size());
        out.put("expires_at", expires.toString());
        return out;
    }

    private static void skip(ArrayNode skipped, UUID customer, String reason) {
        ObjectNode n = skipped.addObject();
        n.put("customer_id", customer.toString());
        n.put("reason", reason);
    }

    // ------------------------------------------------------------------ assignment responses

    public ObjectNode shown(UUID id) {
        return respond(id, a -> {
            boolean first = text(a, "shown_at") == null;
            ObjectNode v = rows.object();
            if (first) {
                v.put("shown_at", rows.now().toString());
            }
            return transition(a, "shown", first ? "engagement.offer.shown" : null, v);
        });
    }

    public ObjectNode clicked(UUID id) {
        return respond(id, a -> {
            boolean first = text(a, "clicked_at") == null;
            String now = rows.now().toString();
            ObjectNode v = rows.object();
            if (text(a, "shown_at") == null) {
                v.put("shown_at", now);
            }
            if (first) {
                v.put("clicked_at", now);
            }
            return transition(a, "clicked", first ? "engagement.offer.clicked" : null, v);
        });
    }

    public ObjectNode accept(UUID id, String resultingType, UUID resultingId) {
        return respond(id, a -> {
            ObjectNode offer = rows.lock(T, Json.uuid(a, "campaign_offer_id"));
            if (!"ACTIVE".equals(text(offer, "status"))) {
                throw ApiException.conflict("offer_not_active", "The offer is " + text(offer, "status"));
            }
            ObjectNode v = rows.object();
            v.put("responded_at", rows.now().toString());
            if (resultingType != null) {
                v.put("resulting_object_type", Json.truncate(resultingType, 40));
            }
            if (resultingId != null) {
                v.put("resulting_object_id", resultingId.toString());
            }
            ObjectNode row = transition(a, "accept", "engagement.offer.accepted", v);
            ObjectNode ov = rows.object();
            ov.put("accepted_count", offer.path("accepted_count").asInt(0) + 1);
            rows.update(T, Json.uuid(offer, "id"), ov);
            UUID campaignId = Json.uuid(a, "campaign_id");
            if (campaignId != null) {
                audience.member(campaignId, Json.uuid(a, "customer_id")).ifPresent(m -> audience.markConverted(m, null));
            }
            return row;
        });
    }

    public ObjectNode decline(UUID id, String reason) {
        return respond(id, a -> {
            ObjectNode v = rows.object();
            v.put("responded_at", rows.now().toString());
            if (reason != null) {
                v.put("decline_reason", Json.truncate(reason, 255));
            }
            return transition(a, "decline", "engagement.offer.declined", v);
        });
    }

    /**
     * Runs a customer response in its own transaction. An open assignment found past its expiry is first committed
     * as EXPIRED (with its event) and the response is then refused with 409 offer_expired.
     */
    private ObjectNode respond(UUID id, Function<ObjectNode, ObjectNode> action) {
        Object result = tx.execute(s -> {
            ObjectNode a = rows.lock(A, id);
            if (Lifecycles.ASSIGNMENT_OPEN.contains(text(a, "status")) && !Json.ts(a, "expires_at").isAfter(rows.now())) {
                transition(a, "expire", "engagement.offer.expired", null);
                return Boolean.FALSE;
            }
            return action.apply(a);
        });
        if (Boolean.FALSE.equals(result)) {
            throw ApiException.conflict("offer_expired", "The offer assignment has expired");
        }
        return (ObjectNode) result;
    }

    private ObjectNode transition(ObjectNode a, String action, String event, ObjectNode extra) {
        String to = Lifecycles.ASSIGNMENT.apply(action, text(a, "status"));
        ObjectNode v = extra == null ? rows.object() : extra;
        v.put("status", to);
        ObjectNode row = rows.update(A, Json.uuid(a, "id"), v);
        if (event != null) {
            events.publish(event, A, row, OfferAssignment.class);
        }
        return row;
    }

    @Transactional(readOnly = true)
    public JsonNode forCustomer(UUID customerId, Integer limit, String cursor) {
        return rows.page("""
                select row_to_json(t)::text from offer_assignment t join campaign_offer o on o.id = t.campaign_offer_id
                where t.customer_id = ? and t.status in ('ASSIGNED', 'SHOWN', 'CLICKED') and t.expires_at > now()
                and o.status = 'ACTIVE' and o.valid_to > now()""", List.of(customerId), limit, cursor);
    }

    private ObjectNode publish(String type, ObjectNode row) {
        events.publish(type, T, row, CampaignOffer.class);
        return row;
    }
}
