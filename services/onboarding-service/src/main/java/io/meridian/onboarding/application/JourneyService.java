package io.meridian.onboarding.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.onboarding.api.generated.model.OnboardingJourney;
import io.meridian.onboarding.domain.DecisionEngine;
import io.meridian.onboarding.domain.JourneyDefinition;
import io.meridian.onboarding.domain.JourneyDefinition.StepDef;
import io.meridian.onboarding.domain.JsonValues;
import io.meridian.onboarding.domain.Lifecycles;
import io.meridian.onboarding.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Versioned journey definitions: draft, maker-checker publication, retirement, new versions and dry runs. */
@Service
public class JourneyService {
    static final String T = "onboarding_journey";
    public static final Set<String> CHANNELS = Set.of("MOBILE_APP", "WEB", "USSD", "AGENT", "BRANCH", "API");
    private static final List<String> COPY = List.of("code", "name", "customer_type", "country", "channels", "product_ids",
            "target_tier", "definition", "decision_rules", "expiry_days");

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;

    public JourneyService(Rows rows, CrudEngine crud, Events events) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        v.remove(List.of("status", "version", "published_at"));
        Integer existing = rows.jdbc().sql("select count(*) from onboarding_journey where code = ?")
                .param(v.path("code").asString()).query(Integer.class).single();
        if (existing > 0) {
            throw ApiException.conflict("journey_code_exists",
                    "A journey with this code exists; use POST /{id}/new-version to create the next version");
        }
        normaliseAndValidate(v);
        v.put("status", "DRAFT");
        v.put("version", 1);
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, Object body, String ifMatch) {
        int expected = Events.parseIfMatch(ifMatch);
        ObjectNode row = rows.lock(T, id);
        if (!"DRAFT".equals(row.get("status").asString())) {
            throw ApiException.conflict("journey_not_editable",
                    "Only DRAFT journeys can be edited; create a new version instead");
        }
        ObjectNode v = crud.toColumns(T, body);
        v.remove(List.of("status", "version", "published_at"));
        if (v.has("code") && !v.get("code").asString().equals(row.get("code").asString())) {
            throw ApiException.unprocessable("code_immutable", "The journey code cannot be changed");
        }
        ObjectNode merged = row.deepCopy();
        merged.setAll(v);
        normaliseAndValidate(merged);
        for (String f : List.of("channels", "product_ids")) {
            if (v.has(f)) {
                v.set(f, merged.get(f));
            }
        }
        return rows.update(T, id, v, expected);
    }

    @Transactional
    public ObjectNode publish(UUID id) {
        ObjectNode row = rows.lock(T, id);
        Lifecycles.journey(row.get("status").asString(), "publish");
        Events.fourEyes(row.path("created_by").asString(null));
        normaliseAndValidate(row.deepCopy());
        rows.jdbc().sql("""
                update onboarding_journey set status = 'RETIRED', updated_by = ?, retired_at = now(),
                    retirement_reason = 'Superseded by version ' || ?::text
                where code = ? and status = 'PUBLISHED' and id <> ?""")
                .params(TenantContext.principal(), row.get("version").asInt(), row.get("code").asString(), id).update();
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("status", "PUBLISHED");
        v.put("published_at", Events.now());
        v.put("published_by", TenantContext.principal());
        ObjectNode updated = rows.update(T, id, v);
        events.publish("party.onboarding_journey.published", T, updated, OnboardingJourney.class);
        return updated;
    }

    @Transactional
    public ObjectNode retire(UUID id, String reason) {
        ObjectNode row = rows.lock(T, id);
        Lifecycles.journey(row.get("status").asString(), "retire");
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("status", "RETIRED");
        v.put("retired_at", Events.now());
        if (reason != null && !reason.isBlank()) {
            v.put("retirement_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
        ObjectNode updated = rows.update(T, id, v);
        events.publish("party.onboarding_journey.retired", T, updated, OnboardingJourney.class);
        return updated;
    }

    @Transactional
    public ObjectNode newVersion(UUID id) {
        ObjectNode src = rows.lock(T, id);
        String code = src.get("code").asString();
        Integer drafts = rows.jdbc().sql("select count(*) from onboarding_journey where code = ? and status = 'DRAFT'")
                .param(code).query(Integer.class).single();
        if (drafts > 0) {
            throw ApiException.conflict("draft_exists", "A DRAFT version of this journey already exists");
        }
        Integer max = rows.jdbc().sql("select max(version) from onboarding_journey where code = ?")
                .param(code).query(Integer.class).single();
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        COPY.forEach(f -> {
            if (src.hasNonNull(f)) {
                v.set(f, src.get(f));
            }
        });
        v.put("version", max + 1);
        v.put("status", "DRAFT");
        return rows.insert(T, v);
    }

    /** Dry run: walks the graph with the sample data, reporting the path, missing fields, risk and decision. */
    @Transactional(readOnly = true)
    public ObjectNode simulate(UUID id, JsonNode sample) {
        ObjectNode row = rows.get(T, id);
        JourneyDefinition def = JourneyDefinition.of(row.get("definition"));
        JsonNode data = sample == null ? JsonNodeFactory.instance.objectNode() : sample;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("journey_id", id.toString());
        out.put("version", row.get("version").asInt());
        ArrayNode path = out.putArray("path");
        boolean complete = true;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String cur = def.start(); cur != null && seen.add(cur); cur = def.next(cur, data)) {
            StepDef s = def.step(cur).orElseThrow();
            ObjectNode p = path.addObject();
            p.put("step_code", s.code());
            p.put("step_type", s.type());
            p.put("required", s.required());
            p.put("kyc_check", s.hasKycCheck());
            ArrayNode missing = p.putArray("missing_fields");
            s.fields().stream().filter(f -> JsonValues.blank(data.get(f))).forEach(missing::add);
            if (s.required() && !missing.isEmpty()) {
                complete = false;
            }
        }
        out.put("complete", complete);
        String channel = data.path("channel").asString(null);
        DecisionEngine.Risk risk = DecisionEngine.assess(0, 0, data, channel);
        ObjectNode facts = data.isObject() ? ((ObjectNode) data).deepCopy() : JsonNodeFactory.instance.objectNode();
        facts.put("risk_score", risk.score());
        facts.put("risk_level", risk.level());
        facts.put("failed_checks", 0);
        facts.put("inconclusive_checks", 0);
        DecisionEngine.Decision d = DecisionEngine.decide(row.get("decision_rules"), facts, 0, 0);
        out.put("risk_score", risk.score());
        out.put("risk_level", risk.level());
        out.set("risk_factors", risk.factors());
        out.put("decision", d.decision());
        ArrayNode reasons = out.putArray("decision_reasons");
        d.reasons().forEach(reasons::add);
        return out;
    }

    /** Validates channels, products, definition and rules; rewrites list-valued json to the canonical object form. */
    private static void normaliseAndValidate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        List<String> channels = JsonValues.values(v.get("channels"));
        if (channels.isEmpty()) {
            errors.add(new FieldError("channels", "required", "at least one channel is required"));
        }
        channels.stream().filter(c -> !CHANNELS.contains(c)).forEach(c ->
                errors.add(new FieldError("channels", "invalid_channel", c + " is not an OnboardingChannel")));
        v.set("channels", JsonValues.of(channels));
        if (v.hasNonNull("product_ids")) {
            v.set("product_ids", JsonValues.of(JsonValues.values(v.get("product_ids"))));
        }
        if (v.hasNonNull("expiry_days") && (v.get("expiry_days").asInt() < 1 || v.get("expiry_days").asInt() > 365)) {
            errors.add(new FieldError("expiry_days", "out_of_range", "expiry_days must be 1..365"));
        }
        errors.addAll(JourneyDefinition.validate(v.get("definition")));
        errors.addAll(DecisionEngine.validateRules(v.get("decision_rules")));
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_definition", "Journey definition is invalid", errors);
        }
    }
}
