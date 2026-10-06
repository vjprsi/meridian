package io.meridian.onboarding.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.onboarding.api.generated.model.OnboardingApplication;
import io.meridian.onboarding.domain.DecisionEngine;
import io.meridian.onboarding.domain.JourneyDefinition;
import io.meridian.onboarding.domain.JourneyDefinition.StepDef;
import io.meridian.onboarding.domain.JsonValues;
import io.meridian.onboarding.domain.Lifecycles;
import io.meridian.onboarding.infrastructure.Rows;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs applications through their journey version: step submission with the next-step engine, KYC checks via
 * kyc-service, submission with risk scoring and automated decision, maker-checker review, abandon and resume.
 */
@Service
public class ApplicationService {
    static final String T_APP = "onboarding_application";
    static final String T_STEP = "onboarding_step";
    static final String T_JOURNEY = "onboarding_journey";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final ServiceClient client;
    private final String resumeSecret;
    private final String resumeBaseUrl;

    public ApplicationService(Rows rows, CrudEngine crud, Events events, ServiceClient client,
                              @Value("${meridian.onboarding.resume-token-secret:dev-only-resume-secret}") String resumeSecret,
                              @Value("${meridian.onboarding.resume-base-url:https://onboarding.meridian.example/resume}")
                              String resumeBaseUrl) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.client = client;
        this.resumeSecret = resumeSecret;
        this.resumeBaseUrl = resumeBaseUrl;
    }

    /** Loaded application with its journey, step graph, step rows (by code) and collected data. */
    private record Ctx(ObjectNode app, ObjectNode journey, JourneyDefinition def, Map<String, ObjectNode> steps,
                       ObjectNode data) {
        String status(String code) {
            ObjectNode s = steps.get(code);
            return s == null ? "PENDING" : s.get("status").asString();
        }

        JourneyDefinition.Walk walk() {
            return def.walk(s -> Lifecycles.STEP_DONE.contains(status(s.code())), data);
        }

        /** Optional steps may be passed over. */
        JourneyDefinition.Walk lenientWalk() {
            return def.walk(s -> Lifecycles.STEP_DONE.contains(status(s.code())) || !s.required(), data);
        }
    }

    // ------------------------------------------------------------------ create / update

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T_APP, body);
        v.remove(List.of("status", "progress_percent", "decision", "risk_score", "risk_level"));
        return createFrom(v);
    }

    /**
     * Creates an application (also used by offline batch sync) and its step rows; publishes started. Must run inside
     * the caller's transaction; deliberately not @Transactional so a validation failure for one offline record does
     * not mark the whole batch transaction rollback-only.
     */
    public ObjectNode createFrom(ObjectNode v) {
        UUID journeyId = Events.uuid(v, "onboarding_journey_id");
        ObjectNode journey = rows.find(T_JOURNEY, journeyId)
                .orElseThrow(() -> ApiException.unprocessable("journey_not_found", "Journey " + journeyId + " does not exist"));
        if (!"PUBLISHED".equals(journey.get("status").asString())) {
            throw ApiException.unprocessable("journey_not_published", "Applications can only start on a PUBLISHED journey");
        }
        if (!JsonValues.values(journey.get("channels")).contains(v.path("channel").asString())) {
            throw ApiException.unprocessable("channel_not_allowed", "The journey does not allow this channel");
        }
        if (v.hasNonNull("product_ids")) {
            List<String> requested = JsonValues.values(v.get("product_ids"));
            List<String> offered = JsonValues.values(journey.get("product_ids"));
            if (!offered.isEmpty() && !offered.containsAll(requested)) {
                throw ApiException.unprocessable("product_not_offered", "A requested product is not offered by the journey");
            }
            v.set("product_ids", JsonValues.of(requested));
        }
        JourneyDefinition def = JourneyDefinition.of(journey.get("definition"));
        if (!v.hasNonNull("collected_data") || !v.get("collected_data").isObject()) {
            v.putObject("collected_data");
        }
        v.put("status", "STARTED");
        v.put("current_step", def.start());
        v.put("progress_percent", 0);
        v.put("expires_at", OffsetDateTime.now(ZoneOffset.UTC).plusDays(journey.path("expiry_days").asInt(30)).toString());
        ObjectNode app = rows.insert(T_APP, v);
        for (StepDef s : def.steps()) {
            ObjectNode step = JsonNodeFactory.instance.objectNode();
            step.put("onboarding_application_id", app.get("id").asString());
            step.put("step_code", s.code());
            step.put("step_type", s.type());
            step.put("sequence", s.index() + 1);
            step.put("status", "PENDING");
            rows.insert(T_STEP, step);
        }
        events.publish("party.onboarding_application.started", T_APP, app, OnboardingApplication.class);
        return app;
    }

    @Transactional
    public ObjectNode update(UUID id, Object body, String ifMatch) {
        int expected = Events.parseIfMatch(ifMatch);
        ObjectNode app = rows.lock(T_APP, id);
        Lifecycles.application(app.get("status").asString(), "update");
        ObjectNode v = crud.toColumns(T_APP, body);
        v.remove(List.of("status", "progress_percent", "current_step", "decision", "collected_data"));
        for (String f : List.of("application_number", "onboarding_journey_id", "channel")) {
            if (v.has(f) && !v.get(f).asString().equals(app.path(f).asString())) {
                throw ApiException.unprocessable("field_immutable", f + " cannot be changed after the application started");
            }
        }
        if (v.hasNonNull("product_ids")) {
            v.set("product_ids", JsonValues.of(JsonValues.values(v.get("product_ids"))));
        }
        return rows.update(T_APP, id, v, expected);
    }

    // ------------------------------------------------------------------ steps

    /** Marks an idle application EXPIRED (own transaction, so the change survives the caller's 409). */
    @Transactional
    public boolean expireIfDue(UUID id) {
        ObjectNode app = rows.lock(T_APP, id);
        OffsetDateTime exp = Events.ts(app, "expires_at");
        if (Lifecycles.APP_OPEN.contains(app.get("status").asString()) && exp != null
                && exp.isBefore(OffsetDateTime.now())) {
            ObjectNode v = JsonNodeFactory.instance.objectNode();
            v.put("status", "EXPIRED");
            ObjectNode updated = rows.update(T_APP, id, v);
            events.publish("party.onboarding_application.abandoned", T_APP, updated, OnboardingApplication.class);
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public ObjectNode nextStep(UUID id) {
        Ctx ctx = load(rows.get(T_APP, id));
        if (!Lifecycles.APP_OPEN.contains(ctx.app().get("status").asString())) {
            throw ApiException.conflict("application_not_open",
                    "Application is " + ctx.app().get("status").asString() + "; no step can be rendered");
        }
        String next = ctx.walk().next();
        if (next == null) {
            throw new ApiException(404, "no_next_step", "All steps are complete; submit the application");
        }
        return ctx.steps().get(next);
    }

    @Transactional
    public ObjectNode submitStep(UUID id, String code, JsonNode input) {
        Ctx ctx = load(rows.lock(T_APP, id));
        Lifecycles.application(ctx.app().get("status").asString(), "submit_step");
        StepDef sd = ctx.def().step(code).orElseThrow(() -> ApiException.notFound("onboarding step", code));
        List<String> reachable = ctx.lenientWalk().visited();
        if (!reachable.contains(code)) {
            throw ApiException.conflict("step_out_of_order", "Step " + code + " is not reachable yet; complete the next step first");
        }
        ObjectNode in = input != null && input.isObject() ? ((ObjectNode) input).deepCopy() : JsonNodeFactory.instance.objectNode();
        List<FieldError> missing = new ArrayList<>();
        sd.fields().stream().filter(f -> JsonValues.blank(in.get(f)))
                .forEach(f -> missing.add(new FieldError("input." + f, "required", f + " is required for this step")));
        if (!missing.isEmpty()) {
            throw new ApiException(422, "missing_fields", "Step " + code + " is missing required fields", missing);
        }
        ObjectNode stepRow = ctx.steps().get(code);
        int attempts = stepRow.path("attempts").asInt(0) + 1;
        if (attempts > sd.maxAttempts()) {
            throw ApiException.conflict("max_attempts_exceeded", "Step " + code + " allows " + sd.maxAttempts() + " attempts");
        }
        ctx.data().setAll(in);

        String status = "COMPLETED";
        String error = null;
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        if (sd.hasKycCheck()) {
            JsonNode check = runKycCheck(ctx, id, sd, in, attempts);
            String checkStatus = check.path("status").asString("PENDING");
            output.put("kyc_check_id", check.path("id").asString(null));
            output.put("kyc_check_status", checkStatus);
            switch (checkStatus) {
                case "PASSED" -> status = "COMPLETED";
                case "FAILED", "ERROR", "EXPIRED" -> {
                    status = "FAILED";
                    error = "kyc_check_" + checkStatus.toLowerCase();
                }
                default -> status = "WAITING_REVIEW";
            }
        }
        ObjectNode sv = JsonNodeFactory.instance.objectNode();
        sv.put("status", status);
        sv.set("input", in);
        sv.set("output", output);
        sv.put("attempts", attempts);
        if (!stepRow.hasNonNull("started_at")) {
            sv.put("started_at", Events.now());
        }
        sv.put("completed_at", Events.now());
        if (error == null) {
            sv.putNull("error");
        } else {
            sv.put("error", error);
        }
        ObjectNode updatedStep = rows.update(T_STEP, Events.uuid(stepRow, "id"), sv);
        ctx.steps().put(code, updatedStep);
        // Optional steps the applicant passed over on the way to this step are skipped.
        for (String passed : reachable.subList(0, reachable.indexOf(code))) {
            boolean optional = !ctx.def().step(passed).orElseThrow().required();
            if (optional && "PENDING".equals(ctx.status(passed))) {
                ObjectNode skip = JsonNodeFactory.instance.objectNode();
                skip.put("status", "SKIPPED");
                ctx.steps().put(passed, rows.update(T_STEP, Events.uuid(ctx.steps().get(passed), "id"), skip));
            }
        }

        ObjectNode av = progress(ctx);
        av.set("collected_data", ctx.data());
        av.put("status", "IN_PROGRESS");
        rows.update(T_APP, id, av);
        return updatedStep;
    }

    private JsonNode runKycCheck(Ctx ctx, UUID appId, StepDef sd, ObjectNode in, int attempts) {
        String profile = !JsonValues.blank(in.get("kyc_profile_id")) ? in.get("kyc_profile_id").asString()
                : ctx.data().path("kyc_profile_id").asString("");
        if (profile.isBlank()) {
            throw ApiException.unprocessable("kyc_profile_required", "kyc_profile_id is required for a step with a KYC check");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kyc_profile_id", profile);
        body.put("onboarding_application_id", appId.toString());
        body.put("check_type", sd.kycCheck().get("check_type").asString());
        body.put("kyc_provider_id", sd.kycCheck().get("kyc_provider_id").asString());
        if (!JsonValues.blank(in.get("kyc_document_id"))) {
            body.put("kyc_document_id", in.get("kyc_document_id").asString());
        }
        return client.post("kyc-service", "/v1/kyc/checks", body, "onb:" + appId + ":" + sd.code() + ":" + attempts);
    }

    // ------------------------------------------------------------------ decision

    @Transactional
    public ObjectNode submit(UUID id) {
        Ctx ctx = load(rows.lock(T_APP, id));
        Lifecycles.application(ctx.app().get("status").asString(), "submit");
        JourneyDefinition.Walk w = ctx.lenientWalk();
        if (w.next() != null) {
            throw new ApiException(422, "incomplete_steps", "Required steps are not complete",
                    List.of(new FieldError("steps." + w.next(), "incomplete", "step " + w.next() + " is not complete")));
        }
        int failed = 0;
        int waiting = 0;
        for (ObjectNode s : ctx.steps().values()) {
            String st = s.get("status").asString();
            String code = s.get("step_code").asString();
            if ("PENDING".equals(st) || "IN_PROGRESS".equals(st)) {
                ObjectNode sv = JsonNodeFactory.instance.objectNode();
                sv.put("status", "SKIPPED");
                rows.update(T_STEP, Events.uuid(s, "id"), sv);
            } else if (w.done().contains(code) && "FAILED".equals(st)) {
                failed++;
            } else if (w.done().contains(code) && "WAITING_REVIEW".equals(st)) {
                waiting++;
            }
        }
        String channel = ctx.app().get("channel").asString();
        DecisionEngine.Risk risk = DecisionEngine.assess(failed, waiting, ctx.data(), channel);
        ObjectNode facts = ctx.data().deepCopy();
        facts.put("risk_score", risk.score());
        facts.put("risk_level", risk.level());
        facts.put("failed_checks", failed);
        facts.put("inconclusive_checks", waiting);
        facts.put("channel", channel);
        DecisionEngine.Decision d = DecisionEngine.decide(ctx.journey().get("decision_rules"), facts, failed, waiting);

        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("status", "SUBMITTED");
        v.put("submitted_at", Events.now());
        v.put("submitted_by", TenantContext.principal());
        v.put("risk_score", risk.score());
        v.put("risk_level", risk.level());
        v.put("decision", d.decision());
        v.set("decision_reasons", JsonValues.of(d.reasons()));
        v.set("risk_factors", risk.factors());
        v.put("progress_percent", 100);
        v.putNull("current_step");
        ObjectNode app = rows.update(T_APP, id, v);
        events.publish("party.onboarding_application.submitted", T_APP, app, OnboardingApplication.class);

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        switch (d.decision()) {
            case "AUTO_APPROVE" -> {
                out.put("status", "APPROVED");
                out.put("completed_at", Events.now());
                ensureCustomer(ctx, app, out);
                app = rows.update(T_APP, id, out);
                events.publish("party.onboarding_application.approved", T_APP, app, OnboardingApplication.class);
            }
            case "REFER" -> {
                out.put("status", "REFERRED");
                app = rows.update(T_APP, id, out);
                events.publish("party.onboarding_application.referred", T_APP, app, OnboardingApplication.class);
            }
            default -> {
                out.put("status", "REJECTED");
                out.put("rejection_reason", String.join(", ", d.reasons()));
                out.put("completed_at", Events.now());
                app = rows.update(T_APP, id, out);
                events.publish("party.onboarding_application.rejected", T_APP, app, OnboardingApplication.class);
            }
        }
        return app;
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        Ctx ctx = load(rows.lock(T_APP, id));
        Lifecycles.application(ctx.app().get("status").asString(), "approve");
        checker(ctx.app());
        ObjectNode v = reviewed("APPROVED");
        if (comment != null) {
            v.put("review_comment", comment.length() > 500 ? comment.substring(0, 500) : comment);
        }
        v.put("completed_at", Events.now());
        ensureCustomer(ctx, ctx.app(), v);
        ObjectNode app = rows.update(T_APP, id, v);
        events.publish("party.onboarding_application.approved", T_APP, app, OnboardingApplication.class);
        return app;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode app = rows.lock(T_APP, id);
        String status = app.get("status").asString();
        Lifecycles.application(status, "reject");
        ObjectNode v;
        if (Lifecycles.APP_IN_REVIEW.contains(status)) {
            checker(app);
            v = reviewed("REJECTED");
        } else {
            v = JsonNodeFactory.instance.objectNode();
            v.put("status", "REJECTED");
        }
        v.put("rejection_reason", reason);
        v.put("completed_at", Events.now());
        ObjectNode updated = rows.update(T_APP, id, v);
        events.publish("party.onboarding_application.rejected", T_APP, updated, OnboardingApplication.class);
        return updated;
    }

    @Transactional
    public ObjectNode requestInfo(UUID id, String message, JsonNode stepCodes) {
        Ctx ctx = load(rows.lock(T_APP, id));
        Lifecycles.application(ctx.app().get("status").asString(), "request_info");
        checker(ctx.app());
        List<String> codes = JsonValues.values(stepCodes);
        List<FieldError> unknown = new ArrayList<>();
        codes.stream().filter(c -> !ctx.steps().containsKey(c))
                .forEach(c -> unknown.add(new FieldError("step_codes", "unknown_step", "step " + c + " is not part of the journey")));
        if (!unknown.isEmpty()) {
            throw new ApiException(422, "unknown_step", "Unknown step codes", unknown);
        }
        for (String c : codes) {
            ObjectNode sv = JsonNodeFactory.instance.objectNode();
            sv.put("status", "PENDING");
            sv.putNull("completed_at");
            sv.putNull("output");
            sv.putNull("error");
            ctx.steps().put(c, rows.update(T_STEP, Events.uuid(ctx.steps().get(c), "id"), sv));
        }
        JsonNode previous = ctx.app().get("info_requests");
        ArrayNode requests = previous != null && previous.isArray() ? (ArrayNode) previous.deepCopy()
                : JsonNodeFactory.instance.arrayNode();
        ObjectNode r = requests.addObject();
        r.put("message", message);
        r.set("step_codes", JsonValues.of(codes));
        r.put("requested_by", TenantContext.principal());
        r.put("requested_at", Events.now());
        ObjectNode v = progress(ctx);
        v.put("status", "IN_PROGRESS");
        v.set("info_requests", requests);
        ObjectNode updated = rows.update(T_APP, id, v);
        events.publish("party.onboarding_application.info_requested", T_APP, updated, OnboardingApplication.class);
        return updated;
    }

    @Transactional
    public ObjectNode abandon(UUID id) {
        ObjectNode app = rows.lock(T_APP, id);
        Lifecycles.application(app.get("status").asString(), "abandon");
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("status", "ABANDONED");
        ObjectNode updated = rows.update(T_APP, id, v);
        events.publish("party.onboarding_application.abandoned", T_APP, updated, OnboardingApplication.class);
        return updated;
    }

    /** Re-opens an abandoned application, extends its expiry and sends a signed resume link to the applicant. */
    @Transactional
    public ObjectNode resume(UUID id, String requestKey) {
        ObjectNode app = rows.lock(T_APP, id);
        String status = app.get("status").asString();
        Lifecycles.application(status, "resume");
        OffsetDateTime exp = Events.ts(app, "expires_at");
        if (exp != null && exp.isBefore(OffsetDateTime.now())) {
            throw ApiException.conflict("application_expired", "The application has expired; start a new one");
        }
        String channel;
        String destination;
        if (app.hasNonNull("phone")) {
            channel = "SMS";
            destination = app.get("phone").asString();
        } else if (app.hasNonNull("email")) {
            channel = "EMAIL";
            destination = app.get("email").asString();
        } else {
            throw ApiException.unprocessable("no_contact_channel", "The application has no phone or email to send the link to");
        }
        ObjectNode journey = rows.get(T_JOURNEY, Events.uuid(app, "onboarding_journey_id"));
        OffsetDateTime newExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusDays(journey.path("expiry_days").asInt(30));
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("expires_at", newExpiry.toString());
        if ("ABANDONED".equals(status)) {
            v.put("status", "IN_PROGRESS");
        }
        ObjectNode updated = rows.update(T_APP, id, v);

        String token = resumeToken(id, newExpiry);
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("recipient_type", app.hasNonNull("customer_id") ? "CUSTOMER" : "EXTERNAL");
        if (app.hasNonNull("customer_id")) {
            notification.put("recipient_id", app.get("customer_id").asString());
        }
        notification.put("channel", channel);
        notification.put("destination", destination);
        notification.put("category", "SERVICE");
        notification.put("priority", "NORMAL");
        notification.put("subject", "Continue your application");
        notification.put("body", "Continue your application " + app.get("application_number").asString() + ": "
                + resumeBaseUrl + "?token=" + token);
        notification.put("expires_at", newExpiry.toString());
        client.post("notification-service", "/v1/notifications", notification,
                "onb-resume:" + id + ":" + sha256(requestKey).substring(0, 16));

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("application_id", id.toString());
        out.put("status", updated.get("status").asString());
        out.put("channel", channel);
        out.put("expires_at", newExpiry.toString());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Ctx load(ObjectNode app) {
        ObjectNode journey = rows.get(T_JOURNEY, Events.uuid(app, "onboarding_journey_id"));
        JourneyDefinition def = JourneyDefinition.of(journey.get("definition"));
        Map<String, ObjectNode> steps = new LinkedHashMap<>();
        rows.query("select row_to_json(s)::text from onboarding_step s where onboarding_application_id = ? order by sequence",
                Events.uuid(app, "id")).forEach(s -> steps.put(s.get("step_code").asString(), s));
        ObjectNode data = app.hasNonNull("collected_data") && app.get("collected_data").isObject()
                ? (ObjectNode) app.get("collected_data").deepCopy() : JsonNodeFactory.instance.objectNode();
        return new Ctx(app, journey, def, steps, data);
    }

    private static ObjectNode progress(Ctx ctx) {
        JourneyDefinition.Walk w = ctx.walk();
        int done = w.done().size();
        int remaining = w.next() == null ? 0 : ctx.def().remainingFrom(w.next(), ctx.data());
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("progress_percent", done + remaining == 0 ? 100 : (int) Math.floor(100.0 * done / (done + remaining)));
        if (w.next() == null) {
            v.putNull("current_step");
        } else {
            v.put("current_step", w.next());
        }
        return v;
    }

    /** Checker must be neither the creator nor the submitter of the application. */
    private static void checker(ObjectNode app) {
        Events.fourEyes(app.path("created_by").asString(null), app.path("submitted_by").asString(null));
    }

    private static ObjectNode reviewed(String status) {
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("status", status);
        v.put("reviewed_at", Events.now());
        v.put("reviewed_by", TenantContext.principal());
        UUID reviewer = Events.principalUuid();
        if (reviewer != null) {
            v.put("reviewer_id", reviewer.toString());
        }
        return v;
    }

    /** Creates the customer in customer-service on approval when the application did not match an existing one. */
    private void ensureCustomer(Ctx ctx, ObjectNode app, ObjectNode values) {
        if (app.hasNonNull("customer_id")) {
            return;
        }
        ObjectNode d = ctx.data();
        String first = d.path("first_name").asString("");
        String last = d.path("last_name").asString("");
        if (JsonValues.blank(app.get("legal_entity_id"))) {
            throw ApiException.unprocessable("legal_entity_required",
                    "legal_entity_id is required on the application to open the customer relationship");
        }
        // customer_number is assigned by customer-service; display_name is derived there when not captured.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_type", ctx.journey().get("customer_type").asString());
        body.put("legal_entity_id", app.get("legal_entity_id").asString());
        body.put("external_ref", app.get("application_number").asString());
        for (String f : List.of("display_name", "legal_name", "trading_name", "registration_number", "nationality")) {
            if (!JsonValues.blank(d.get(f))) {
                body.put(f, d.get(f).asString());
            }
        }
        if (!first.isBlank()) {
            body.put("first_name", first);
        }
        if (!last.isBlank()) {
            body.put("last_name", last);
        }
        if (!JsonValues.blank(d.get("date_of_birth"))) {
            body.put("date_of_birth", d.get("date_of_birth").asString());
        }
        body.put("onboarding_channel", app.get("channel").asString());
        JsonNode customer = client.post("customer-service", "/v1/customers", body, "onb-cust:" + app.get("id").asString());
        if (!JsonValues.blank(customer.get("id"))) {
            values.put("customer_id", customer.get("id").asString());
        }
    }

    private String resumeToken(UUID id, OffsetDateTime expiry) {
        String payload = id + ":" + expiry.toEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(resumeSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sig;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
