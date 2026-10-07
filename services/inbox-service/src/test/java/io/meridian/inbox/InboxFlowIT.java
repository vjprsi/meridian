package io.meridian.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * inbox-service end to end over HTTP against PostgreSQL 18 with row-level security. crm-service (work items),
 * document-service (attachments), notification-service (alerts) and customer-service (segments) are stubbed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InboxFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";
    static final String ALICE = "0199a000-0000-7000-8000-00000000a101";
    static final String BOB = "0199a000-0000-7000-8000-00000000a102";
    static final String QUEUE = "0199a000-0000-7000-8000-00000000c101";
    static final String AGENT = "0199a000-0000-7000-8000-00000000f101";
    static final String DOC_OK = "0199a000-0000-7000-8000-00000000d101";
    static final String DOC_INFECTED = "0199a000-0000-7000-8000-00000000d102";
    static final String DOC_MISSING = "0199a000-0000-7000-8000-00000000d103";
    static final String SEGMENT = "0199a000-0000-7000-8000-000000005101";
    static final String WORK_ITEM = "0199a000-0000-7000-8000-00000000b101";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> crmCalls = new CopyOnWriteArrayList<>();
    final List<JsonNode> notifications = new CopyOnWriteArrayList<>();
    final AtomicBoolean notificationsDown = new AtomicBoolean(false);

    String thread;
    String staffMessage;
    String banner;
    String targeted;
    String later;
    String sticky;

    @BeforeEach
    void stubOtherServices() {
        when(serviceClient.post(eq("crm-service"), anyString(), any(), any())).thenAnswer(inv -> {
            crmCalls.add(inv.getArgument(1));
            return json.readTree("{\"id\":\"" + WORK_ITEM + "\",\"status\":\"QUEUED\"}");
        });
        when(serviceClient.get(eq("document-service"), startsWith("/v1/documents/"))).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            if (path.endsWith(DOC_MISSING)) {
                throw ApiException.notFound("document", DOC_MISSING);
            }
            String status = path.endsWith(DOC_INFECTED) ? "QUARANTINED" : "AVAILABLE";
            return json.readTree("{\"id\":\"" + path.substring(path.lastIndexOf('/') + 1) + "\",\"status\":\"" + status + "\"}");
        });
        when(serviceClient.post(eq("notification-service"), eq("/v1/notifications/send-templated"), any(), any()))
                .thenAnswer(inv -> {
                    if (notificationsDown.get()) {
                        throw new ApiException(502, "upstream_error", "notification-service is not reachable");
                    }
                    notifications.add(json.valueToTree(inv.getArgument(2)));
                    return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"QUEUED\"}");
                });
        when(serviceClient.get(eq("customer-service"), startsWith("/v1/customers/" + ALICE + "/segments"))).thenAnswer(inv ->
                json.readTree("{\"data\":[{\"customer_id\":\"" + ALICE + "\",\"customer_segment_id\":\"" + SEGMENT
                        + "\"}],\"has_more\":false}"));
    }

    // ------------------------------------------------------------------ threads

    @Test
    @Order(1)
    void customerOpensThreadWithFirstMessageRoutedToCrm() throws Exception {
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/threads", """
                {"customer_id":"%s","subject":"x","initial_attachment_document_ids":["%s"]}""".formatted(ALICE, DOC_OK), 422)))
                .isEqualTo("initial_message_required");
        String body = """
                {"customer_id":"%s","subject":"Card charge I do not recognise","topic":"CARDS","work_queue_id":"%s",
                 "initial_message":"There is a charge of 49.99 I did not make","initial_attachment_document_ids":["%s"]}"""
                .formatted(ALICE, QUEUE, DOC_OK);
        HttpResponse<String> r = send("POST", "/v1/inbox/threads", body, "open-thread-1", TENANT, ALICE);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode t = json.readTree(r.body());
        thread = t.get("id").asString();
        assertThat(t.get("status").asString()).isEqualTo("AWAITING_BANK");
        assertThat(t.get("opened_by").asString()).isEqualTo("CUSTOMER");
        assertThat(t.get("work_item_id").asString()).isEqualTo(WORK_ITEM);
        assertThat(t.get("message_count").asInt()).isEqualTo(1);
        assertThat(t.get("unread_staff").asInt()).isEqualTo(1);
        assertThat(t.get("unread_customer").asInt()).isZero();
        assertThat(t.get("last_sender_type").asString()).isEqualTo("CUSTOMER");
        assertThat(crmCalls).contains("/v1/servicing/work-items");

        HttpResponse<String> replay = send("POST", "/v1/inbox/threads", body, "open-thread-1", TENANT, ALICE);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(thread);
        assertThat(get("/v1/inbox/threads/" + thread + "/messages").get("data")).hasSize(1);
        assertThat(get("/v1/inbox/threads/" + thread + "/messages").get("data").get(0).get("attachment_document_ids").get(0)
                .asString()).isEqualTo(DOC_OK);
        assertThat(outbox("engagement.secure_message_thread.opened", thread)).isEqualTo(1);
        assertThat(notifications).as("customer's own message is not alerted to the customer").isEmpty();
    }

    @Test
    @Order(2)
    void staffRepliesWithVerifiedAttachmentsAndInternalNotes() throws Exception {
        assertThat(code(post("/v1/inbox/threads/" + thread + "/messages", """
                {"body":"see attached","sender_type":"STAFF","attachment_document_ids":["%s"]}""".formatted(DOC_INFECTED), 422)))
                .isEqualTo("attachment_not_available");
        assertThat(code(post("/v1/inbox/threads/" + thread + "/messages", """
                {"body":"see attached","sender_type":"STAFF","attachment_document_ids":["%s"]}""".formatted(DOC_MISSING), 422)))
                .isEqualTo("attachment_not_found");
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/threads/" + thread + "/messages", """
                {"body":"secret","is_internal_note":true}""", 422)))
                .isEqualTo("internal_note_not_allowed");
        assertThat(code(post("/v1/inbox/threads/" + thread + "/messages", """
                {"body":"pretending","sender_type":"CUSTOMER"}""", 422)))
                .as("staff cannot post as the customer").isEqualTo("sender_mismatch");
        assertThat(code(post("/v1/inbox/threads/" + thread + "/messages", """
                {"body":"as someone else","sender_user_id":"%s"}""".formatted(AGENT), 422)))
                .as("sender_user_id must be the caller").isEqualTo("sender_mismatch");
        assertThat(code(post("/v1/inbox/threads/" + thread + "/messages", """
                {"sender_type":"STAFF"}""", 400))).isNotBlank();

        JsonNode reply = asUser(AGENT, "POST", "/v1/inbox/threads/" + thread + "/messages", """
                {"body":"We have blocked the card; dispute form attached","sender_type":"STAFF","sender_user_id":"%s",
                 "attachment_document_ids":["%s"],"client_message_id":"web-1"}""".formatted(AGENT, DOC_OK), 201);
        assertThat(reply.get("sender_user_id").asString()).isEqualTo(AGENT);
        staffMessage = reply.get("id").asString();
        assertThat(reply.hasNonNull("notification_id")).isTrue();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().get("recipient_id").asString()).isEqualTo(ALICE);

        JsonNode dup = asUser(AGENT, "POST", "/v1/inbox/threads/" + thread + "/messages", """
                {"body":"We have blocked the card; dispute form attached","attachment_document_ids":["%s"],
                 "client_message_id":"web-1"}""".formatted(DOC_OK), 201);
        assertThat(dup.get("id").asString()).as("client_message_id de-duplicates").isEqualTo(staffMessage);

        post("/v1/inbox/threads/" + thread + "/messages", """
                {"body":"Customer called earlier, verified","is_internal_note":true}""", 201);
        JsonNode t = get("/v1/inbox/threads/" + thread);
        assertThat(t.get("status").asString()).isEqualTo("AWAITING_CUSTOMER");
        assertThat(t.get("unread_customer").asInt()).isEqualTo(1);
        assertThat(t.get("message_count").asInt()).isEqualTo(3);
        assertThat(get("/v1/inbox/threads/" + thread + "/messages").get("data")).hasSize(2);
        assertThat(get("/v1/inbox/threads/" + thread + "/messages?include_internal=true").get("data")).hasSize(3);
        assertThat(notifications).as("internal notes are not alerted").hasSize(1);

        notificationsDown.set(true);
        try {
            JsonNode quiet = post("/v1/inbox/threads/" + thread + "/messages", """
                    {"body":"Any update?","sender_type":"SYSTEM"}""", 201);
            assertThat(!quiet.hasNonNull("notification_id")).as("alerts are fail-open").isTrue();
        } finally {
            notificationsDown.set(false);
        }
    }

    @Test
    @Order(3)
    void readReceipts() throws Exception {
        assertThat(code(post("/v1/inbox/threads/" + thread + "/read", "{\"reader_type\":\"CUSTOMER\"}", 422)))
                .as("staff cannot mark read as the customer").isEqualTo("reader_mismatch");
        JsonNode t = asCustomer(ALICE, "POST", "/v1/inbox/threads/" + thread + "/read", "{}", 200);
        assertThat(t.get("unread_customer").asInt()).isZero();
        assertThat(t.get("unread_staff").asInt()).isEqualTo(1);
        JsonNode receipts = get("/v1/inbox/messages/" + staffMessage + "/receipts").get("data");
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).get("reader_type").asString()).isEqualTo("CUSTOMER");
        asCustomer(ALICE, "POST", "/v1/inbox/threads/" + thread + "/read", "{\"reader_type\":\"CUSTOMER\"}", 200);
        assertThat(get("/v1/inbox/messages/" + staffMessage + "/receipts").get("data")).as("receipts are written once").hasSize(1);
        assertThat(outbox("engagement.secure_message.read", null)).isEqualTo(2); // staff reply + system message

        JsonNode staffRead = asUser(AGENT, "POST", "/v1/inbox/threads/" + thread + "/read", """
                {"reader_type":"STAFF","reader_user_id":"%s"}""".formatted(AGENT), 200);
        assertThat(staffRead.get("unread_staff").asInt()).isZero();
    }

    @Test
    @Order(4)
    void assignCloseReopenAndTransitions() throws Exception {
        JsonNode a = post("/v1/inbox/threads/" + thread + "/assign", "{\"assigned_user_id\":\"%s\"}".formatted(AGENT), 200);
        assertThat(a.get("assigned_user_id").asString()).isEqualTo(AGENT);
        assertThat(crmCalls).contains("/v1/servicing/work-items/" + WORK_ITEM + "/assign");

        assertThat(code(post("/v1/inbox/threads/" + thread + "/reopen", "{}", 409))).isEqualTo("invalid_transition");
        JsonNode closed = post("/v1/inbox/threads/" + thread + "/close", "{\"reason\":\"resolved\"}", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("closed_by").asString()).isEqualTo("maker");
        assertThat(crmCalls).contains("/v1/servicing/work-items/" + WORK_ITEM + "/complete");
        assertThat(code(post("/v1/inbox/threads/" + thread + "/close", "{}", 409))).isEqualTo("invalid_transition");
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/threads/" + thread + "/messages", """
                {"body":"one more thing"}""", 409))).isEqualTo("thread_closed");
        assertThat(code(post("/v1/inbox/threads/" + thread + "/assign", "{\"assigned_user_id\":\"%s\"}".formatted(AGENT), 409)))
                .isEqualTo("thread_closed");
        assertThat(code(patch("/v1/inbox/threads/" + thread, "{\"subject\":\"x\"}", closed.get("row_version").asInt(), 409)))
                .isEqualTo("thread_closed");

        JsonNode reopened = post("/v1/inbox/threads/" + thread + "/reopen", "{\"reason\":\"customer replied\"}", 200);
        assertThat(reopened.get("status").asString()).isEqualTo("OPEN");
        assertThat(reopened.get("reopened_count").asInt()).isEqualTo(1);
        assertThat(!reopened.hasNonNull("closed_at")).isTrue();
        assertThat(outbox("engagement.secure_message_thread.reopened", thread)).isEqualTo(1);

        int version = reopened.get("row_version").asInt();
        assertThat(code(patch("/v1/inbox/threads/" + thread, "{\"customer_id\":\"%s\"}".formatted(UUID.randomUUID()), version, 422)))
                .isEqualTo("immutable_field");
        JsonNode renamed = patch("/v1/inbox/threads/" + thread, "{\"subject\":\"Disputed card charge\"}", version, 200);
        assertThat(renamed.get("subject").asString()).isEqualTo("Disputed card charge");
        assertThat(code(patch("/v1/inbox/threads/" + thread, "{\"subject\":\"stale\"}", version, 409))).isEqualTo("version_conflict");
    }

    @Test
    @Order(5)
    void tenantIsolation() throws Exception {
        assertThat(send("GET", "/v1/inbox/threads/" + thread, null, null, OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/inbox/threads/" + thread + "/messages",
                "{\"body\":\"hi\",\"sender_type\":\"STAFF\"}", key(), OTHER_TENANT).statusCode()).isEqualTo(404);
        HttpResponse<String> list = send("GET", "/v1/inbox/threads?customer_id=" + ALICE, null, null, OTHER_TENANT);
        assertThat(json.readTree(list.body()).get("data")).isEmpty();
    }

    @Test
    @Order(6)
    void customersAreConfinedToTheirOwnThreadsAndNeverSeeInternalNotes() throws Exception {
        // opened_by is server-set: a staff caller sending opened_by=CUSTOMER still opens as STAFF
        JsonNode staffOpened = post("/v1/inbox/threads", """
                {"customer_id":"%s","subject":"Your new card","opened_by":"CUSTOMER","initial_message":"It is on its way"}"""
                .formatted(ALICE), 201);
        assertThat(staffOpened.get("opened_by").asString()).isEqualTo("STAFF");
        assertThat(staffOpened.get("last_sender_type").asString()).isEqualTo("STAFF");

        // a customer cannot open a thread for somebody else, and opens as CUSTOMER
        assertThat(code(asCustomer(BOB, "POST", "/v1/inbox/threads", """
                {"customer_id":"%s","subject":"x","initial_message":"y"}""".formatted(ALICE), 422)))
                .isEqualTo("customer_mismatch");
        JsonNode bobs = asCustomer(BOB, "POST", "/v1/inbox/threads", """
                {"customer_id":"%s","subject":"Loan question","initial_message":"Can I repay early?"}""".formatted(BOB), 201);
        assertThat(bobs.get("customer_id").asString()).isEqualTo(BOB);
        assertThat(bobs.get("opened_by").asString()).isEqualTo("CUSTOMER");
        String bobThread = bobs.get("id").asString();

        String aliceThread = staffOpened.get("id").asString();
        post("/v1/inbox/threads/" + aliceThread + "/messages", """
                {"body":"Card shipped by courier, tracking ABC","is_internal_note":true}""", 201);

        // customer cannot post as STAFF or as a staff user
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/threads/" + aliceThread + "/messages", """
                {"body":"I am the bank","sender_type":"STAFF"}""", 422))).isEqualTo("sender_mismatch");
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/threads/" + aliceThread + "/messages", """
                {"body":"I am the bank","sender_user_id":"%s"}""".formatted(AGENT), 422))).isEqualTo("sender_mismatch");
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/threads/" + aliceThread + "/read", """
                {"reader_type":"STAFF"}""", 422))).isEqualTo("reader_mismatch");
        JsonNode mine = asCustomer(ALICE, "POST", "/v1/inbox/threads/" + aliceThread + "/messages", """
                {"body":"Thanks!"}""", 201);
        assertThat(mine.get("sender_type").asString()).isEqualTo("CUSTOMER");
        assertThat(mine.has("sender_user_id")).isFalse();

        // internal notes: never for customers
        assertThat(code(asCustomer(ALICE, "GET", "/v1/inbox/threads/" + aliceThread + "/messages?include_internal=true",
                null, 403))).isEqualTo("staff_only");
        JsonNode visible = asCustomer(ALICE, "GET", "/v1/inbox/threads/" + aliceThread + "/messages", null, 200).get("data");
        assertThat(visible).hasSize(2);
        visible.forEach(m -> assertThat(m.get("is_internal_note").asBoolean()).isFalse());
        assertThat(get("/v1/inbox/threads/" + aliceThread + "/messages?include_internal=true").get("data")).hasSize(3);
        assertThat(code(asCustomer(ALICE, "GET", "/v1/inbox/messages?secure_message_thread_id=" + aliceThread
                + "&is_internal_note=true", null, 403))).isEqualTo("staff_only");
        assertThat(code(asCustomer(ALICE, "GET", "/v1/inbox/messages/" + mine.get("id").asString() + "/receipts", null, 403)))
                .isEqualTo("staff_only");

        // another customer's thread does not exist for Bob
        asCustomer(BOB, "GET", "/v1/inbox/threads/" + aliceThread, null, 404);
        asCustomer(BOB, "GET", "/v1/inbox/threads/" + aliceThread + "/messages", null, 404);
        asCustomer(BOB, "POST", "/v1/inbox/threads/" + aliceThread + "/messages", "{\"body\":\"hi\"}", 404);
        asCustomer(BOB, "POST", "/v1/inbox/threads/" + aliceThread + "/read", "{}", 404);
        asCustomer(BOB, "POST", "/v1/inbox/threads/" + aliceThread + "/close", "{}", 404);
        JsonNode bobList = asCustomer(BOB, "GET", "/v1/inbox/threads?customer_id=" + ALICE, null, 200).get("data");
        assertThat(ids(bobList)).containsExactly(bobThread);
        assertThat(asCustomer(ALICE, "GET", "/v1/inbox/threads/" + aliceThread, null, 200).get("id").asString())
                .isEqualTo(aliceThread);

        // staff-only operations
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/threads/" + aliceThread + "/assign",
                "{\"assigned_user_id\":\"%s\"}".formatted(AGENT), 403))).isEqualTo("staff_only");
        assertThat(code(asCustomer(ALICE, "POST", "/v1/inbox/announcements/refresh-status", null, 403))).isEqualTo("staff_only");
        assertThat(code(asCustomer(ALICE, "GET", "/v1/inbox/announcements/active?customer_id=" + BOB + "&app=CUSTOMER_WEB",
                null, 422))).isEqualTo("customer_mismatch");
        asCustomer(ALICE, "GET", "/v1/inbox/announcements/active?customer_id=" + ALICE + "&app=CUSTOMER_WEB", null, 200);

        // customers can close their own thread; staff behaviour is unchanged
        assertThat(asCustomer(BOB, "POST", "/v1/inbox/threads/" + bobThread + "/close", "{}", 200).get("status").asString())
                .isEqualTo("CLOSED");
        assertThat(asUser(AGENT, "GET", "/v1/inbox/threads?customer_id=" + ALICE, null, 200).get("data").size())
                .isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ announcements

    @Test
    @Order(10)
    void announcementsLifecycleAndTargeting() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(code(post("/v1/inbox/announcements", """
                {"title":"x","body":"y","placement":"HOME_BANNER","starts_at":"%s","ends_at":"%s"}"""
                .formatted(now, now.minusDays(1)), 422))).isEqualTo("invalid_announcement");
        banner = post("/v1/inbox/announcements", """
                {"title":"New savings rates","body":"Rates up to 4%%","placement":"HOME_BANNER","priority":10,
                 "apps":["CUSTOMER_MOBILE"],"starts_at":"%s","languages":{"fr":{"title":"Nouveaux taux"}}}"""
                .formatted(now.minusHours(1)), 201).get("id").asString();
        targeted = post("/v1/inbox/announcements", """
                {"title":"Premier event","body":"Invitation","placement":"HOME_BANNER","priority":20,
                 "segment_ids":["%s"],"starts_at":"%s"}""".formatted(SEGMENT, now.minusHours(1)), 201).get("id").asString();
        String otherSegment = post("/v1/inbox/announcements", """
                {"title":"Students","body":"Offer","placement":"HOME_BANNER","segment_ids":["%s"],"starts_at":"%s"}"""
                .formatted(UUID.randomUUID(), now.minusHours(1)), 201).get("id").asString();
        sticky = post("/v1/inbox/announcements", """
                {"title":"Maintenance","body":"Sunday 2-4am","placement":"HOME_BANNER","priority":5,"dismissible":false,
                 "starts_at":"%s"}""".formatted(now.minusHours(1)), 201).get("id").asString();
        later = post("/v1/inbox/announcements", """
                {"title":"Coming soon","body":"Soon","placement":"LOGIN","starts_at":"%s"}"""
                .formatted(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3)), 201).get("id").asString();

        JsonNode live = post("/v1/inbox/announcements/" + banner + "/publish", null, 200);
        assertThat(live.get("status").asString()).isEqualTo("LIVE");
        assertThat(code(post("/v1/inbox/announcements/" + banner + "/publish", null, 409))).isEqualTo("invalid_transition");
        assertThat(code(patch("/v1/inbox/announcements/" + banner, "{\"title\":\"z\"}", live.get("row_version").asInt(), 409)))
                .isEqualTo("announcement_not_draft");
        post("/v1/inbox/announcements/" + targeted + "/publish", null, 200);
        post("/v1/inbox/announcements/" + otherSegment + "/publish", null, 200);
        post("/v1/inbox/announcements/" + sticky + "/publish", null, 200);
        assertThat(post("/v1/inbox/announcements/" + later + "/publish", null, 200).get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(outbox("engagement.announcement.published", banner)).isEqualTo(1);
        assertThat(outbox("engagement.announcement.live", banner)).isEqualTo(1);

        JsonNode mobile = get("/v1/inbox/announcements/active?customer_id=" + ALICE + "&app=CUSTOMER_MOBILE&language=fr").get("data");
        assertThat(ids(mobile)).containsExactly(sticky, banner, targeted);
        assertThat(mobile.get(1).get("title").asString()).isEqualTo("Nouveaux taux");
        JsonNode web = get("/v1/inbox/announcements/active?customer_id=" + ALICE + "&app=CUSTOMER_WEB").get("data");
        assertThat(ids(web)).containsExactly(sticky, targeted);
        JsonNode stranger = get("/v1/inbox/announcements/active?customer_id=" + UUID.randomUUID() + "&app=CUSTOMER_WEB").get("data");
        assertThat(ids(stranger)).as("segment lookup fails for unknown customers: targeted hidden").containsExactly(sticky);
        assertThat(send("GET", "/v1/inbox/announcements/active?app=CUSTOMER_WEB", null, null, TENANT).statusCode()).isEqualTo(400);

        assertThat(send("POST", "/v1/inbox/announcements/" + banner + "/dismiss", "{\"customer_id\":\"%s\"}".formatted(ALICE),
                key(), TENANT).statusCode()).isEqualTo(204);
        assertThat(send("POST", "/v1/inbox/announcements/" + banner + "/dismiss", "{\"customer_id\":\"%s\"}".formatted(ALICE),
                key(), TENANT).statusCode()).as("dismissal is idempotent").isEqualTo(204);
        assertThat(code(post("/v1/inbox/announcements/" + sticky + "/dismiss", "{\"customer_id\":\"%s\"}".formatted(ALICE), 422)))
                .isEqualTo("not_dismissible");
        assertThat(ids(get("/v1/inbox/announcements/active?customer_id=" + ALICE + "&app=CUSTOMER_MOBILE").get("data")))
                .containsExactly(sticky, targeted);

        JsonNode withdrawn = post("/v1/inbox/announcements/" + targeted + "/withdraw", "{\"reason\":\"event cancelled\"}", 200);
        assertThat(withdrawn.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(code(post("/v1/inbox/announcements/" + targeted + "/withdraw", "{}", 409))).isEqualTo("invalid_transition");
        assertThat(ids(get("/v1/inbox/announcements/active?customer_id=" + ALICE + "&app=CUSTOMER_MOBILE").get("data")))
                .containsExactly(sticky);
        assertThat(send("DELETE", "/v1/inbox/announcements/" + sticky, null, null, TENANT).statusCode()).isEqualTo(409);
        String draft = post("/v1/inbox/announcements", """
                {"title":"Draft","body":"d","placement":"MODAL","starts_at":"%s"}""".formatted(now), 201).get("id").asString();
        assertThat(send("DELETE", "/v1/inbox/announcements/" + draft, null, null, TENANT).statusCode()).isEqualTo(204);
    }

    @Test
    @Order(11)
    void scheduledAnnouncementGoesLiveOnRefresh() throws Exception {
        // poll instead of a fixed sleep: the announcement starts a few seconds after it was scheduled, and the
        // database clock decides (a fixed sleep was flaky under load)
        int wentLive = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (wentLive == 0 && System.nanoTime() < deadline) {
            wentLive += post("/v1/inbox/announcements/refresh-status", null, 200).get("went_live").asInt();
            if (wentLive == 0) {
                Thread.sleep(250);
            }
        }
        assertThat(wentLive).isEqualTo(1);
        assertThat(get("/v1/inbox/announcements/" + later).get("status").asString()).isEqualTo("LIVE");
        assertThat(outbox("engagement.announcement.live", later)).isEqualTo(1);
        assertThat(post("/v1/inbox/announcements/refresh-status", null, 200).get("went_live").asInt()).isZero();
        assertThat(ids(get("/v1/inbox/announcements/active?customer_id=" + ALICE + "&app=CUSTOMER_WEB&placement=LOGIN")
                .get("data"))).containsExactly(later);
    }

    // ------------------------------------------------------------------ helpers

    private List<String> ids(JsonNode data) {
        List<String> out = new java.util.ArrayList<>();
        data.forEach(n -> out.add(n.get("id").asString()));
        return out;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode asCustomer(String customer, String method, String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send(method, path, body, "GET".equals(method) ? null : key(), TENANT, customer);
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode asUser(String user, String method, String path, String body, int expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json");
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", key());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, int version, int expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "maker").header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant) throws Exception {
        return send(method, path, body, idemKey, tenant, null);
    }

    /** {@code customer} != null: relayed by the customer channel service on behalf of that customer. */
    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String customer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "maker").header("Content-Type", "application/json");
        if (customer != null) {
            b.header("X-On-Behalf-Of-Customer", customer);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int outbox(String type, String aggregateId) {
        return aggregateId == null
                ? jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                        .params(type, TENANT).query(Integer.class).single()
                : jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                        .params(type, aggregateId).query(Integer.class).single();
    }
}
