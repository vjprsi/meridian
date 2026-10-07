package io.meridian.onboarding.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.time.Clocks;
import io.meridian.onboarding.domain.JourneyDefinition.StepDef;
import io.meridian.onboarding.domain.JsonValues;
import io.meridian.onboarding.domain.Lifecycles;
import io.meridian.onboarding.domain.StepField;
import io.meridian.onboarding.infrastructure.Rows;
import io.meridian.web.client.ServiceClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Public applicant surface: unauthenticated prospects start an application on a published journey and then act with
 * a short-lived opaque applicant token (only its SHA-256 is stored) bound to that one application. Contact steps are
 * verified with one-time codes (only HMACs are stored; expiry, attempt limit, resend cooldown and send limit);
 * documents are registered with document-service on the applicant's behalf. Tenant binding is the caller's job
 * ({@code ?tenant_id}); everything here runs under row-level security for that tenant.
 */
@Service
public class ApplicantService {
    static final String T_APP = "onboarding_application";
    static final String T_TOKEN = "onboarding_applicant_token";
    static final String T_OTP = "onboarding_otp";
    static final String DOCUMENT_OWNER = "ONBOARDING_APPLICATION";
    private static final Set<String> PUBLIC_CHANNELS = Set.of("MOBILE_APP", "WEB");
    /** Values only the bank (staff, KYC integration) may set: never accepted from the applicant. */
    static final Set<String> SERVER_MANAGED = Set.of("kyc_profile_id", "kyc_document_id", "customer_id");
    private static final Set<String> CONTACT_STEPS = Set.of("PHONE_VERIFICATION", "EMAIL_VERIFICATION");
    private static final Set<String> DOCUMENT_STEPS = Set.of("DOCUMENT_CAPTURE", "SELFIE_LIVENESS");
    private static final Set<String> DOCUMENT_TYPES = Set.of("ID_CARD", "PASSPORT", "DRIVING_LICENSE", "RESIDENCE_PERMIT",
            "PROOF_OF_ADDRESS", "SELFIE", "VIDEO", "BANK_STATEMENT", "OTHER");
    private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/heic", "application/pdf",
            "video/mp4");
    /** Response fields an applicant never sees (risk, decision internals, reviewers, capture metadata). */
    private static final List<String> HIDDEN = List.of("risk_score", "risk_level", "decision", "decision_reasons",
            "risk_factors", "reviewer_id", "reviewed_by", "review_comment", "submitted_by", "created_by", "updated_by",
            "ip_address", "device_fingerprint", "geo_location", "staff_user_id", "agent_id", "branch_id", "utm");
    private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Rows rows;
    private final ApplicationService apps;
    private final ServiceClient client;
    private final Settings settings;

    /** Public surface limits ({@code meridian.onboarding.public.*}). */
    public record Settings(int tokenTtlMinutes, int startsPerIpPerHour, int startsPerDevicePerHour,
                           boolean attestationRequired, int otpTtlSeconds, int otpMaxAttempts, int otpMaxSends,
                           int otpResendSeconds, String otpSecret, int maxDocuments,
                           int otpSendsPerDestinationPerHour, int otpSendsPerHour) {}

    public ApplicantService(Rows rows, ApplicationService apps, ServiceClient client,
                            @Value("${meridian.onboarding.public.token-ttl-minutes:30}") int tokenTtlMinutes,
                            @Value("${meridian.onboarding.public.starts-per-ip-per-hour:20}") int startsPerIp,
                            @Value("${meridian.onboarding.public.starts-per-device-per-hour:5}") int startsPerDevice,
                            @Value("${meridian.onboarding.public.attestation-required:false}") boolean attestationRequired,
                            @Value("${meridian.onboarding.public.otp-ttl-seconds:300}") int otpTtl,
                            @Value("${meridian.onboarding.public.otp-max-attempts:5}") int otpMaxAttempts,
                            @Value("${meridian.onboarding.public.otp-max-sends:5}") int otpMaxSends,
                            @Value("${meridian.onboarding.public.otp-resend-seconds:30}") int otpResend,
                            @Value("${meridian.onboarding.public.otp-secret:dev-only-otp-secret}") String otpSecret,
                            @Value("${meridian.onboarding.public.max-documents:20}") int maxDocuments,
                            @Value("${meridian.onboarding.public.otp-sends-per-destination-per-hour:5}") int otpPerDestination,
                            @Value("${meridian.onboarding.public.otp-sends-per-hour:500}") int otpPerHour) {
        this.rows = rows;
        this.apps = apps;
        this.client = client;
        this.settings = new Settings(tokenTtlMinutes, startsPerIp, startsPerDevice, attestationRequired, otpTtl,
                otpMaxAttempts, otpMaxSends, otpResend, otpSecret, maxDocuments, otpPerDestination, otpPerHour);
    }

    // ------------------------------------------------------------------ start / tokens

    /** Starts an application for an anonymous prospect and issues its first applicant token. */
    @Transactional
    public ObjectNode start(JsonNode req, String clientIp) {
        String channel = req.path("channel").asString("");
        if (!PUBLIC_CHANNELS.contains(channel)) {
            throw ApiException.unprocessable("channel_not_allowed", "Self-service applications use MOBILE_APP or WEB");
        }
        String device = req.path("device_fingerprint").asString("").trim();
        if (device.isEmpty()) {
            throw new ApiException(422, "invalid_fields", "device_fingerprint is required",
                    List.of(new FieldError("device_fingerprint", "required", "device_fingerprint is required")));
        }
        if (settings.attestationRequired() && JsonValues.blank(req.get("attestation_token"))) {
            // Placeholder: a CAPTCHA / Play Integrity / App Attest verdict check plugs in here.
            throw ApiException.unprocessable("attestation_required", "A CAPTCHA or app attestation token is required");
        }
        String ip = IP.matcher(clientIp == null ? "" : clientIp).matches() ? clientIp : null;
        rateLimit(ip, device);
        ObjectNode journey = rows.query("""
                select row_to_json(j)::text from onboarding_journey j where code = ? and status = 'PUBLISHED'
                order by version desc limit 1""", req.path("journey_code").asString("")).stream().findFirst()
                .orElseThrow(() -> ApiException.unprocessable("journey_not_found", "No published journey with this code"));
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("onboarding_journey_id", journey.get("id").asString());
        v.put("channel", channel);
        v.put("device_fingerprint", device.length() > 128 ? device.substring(0, 128) : device);
        if (ip != null) {
            v.put("ip_address", ip);
        }
        if (req.hasNonNull("utm") && req.get("utm").isObject()) {
            v.set("utm", req.get("utm"));
        }
        if (req.hasNonNull("product_ids")) {
            v.set("product_ids", req.get("product_ids"));
        }
        ObjectNode app = apps.createFrom(v);
        return issue(app, ip, device);
    }

    private void rateLimit(String ip, String device) {
        // Serialise starts per client so concurrent requests cannot overrun the window.
        rows.jdbc().sql("select 1 from pg_advisory_xact_lock(hashtext(?))").param("onb-start:" + ip + ":" + device)
                .query(Integer.class).single();
        if (ip != null) {
            int byIp = rows.jdbc().sql("""
                    select count(*) from onboarding_application
                    where ip_address = ?::inet and created_at > now() - interval '1 hour'""")
                    .param(ip).query(Integer.class).single();
            if (byIp >= settings.startsPerIpPerHour()) {
                throw new ApiException(429, "rate_limited", "Too many applications from this network; try again later");
            }
        }
        int byDevice = rows.jdbc().sql("""
                select count(*) from onboarding_application
                where device_fingerprint = ? and created_at > now() - interval '1 hour'""")
                .param(device).query(Integer.class).single();
        if (byDevice >= settings.startsPerDevicePerHour()) {
            throw new ApiException(429, "rate_limited", "Too many applications from this device; try again later");
        }
    }

    /**
     * Checks the applicant token: 401 when absent, unknown, revoked or expired; 404 when it belongs to another
     * application (the caller learns nothing about it). Records the use.
     */
    @Transactional
    public void authenticate(UUID applicationId, String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(401, "applicant_token_required", "Send the applicant token in X-Applicant-Token");
        }
        ObjectNode t = rows.query("select row_to_json(t)::text from onboarding_applicant_token t where token_hash = ?",
                        sha256(token.trim())).stream().findFirst()
                .orElseThrow(() -> new ApiException(401, "applicant_token_invalid", "The applicant token is not valid"));
        if (t.hasNonNull("revoked_at")) {
            throw new ApiException(401, "applicant_token_invalid", "The applicant token is no longer valid");
        }
        if (!applicationId.toString().equals(t.path("onboarding_application_id").asString())) {
            throw ApiException.notFound("onboarding application", applicationId);
        }
        if (Events.ts(t, "expires_at").isBefore(Clocks.now())) {
            throw new ApiException(401, "applicant_token_expired", "The applicant token has expired");
        }
        ObjectNode used = JsonNodeFactory.instance.objectNode();
        used.put("last_used_at", Clocks.now().toString());
        rows.update(T_TOKEN, Events.uuid(t, "id"), used);
    }

    /** Rotates the presented token: it is revoked and a new one is issued for the same application. */
    @Transactional
    public ObjectNode refresh(UUID applicationId, String token) {
        authenticate(applicationId, token);
        revoke(applicationId, sha256(token.trim()), "ROTATED");
        ObjectNode app = rows.get(T_APP, applicationId);
        if (!Lifecycles.APP_OPEN.contains(app.path("status").asString())) {
            throw ApiException.conflict("application_not_open", "The application is " + app.path("status").asString());
        }
        return issue(app, null, null);
    }

    private ObjectNode issue(ObjectNode app, String ip, String device) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "oat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("onboarding_application_id", app.get("id").asString());
        v.put("token_hash", sha256(token));
        v.put("expires_at", Clocks.now().plusMinutes(settings.tokenTtlMinutes()).toString());
        if (ip != null) {
            v.put("issued_ip", ip);
        }
        if (device != null) {
            v.put("device_fingerprint", device.length() > 128 ? device.substring(0, 128) : device);
        }
        ObjectNode row = rows.insert(T_TOKEN, v);
        row.remove("token_hash");
        row.put("applicant_token", token);
        row.put("application_number", app.path("application_number").asString());
        if (app.hasNonNull("current_step")) {
            row.put("current_step", app.get("current_step").asString());
        }
        return row;
    }

    /** Revokes the token with this hash, or every live token of the application when {@code hash} is null. */
    private void revoke(UUID applicationId, String hash, String reason) {
        rows.jdbc().sql("""
                update onboarding_applicant_token set revoked_at = ?::timestamptz, revoked_reason = ?
                where onboarding_application_id = ? and revoked_at is null and (?::text is null or token_hash = ?)""")
                .params(Clocks.now().toString(), reason, applicationId, hash, hash).update();
    }

    // ------------------------------------------------------------------ application and steps

    /** The applicant's view of the application: risk, decision and review internals removed. */
    @Transactional(readOnly = true)
    public ObjectNode view(UUID applicationId) {
        return redact(rows.get(T_APP, applicationId));
    }

    static ObjectNode redact(ObjectNode app) {
        ObjectNode out = app.deepCopy();
        HIDDEN.forEach(out::remove);
        if (out.get("collected_data") instanceof ObjectNode data) {
            SERVER_MANAGED.forEach(data::remove);
        }
        if (out.get("info_requests") != null && out.get("info_requests").isArray()) {
            out.get("info_requests").forEach(r -> {
                if (r instanceof ObjectNode o) {
                    o.remove("requested_by");
                }
            });
        }
        return out;
    }

    /**
     * Submits a step for the applicant: contact steps only complete through the one-time code, only the step's
     * declared fields are accepted (bank-managed values are taken from the application, never from the applicant),
     * and document fields must reference this application's documents.
     */
    public ObjectNode submitStep(UUID applicationId, String code, JsonNode input) {
        StepDef sd = apps.stepDef(applicationId, code);
        if (CONTACT_STEPS.contains(sd.type())) {
            throw ApiException.conflict("otp_required", "Verify this step with otp/send and otp/verify");
        }
        ObjectNode in = input != null && input.isObject() ? ((ObjectNode) input).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        List<FieldError> errors = new ArrayList<>();
        Set<String> declared = new java.util.HashSet<>();
        sd.fields().forEach(f -> declared.add(f.name()));
        in.propertyNames().forEach(name -> {
            if (SERVER_MANAGED.contains(name)) {
                errors.add(new FieldError("input." + name, "server_managed", name + " is set by the bank"));
            } else if (!declared.contains(name)) {
                errors.add(new FieldError("input." + name, "unknown_field", name + " is not a field of step " + code));
            }
        });
        ObjectNode app = rows.get(T_APP, applicationId);
        JsonNode data = app.path("collected_data");
        for (StepField f : sd.fields()) {
            if (SERVER_MANAGED.contains(f.name())) {
                if (!JsonValues.blank(data.get(f.name()))) {
                    in.set(f.name(), data.get(f.name()));
                }
                continue;
            }
            f.validate(in.get(f.name()), errors);
            if ("DOCUMENT".equals(f.type()) && in.hasNonNull(f.name()) && errors.stream()
                    .noneMatch(e -> e.field().equals("input." + f.name()))) {
                if (!ownsDocument(applicationId, in.get(f.name()).asString())) {
                    errors.add(new FieldError("input." + f.name(), "document_not_found",
                            "the document is not one of this application's documents"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_fields", "Step " + code + " has invalid fields", errors);
        }
        return apps.submitStep(applicationId, code, in);
    }

    /** Submits for decision; the applicant's tokens are revoked (the decision is communicated by the bank). */
    @Transactional
    public ObjectNode submit(UUID applicationId) {
        ObjectNode app = apps.submit(applicationId);
        revoke(applicationId, null, "SUBMITTED");
        return redact(app);
    }

    // ------------------------------------------------------------------ one-time codes

    /** Sends a one-time code for a contact step; a new code supersedes the previous one. */
    @Transactional
    public ObjectNode sendOtp(UUID applicationId, String stepCode, String destination) {
        ObjectNode app = rows.lock(T_APP, applicationId);
        Lifecycles.application(app.path("status").asString(), "submit_step");
        StepDef sd = apps.stepDef(applicationId, stepCode);
        if (!CONTACT_STEPS.contains(sd.type())) {
            throw ApiException.unprocessable("not_a_contact_step", "Step " + stepCode + " is not a phone or email verification");
        }
        boolean phone = "PHONE_VERIFICATION".equals(sd.type());
        String dest = destination == null ? "" : destination.trim();
        if (!(phone ? StepField.E164 : StepField.EMAIL).matcher(dest).matches()) {
            throw new ApiException(422, "invalid_fields", "destination is not valid", List.of(new FieldError("destination",
                    phone ? "invalid_phone" : "invalid_email",
                    phone ? "destination must be an E.164 phone number" : "destination must be an email address")));
        }
        List<ObjectNode> sent = rows.query("""
                select row_to_json(t)::text from onboarding_otp t where onboarding_application_id = ?
                order by created_at desc, id desc""", applicationId);
        if (sent.size() >= settings.otpMaxSends()) {
            throw new ApiException(429, "otp_send_limit", "Too many codes were requested for this application");
        }
        quota(dest);
        // database clock: created_at of earlier codes comes from now() (JVM and DB clocks drift apart)
        OffsetDateTime now = rows.jdbc().sql("select now()").query(OffsetDateTime.class).single();
        sent.stream().filter(o -> stepCode.equals(o.path("step_code").asString())).findFirst()
                .map(o -> Events.ts(o, "created_at").plusSeconds(settings.otpResendSeconds()))
                .filter(next -> next.isAfter(now))
                .ifPresent(next -> {
                    throw new ApiException(429, "otp_resend_too_soon", "Wait until " + next + " before requesting a new code");
                });
        rows.jdbc().sql("""
                update onboarding_otp set status = 'SUPERSEDED'
                where onboarding_application_id = ? and step_code = ? and status = 'PENDING'""")
                .params(applicationId, stepCode).update();
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("onboarding_application_id", applicationId.toString());
        v.put("step_code", stepCode);
        v.put("channel", phone ? "SMS" : "EMAIL");
        v.put("destination", dest);
        v.put("code_hash", codeHash(applicationId, stepCode, code));
        v.put("status", "PENDING");
        v.put("attempts", 0);
        v.put("max_attempts", settings.otpMaxAttempts());
        v.put("expires_at", now.plusSeconds(settings.otpTtlSeconds()).toString());
        ObjectNode otp = rows.insert(T_OTP, v);

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("recipient_type", "EXTERNAL");
        n.put("channel", phone ? "SMS" : "EMAIL");
        n.put("destination", dest);
        n.put("category", "SECURITY");
        n.put("priority", "HIGH");
        n.put("subject", "Your verification code");
        n.put("body", "Your verification code is " + code + ". It expires in " + Math.max(1, settings.otpTtlSeconds() / 60)
                + " minutes. Never share it.");
        n.put("expires_at", now.plusSeconds(settings.otpTtlSeconds()).toString());
        JsonNode notification = client.post("notification-service", "/v1/notifications", n, "onb-otp:" + otp.get("id").asString());
        if (notification != null && !JsonValues.blank(notification.get("id"))) {
            ObjectNode nv = JsonNodeFactory.instance.objectNode();
            nv.put("notification_id", notification.get("id").asString());
            otp = rows.update(T_OTP, Events.uuid(otp, "id"), nv);
        }
        return otpView(otp, dest, now.plusSeconds(settings.otpResendSeconds()));
    }

    /**
     * SEC-16: codes per destination (across applications, so new applications cannot pump one number) and per tenant
     * per hour (SMS pumping / toll fraud ceiling). Serialised per destination so concurrent sends cannot overrun.
     */
    private void quota(String dest) {
        rows.jdbc().sql("select 1 from pg_advisory_xact_lock(hashtext(?))").param("onb-otp:" + dest)
                .query(Integer.class).single();
        int toDestination = rows.jdbc().sql("""
                select count(*) from onboarding_otp where destination = ? and created_at > now() - interval '1 hour'""")
                .param(dest).query(Integer.class).single();
        if (toDestination >= settings.otpSendsPerDestinationPerHour()) {
            throw new ApiException(429, "otp_destination_limit", "Too many codes were sent to this destination; try again later");
        }
        int lastHour = rows.jdbc().sql("select count(*) from onboarding_otp where created_at > now() - interval '1 hour'")
                .query(Integer.class).single();
        if (lastHour >= settings.otpSendsPerHour()) {
            throw new ApiException(429, "otp_quota_exceeded", "Verification codes are temporarily unavailable; try again later");
        }
    }

    /** Outcome of a verification; failures are returned (not thrown) so the attempt counter commits. */
    public record OtpOutcome(ObjectNode step, String error, String detail) {}

    @Transactional
    public OtpOutcome verifyOtp(UUID applicationId, String stepCode, String code) {
        ObjectNode app = rows.lock(T_APP, applicationId);
        Lifecycles.application(app.path("status").asString(), "submit_step");
        ObjectNode otp = rows.query("""
                select row_to_json(t)::text from onboarding_otp t
                where onboarding_application_id = ? and step_code = ? and status <> 'SUPERSEDED'
                order by created_at desc, id desc limit 1""", applicationId, stepCode).stream().findFirst()
                .orElse(null);
        if (otp == null || "VERIFIED".equals(otp.path("status").asString())) {
            return new OtpOutcome(null, "otp_not_requested", "Request a code with otp/send first");
        }
        UUID otpId = Events.uuid(otp, "id");
        String status = otp.path("status").asString();
        if ("LOCKED".equals(status)) {
            return new OtpOutcome(null, "otp_locked", "Too many wrong codes; request a new code");
        }
        if ("EXPIRED".equals(status) || Events.ts(otp, "expires_at").isBefore(Clocks.now())) {
            setOtp(otpId, "EXPIRED", null);
            return new OtpOutcome(null, "otp_expired", "The code has expired; request a new one");
        }
        String expected = otp.path("code_hash").asString();
        String actual = codeHash(applicationId, stepCode, code == null ? "" : code.trim());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            int attempts = otp.path("attempts").asInt() + 1;
            boolean locked = attempts >= otp.path("max_attempts").asInt(settings.otpMaxAttempts());
            ObjectNode v = JsonNodeFactory.instance.objectNode();
            v.put("attempts", attempts);
            if (locked) {
                v.put("status", "LOCKED");
            }
            rows.update(T_OTP, otpId, v);
            return locked ? new OtpOutcome(null, "otp_locked", "Too many wrong codes; request a new code")
                    : new OtpOutcome(null, "otp_invalid", "Wrong code; "
                            + (otp.path("max_attempts").asInt() - attempts) + " attempts left");
        }
        setOtp(otpId, "VERIFIED", Clocks.now());
        boolean phone = "SMS".equals(otp.path("channel").asString());
        return new OtpOutcome(apps.completeVerifiedContact(applicationId, stepCode, otp.path("destination").asString(), phone),
                null, null);
    }

    private void setOtp(UUID id, String status, OffsetDateTime verifiedAt) {
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("status", status);
        if (verifiedAt != null) {
            v.put("verified_at", verifiedAt.toString());
        }
        rows.update(T_OTP, id, v);
    }

    private static ObjectNode otpView(ObjectNode otp, String destination, OffsetDateTime resendAfter) {
        ObjectNode out = otp.deepCopy();
        out.remove(List.of("code_hash", "destination"));
        out.put("destination_masked", mask(destination));
        out.put("resend_after", resendAfter.toString());
        return out;
    }

    static String mask(String d) {
        int at = d.indexOf('@');
        if (at > 0) {
            return d.charAt(0) + "***" + d.substring(at);
        }
        return d.length() <= 6 ? "******" : d.substring(0, 5) + "*".repeat(d.length() - 7) + d.substring(d.length() - 2);
    }

    private String codeHash(UUID applicationId, String stepCode, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.otpSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((applicationId + ":" + stepCode + ":" + code)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ documents

    /** Registers a document with document-service for the applicant and returns its pre-signed upload. */
    public ObjectNode createDocument(UUID applicationId, JsonNode req) {
        ObjectNode app = rows.get(T_APP, applicationId);
        Lifecycles.application(app.path("status").asString(), "submit_step");
        String stepCode = req.path("step_code").asString("");
        StepDef sd = apps.stepDef(applicationId, stepCode);
        if (!DOCUMENT_STEPS.contains(sd.type()) && sd.fields().stream().noneMatch(f -> "DOCUMENT".equals(f.type()))) {
            throw ApiException.unprocessable("not_a_document_step", "Step " + stepCode + " does not capture documents");
        }
        List<FieldError> errors = new ArrayList<>();
        String type = req.path("document_type").asString("");
        if (!DOCUMENT_TYPES.contains(type)) {
            errors.add(new FieldError("document_type", "invalid_value", "document_type must be one of " + DOCUMENT_TYPES));
        }
        String contentType = req.path("content_type").asString("").toLowerCase(java.util.Locale.ROOT);
        if (!CONTENT_TYPES.contains(contentType)) {
            errors.add(new FieldError("content_type", "invalid_value", "content_type must be one of " + CONTENT_TYPES));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_fields", "The document is not acceptable", errors);
        }
        JsonNode existing = client.get("document-service", "/v1/documents?owner_type=" + DOCUMENT_OWNER + "&owner_id="
                + applicationId + "&limit=" + settings.maxDocuments());
        if (existing != null && existing.path("data").size() >= settings.maxDocuments()) {
            throw new ApiException(429, "document_limit", "This application already has the maximum number of documents");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("owner_type", DOCUMENT_OWNER);
        body.put("owner_id", applicationId.toString());
        body.put("document_type", type);
        String name = req.path("file_name").asString("upload");
        body.put("file_name", name.length() > 255 ? name.substring(0, 255) : name);
        body.put("content_type", contentType);
        body.put("uploaded_via", app.path("channel").asString("API"));
        body.put("metadata", Map.of("step_code", stepCode, "application_number", app.path("application_number").asString()));
        JsonNode doc = client.post("document-service", "/v1/documents", body,
                "onb-doc:" + applicationId + ":" + UUID.randomUUID());
        String documentId = doc.path("id").asString();
        JsonNode upload = client.post("document-service", "/v1/documents/" + documentId + "/upload-url", null,
                "onb-doc-url:" + documentId);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("document_id", documentId);
        out.put("step_code", stepCode);
        if (upload != null && upload.isObject()) {
            upload.properties().forEach(e -> out.set(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Confirms the content of one of the application's documents (404 for any other document). */
    public ObjectNode completeDocument(UUID applicationId, UUID documentId, JsonNode req) {
        if (!ownsDocument(applicationId, documentId.toString())) {
            throw ApiException.notFound("document", documentId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size_bytes", req.path("size_bytes").asLong());
        body.put("checksum", req.path("checksum").asString());
        JsonNode doc = client.post("document-service", "/v1/documents/" + documentId + "/complete-upload", body,
                "onb-doc-complete:" + documentId + ":" + req.path("checksum").asString());
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("document_id", documentId.toString());
        if (doc != null) {
            for (String f : List.of("status", "size_bytes", "checksum", "document_type", "file_name")) {
                if (doc.hasNonNull(f)) {
                    out.set(f, doc.get(f));
                }
            }
        }
        return out;
    }

    private boolean ownsDocument(UUID applicationId, String documentId) {
        try {
            UUID.fromString(documentId);
            JsonNode doc = client.get("document-service", "/v1/documents/" + documentId);
            return doc != null && DOCUMENT_OWNER.equals(doc.path("owner_type").asString())
                    && applicationId.toString().equals(doc.path("owner_id").asString());
        } catch (ApiException e) {
            if (e.status() == 404) {
                return false;
            }
            throw e;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
