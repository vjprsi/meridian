package io.meridian.liquidity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.liquidity.domain.StressEngine;
import io.meridian.liquidity.infrastructure.Rows;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Liquidity stress scenarios (maker-checker approved before use) and their runs over position lines. Changing the
 * assumptions, horizon or severity of an approved scenario withdraws the approval.
 */
@Service
public class StressService {
    static final String SCENARIO = "liquidity_stress_scenario";
    static final String RESULT = "liquidity_stress_result";
    static final List<String> MATERIAL = List.of("assumptions", "horizon_days", "severity");

    private final Rows rows;
    private final Events events;

    public StressService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(SCENARIO, body);
        int horizon = v.hasNonNull("horizon_days") ? v.get("horizon_days").asInt() : 30;
        validate(v.get("assumptions"), horizon);
        String code = Rows.str(v, "code");
        if (rows.queryOne("select row_to_json(s)::text from liquidity_stress_scenario s where code = ?", code).isPresent()) {
            throw ApiException.conflict("scenario_code_exists", "a stress scenario with code " + code + " already exists");
        }
        return rows.insert(SCENARIO, v);
    }

    /** PATCH with optimistic locking; material changes are validated and withdraw the approval. */
    @Transactional
    public ObjectNode update(UUID id, int expectedVersion, Object body) {
        ObjectNode current = rows.lock(SCENARIO, id);
        if (current.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict",
                    "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        ObjectNode v = rows.columns(SCENARIO, body);
        if (v.has("code") && !Rows.str(v, "code").equals(Rows.str(current, "code"))) {
            throw ApiException.unprocessable("field_not_updatable", "code cannot be changed");
        }
        boolean material = MATERIAL.stream().anyMatch(f -> v.has(f) && !v.get(f).equals(current.get(f)));
        if (material) {
            int horizon = v.hasNonNull("horizon_days") ? v.get("horizon_days").asInt() : current.get("horizon_days").asInt();
            validate(v.has("assumptions") ? v.get("assumptions") : current.get("assumptions"), horizon);
            v.putNull("approved_by");
            v.putNull("approved_at");
        }
        return v.isEmpty() ? current : rows.update(SCENARIO, id, v);
    }

    @Transactional
    public ObjectNode approve(UUID id) {
        ObjectNode s = rows.lock(SCENARIO, id);
        if (s.hasNonNull("approved_by")) {
            throw ApiException.conflict("invalid_transition", "the scenario is already approved");
        }
        String me = TenantContext.principal();
        if (me.equals(Rows.str(s, "created_by")) || me.equals(Rows.str(s, "updated_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "the scenario's maker cannot approve it");
        }
        rows.execute("update liquidity_stress_scenario set approved_by = ?, approved_at = now(), updated_by = ? where id = ?",
                me, me, id);
        ObjectNode approved = rows.get(SCENARIO, id);
        events.publish("treasury.liquidity_stress_scenario.approved", SCENARIO, approved);
        return approved;
    }

    @Transactional
    public ObjectNode run(UUID id, JsonNode req) {
        ObjectNode s = rows.get(SCENARIO, id);
        if (!s.hasNonNull("approved_by")) {
            throw ApiException.unprocessable("scenario_not_approved", "the scenario must be approved before it is run");
        }
        if (!Rows.bool(s, "is_active")) {
            throw ApiException.unprocessable("scenario_inactive", "the scenario is not active");
        }
        UUID entity = Inputs.uuid(req, "legal_entity_id", "request");
        LocalDate asOf = Inputs.date(req, "as_of_date", "request");
        String ccy = Inputs.currency(req, "currency", "request", true);
        if (entity == null || asOf == null) {
            throw ApiException.unprocessable("missing_field", "legal_entity_id and as_of_date are required");
        }
        int horizon = s.get("horizon_days").asInt();
        StressEngine.Assumptions a = validate(s.get("assumptions"), horizon);
        List<StressEngine.Line> lines = new ArrayList<>();
        List<JsonNode> positions = Inputs.list(req, "positions");
        for (int i = 0; i < positions.size(); i++) {
            JsonNode p = positions.get(i);
            String ctx = "positions[" + i + "]";
            String line = Inputs.text(p, "line");
            Integer days = Inputs.integer(p, "days", ctx);
            if (days != null && days < 0) {
                throw ApiException.unprocessable("invalid_days", ctx + ": days must not be negative");
            }
            lines.add(new StressEngine.Line(line == null ? String.valueOf(i + 1) : line,
                    Inputs.requiredText(p, "code", ctx).toUpperCase(Locale.ROOT), Inputs.decimal(p, "amount", ctx, true), days));
        }
        StressEngine.Result r = Inputs.domain(() -> StressEngine.run(a, horizon, lines));
        ObjectNode v = rows.object();
        v.put("liquidity_stress_scenario_id", id.toString());
        v.put("legal_entity_id", entity.toString());
        v.put("as_of_date", asOf.toString());
        v.put("currency", ccy);
        v.put("stressed_outflows", r.stressedOutflows().toPlainString());
        v.put("stressed_inflows", r.stressedInflows().toPlainString());
        v.put("counterbalancing_capacity", r.counterbalancingCapacity().toPlainString());
        v.put("net_position", r.netPosition().toPlainString());
        v.put("survival_days", r.survivalDays());
        v.put("breach", r.breach());
        ArrayNode profile = v.putArray("daily_profile");
        for (StressEngine.Day d : r.profile()) {
            ObjectNode n = profile.addObject();
            n.put("day", d.day());
            n.put("outflows", d.outflows().toPlainString());
            n.put("inflows", d.inflows().toPlainString());
            n.put("cumulative_position", d.cumulativePosition().toPlainString());
        }
        ObjectNode result = rows.insert(RESULT, v);
        events.publish("treasury.liquidity_stress.completed", RESULT, result);
        if (r.breach()) {
            events.publish("treasury.liquidity_stress.breached", RESULT, result);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private StressEngine.Assumptions validate(JsonNode assumptions, int horizon) {
        if (assumptions == null || assumptions.isNull() || !assumptions.isObject()) {
            throw ApiException.unprocessable("invalid_assumptions", "assumptions must be an object");
        }
        Map<String, Object> raw = rows.json().convertValue(assumptions, Map.class);
        return Inputs.domain(() -> StressEngine.validate(raw, horizon));
    }
}
