package io.meridian.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.application.NotificationService;
import io.meridian.notification.application.RuleService;
import io.meridian.notification.domain.CallbackSignature;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Notification flows over HTTP against PostgreSQL 18 (RLS enforced): templates (versioning, preview, maker-checker
 * publish), provider failover (a real SMTP connection that is refused, then the sandbox), retries with backoff,
 * preferences and quiet hours, provider receipts, in-app read, rules from domain events, idempotency, isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.notifications.scheduler-enabled=false", "meridian.notifications.consumer-enabled=false",
                "meridian.notifications.callback-secrets.twilio=" + NotificationFlowIT.TWILIO_SECRET})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000cafe";
    static final String TWILIO_SECRET = "twilio-callback-secret";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RuleService rules;

    @Autowired
    NotificationService notifications;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    String smsProvider;
    String pushProvider;
    String welcomeV1;
    String sentEmail;
    String sentSms;

    @BeforeAll
    void setUp() throws Exception {
        when(services.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            JsonNode req = json.valueToTree(inv.getArgument(2));
            ObjectNode apr = ((ObjectNode) req).deepCopy();
            apr.put("id", UUID.randomUUID().toString());
            apr.put("status", "PENDING");
            apr.put("priority", "NORMAL");
            apr.put("maker", "maker-1");
            apr.put("required_approvals", 1);
            apr.put("approvals_received", 0);
            apr.put("current_level", 1);
            apr.put("row_version", 1);
            apr.put("created_at", OffsetDateTime.now().toString());
            apr.put("updated_at", OffsetDateTime.now().toString());
            return apr;
        });
        // EMAIL: priority 1 is a real SMTP server that refuses connections, priority 2 the sandbox (failover).
        post("/v1/notification-providers", """
                {"channel":"EMAIL","provider_name":"SMTP","priority":1,"credentials_ref":"vault:smtp","status":"ACTIVE",
                 "sender_id":"bank@meridian.test","config":{"smtp_host":"127.0.0.1","smtp_port":1}}""", 201);
        post("/v1/notification-providers", """
                {"channel":"EMAIL","provider_name":"SES","priority":2,"credentials_ref":"vault:ses","status":"ACTIVE",
                 "config":{"sandbox":true}}""", 201);
        JsonNode twilio = post("/v1/notification-providers", """
                {"channel":"SMS","provider_name":"TWILIO","priority":1,"credentials_ref":"vault:twilio","status":"ACTIVE",
                 "country_scope":["KE","UG"]}""", 201);
        smsProvider = twilio.get("id").asString();
        assertThat(twilio.get("credentials_ref").asString()).isEqualTo("vault:twilio");
        assertThat(twilio.get("country_scope").isArray()).isTrue();
        pushProvider = post("/v1/notification-providers", """
                {"channel":"PUSH","provider_name":"FCM","priority":1,"credentials_ref":"vault:fcm","status":"ACTIVE",
                 "config":{"simulate":"ERROR"}}""", 201).get("id").asString();
    }

    @Test
    @Order(1)
    void templateVersioningPreviewAndPublishThroughApproval() throws Exception {
        JsonNode v1 = post("/v1/notification-templates", """
                {"code":"WELCOME","channel":"EMAIL","locale":"en","category":"SERVICE","subject":"Welcome {{name}}",
                 "body":"<p>Hello {{name}}, your account {{account.last4}} is ready</p>","status":"ACTIVE"}""", 201);
        welcomeV1 = v1.get("id").asString();
        assertThat(v1.get("status").asString()).isEqualTo("DRAFT"); // status is never client-set
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        JsonNode v2 = post("/v1/notification-templates", """
                {"code":"WELCOME","channel":"EMAIL","category":"SERVICE","body":"Hi {{name}}"}""", 201);
        assertThat(v2.get("version").asInt()).isEqualTo(2);

        JsonNode bad = post("/v1/notification-templates", """
                {"code":"BAD","channel":"SMS","category":"SERVICE","body":"{{#each items}}x{{/each}}"}""", 422);
        assertThat(bad.get("code").asString()).isEqualTo("invalid_template");

        JsonNode preview = post("/v1/notification-templates/" + welcomeV1 + "/preview", """
                {"variables":{"name":"<b>Ann</b>","account":{"last4":"4242"}}}""", 200);
        assertThat(preview.get("subject").asString()).isEqualTo("Welcome <b>Ann</b>");
        assertThat(preview.get("body").asString())
                .isEqualTo("<p>Hello &lt;b&gt;Ann&lt;&#x2F;b&gt;, your account 4242 is ready</p>");
        JsonNode missing = post("/v1/notification-templates/" + welcomeV1 + "/preview", """
                {"variables":{"name":"Ann"}}""", 422);
        assertThat(missing.get("code").asString()).isEqualTo("missing_variables");
        assertThat(missing.get("errors").get(0).get("field").asString()).isEqualTo("variables.account.last4");

        JsonNode apr = post("/v1/notification-templates/" + welcomeV1 + "/publish", null, 200);
        assertThat(apr.get("resource_type").asString()).isEqualTo("notification_template");
        assertThat(apr.get("resource_id").asString()).isEqualTo(welcomeV1);
        assertThat(apr.get("status").asString()).isEqualTo("PENDING");
        assertThat(get("/v1/notification-templates/" + welcomeV1, TENANT).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(post("/v1/notification-templates/" + welcomeV1 + "/publish", null, 409).get("code").asString())
                .isEqualTo("invalid_transition");
        HttpResponse<String> patch = send("PATCH", "/v1/notification-templates/" + welcomeV1, "{\"body\":\"x\"}", null,
                TENANT, "\"" + get("/v1/notification-templates/" + welcomeV1, TENANT).get("row_version").asString() + "\"");
        assertThat(patch.statusCode()).isEqualTo(409);
        assertThat(json.readTree(patch.body()).get("code").asString()).isEqualTo("template_not_editable");

        approvalOutcome(welcomeV1, "approved", "APPROVED");
        assertThat(get("/v1/notification-templates/" + welcomeV1, TENANT).get("status").asString()).isEqualTo("ACTIVE");

        // Version 2 approved later retires version 1.
        String v2Id = v2.get("id").asString();
        post("/v1/notification-templates/" + v2Id + "/publish", null, 200);
        approvalOutcome(v2Id, "rejected", "REJECTED");
        assertThat(get("/v1/notification-templates/" + v2Id, TENANT).get("status").asString()).isEqualTo("DRAFT");
        assertThat(get("/v1/notification-templates/" + welcomeV1, TENANT).get("status").asString()).isEqualTo("ACTIVE");
        // Changes requested by the checker also return the template to DRAFT.
        post("/v1/notification-templates/" + v2Id + "/publish", null, 200);
        assertThat(get("/v1/notification-templates/" + v2Id, TENANT).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        approvalOutcome(v2Id, "changes_requested", "CHANGES_REQUESTED");
        assertThat(get("/v1/notification-templates/" + v2Id, TENANT).get("status").asString()).isEqualTo("DRAFT");

        JsonNode noProvider = post("/v1/notification-providers", """
                {"channel":"VOICE","provider_name":"AFRICAS_TALKING","priority":1,"country_scope":["kenya"]}""", 422);
        assertThat(noProvider.get("code").asString()).isEqualTo("invalid_country_scope");
        JsonNode defaulted = post("/v1/notification-providers", """
                {"channel":"VOICE","provider_name":"AFRICAS_TALKING","priority":1,"status":"INACTIVE"}""", 201);
        assertThat(defaulted.get("credentials_ref").asString()).startsWith("vault:notification/providers/");
    }

    @Test
    @Order(2)
    void templatedEmailFailsOverFromSmtpToSandbox() throws Exception {
        JsonNode n = post("/v1/notifications/send-templated", """
                {"template_code":"WELCOME","recipient_type":"CUSTOMER","recipient_id":"%s","channel":"EMAIL",
                 "variables":{"name":"Ann & Bob","account":{"last4":"0001"},"destination":"ann@example.com"}}"""
                .formatted(CUSTOMER), 201);
        sentEmail = n.get("id").asString();
        assertThat(n.get("status").asString()).isEqualTo("SENT");
        assertThat(n.get("subject").asString()).isEqualTo("Welcome Ann & Bob");
        assertThat(n.get("body").asString()).contains("Ann &amp; Bob");
        assertThat(n.get("provider_message_id").asString()).startsWith("sbx_");
        assertThat(n.get("notification_template_id").asString()).isEqualTo(welcomeV1);
        JsonNode attempts = get("/v1/notifications/" + sentEmail + "/attempts", TENANT).get("data");
        assertThat(attempts).hasSize(2);
        assertThat(statuses(attempts)).containsExactlyInAnyOrder("ERROR", "ACCEPTED");
        assertThat(events(sentEmail)).containsExactly("platform.notification.sent");

        JsonNode missing = post("/v1/notifications/send-templated", """
                {"template_code":"WELCOME","recipient_type":"CUSTOMER","recipient_id":"%s","channel":"EMAIL",
                 "variables":{"destination":"ann@example.com"}}""".formatted(CUSTOMER), 422);
        assertThat(missing.get("code").asString()).isEqualTo("missing_variables");
        JsonNode noDest = post("/v1/notifications/send-templated", """
                {"template_code":"WELCOME","recipient_type":"CUSTOMER","recipient_id":"%s","channel":"EMAIL",
                 "variables":{"name":"x","account":{"last4":"1"}}}""".formatted(CUSTOMER), 422);
        assertThat(noDest.get("code").asString()).isEqualTo("destination_required");
        JsonNode noTemplate = post("/v1/notifications/send-templated", """
                {"template_code":"NOPE","recipient_type":"CUSTOMER","recipient_id":"%s"}""".formatted(CUSTOMER), 422);
        assertThat(noTemplate.get("code").asString()).isEqualTo("template_not_found");

        // Destination, priority, data and scheduling are first-class body fields (no channel: picked by template).
        String sendAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3).toString();
        JsonNode explicit = post("/v1/notifications/send-templated", """
                {"template_code":"WELCOME","recipient_type":"CUSTOMER","recipient_id":"%s",
                 "destination":"bob@example.com","priority":"HIGH","data":{"deep_link":"app://welcome"},
                 "scheduled_at":"%s","variables":{"name":"Bob","account":{"last4":"0002"}}}"""
                .formatted(CUSTOMER, sendAt), 201);
        assertThat(explicit.get("channel").asString()).isEqualTo("EMAIL");
        assertThat(explicit.get("destination").asString()).isEqualTo("bob@example.com");
        assertThat(explicit.get("priority").asString()).isEqualTo("HIGH");
        assertThat(explicit.get("data").get("deep_link").asString()).isEqualTo("app://welcome");
        assertThat(explicit.get("status").asString()).isEqualTo("SCHEDULED");
    }

    @Test
    @Order(3)
    void receiptsDeliverAndInvalidTransitionsAreRejected() throws Exception {
        JsonNode sms = post("/v1/notifications", sms("+254700000001", "TRANSACTIONAL", null), 201);
        sentSms = sms.get("id").asString();
        assertThat(sms.get("status").asString()).isEqualTo("SENT");
        String mid = sms.get("provider_message_id").asString();
        String delivered = "{\"provider_message_id\":\"%s\",\"status\":\"delivered\"}".formatted(mid);

        // Public callback: no token, tenant header or Idempotency-Key; tenant from the URL, HMAC-signed payload.
        HttpResponse<String> unsigned = callback("twilio", TENANT, delivered, null);
        assertThat(unsigned.statusCode()).isEqualTo(401);
        assertThat(json.readTree(unsigned.body()).get("code").asString()).isEqualTo("invalid_signature");
        assertThat(callback("twilio", TENANT, delivered, "wrong-secret").statusCode()).isEqualTo(401);
        assertThat(callback("infobip", TENANT, delivered, TWILIO_SECRET).statusCode()).isEqualTo(401); // no secret configured
        HttpResponse<String> noTenant = callback("twilio", null, delivered, TWILIO_SECRET);
        assertThat(noTenant.statusCode()).isEqualTo(400);
        assertThat(json.readTree(noTenant.body()).get("code").asString()).isEqualTo("malformed_request");
        assertThat(callback("twilio", OTHER_TENANT, delivered, TWILIO_SECRET).statusCode()).isEqualTo(404);
        assertThat(get("/v1/notifications/" + sentSms, TENANT).get("status").asString()).isEqualTo("SENT");

        HttpResponse<String> ok = callback("twilio", TENANT, delivered, TWILIO_SECRET);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        JsonNode receipt = json.readTree(ok.body());
        assertThat(receipt.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(receipt.get("applied").asBoolean()).isTrue();
        assertThat(get("/v1/notifications/" + sentSms, TENANT).get("delivered_at").isNull()).isFalse();
        assertThat(events(sentSms)).contains("platform.notification.sent", "platform.notification.delivered");
        // A late "sent" receipt (or a replay) never moves the status backwards.
        JsonNode late = json.readTree(callback("twilio", TENANT,
                "{\"provider_message_id\":\"%s\",\"status\":\"sent\"}".formatted(mid), TWILIO_SECRET).body());
        assertThat(late.get("applied").asBoolean()).isFalse();
        HttpResponse<String> unknown = callback("twilio", TENANT,
                "{\"provider_message_id\":\"nope\",\"status\":\"delivered\"}", TWILIO_SECRET);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).get("code").asString()).isEqualTo("not_found");

        assertThat(post("/v1/notifications/" + sentSms + "/resend", null, 409).get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/notifications/" + sentSms + "/cancel", null, 409).get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/notifications/" + sentSms + "/mark-read", null, 422).get("code").asString()).isEqualTo("not_in_app");
    }

    @Test
    @Order(4)
    void inAppDeliveredAndMarkedRead() throws Exception {
        JsonNode n = post("/v1/notifications", """
                {"recipient_type":"CUSTOMER","recipient_id":"%s","channel":"IN_APP","destination":"%s",
                 "category":"SERVICE","subject":"Statement ready","body":"Your statement is ready"}"""
                .formatted(CUSTOMER, CUSTOMER), 201);
        assertThat(n.get("status").asString()).isEqualTo("DELIVERED");
        JsonNode read = post("/v1/notifications/" + n.get("id").asString() + "/mark-read", null, 200);
        assertThat(read.get("status").asString()).isEqualTo("READ");
        assertThat(read.get("read_at").isNull()).isFalse();
    }

    @Test
    @Order(5)
    void preferencesSuppressAndQuietHoursSchedule() throws Exception {
        post("/v1/notification-preferences", """
                {"recipient_type":"CUSTOMER","recipient_id":"%s","category":"MARKETING","channel":"SMS","is_enabled":false}"""
                .formatted(CUSTOMER), 201);
        JsonNode mandatory = post("/v1/notification-preferences", """
                {"recipient_type":"CUSTOMER","recipient_id":"%s","category":"SECURITY","channel":"SMS","is_enabled":false}"""
                .formatted(CUSTOMER), 422);
        assertThat(mandatory.get("code").asString()).isEqualTo("category_mandatory");
        JsonNode tz = post("/v1/notification-preferences", """
                {"recipient_type":"CUSTOMER","recipient_id":"%s","category":"SERVICE","channel":"EMAIL",
                 "quiet_hours_start":"22:00","quiet_hours_end":"07:00","timezone":"Mars/Olympus"}""".formatted(CUSTOMER), 422);
        assertThat(tz.get("code").asString()).isEqualTo("invalid_timezone");

        JsonNode suppressed = post("/v1/notifications", sms("+254700000001", "MARKETING", null), 201);
        assertThat(suppressed.get("status").asString()).isEqualTo("SUPPRESSED");
        assertThat(suppressed.get("failure_reason").asString()).isEqualTo("recipient_opted_out");

        // Quiet window around "now" in UTC: SERVICE messages are deferred to its end; SECURITY ones are not.
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String start = now.minusHours(1).format(hhmm);
        String end = now.plusHours(1).format(hhmm);
        post("/v1/notification-preferences", """
                {"recipient_type":"CUSTOMER","recipient_id":"%s","category":"SERVICE","channel":"SMS",
                 "quiet_hours_start":"%s","quiet_hours_end":"%s","timezone":"UTC","locale":"en"}"""
                .formatted(CUSTOMER, start, end), 201);
        JsonNode scheduled = post("/v1/notifications", sms("+254700000001", "SERVICE", null), 201);
        assertThat(scheduled.get("status").asString()).isEqualTo("SCHEDULED");
        Instant at = OffsetDateTime.parse(scheduled.get("scheduled_at").asString()).toInstant();
        assertThat(at).isBetween(Instant.now().plus(55, ChronoUnit.MINUTES), Instant.now().plus(61, ChronoUnit.MINUTES));
        assertThat(eventsCount("platform.notification_preference.changed")).isGreaterThanOrEqualTo(2);

        // Not due yet: the sweep leaves it alone; after the window it is sent.
        String id = scheduled.get("id").asString();
        callAs(() -> notifications.processDue(Instant.now()));
        assertThat(get("/v1/notifications/" + id, TENANT).get("status").asString()).isEqualTo("SCHEDULED");
        callAs(() -> notifications.processDue(at.plusSeconds(1)));
        assertThat(get("/v1/notifications/" + id, TENANT).get("status").asString()).isEqualTo("SENT");

        JsonNode later = post("/v1/notifications", sms("+254700000002", "SERVICE", null), 201);
        JsonNode cancelled = post("/v1/notifications/" + later.get("id").asString() + "/cancel", null, 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.hasNonNull("failure_reason")).isFalse();
        assertThat(events(later.get("id").asString())).containsExactly("platform.notification.cancelled");
        post("/v1/notifications/" + later.get("id").asString() + "/cancel", null, 409);
        callAs(() -> notifications.processDue(at.plus(1, ChronoUnit.HOURS)));
        assertThat(get("/v1/notifications/" + later.get("id").asString(), TENANT).get("status").asString())
                .isEqualTo("CANCELLED");

        JsonNode security = post("/v1/notifications", sms("+254700000003", "SECURITY", null), 201);
        assertThat(security.get("status").asString()).isEqualTo("SENT");
    }

    @Test
    @Order(6)
    void transientFailuresRetryWithBackoffThenFailAndResend() throws Exception {
        JsonNode n = post("/v1/notifications", """
                {"recipient_type":"CUSTOMER","recipient_id":"%s","channel":"PUSH","destination":"device-token-1",
                 "category":"TRANSACTIONAL","subject":"Card used","body":"Your card was used"}""".formatted(CUSTOMER), 201);
        String id = n.get("id").asString();
        assertThat(n.get("status").asString()).isEqualTo("QUEUED");
        assertThat(n.get("failure_reason").asString()).startsWith("retrying");
        Instant first = OffsetDateTime.parse(n.get("scheduled_at").asString()).toInstant();
        assertThat(first).isAfter(Instant.now().plusSeconds(20));

        Instant clock = Instant.now();
        for (int i = 0; i < 6; i++) {
            clock = clock.plus(2, ChronoUnit.HOURS);
            Instant t = clock;
            callAs(() -> notifications.processDue(t));
        }
        JsonNode failed = get("/v1/notifications/" + id, TENANT);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failure_reason").asString()).startsWith("retries_exhausted");
        JsonNode firstCycle = get("/v1/notifications/" + id + "/attempts?limit=50", TENANT).get("data");
        assertThat(firstCycle).hasSize(5);
        firstCycle.forEach(a -> assertThat(a.get("cycle").asInt()).isEqualTo(1));
        firstCycle.forEach(a -> assertThat(a.path("provider_response").has("cycle")).isFalse());
        assertThat(events(id)).contains("platform.notification.failed");

        // Fix the provider and resend: a new cycle succeeds.
        JsonNode provider = get("/v1/notification-providers/" + pushProvider, TENANT);
        HttpResponse<String> fixed = send("PATCH", "/v1/notification-providers/" + pushProvider,
                "{\"config\":{\"simulate\":\"NONE\"}}", null, TENANT, "\"" + provider.get("row_version").asString() + "\"");
        assertThat(fixed.statusCode()).as(fixed.body()).isEqualTo(200);
        JsonNode resent = post("/v1/notifications/" + id + "/resend", null, 200);
        assertThat(resent.get("status").asString()).isEqualTo("SENT");
        JsonNode all = get("/v1/notifications/" + id + "/attempts?limit=50", TENANT).get("data");
        assertThat(all).hasSize(6);
        assertThat(all).anySatisfy(a -> assertThat(a.get("cycle").asInt()).isEqualTo(2));
    }

    @Test
    @Order(7)
    void validationErrors() throws Exception {
        assertThat(post("/v1/notifications", sms("not-a-phone", "TRANSACTIONAL", null), 422).get("code").asString())
                .isEqualTo("invalid_destination");
        assertThat(post("/v1/notifications", """
                {"recipient_type":"CUSTOMER","channel":"SMS","destination":"+254700000001","category":"SERVICE"}""", 400)
                .get("code").asString()).isEqualTo("validation_failed");
        assertThat(post("/v1/notifications", """
                {"recipient_type":"CUSTOMER","channel":"SMS","destination":"+254700000001","category":"SERVICE",
                 "body":"x","expires_at":"2020-01-01T00:00:00Z"}""", 422).get("code").asString()).isEqualTo("already_expired");
        assertThat(post("/v1/notification-rules", """
                {"event_type":"x.y","template_code":"WELCOME","channels":["FAX"],
                 "recipient_expression":"CUSTOMER:customer_id","category":"SERVICE"}""", 422).get("code").asString())
                .isEqualTo("invalid_channels");
        assertThat(post("/v1/notification-rules", """
                {"event_type":"x.y","template_code":"WELCOME","channels":[],
                 "recipient_expression":"CUSTOMER:customer_id","category":"SERVICE"}""", 422).get("code").asString())
                .isEqualTo("invalid_channels");
        assertThat(post("/v1/notification-rules", """
                {"event_type":"x.y","template_code":"WELCOME","channels":["SMS"],
                 "recipient_expression":"CUSTOMER:customer_id","category":"SERVICE","conditions":"system('rm')"}""", 422)
                .get("code").asString()).isEqualTo("invalid_conditions");
        HttpResponse<String> noKey = send("POST", "/v1/notifications/" + sentSms + "/cancel", null, null, TENANT, null);
        assertThat(noKey.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(8)
    void idempotentReplayAndSourceEventDedupe() throws Exception {
        String body = sms("+254700000009", "TRANSACTIONAL", "evt-dedupe-1");
        HttpResponse<String> first = send("POST", "/v1/notifications", body, "idem-sms-1", TENANT, null);
        assertThat(first.statusCode()).isEqualTo(201);
        HttpResponse<String> replay = send("POST", "/v1/notifications", body, "idem-sms-1", TENANT, null);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(json.readTree(first.body()).get("id").asString());
        HttpResponse<String> reused = send("POST", "/v1/notifications", sms("+254700000008", "TRANSACTIONAL", null),
                "idem-sms-1", TENANT, null);
        assertThat(reused.statusCode()).isEqualTo(422);
        // Same source event with a new key: deduplicated to the existing notification.
        HttpResponse<String> dup = send("POST", "/v1/notifications", body, "idem-sms-2", TENANT, null);
        assertThat(dup.statusCode()).isEqualTo(200);
        assertThat(json.readTree(dup.body()).get("id").asString()).isEqualTo(json.readTree(first.body()).get("id").asString());
        assertThat(get("/v1/notifications?source_event_id=evt-dedupe-1", TENANT).get("data")).hasSize(1);
    }

    @Test
    @Order(9)
    void rulesTurnDomainEventsIntoNotifications() throws Exception {
        post("/v1/notification-rules", """
                {"event_type":"payments.payment.*","template_code":"WELCOME","channels":["SMS","EMAIL"],
                 "recipient_expression":"CUSTOMER:customer_id","category":"TRANSACTIONAL","conditions":"amount >= 100 && currency == 'KES'"}""",
                201);
        String payload = """
                {"customer_id":"%s","amount":"150.00","currency":"KES","name":"Ann","account":{"last4":"7777"},
                 "email":"ann@example.com","phone":"+254700000001"}""".formatted(CUSTOMER);
        List<UUID> created = callAs(() -> rules.onEvent("ce-1", "payments.payment.completed", json.readTree(payload)));
        assertThat(created).hasSize(1); // no ACTIVE SMS template, so EMAIL is used
        JsonNode n = get("/v1/notifications/" + created.getFirst(), TENANT);
        assertThat(n.get("channel").asString()).isEqualTo("EMAIL");
        assertThat(n.get("source_event_id").asString()).isEqualTo("ce-1");
        assertThat(n.get("body").asString()).contains("7777");
        callAs(() -> rules.onEvent("ce-1", "payments.payment.completed", json.readTree(payload))); // redelivery
        assertThat(get("/v1/notifications?source_event_id=ce-1", TENANT).get("data")).hasSize(1);
        List<UUID> none = callAs(() -> rules.onEvent("ce-2", "payments.payment.completed",
                json.readTree(payload.replace("150.00", "50.00"))));
        assertThat(none).isEmpty();
    }

    @Test
    @Order(10)
    void providerTestAndTemplateTestSend() throws Exception {
        JsonNode ok = post("/v1/notification-providers/" + smsProvider + "/test", "{\"destination\":\"+254700000001\"}", 200);
        assertThat(ok.get("success").asBoolean()).isTrue();
        assertThat(ok.get("provider_message_id").asString()).startsWith("sbx_");
        assertThat(post("/v1/notification-providers/" + smsProvider + "/test", "{\"destination\":\"bad\"}", 422)
                .get("code").asString()).isEqualTo("invalid_destination");
        JsonNode test = post("/v1/notification-templates/" + welcomeV1 + "/test-send", "{\"destination\":\"qa@example.com\"}", 200);
        assertThat(test.get("status").asString()).isEqualTo("SENT");
        assertThat(test.get("subject").asString()).startsWith("[TEST] Welcome [name]");
    }

    @Test
    @Order(11)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/notifications/" + sentEmail, null, null, OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(get("/v1/notifications", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/notification-templates", OTHER_TENANT).get("data")).isEmpty();
        HttpResponse<String> cancel = send("POST", "/v1/notifications/" + sentEmail + "/cancel", null,
                UUID.randomUUID().toString(), OTHER_TENANT, null);
        assertThat(cancel.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private void approvalOutcome(String templateId, String verb, String status) throws Exception {
        String payload = """
                {"resource_type":"notification_template","resource_id":"%s","status":"%s"}""".formatted(templateId, status);
        callAs(() -> rules.onEvent(UUID.randomUUID().toString(), "platform.approval_request." + verb, json.readTree(payload)));
    }

    /** A provider callback as a provider sends it: no bearer token, tenant header or Idempotency-Key. */
    private HttpResponse<String> callback(String provider, String tenant, String payload, String secret) throws Exception {
        String path = "/v1/notifications/provider-callbacks/" + provider + "/events"
                + (tenant == null ? "" : "?tenant_id=" + tenant);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":" + payload + "}"));
        if (secret != null) {
            b.header(CallbackSignature.HEADER, CallbackSignature.sign(secret, json.readTree(payload)));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private <T> T callAs(java.util.concurrent.Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private static String sms(String to, String category, String sourceEventId) {
        return """
                {"recipient_type":"CUSTOMER","recipient_id":"%s","channel":"SMS","destination":"%s","category":"%s",
                 "body":"Hello"%s}""".formatted(CUSTOMER, to, category,
                sourceEventId == null ? "" : ",\"source_event_id\":\"" + sourceEventId + "\"");
    }

    private List<String> events(String aggregateId) {
        return jdbc.sql("select event_type from outbox where aggregate_id = ?::uuid order by created_at, id")
                .param(aggregateId).query(String.class).list();
    }

    private Integer eventsCount(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ?").param(type).query(Integer.class).single();
    }

    private static List<String> statuses(JsonNode attempts) {
        List<String> out = new java.util.ArrayList<>();
        attempts.forEach(a -> out.add(a.get("status").asString()));
        return out;
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "maker-1").header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
