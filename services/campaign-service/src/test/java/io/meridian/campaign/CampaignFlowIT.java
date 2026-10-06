package io.meridian.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.campaign.domain.AbTesting;
import io.meridian.campaign.domain.CampaignMath;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
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
 * campaign-service end to end over HTTP against PostgreSQL 18 with row-level security. customer-service (segments,
 * marketing consents) and notification-service are stubbed through ServiceClient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampaignFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";
    static final UUID GOLD = UUID.fromString("0199a000-0000-7000-8000-00000000a0a1");
    static final UUID BLOCKED = UUID.fromString("0199a000-0000-7000-8000-00000000a0a2");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final Map<UUID, Set<UUID>> segments = new HashMap<>();
    final Map<UUID, String> consents = new HashMap<>();
    final List<JsonNode> notifications = Collections.synchronizedList(new ArrayList<>());

    final List<UUID> gold = new ArrayList<>();
    final UUID notInSegment = UUID.randomUUID();
    final UUID blocked = UUID.randomUUID();
    final UUID noConsent = UUID.randomUUID();
    final UUID consentDown = UUID.randomUUID();
    final UUID capped = UUID.randomUUID();

    String campaign;
    String variantA;
    String variantB;
    Map<String, String> allocation = new HashMap<>();

    @BeforeEach
    void stubOtherServices() {
        if (gold.isEmpty()) {
            for (int i = 0; i < 20; i++) {
                gold.add(UUID.randomUUID());
            }
            for (UUID c : gold) {
                segments.put(c, Set.of(GOLD));
                consents.put(c, "GIVEN");
            }
            segments.put(notInSegment, Set.of());
            consents.put(notInSegment, "GIVEN");
            segments.put(blocked, Set.of(GOLD, BLOCKED));
            consents.put(blocked, "GIVEN");
            segments.put(noConsent, Set.of(GOLD));
            segments.put(consentDown, Set.of(GOLD));
            consents.put(consentDown, "DOWN");
            segments.put(capped, Set.of(GOLD));
            consents.put(capped, "GIVEN");
        }
        when(serviceClient.get(eq("customer-service"), startsWith("/v1/customers/"))).thenAnswer(inv -> {
            UUID customer = UUID.fromString(((String) inv.getArgument(1)).split("/")[3]);
            StringBuilder sb = new StringBuilder("{\"data\":[");
            sb.append(segments.getOrDefault(customer, Set.of()).stream().map(s -> """
                    {"id":"%s","customer_id":"%s","customer_segment_id":"%s","valid_from":"2020-01-01"}"""
                    .formatted(UUID.randomUUID(), customer, s)).collect(Collectors.joining(",")));
            return json.readTree(sb.append("],\"has_more\":false}").toString());
        });
        when(serviceClient.get(eq("customer-service"), startsWith("/v1/consents?"))).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            UUID customer = UUID.fromString(path.substring(path.indexOf("customer_id=") + 12, path.indexOf("customer_id=") + 48));
            String state = consents.get(customer);
            if ("DOWN".equals(state)) {
                throw new ApiException(502, "upstream_error", "customer-service is not reachable");
            }
            if (state == null) {
                return json.readTree("{\"data\":[],\"has_more\":false}");
            }
            return json.readTree("""
                    {"data":[{"id":"%s","customer_id":"%s","purpose":"MARKETING","status":"GIVEN"}],"has_more":false}"""
                    .formatted(UUID.randomUUID(), customer));
        });
        when(serviceClient.post(eq("notification-service"), anyString(), any(), anyString())).thenAnswer(inv -> {
            JsonNode body = json.valueToTree(inv.getArgument(2));
            notifications.add(body);
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"QUEUED\"}");
        });
    }

    // ------------------------------------------------------------------ definition and maker-checker

    @Test
    @Order(1)
    void campaignValidationAndMakerChecker() throws Exception {
        String start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();
        assertThat(code(post("/v1/campaigns", """
                {"code":"BAD","name":"x","segment_ids":[],"channels":["PUSH"],"start_at":"%s"}""".formatted(start), 422)))
                .isEqualTo("invalid_campaign");
        assertThat(code(post("/v1/campaigns", """
                {"code":"BAD","name":"x","segment_ids":["%s"],"channels":["PUSH"],"start_at":"%s","control_group_pct":"0.6"}"""
                .formatted(GOLD, start), 422))).isEqualTo("invalid_campaign");
        assertThat(code(post("/v1/campaigns", """
                {"code":"BAD","name":"x","segment_ids":["%s"],"channels":["PUSH"],"start_at":"%s",
                 "end_at":"2020-01-01T00:00:00Z"}""".formatted(GOLD, start), 422))).isEqualTo("invalid_campaign");
        assertThat(code(post("/v1/campaigns", """
                {"code":"BAD","name":"x","segment_ids":["%s"],"channels":["PUSH"],"start_at":"%s","budget_amount":"10"}"""
                .formatted(GOLD, start), 422))).isEqualTo("invalid_campaign");

        String body = """
                {"code":"spring-promo","name":"Spring promo","segment_ids":["%s"],"exclusion_segment_ids":["%s"],
                 "channels":["PUSH","EMAIL"],"start_at":"%s","control_group_pct":"0.2","budget_amount":"0.05",
                 "currency":"GBP","cost_per_message":"0.01","frequency_cap":3}""".formatted(GOLD, BLOCKED, start);
        HttpResponse<String> created = send("POST", "/v1/campaigns", body, "cmp-create", TENANT, "maker");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode c = json.readTree(created.body());
        campaign = c.get("id").asString();
        assertThat(c.get("code").asString()).isEqualTo("SPRING-PROMO");
        assertThat(c.get("status").asString()).isEqualTo("DRAFT");
        assertThat(c.get("channels")).hasSize(2);
        HttpResponse<String> replay = send("POST", "/v1/campaigns", body, "cmp-create", TENANT, "maker");
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(campaign);

        // Variants: validation, then A (template) and B (body) on PUSH.
        assertThat(code(post("/v1/campaign-variants", """
                {"campaign_id":"%s","code":"A","traffic_share":"1.5","channel":"PUSH","body":"Hi"}""".formatted(campaign), 422)))
                .isEqualTo("invalid_variant");
        assertThat(code(post("/v1/campaign-variants", """
                {"campaign_id":"%s","code":"A","traffic_share":"0.5","channel":"SMS","body":"Hi"}""".formatted(campaign), 422)))
                .isEqualTo("invalid_variant");
        assertThat(code(post("/v1/campaigns/" + campaign + "/submit", null, 422))).isEqualTo("no_variants");
        variantA = post("/v1/campaign-variants", """
                {"campaign_id":"%s","code":"a","traffic_share":"0.5","channel":"PUSH","notification_template_code":"SPRING_A"}"""
                .formatted(campaign), 201).get("id").asString();
        variantB = post("/v1/campaign-variants", """
                {"campaign_id":"%s","code":"B","traffic_share":"0.5","channel":"PUSH","subject":"Spring","body":"Save now",
                 "deeplink":"app://offers"}""".formatted(campaign), 201).get("id").asString();
        JsonNode test = post("/v1/campaign-variants/" + variantB + "/test-send", "{\"recipient\":\"qa@example.com\"}", 200);
        assertThat(test.get("notification_id").asString()).isNotBlank();
        assertThat(notifications.getLast().get("destination").asString()).isEqualTo("qa@example.com");
        assertThat(get("/v1/campaigns/" + campaign + "/variants").get("data")).hasSize(2);

        assertThat(code(post("/v1/campaigns/" + campaign + "/approve", "{}", 409))).isEqualTo("invalid_transition");
        JsonNode submitted = post("/v1/campaigns/" + campaign + "/submit", null, 200);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.get("submitted_by").asString()).isEqualTo("maker");
        assertThat(code(patch("/v1/campaigns/" + campaign, "{\"name\":\"Renamed\"}",
                submitted.get("row_version").asInt(), 409))).isEqualTo("campaign_not_editable");
        assertThat(code(post("/v1/campaign-variants", """
                {"campaign_id":"%s","code":"C","traffic_share":"0.5","channel":"PUSH","body":"x"}""".formatted(campaign), 409)))
                .isEqualTo("campaign_not_editable");
        assertThat(code(post("/v1/campaigns/" + campaign + "/approve", "{\"comment\":\"self\"}", 422)))
                .isEqualTo("four_eyes_violation");
        JsonNode approved = json.readTree(send("POST", "/v1/campaigns/" + campaign + "/approve",
                "{\"comment\":\"ok\"}", key(), TENANT, "checker").body());
        assertThat(approved.get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker");
        assertThat(code(post("/v1/campaigns/" + campaign + "/launch", null, 422))).isEqualTo("audience_not_built");
        assertThat(outbox("engagement.campaign.submitted", campaign)).isEqualTo(1);
        assertThat(outbox("engagement.campaign.approved", campaign)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ audience

    @Test
    @Order(2)
    void audienceSelectionWithConsentExclusionsAndDeterministicAllocation() throws Exception {
        // capped customer: 3 messages in the last 7 days from other campaigns.
        for (int i = 0; i < 3; i++) {
            String other = post("/v1/campaigns", """
                    {"code":"OLD%d","name":"Old","segment_ids":["%s"],"channels":["SMS"],"start_at":"2026-01-01T00:00:00Z"}"""
                    .formatted(i, GOLD), 201).get("id").asString();
            asTenant(() -> jdbc.sql("""
                    insert into campaign_audience_member (tenant_id, campaign_id, customer_id, sent_at)
                    values (?::uuid, ?::uuid, ?, now() - interval '1 day')""").params(TENANT, other, capped).update());
        }

        List<UUID> candidates = new ArrayList<>(gold);
        candidates.addAll(List.of(notInSegment, blocked, noConsent, consentDown, capped, gold.getFirst()));
        String customerIds = ids(candidates);

        JsonNode preview = post("/v1/campaigns/" + campaign + "/preview-audience", "{\"customer_ids\":" + customerIds + "}", 200);
        assertThat(preview.get("persisted").asBoolean()).isFalse();
        assertThat(preview.get("selected").asInt()).isEqualTo(20);
        assertThat(get("/v1/campaigns/" + campaign + "/audience").get("data")).isEmpty();

        JsonNode built = post("/v1/campaigns/" + campaign + "/build-audience", "{\"customer_ids\":" + customerIds + "}", 200);
        assertThat(built.get("candidates").asInt()).isEqualTo(25);
        assertThat(built.get("selected").asInt()).isEqualTo(20);
        JsonNode excluded = built.get("excluded");
        assertThat(excluded.get("not_in_segment").asInt()).isEqualTo(1);
        assertThat(excluded.get("excluded_segment").asInt()).isEqualTo(1);
        assertThat(excluded.get("no_marketing_consent").asInt()).isEqualTo(1);
        assertThat(excluded.get("consent_check_failed").asInt()).isEqualTo(1);
        assertThat(excluded.get("frequency_cap").asInt()).isEqualTo(1);

        // Allocation matches the pure hashing function exactly.
        Map<String, Integer> expected = new HashMap<>();
        for (UUID c : gold) {
            AbTesting.Allocation a = AbTesting.allocate(UUID.fromString(campaign), c, new BigDecimal("0.2"),
                    Map.of("A", new BigDecimal("0.5"), "B", new BigDecimal("0.5")));
            String group = a.control() ? "CONTROL" : a.variantCode();
            expected.merge(group, 1, Integer::sum);
            allocation.put(c.toString(), group);
        }
        assertThat(built.get("control").asInt()).isEqualTo(expected.getOrDefault("CONTROL", 0));
        assertThat(built.get("variants").path("A").asInt(0)).isEqualTo(expected.getOrDefault("A", 0));
        assertThat(built.get("variants").path("B").asInt(0)).isEqualTo(expected.getOrDefault("B", 0));
        JsonNode members = get("/v1/campaign-audience-members?campaign_id=" + campaign + "&limit=100").get("data");
        assertThat(members).hasSize(20);
        for (JsonNode m : members) {
            String group = m.get("is_control").asBoolean() ? "CONTROL" : m.get("variant_code").asString();
            assertThat(group).isEqualTo(allocation.get(m.get("customer_id").asString()));
            if (!m.get("is_control").asBoolean()) {
                assertThat(m.get("campaign_variant_id").asString()).isIn(variantA, variantB);
                assertThat(m.get("channel").asString()).isEqualTo("PUSH");
            }
            assertThat(absent(m, "marketing_consent_snapshot_id")).isFalse();
        }

        // Rebuild is stable: nobody moves, nobody is added twice.
        JsonNode rebuilt = post("/v1/campaigns/" + campaign + "/build-audience", "{\"customer_ids\":" + ids(gold) + "}", 200);
        assertThat(rebuilt.get("already_members").asInt()).isEqualTo(20);
        assertThat(rebuilt.get("selected").asInt()).isZero();
        assertThat(rebuilt.get("audience_size").asInt()).isEqualTo(20);

        JsonNode snapshots = get("/v1/campaign-consent-snapshots?campaign_id=" + campaign + "&is_permitted=false&limit=100").get("data");
        Set<String> reasons = new HashSet<>();
        snapshots.forEach(s -> reasons.add(s.get("reason").asString()));
        assertThat(reasons).containsExactlyInAnyOrder("no_marketing_consent", "consent_check_failed", "frequency_cap");
        assertThat(get("/v1/campaigns/" + campaign).get("audience_size").asInt()).isEqualTo(20);
        assertThat(outbox("engagement.campaign.audience_built", campaign)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ sending, budget, engagement

    @Test
    @Order(3)
    void launchDispatchBudgetCapAndEngagement() throws Exception {
        int before = notifications.size();
        JsonNode launched = post("/v1/campaigns/" + campaign + "/launch", null, 200);
        // budget 0.05 at 0.01 per message: five messages, then the campaign pauses itself.
        assertThat(launched.get("status").asString()).isEqualTo("PAUSED");
        assertThat(launched.get("status_reason").asString()).isEqualTo("budget_exhausted");
        assertThat(new BigDecimal(launched.get("spent_amount").asString())).isEqualByComparingTo("0.05");
        assertThat(absent(launched, "launched_at")).isFalse();
        assertThat(notifications.size() - before).isEqualTo(5);
        JsonNode sentMsg = notifications.getLast();
        assertThat(sentMsg.get("category").asString()).isEqualTo("MARKETING");
        assertThat(sentMsg.get("template_code").asString()).isIn("SPRING_A", "CAMPAIGN_MESSAGE");
        assertThat(code(post("/v1/campaigns/" + campaign + "/resume", null, 422))).isEqualTo("budget_exhausted");
        assertThat(code(post("/v1/campaigns/" + campaign + "/dispatch", null, 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/campaigns/" + campaign + "/build-audience", "{\"customer_ids\":" + ids(gold) + "}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(outbox("engagement.campaign.started", campaign)).isEqualTo(1);
        assertThat(outbox("engagement.campaign.paused", campaign)).isEqualTo(1);

        JsonNode members = get("/v1/campaign-audience-members?campaign_id=" + campaign + "&limit=100").get("data");
        List<JsonNode> sent = new ArrayList<>();
        JsonNode control = null;
        for (JsonNode m : members) {
            if (!absent(m, "sent_at")) {
                sent.add(m);
                assertThat(absent(m, "notification_id")).isFalse();
            }
            if (m.get("is_control").asBoolean()) {
                control = m;
                assertThat(absent(m, "sent_at")).isTrue();
            }
        }
        assertThat(sent).hasSize(5);
        String s1 = sent.get(0).get("id").asString();
        assertThat(post("/v1/campaign-audience-members/" + s1 + "/opened", null, 200).get("opened_at").isNull()).isFalse();
        JsonNode clicked = post("/v1/campaign-audience-members/" + s1 + "/clicked", null, 200);
        assertThat(absent(clicked, "clicked_at")).isFalse();
        JsonNode conv = post("/v1/campaign-audience-members/" + s1 + "/converted", "{\"revenue\":\"100.00\"}", 200);
        assertThat(new BigDecimal(conv.get("revenue").asString())).isEqualByComparingTo("100");
        String convertedAt = conv.get("converted_at").asString();
        assertThat(post("/v1/campaign-audience-members/" + s1 + "/converted", "{\"revenue\":\"5\"}", 200)
                .get("converted_at").asString()).isEqualTo(convertedAt);
        post("/v1/campaign-audience-members/" + sent.get(1).get("id").asString() + "/unsubscribe", null, 200);
        if (control != null) {
            String cid = control.get("id").asString();
            assertThat(code(post("/v1/campaign-audience-members/" + cid + "/opened", null, 409))).isEqualTo("message_not_sent");
            post("/v1/campaign-audience-members/" + cid + "/converted", "{}", 200);
        }
        assertThat(outbox("engagement.campaign_audience.converted", null)).isEqualTo(control == null ? 1 : 2);
        assertThat(outbox("engagement.campaign_audience.unsubscribed", null)).isEqualTo(1);

        JsonNode perf = get("/v1/campaigns/" + campaign + "/performance");
        long controlN = members.findValues("is_control").stream().filter(JsonNode::asBoolean).count();
        long treatedN = 20 - controlN;
        assertThat(perf.get("treated").get("members").asLong()).isEqualTo(treatedN);
        assertThat(perf.get("treated").get("sent").asLong()).isEqualTo(5);
        assertThat(perf.get("treated").get("converted").asLong()).isEqualTo(1);
        BigDecimal uplift = CampaignMath.uplift(1, treatedN, control == null ? 0 : 1, controlN);
        if (uplift == null) {
            assertThat(absent(perf.get("treated"), "uplift_pct")).isTrue();
        } else {
            assertThat(new BigDecimal(perf.get("treated").get("uplift_pct").asString())).isEqualByComparingTo(uplift);
        }
    }

    @Test
    @Order(4)
    void metricsRollupIsIdempotentPerBusinessDate() throws Exception {
        LocalDate today = asTenant(() -> jdbc.sql("select (now() at time zone 'utc')::date").query(LocalDate.class).single());
        JsonNode run = post("/v1/campaign-job-runs/metrics-rollup?business_date=" + today, null, 200);
        assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("COMPLETED");
        assertThat(run.get("replayed").asBoolean()).isFalse();
        JsonNode metrics = get("/v1/campaigns/" + campaign + "/metrics").get("data");
        int variants = (int) get("/v1/campaign-audience-members?campaign_id=" + campaign + "&is_control=false&limit=100")
                .get("data").findValues("variant_code").stream().map(JsonNode::asString).distinct().count();
        assertThat(metrics).hasSize(variants + 1);
        JsonNode total = null;
        for (JsonNode m : metrics) {
            if (absent(m, "campaign_variant_id")) {
                total = m;
            }
        }
        assertThat(total).isNotNull();
        assertThat(total.get("sent").asInt()).isEqualTo(5);
        assertThat(total.get("converted").asInt()).isEqualTo(1);
        assertThat(total.get("unsubscribed").asInt()).isEqualTo(1);
        assertThat(total.get("audience").asInt()).isEqualTo(20);
        assertThat(new BigDecimal(total.get("cost").asString())).isEqualByComparingTo("0.05");
        assertThat(new BigDecimal(total.get("ctr").asString())).isEqualByComparingTo("0.2");

        JsonNode again = post("/v1/campaign-job-runs/metrics-rollup?business_date=" + today, null, 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(get("/v1/campaigns/" + campaign + "/metrics").get("data")).hasSize(variants + 1);
        assertThat(get("/v1/campaign-job-runs?job=METRICS_ROLLUP").get("data")).hasSize(1);
    }

    @Test
    @Order(5)
    void lifecycleJobLaunchesDueCampaignsAndCompleteCloses() throws Exception {
        String due = post("/v1/campaigns", """
                {"code":"DUE","name":"Due now","segment_ids":["%s"],"channels":["IN_APP"],"requires_marketing_consent":false,
                 "start_at":"%s"}""".formatted(GOLD, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)), 201).get("id").asString();
        post("/v1/campaign-variants", """
                {"campaign_id":"%s","code":"A","traffic_share":"1","channel":"IN_APP","body":"Hello"}""".formatted(due), 201);
        post("/v1/campaigns/" + due + "/submit", null, 200);
        send("POST", "/v1/campaigns/" + due + "/approve", "{}", key(), TENANT, "checker");
        JsonNode built = post("/v1/campaigns/" + due + "/build-audience", "{\"customer_ids\":" + ids(gold.subList(0, 3)) + "}", 200);
        assertThat(built.get("selected").asInt()).isEqualTo(3);
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        JsonNode run = post("/v1/campaign-job-runs/lifecycle?business_date=" + date, null, 200);
        assertThat(run.get("counts").get("launched").asInt()).isEqualTo(1);
        assertThat(get("/v1/campaigns/" + due).get("status").asString()).isEqualTo("RUNNING");
        JsonNode completed = post("/v1/campaigns/" + due + "/complete", null, 200);
        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(code(post("/v1/campaigns/" + due + "/cancel", "{\"reason\":\"late\"}", 409))).isEqualTo("invalid_transition");
        assertThat(post("/v1/campaign-job-runs/lifecycle?business_date=" + date, null, 200).get("replayed").asBoolean()).isTrue();
        assertThat(outbox("engagement.campaign.completed", due)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ offers

    @Test
    @Order(6)
    void offersAssignmentWithConsentAndResponses() throws Exception {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String later = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).toString();
        assertThat(code(post("/v1/offers", """
                {"code":"BAD","name":"x","offer_type":"FEE_WAIVER","terms":{},"valid_from":"%s","valid_to":"2020-01-01T00:00:00Z"}"""
                .formatted(now), 422))).isEqualTo("invalid_offer");
        String offer = post("/v1/offers", """
                {"code":"loan-pa","name":"Pre-approved loan","offer_type":"PRE_APPROVED_LOAN","terms":{"rate":"0.079"},
                 "max_amount":"5000","currency":"GBP","max_assignments":3,"valid_from":"%s","valid_to":"%s",
                 "eligibility_rules":{"segment_ids":["%s"],"min_score":0.5}}"""
                .formatted(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), later, GOLD), 201).get("id").asString();
        String assignBody = """
                {"customer_ids":%s,"campaign_id":"%s","score":"0.7","personalised_terms":{"amount":"3000"}}"""
                .formatted(ids(List.of(gold.get(0), gold.get(1), noConsent, notInSegment, gold.get(2), gold.get(3))), campaign);
        assertThat(code(post("/v1/offers/" + offer + "/assign", assignBody, 409))).isEqualTo("offer_not_active");
        post("/v1/offers/" + offer + "/activate", null, 200);
        JsonNode result = post("/v1/offers/" + offer + "/assign", assignBody, 200);
        assertThat(result.get("assigned").asInt()).isEqualTo(3);
        Map<String, String> skipped = new HashMap<>();
        result.get("skipped").forEach(s -> skipped.put(s.get("customer_id").asString(), s.get("reason").asString()));
        assertThat(skipped).containsEntry(noConsent.toString(), "no_marketing_consent")
                .containsEntry(notInSegment.toString(), "not_in_segment")
                .containsEntry(gold.get(3).toString(), "offer_cap_reached");
        JsonNode lowScore = post("/v1/offers/" + offer + "/assign", """
                {"customer_ids":["%s"],"score":"0.2"}""".formatted(gold.get(0)), 200);
        assertThat(lowScore.get("skipped").get(0).get("reason").asString()).isEqualTo("already_assigned");
        assertThat(get("/v1/offers/" + offer).get("assigned_count").asInt()).isEqualTo(3);
        assertThat(get("/v1/campaign-consent-snapshots?campaign_offer_id=" + offer + "&limit=50").get("data")).hasSize(4);

        Map<String, String> byCustomer = new HashMap<>();
        get("/v1/offer-assignments?campaign_offer_id=" + offer).get("data")
                .forEach(a -> byCustomer.put(a.get("customer_id").asString(), a.get("id").asString()));
        String a1 = byCustomer.get(gold.get(0).toString());
        String a2 = byCustomer.get(gold.get(1).toString());
        String a3 = byCustomer.get(gold.get(2).toString());
        JsonNode first = get("/v1/offer-assignments/" + a1);
        assertThat(first.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(absent(first, "consent_snapshot_id")).isFalse();
        assertThat(first.get("expires_at").asString()).isNotBlank();

        assertThat(post("/v1/offer-assignments/" + a1 + "/shown", null, 200).get("status").asString()).isEqualTo("SHOWN");
        assertThat(post("/v1/offer-assignments/" + a1 + "/clicked", null, 200).get("status").asString()).isEqualTo("CLICKED");
        assertThat(post("/v1/offer-assignments/" + a1 + "/shown", null, 200).get("status").asString()).isEqualTo("CLICKED");
        String loanApp = UUID.randomUUID().toString();
        JsonNode accepted = post("/v1/offer-assignments/" + a1 + "/accept", """
                {"resulting_object_type":"LOAN_APPLICATION","resulting_object_id":"%s"}""".formatted(loanApp), 200);
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("resulting_object_id").asString()).isEqualTo(loanApp);
        assertThat(code(post("/v1/offer-assignments/" + a1 + "/accept", "{}", 409))).isEqualTo("invalid_transition");
        assertThat(get("/v1/offers/" + offer).get("accepted_count").asInt()).isEqualTo(1);
        JsonNode member = get("/v1/campaign-audience-members?campaign_id=" + campaign + "&customer_id=" + gold.get(0)).get("data");
        if (!member.isEmpty()) {
            assertThat(absent(member.get(0), "converted_at")).isFalse();
        }

        JsonNode declined = post("/v1/offer-assignments/" + a2 + "/decline", "{\"reason\":\"Not now\"}", 200);
        assertThat(declined.get("status").asString()).isEqualTo("DECLINED");
        assertThat(declined.get("decline_reason").asString()).isEqualTo("Not now");
        assertThat(code(post("/v1/offer-assignments/" + a2 + "/shown", null, 409))).isEqualTo("invalid_transition");

        JsonNode open = get("/v1/offer-assignments/for-customer?customer_id=" + gold.get(2)).get("data");
        assertThat(open).hasSize(1);
        assertThat(get("/v1/offer-assignments/for-customer?customer_id=" + gold.get(1)).get("data")).isEmpty();

        // Expired assignment: the response is refused and the assignment is committed as EXPIRED.
        asTenant(() -> jdbc.sql("update offer_assignment set expires_at = now() - interval '1 minute' where id = ?::uuid")
                .param(a3).update());
        assertThat(code(post("/v1/offer-assignments/" + a3 + "/accept", "{}", 409))).isEqualTo("offer_expired");
        assertThat(get("/v1/offer-assignments/" + a3).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(outbox("engagement.offer.assigned", null)).isEqualTo(3);
        assertThat(outbox("engagement.offer.accepted", a1)).isEqualTo(1);
        assertThat(outbox("engagement.offer.declined", a2)).isEqualTo(1);
        assertThat(outbox("engagement.offer.expired", a3)).isEqualTo(1);
        assertThat(outbox("engagement.offer.shown", a1)).isEqualTo(1);
        assertThat(outbox("engagement.offer.clicked", a1)).isEqualTo(1);
    }

    @Test
    @Order(7)
    void withdrawCascadeAndOfferExpiryJob() throws Exception {
        String from = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString();
        String to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(10).toString();
        String o2 = post("/v1/offers", """
                {"code":"FEE-WAIVE","name":"Fee waiver","offer_type":"FEE_WAIVER","terms":{"months":3},"valid_from":"%s","valid_to":"%s"}"""
                .formatted(from, to), 201).get("id").asString();
        post("/v1/offers/" + o2 + "/activate", null, 200);
        assertThat(post("/v1/offers/" + o2 + "/assign", "{\"customer_ids\":" + ids(gold.subList(4, 6)) + "}", 200)
                .get("assigned").asInt()).isEqualTo(2);
        JsonNode withdrawn = post("/v1/offers/" + o2 + "/withdraw", "{\"reason\":\"Pricing change\"}", 200);
        assertThat(withdrawn.get("status").asString()).isEqualTo("WITHDRAWN");
        get("/v1/offer-assignments?campaign_offer_id=" + o2).get("data")
                .forEach(a -> assertThat(a.get("status").asString()).isEqualTo("WITHDRAWN"));
        assertThat(outbox("engagement.offer.withdrawn", null)).isEqualTo(2);
        assertThat(code(post("/v1/offers/" + o2 + "/activate", null, 409))).isEqualTo("invalid_transition");

        String o3 = post("/v1/offers", """
                {"code":"CARD-UP","name":"Card upgrade","offer_type":"CARD_UPGRADE","terms":{},"valid_from":"%s","valid_to":"%s"}"""
                .formatted(from, to), 201).get("id").asString();
        post("/v1/offers/" + o3 + "/activate", null, 200);
        post("/v1/offers/" + o3 + "/assign", "{\"customer_ids\":[\"" + gold.get(7) + "\"]}", 200);
        asTenant(() -> jdbc.sql("update campaign_offer set valid_to = now() - interval '1 minute' where id = ?::uuid")
                .param(o3).update());
        JsonNode run = post("/v1/campaign-job-runs/offer-expiry?business_date=" + LocalDate.now(ZoneOffset.UTC), null, 200);
        assertThat(run.get("counts").get("offers_expired").asInt()).isEqualTo(1);
        assertThat(run.get("counts").get("assignments_expired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/offers/" + o3).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(get("/v1/offer-assignments?campaign_offer_id=" + o3).get("data").get(0).get("status").asString())
                .isEqualTo("EXPIRED");
        assertThat(outbox("engagement.campaign_offer.expired", o3)).isEqualTo(1);
    }

    @Test
    @Order(8)
    void tenantIsolationAndCancel() throws Exception {
        HttpResponse<String> foreign = send("GET", "/v1/campaigns/" + campaign, null, null, OTHER_TENANT, "reader");
        assertThat(foreign.statusCode()).isEqualTo(404);
        HttpResponse<String> list = send("GET", "/v1/offers", null, null, OTHER_TENANT, "reader");
        assertThat(json.readTree(list.body()).get("data")).isEmpty();
        HttpResponse<String> act = send("POST", "/v1/campaigns/" + campaign + "/cancel", "{\"reason\":\"x\"}", key(),
                OTHER_TENANT, "maker");
        assertThat(act.statusCode()).isEqualTo(404);
        JsonNode cancelled = post("/v1/campaigns/" + campaign + "/cancel", "{\"reason\":\"Season over\"}", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(outbox("engagement.campaign.cancelled", campaign)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static String ids(List<UUID> ids) {
        return ids.stream().map(u -> "\"" + u + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private static boolean absent(JsonNode n, String field) {
        return n.path(field).isMissingNode() || n.path(field).isNull();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT, "maker");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
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
        HttpResponse<String> r = send("GET", path, null, null, TENANT, "reader");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int outbox(String type, String aggregateId) throws Exception {
        return asTenant(() -> aggregateId == null
                ? jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                        .params(type, TENANT).query(Integer.class).single()
                : jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                        .params(type, aggregateId).query(Integer.class).single());
    }
}
