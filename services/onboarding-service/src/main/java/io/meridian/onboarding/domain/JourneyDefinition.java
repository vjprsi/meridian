package io.meridian.onboarding.domain;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A journey version's step graph. Definition shape:
 * <pre>{"steps": [{"code": "phone", "type": "PHONE_VERIFICATION", "required": true,
 *   "fields": ["phone" | {"name": "phone", "type": "PHONE", "label_key": "...", "required": true, ...}],
 *   "next": "details" | {"rules": [{"when": {cond}, "goto": "code"}], "default": "code"},
 *   "kyc_check": {"check_type": "...", "kyc_provider_id": "..."}, "max_attempts": 3}]}</pre>
 * The first step is the start; a step without {@code next} continues with the following step in the list;
 * {@code "$end"} ends the journey explicitly.
 */
public final class JourneyDefinition {
    public static final String END = "$end";
    public static final Set<String> STEP_TYPES = Set.of("PHONE_VERIFICATION", "EMAIL_VERIFICATION", "PERSONAL_DETAILS",
            "BUSINESS_DETAILS", "ADDRESS", "DOCUMENT_CAPTURE", "SELFIE_LIVENESS", "SCREENING", "RISK_ASSESSMENT",
            "UBO_DECLARATION", "TAX_DECLARATION", "PRODUCT_SELECTION", "TERMS_ACCEPTANCE", "SIGNATURE",
            "CREDENTIAL_SETUP", "MANUAL_REVIEW", "CUSTOM");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    public record StepDef(String code, String type, boolean required, List<StepField> fields, JsonNode next,
                          JsonNode kycCheck, int maxAttempts, int index) {
        public boolean hasKycCheck() {
            return kycCheck != null && kycCheck.isObject();
        }
    }

    /** Result of walking the graph: steps passed through (done) and the first step not yet done. */
    public record Walk(List<String> done, String next, List<String> visited) {}

    private final List<StepDef> steps;
    private final Map<String, StepDef> byCode;

    private JourneyDefinition(List<StepDef> steps) {
        this.steps = steps;
        this.byCode = new LinkedHashMap<>();
        steps.forEach(s -> byCode.put(s.code(), s));
    }

    /** Validates and parses; throws 422 invalid_definition with field errors. */
    public static JourneyDefinition of(JsonNode def) {
        List<FieldError> errors = validate(def);
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_definition", "Journey definition is invalid", errors);
        }
        return parse(def);
    }

    private static JourneyDefinition parse(JsonNode def) {
        List<StepDef> list = new ArrayList<>();
        int i = 0;
        for (JsonNode s : def.path("steps")) {
            List<StepField> fields = new ArrayList<>();
            s.path("fields").forEach(f -> fields.add(StepField.parse(f)));
            list.add(new StepDef(s.path("code").asString(), s.path("type").asString(),
                    !s.has("required") || s.get("required").asBoolean(true), fields, s.get("next"),
                    s.get("kyc_check"), s.path("max_attempts").asInt(3), i++));
        }
        return new JourneyDefinition(list);
    }

    public static List<FieldError> validate(JsonNode def) {
        List<FieldError> errors = new ArrayList<>();
        if (def == null || !def.isObject() || !def.path("steps").isArray() || def.path("steps").isEmpty()) {
            errors.add(new FieldError("definition.steps", "required", "definition.steps must be a non-empty array"));
            return errors;
        }
        Set<String> codes = new HashSet<>();
        int i = 0;
        for (JsonNode s : def.get("steps")) {
            String p = "definition.steps[" + i++ + "]";
            String code = s.path("code").asString("");
            if (!CODE.matcher(code).matches() || END.equals(code)) {
                errors.add(new FieldError(p + ".code", "invalid_code", "step code must match [A-Za-z0-9_-]{1,64}"));
            } else if (!codes.add(code)) {
                errors.add(new FieldError(p + ".code", "duplicate_code", "step code " + code + " is used twice"));
            }
            if (!STEP_TYPES.contains(s.path("type").asString(""))) {
                errors.add(new FieldError(p + ".type", "invalid_type", "type must be an OnboardingStepType"));
            }
            if (s.has("fields") && !s.get("fields").isArray()) {
                errors.add(new FieldError(p + ".fields", "invalid_fields", "fields must be an array of names or field definitions"));
            } else if (s.has("fields")) {
                int fi = 0;
                for (JsonNode f : s.get("fields")) {
                    StepField.validateDefinition(f, p + ".fields[" + fi++ + "]", errors);
                }
            }
            if (s.has("max_attempts") && (s.get("max_attempts").asInt(0) < 1 || s.get("max_attempts").asInt(0) > 10)) {
                errors.add(new FieldError(p + ".max_attempts", "out_of_range", "max_attempts must be 1..10"));
            }
            JsonNode kyc = s.get("kyc_check");
            if (kyc != null && !kyc.isNull()) {
                if (!kyc.isObject() || JsonValues.blank(kyc.get("check_type")) || JsonValues.blank(kyc.get("kyc_provider_id"))) {
                    errors.add(new FieldError(p + ".kyc_check", "invalid_kyc_check",
                            "kyc_check needs check_type and kyc_provider_id"));
                }
            }
            JsonNode next = s.get("next");
            if (next != null && !next.isNull() && !next.isString()) {
                if (!next.isObject()) {
                    errors.add(new FieldError(p + ".next", "invalid_next", "next must be a step code or a rules object"));
                } else {
                    int r = 0;
                    for (JsonNode rule : next.path("rules")) {
                        Conditions.validate(rule.get("when"), p + ".next.rules[" + r + "].when", errors);
                        if (JsonValues.blank(rule.get("goto"))) {
                            errors.add(new FieldError(p + ".next.rules[" + r + "].goto", "required", "goto is required"));
                        }
                        r++;
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        JourneyDefinition d = parse(def);
        for (StepDef s : d.steps) {
            for (String t : d.declaredTargets(s)) {
                if (!END.equals(t) && !d.byCode.containsKey(t)) {
                    errors.add(new FieldError("definition.steps[" + s.index() + "].next", "unknown_step",
                            "transition target " + t + " does not exist"));
                }
            }
        }
        if (errors.isEmpty() && d.hasCycle()) {
            errors.add(new FieldError("definition.steps", "cycle", "the step graph must not contain cycles"));
        }
        return errors;
    }

    public List<StepDef> steps() {
        return steps;
    }

    public String start() {
        return steps.getFirst().code();
    }

    public Optional<StepDef> step(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** The step after {@code code} given the collected data, or null at the end. */
    public String next(String code, JsonNode data) {
        StepDef s = byCode.get(code);
        JsonNode n = s.next();
        String target;
        if (n == null || n.isNull()) {
            target = fallthrough(s);
        } else if (n.isString()) {
            target = n.asString();
        } else {
            target = null;
            for (JsonNode rule : n.path("rules")) {
                if (Conditions.eval(rule.get("when"), data)) {
                    target = rule.path("goto").asString();
                    break;
                }
            }
            if (target == null) {
                target = n.hasNonNull("default") ? n.get("default").asString() : fallthrough(s);
            }
        }
        return target == null || END.equals(target) ? null : target;
    }

    /** Walks from the start, passing steps for which {@code done} holds, up to the first that is not done. */
    public Walk walk(Predicate<StepDef> done, JsonNode data) {
        List<String> passed = new ArrayList<>();
        List<String> visited = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = start();
        while (cur != null && seen.add(cur)) {
            visited.add(cur);
            if (!done.test(byCode.get(cur))) {
                return new Walk(passed, cur, visited);
            }
            passed.add(cur);
            cur = next(cur, data);
        }
        return new Walk(passed, null, visited);
    }

    /** Number of steps from {@code code} (inclusive) to the end along the current transitions. */
    public int remainingFrom(String code, JsonNode data) {
        int n = 0;
        Set<String> seen = new HashSet<>();
        for (String cur = code; cur != null && seen.add(cur); cur = next(cur, data)) {
            n++;
        }
        return n;
    }

    private String fallthrough(StepDef s) {
        return s.index() + 1 < steps.size() ? steps.get(s.index() + 1).code() : null;
    }

    private Set<String> declaredTargets(StepDef s) {
        Set<String> t = new LinkedHashSet<>();
        JsonNode n = s.next();
        if (n == null || n.isNull()) {
            return t;
        }
        if (n.isString()) {
            t.add(n.asString());
        } else {
            n.path("rules").forEach(r -> t.add(r.path("goto").asString()));
            if (n.hasNonNull("default")) {
                t.add(n.get("default").asString());
            }
        }
        return t;
    }

    private Set<String> successors(StepDef s) {
        Set<String> t = new LinkedHashSet<>(declaredTargets(s));
        JsonNode n = s.next();
        if (n == null || n.isNull() || (n.isObject() && !n.hasNonNull("default"))) {
            String f = fallthrough(s);
            if (f != null) {
                t.add(f);
            }
        }
        t.remove(END);
        return t;
    }

    private boolean hasCycle() {
        Map<String, Integer> state = new HashMap<>();
        for (StepDef s : steps) {
            if (dfs(s.code(), state)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfs(String code, Map<String, Integer> state) {
        Integer st = state.get(code);
        if (st != null) {
            return st == 1;
        }
        state.put(code, 1);
        for (String nxt : successors(byCode.get(code))) {
            if (dfs(nxt, state)) {
                return true;
            }
        }
        state.put(code, 2);
        return false;
    }
}
