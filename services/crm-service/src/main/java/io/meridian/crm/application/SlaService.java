package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.crm.domain.BusinessCalendar;
import io.meridian.crm.infrastructure.ExternalServices;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** SLA policies: validation, matching to cases and complaints, and the business-hours calendar they define. */
@Service
public class SlaService {
    static final String T = "sla_policy";
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

    private final Rows rows;
    private final CrudEngine crud;
    private final ExternalServices external;

    public SlaService(Rows rows, CrudEngine crud, ExternalServices external) {
        this.rows = rows;
        this.crud = crud;
        this.external = external;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        ObjectNode merged = defaults();
        merged.setAll(v);
        validate(merged);
        normalise(v);
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        ObjectNode v = crud.toColumns(T, body);
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        normalise(v);
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    /** Policies referenced by cases or complaints are deactivated, not deleted (audit of the deadlines applied). */
    @Transactional
    public void delete(UUID id) {
        rows.lock(T, id);
        int used = rows.count("select count(*) from service_case where sla_policy_id = ?", id)
                + rows.count("select count(*) from complaint where sla_policy_id = ?", id);
        if (used > 0) {
            throw ApiException.conflict("policy_in_use", "The policy was applied to " + used
                    + " cases or complaints; deactivate it (is_active=false) instead");
        }
        rows.execute("delete from sla_policy where id = ?", id);
    }

    private ObjectNode defaults() {
        ObjectNode d = rows.object();
        d.put("business_hours_only", true);
        d.put("business_day_start", "09:00");
        d.put("business_day_end", "17:00");
        d.put("time_zone", "UTC");
        d.put("at_risk_percent", 80);
        return d;
    }

    private void validate(ObjectNode p) {
        List<FieldError> errors = new ArrayList<>();
        String appliesTo = text(p, "applies_to");
        if ("CASE".equals(appliesTo) && text(p, "complaint_category") != null) {
            errors.add(new FieldError("complaint_category", "not_applicable", "A CASE policy cannot target a complaint category"));
        }
        if ("COMPLAINT".equals(appliesTo) && text(p, "case_type") != null) {
            errors.add(new FieldError("case_type", "not_applicable", "A COMPLAINT policy cannot target a case type"));
        }
        int first = Json.integer(p, "first_response_minutes", 0);
        int resolution = Json.integer(p, "resolution_minutes", 0);
        if (first <= 0) {
            errors.add(new FieldError("first_response_minutes", "must_be_positive", "first_response_minutes must be > 0"));
        }
        if (resolution <= 0 || resolution < first) {
            errors.add(new FieldError("resolution_minutes", "invalid_target",
                    "resolution_minutes must be > 0 and not shorter than first_response_minutes"));
        }
        String start = text(p, "business_day_start");
        String end = text(p, "business_day_end");
        if (start == null || !HHMM.matcher(start).matches()) {
            errors.add(new FieldError("business_day_start", "invalid_time", "business_day_start must be HH:mm"));
        }
        if (end == null || !HHMM.matcher(end).matches()) {
            errors.add(new FieldError("business_day_end", "invalid_time", "business_day_end must be HH:mm"));
        }
        if (start != null && end != null && HHMM.matcher(start).matches() && HHMM.matcher(end).matches()
                && end.compareTo(start) <= 0) {
            errors.add(new FieldError("business_day_end", "invalid_window", "business_day_end must be after business_day_start"));
        }
        JsonNode days = p.get("working_days");
        if (days != null && !days.isNull()) {
            Set<Integer> seen = new HashSet<>();
            for (JsonNode d : days) {
                if (!d.isIntegralNumber() || d.asInt() < 1 || d.asInt() > 7 || !seen.add(d.asInt())) {
                    errors.add(new FieldError("working_days", "invalid_day", "working_days are distinct ISO days 1..7"));
                    break;
                }
            }
            if (days.isEmpty()) {
                errors.add(new FieldError("working_days", "empty", "working_days needs at least one day (omit for Mon-Fri)"));
            }
        }
        try {
            ZoneId.of(text(p, "time_zone") == null ? "UTC" : text(p, "time_zone"));
        } catch (DateTimeException e) {
            errors.add(new FieldError("time_zone", "invalid_time_zone", "time_zone must be an IANA zone id"));
        }
        int risk = Json.integer(p, "at_risk_percent", 80);
        if (risk < 1 || risk > 99) {
            errors.add(new FieldError("at_risk_percent", "out_of_range", "at_risk_percent must be 1..99"));
        }
        JsonNode rules = p.get("escalation_rules");
        if (rules != null && rules.isArray()) {
            int i = 0;
            for (JsonNode r : rules) {
                String f = "escalation_rules[" + i++ + "]";
                if (!r.isObject() || !r.path("at_percent").isIntegralNumber() || r.get("at_percent").asInt() < 1) {
                    errors.add(new FieldError(f, "invalid_rule", "each rule needs at_percent >= 1"));
                    continue;
                }
                if (r.hasNonNull("priority") && !PRIORITIES.contains(r.get("priority").asString())) {
                    errors.add(new FieldError(f + ".priority", "invalid_priority", "priority must be a ServiceCasePriority"));
                }
                if (r.hasNonNull("work_queue_id")) {
                    try {
                        UUID q = UUID.fromString(r.get("work_queue_id").asString());
                        if (rows.find("work_queue", q).isEmpty()) {
                            errors.add(new FieldError(f + ".work_queue_id", "not_found", "work queue does not exist"));
                        }
                    } catch (IllegalArgumentException e) {
                        errors.add(new FieldError(f + ".work_queue_id", "invalid_uuid", "work_queue_id must be a uuid"));
                    }
                }
            }
        }
        if (Json.bool(p, "is_regulatory") && text(p, "regulation_reference") == null) {
            errors.add(new FieldError("regulation_reference", "required", "A regulatory policy needs its rule reference"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_sla_policy", "SLA policy is invalid", errors);
        }
    }

    private static void normalise(ObjectNode v) {
        if (v.has("escalation_rules") && v.get("escalation_rules").isArray()) {
            List<JsonNode> sorted = new ArrayList<>();
            v.get("escalation_rules").forEach(sorted::add);
            sorted.sort((a, b) -> Integer.compare(a.path("at_percent").asInt(), b.path("at_percent").asInt()));
            tools.jackson.databind.node.ArrayNode arr = v.putArray("escalation_rules");
            sorted.forEach(arr::add);
        }
    }

    // ------------------------------------------------------------------ matching and clocks

    /** The most specific active policy (each matching dimension counts; newer wins ties). */
    public Optional<ObjectNode> match(String appliesTo, String caseType, String category, String priority, String country) {
        return rows.query("""
                select row_to_json(t)::text from sla_policy t
                where t.is_active and t.applies_to = ?
                  and (t.case_type is null or t.case_type = ?)
                  and (t.complaint_category is null or t.complaint_category = ?)
                  and (t.priority is null or t.priority = ?)
                  and (t.country is null or t.country = ?)
                order by (case when t.case_type is null then 0 else 1 end)
                       + (case when t.complaint_category is null then 0 else 1 end)
                       + (case when t.priority is null then 0 else 1 end)
                       + (case when t.country is null then 0 else 1 end) desc,
                         t.is_regulatory desc, t.id desc
                limit 1""", appliesTo, caseType, category, priority, country).stream().findFirst();
    }

    public Optional<ObjectNode> policy(UUID id) {
        return id == null ? Optional.empty() : rows.find(T, id);
    }

    /** The calendar of a policy (holidays from its calendar, fail-open). */
    public BusinessCalendar calendar(ObjectNode policy) {
        if (policy == null) {
            return BusinessCalendar.ALWAYS;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Json.strings(policy.get("working_days")).forEach(d -> days.add(DayOfWeek.of(Integer.parseInt(d))));
        if (days.isEmpty()) {
            days.addAll(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        }
        try {
            return new BusinessCalendar(Json.bool(policy, "business_hours_only"),
                    LocalTime.parse(text(policy, "business_day_start")), LocalTime.parse(text(policy, "business_day_end")),
                    days, ZoneId.of(text(policy, "time_zone")), external.holidays(Json.uuid(policy, "holiday_calendar_id")));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_sla_policy", "SLA policy " + text(policy, "id") + " has an invalid calendar");
        }
    }

    public record Targets(OffsetDateTime firstResponseDue, OffsetDateTime resolutionDue) {}

    public Targets targets(ObjectNode policy, OffsetDateTime from) {
        BusinessCalendar cal = calendar(policy);
        return new Targets(cal.addMinutes(from, policy.get("first_response_minutes").asLong()),
                cal.addMinutes(from, policy.get("resolution_minutes").asLong()));
    }
}
