package io.meridian.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenant starter packs end to end over HTTP (PostgreSQL 18 with RLS for runs and the item registry), with every
 * owning service replaced by a stateful fake behind the mocked {@link ServiceClient}: the operator applies the
 * GENERIC pack to another tenant (service identity acting for it), items are created in dependency order and
 * maker-checker items are submitted, never approved; a re-run is a no-op; after the checker approves, resuming
 * publishes and activates what was waiting; a failure that loses a committed response is resumed by replaying the
 * same Idempotency-Key; a newer pack version upgrades only pack-owned items that changed; provisioning applies a
 * pack asynchronously; dry runs call nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.listener-enabled=false"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StarterPackFlowIT {
    static final String OPERATOR_TENANT = "0199b000-0000-7000-8000-0000000000f1";
    static final String OPERATOR = "ops-admin";
    static final String CHECKER = "ops-checker";
    static final String PARAMS = """
            {"pack_code":"GENERIC","parameters":{"alert_channels":["EMAIL","IN_APP"],"timezone":"Europe/Dublin"}}""";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient serviceClient;

    static final String UUIDS = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    final HttpClient http = HttpClient.newHttpClient();
    FakePlatform fake;
    UUID bank;
    String bankRun;

    @BeforeAll
    void createFake() {
        fake = new FakePlatform(json);
    }

    @BeforeEach
    void wireFake() {
        when(serviceClient.post(anyString(), anyString(), any(), nullable(String.class)))
                .thenAnswer(i -> fake.handle(i.getArgument(0), "POST", i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> fake.handle(i.getArgument(0), "GET", i.getArgument(1), null, null));
        when(serviceClient.patch(anyString(), anyString(), any(), nullable(String.class)))
                .thenAnswer(i -> fake.handle(i.getArgument(0), "PATCH", i.getArgument(1), i.getArgument(2),
                        i.getArgument(3) == null ? null : "if-match:" + i.getArgument(3)));
        when(serviceClient.send(anyString(), anyString(), anyString(), any(), nullable(String.class)))
                .thenAnswer(i -> fake.handle(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4)));
    }

    @Test
    @Order(1)
    void catalogListsThePacksWithParametersAndSteps() throws Exception {
        JsonNode cat = call("GET", "/v1/starter-pack-runs/catalog", null, OPERATOR_TENANT, OPERATOR, 200);
        Set<String> codes = new HashSet<>();
        cat.get("data").forEach(p -> codes.add(p.get("code").asString()));
        assertThat(codes).contains("GENERIC", "KE", "TEST_MINI");
        JsonNode ke = find(cat.get("data"), "code", "KE");
        assertThat(ke.at("/parameters/currency/default").asString()).isEqualTo("KES");
        assertThat(ke.get("notice").asString()).contains("DEFAULT");
        JsonNode mini = find(cat.get("data"), "code", "TEST_MINI");
        assertThat(mini.get("versions").toString()).isEqualTo("[\"2026.1.0\",\"2026.2.0\"]");
        JsonNode full = call("GET", "/v1/starter-pack-runs/catalog?pack_code=generic", null, OPERATOR_TENANT, OPERATOR, 200);
        assertThat(full.get("code").asString()).isEqualTo("GENERIC");
        assertThat(full.get("steps").size()).isEqualTo(13);

        JsonNode unknown = call("POST", "/v1/starter-pack-runs/apply", "{\"pack_code\":\"NOPE\"}", OPERATOR_TENANT, OPERATOR, 422);
        assertThat(unknown.get("code").asString()).isEqualTo("unknown_starter_pack");
        JsonNode badVersion = call("POST", "/v1/starter-pack-runs/apply", "{\"pack_code\":\"GENERIC\",\"version\":\"1999.1.0\"}",
                OPERATOR_TENANT, OPERATOR, 422);
        assertThat(badVersion.get("code").asString()).isEqualTo("unknown_starter_pack_version");
        JsonNode badParam = call("POST", "/v1/starter-pack-runs/apply", "{\"pack_code\":\"GENERIC\",\"parameters\":{\"colour\":\"red\"}}",
                OPERATOR_TENANT, OPERATOR, 422);
        assertThat(badParam.get("code").asString()).isEqualTo("unknown_parameter");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    @Order(2)
    void operatorAppliesThePackInDependencyOrderAndSubmitsMakerCheckerItems() throws Exception {
        bank = UUID.fromString(call("POST", "/v1/platform/tenants", """
                {"code":"sp-bank","name":"SP Bank","legal_name":"SP Bank plc","tenant_type":"BANK","home_region":"eu-west-1",
                 "default_currency":"EUR","default_country":"IE","primary_contact_email":"ops@sp.example"}""",
                OPERATOR_TENANT, OPERATOR, 201).get("id").asString());

        JsonNode run = call("POST", "/v1/platform/tenants/" + bank + "/apply-starter-pack",
                PARAMS, OPERATOR_TENANT, OPERATOR, 200);
        bankRun = run.get("id").asString();
        assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.get("trigger").asString()).isEqualTo("OPERATOR");
        assertThat(run.get("pack_version").asString()).isEqualTo("2026.10.0");
        assertThat(run.get("failed_count").asInt()).isZero();
        assertThat(run.get("results").size()).isEqualTo(13);
        // tenant facts fill the parameters (the bank's currency, country and name); the request adds the rest
        assertThat(run.at("/parameters/currency").asString()).isEqualTo("EUR");
        assertThat(run.at("/parameters/timezone").asString()).isEqualTo("Europe/Dublin");

        // every call was made for the new tenant (X-Tenant-Id delegation) as the operator, never for the operator's tenant
        List<FakePlatform.Call> calls = fake.calls(bank);
        assertThat(calls).isNotEmpty().allSatisfy(c -> assertThat(c.principal()).isEqualTo(OPERATOR));
        assertThat(fake.calls).allSatisfy(c -> assertThat(c.tenant()).isEqualTo(bank));
        assertThat(calls).filteredOn(c -> "POST".equals(c.method())).allSatisfy(c -> assertThat(c.key()).startsWith("sp-generic-"));

        // dependency order
        List<String> writes = fake.writes(bank).stream().map(c -> c.method() + " " + c.path().replaceAll(UUIDS, "{id}")).toList();
        assertBefore(writes, "POST /v1/legal-entities", "POST /v1/approval-policies");
        assertBefore(writes, "POST /v1/approval-policies", "POST /v1/ledgers");
        assertBefore(writes, "POST /v1/ledgers", "POST /v1/ledger-accounts");
        assertBefore(writes, "POST /v1/ledger-accounts", "POST /v1/config/entries");
        assertBefore(writes, "POST /v1/config/entries", "POST /v1/wallets");
        assertBefore(writes, "POST /v1/products", "POST /v1/product-versions");
        assertBefore(writes, "POST /v1/product-versions", "POST /v1/interest-rate-configs");
        assertBefore(writes, "POST /v1/interest-rate-configs", "POST /v1/product-versions/{id}/submit");
        assertBefore(writes, "POST /v1/commission-plans", "POST /v1/commission-rules");
        assertBefore(writes, "POST /v1/commission-rules", "POST /v1/commission-plans/{id}/submit");
        assertBefore(writes, "POST /v1/operations/eod-jobs/install-standard-pack", "PATCH /v1/operations/eod-jobs/{id}");

        // what was created in the owning services
        assertThat(fake.all(bank, "/v1/legal-entities")).singleElement().satisfies(le -> {
            assertThat(le.get("base_currency").asString()).isEqualTo("EUR");
            assertThat(le.get("legal_name").asString()).isEqualTo("SP Bank plc");
        });
        String ledger = fake.all(bank, "/v1/ledgers").getFirst().get("id").asString();
        assertThat(fake.all(bank, "/v1/ledger-accounts")).hasSize(37).allSatisfy(a -> assertThat(a.get("ledger_id").asString()).isEqualTo(ledger));
        assertThat(fake.all(bank, "/v1/config/entries")).hasSize(16).allSatisfy(e -> {
            assertThat(e.get("namespace").asString()).isIn("ledger", "account_numbering");
            assertThat(e.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        });
        // account numbering of the main legal entity (deposit-service): keyed by its id, scheme from its country
        String mainEntity = fake.all(bank, "/v1/legal-entities").getFirst().get("id").asString();
        assertThat(fake.all(bank, "/v1/config/entries").stream().filter(e -> e.get("namespace").asString().equals("account_numbering")))
                .singleElement().satisfies(e -> {
                    assertThat(e.get("config_key").asString()).isEqualTo("le-" + mainEntity);
                    assertThat(e.at("/value/scheme").asString()).isEqualTo("AUTO");
                });
        // the public sign-up journey: a DRAFT with typed fields for the bank's entity and products, published by a checker
        String entityId = fake.all(bank, "/v1/legal-entities").getFirst().get("id").asString();
        Set<String> productIds = fake.all(bank, "/v1/products").stream().map(p -> p.get("id").asString()).collect(Collectors.toSet());
        assertThat(fake.all(bank, "/v1/onboarding/journeys")).singleElement().satisfies(j -> {
            assertThat(j.get("code").asString()).isEqualTo("RETAIL_ONLINE");
            assertThat(j.get("status").asString()).isEqualTo("DRAFT");
            assertThat(j.get("country").asString()).isEqualTo("IE");
            assertThat(j.get("legal_entity_id").asString()).isEqualTo(entityId);
            assertThat(ids(j.get("product_ids"))).hasSize(2).allSatisfy(id -> assertThat(productIds).contains(id));
            assertThat(j.at("/definition/steps/0/type").asString()).isEqualTo("PHONE_VERIFICATION");
            assertThat(j.at("/definition/steps/0/fields/0/type").asString()).isEqualTo("PHONE");
        });
        JsonNode journey = item(run, "onboarding", "journey_retail_online");
        assertThat(journey.get("outcome").asString()).isEqualTo("CREATED");
        assertThat(journey.get("status").asString()).isEqualTo("AWAITING_ACTIVATION");
        assertThat(journey.get("pending_action").asString()).contains("/publish by a user other than " + OPERATOR);
        JsonNode payment = fake.all(bank, "/v1/config/entries").stream()
                .filter(e -> e.get("config_key").asString().equals("payment-service")).findFirst().orElseThrow();
        Set<String> accountIds = fake.all(bank, "/v1/ledger-accounts").stream().map(a -> a.get("id").asString()).collect(Collectors.toSet());
        assertThat(accountIds).contains(payment.at("/value/gl/clearing").asString(), payment.at("/value/gl/fee_income").asString());
        // fx-service routing: currency codes as map keys, one position and one clearing account per fx currency
        JsonNode fx = fake.all(bank, "/v1/config/entries").stream()
                .filter(e -> e.get("config_key").asString().equals("fx-service")).findFirst().orElseThrow();
        Map<String, JsonNode> accountsById = fake.all(bank, "/v1/ledger-accounts").stream()
                .collect(Collectors.toMap(a -> a.get("id").asString(), a -> a));
        assertThat(fx.at("/value/position_accounts").propertyNames()).containsExactly("USD", "EUR", "GBP");
        assertThat(fx.at("/value/clearing_accounts").propertyNames()).containsExactly("USD", "EUR", "GBP");
        for (String ccy : List.of("USD", "EUR", "GBP")) {
            JsonNode position = accountsById.get(fx.at("/value/position_accounts/" + ccy).asString());
            JsonNode clearing = accountsById.get(fx.at("/value/clearing_accounts/" + ccy).asString());
            assertThat(position.get("code").asString()).isEqualTo("1400-" + ccy);
            assertThat(position.get("currency").asString()).isEqualTo(ccy);
            assertThat(clearing.get("code").asString()).isEqualTo("1410-" + ccy);
            assertThat(clearing.get("currency").asString()).isEqualTo(ccy);
        }
        assertThat(item(run, "ledger", "ledger_book/fx_position.eur").get("status").asString()).isEqualTo("DONE");
        assertThat(fake.all(bank, "/v1/wallet-fee-rules")).hasSize(6);
        assertThat(fake.all(bank, "/v1/wallet-tier-limits")).hasSize(4);
        assertThat(fake.all(bank, "/v1/approval-policies")).hasSize(12).allSatisfy(p -> assertThat(p.get("allow_self_approval").asBoolean()).isFalse());
        // payment alerts use the pack's alert_channels; the new-device security alert has its own fixed channels
        assertThat(fake.all(bank, "/v1/notification-rules")).allSatisfy(r -> assertThat(r.get("channels").toString())
                .isEqualTo("NEW_DEVICE_SIGN_IN".equals(r.get("template_code").asString())
                        ? "[\"PUSH\",\"EMAIL\",\"SMS\"]" : "[\"EMAIL\",\"IN_APP\"]"));
        // LEDGER_EOD closes every book of the tenant (no ledger_id pinned) and is active
        assertThat(fake.all(bank, "/v1/operations/eod-jobs")).singleElement().satisfies(j -> {
            assertThat(j.get("parameters").isEmpty()).isTrue();
            assertThat(j.get("is_active").asBoolean()).isTrue();
        });
        assertThat(fake.all(bank, "/v1/operations/eod-trigger-policies")).singleElement()
                .satisfies(p -> assertThat(p.get("time_zone").asString()).isEqualTo("Europe/Dublin"));

        // maker-checker: submitted, never approved; requests listed in the run; the operator is the maker
        List<String> pendingApprovals = fake.all(bank, FakePlatform.APPROVALS).stream().map(r -> r.get("id").asString()).toList();
        assertThat(pendingApprovals).hasSize(16 + 3 + 1 + 15); // config entries (ledger routing incl. wallet and fx, account numbering), product versions, plan, templates
        assertThat(fake.all(bank, FakePlatform.APPROVALS)).allSatisfy(r -> {
            assertThat(r.get("status").asString()).isEqualTo("PENDING");
            assertThat(r.get("maker").asString()).isEqualTo(OPERATOR);
        });
        assertThat(ids(run.get("approval_request_ids"))).containsExactlyInAnyOrderElementsOf(pendingApprovals);
        assertThat(writes).noneMatch(w -> w.contains("/approve") || w.contains("/v1/risk/limits/rules/{id}/activate")
                || w.contains("/v1/product-versions/{id}/publish") || w.contains("/v1/wallets/{id}/activate")
                || w.contains("/v1/onboarding/journeys/{id}/publish"));
        JsonNode limits = step(run, "limits");
        assertThat(limits.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(limits.get("items")).hasSize(5).allSatisfy(i -> {
            assertThat(i.get("outcome").asString()).isEqualTo("CREATED");
            assertThat(i.get("status").asString()).isEqualTo("AWAITING_ACTIVATION");
            assertThat(i.get("pending_action").asString()).contains("/activate by a user other than " + OPERATOR);
        });
        JsonNode treasury = item(run, "wallets", "treasury_wallet");
        assertThat(treasury.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(treasury.get("message").asString()).contains("waits for routing_default, routing_wallet_service");
        JsonNode version = item(run, "products", "savings/version");
        assertThat(version.get("outcome").asString()).isEqualTo("CREATED");
        assertThat(version.get("resource_status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(version.get("approval_request_ids")).hasSize(1);
        assertThat(item(run, "ledger", "ledger_book/cash").get("status").asString()).isEqualTo("DONE");

        // the registry is readable by the tenant, not by others
        JsonNode items = call("GET", "/v1/starter-pack-items?pack_code=GENERIC&limit=200", null, bank.toString(), "tenant-admin", 200);
        assertThat(items.get("data").size()).isGreaterThan(100);
        assertThat(items.get("data")).allSatisfy(i -> assertThat(i.get("is_pack_owned").asBoolean()).isTrue());
        assertThat(call("GET", "/v1/starter-pack-items?pack_code=GENERIC", null, OPERATOR_TENANT, OPERATOR, 200).get("data")).isEmpty();
        assertThat(call("GET", "/v1/starter-pack-runs/" + bankRun, null, bank.toString(), "tenant-admin", 200)
                .get("status").asString()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    @Order(3)
    void reRunningIsANoOp() throws Exception {
        int writes = fake.writes(bank).size();
        int approvals = fake.all(bank, FakePlatform.APPROVALS).size();
        JsonNode run = call("POST", "/v1/starter-pack-runs/apply", PARAMS, bank.toString(), "tenant-admin", 200);
        assertThat(run.get("trigger").asString()).isEqualTo("SELF_SERVICE");
        assertThat(run.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.get("created_count").asInt()).isZero();
        assertThat(run.get("updated_count").asInt()).isZero();
        assertThat(run.get("unchanged_count").asInt()).isGreaterThan(100);
        assertThat(fake.writes(bank)).hasSize(writes);                      // nothing created, submitted or patched again
        assertThat(fake.all(bank, FakePlatform.APPROVALS)).hasSize(approvals);
        assertThat(ids(run.get("approval_request_ids"))).hasSize(approvals); // still listed while pending
    }

    @Test
    @Order(4)
    void afterTheCheckerApprovesResumePublishesAndActivates() throws Exception {
        assertThat(fake.approveAll(bank, CHECKER)).isEqualTo(35);   // incl. 3 NEW_DEVICE_SIGN_IN templates
        JsonNode run = call("POST", "/v1/starter-pack-runs/" + bankRun + "/resume", null, bank.toString(), "tenant-admin", 200);
        assertThat(run.get("trigger").asString()).isEqualTo("RESUME");
        assertThat(run.get("resumed_from_id").asString()).isEqualTo(bankRun);
        // products published, wallets activated now that their routing is approved; limits still need the checker
        assertThat(fake.writes(bank).stream().filter(c -> c.path().endsWith("/publish") && c.path().startsWith("/v1/product-versions"))).hasSize(3);
        assertThat(fake.all(bank, "/v1/wallets")).hasSize(2).allSatisfy(w -> assertThat(w.get("status").asString()).isEqualTo("ACTIVE"));
        assertThat(run.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.get("awaiting_approval_count").asInt()).isEqualTo(6); // 5 limit rules + the sign-up journey
        assertThat(run.get("approval_request_ids")).isEmpty();

        assertThat(fake.activateLimitRules(bank)).isEqualTo(5);
        assertThat(fake.publishJourneys(bank, CHECKER)).isEqualTo(1);
        JsonNode done = call("POST", "/v1/starter-pack-runs/" + run.get("id").asString() + "/resume", null, bank.toString(), "tenant-admin", 200);
        assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("COMPLETED");
        assertThat(done.get("awaiting_approval_count").asInt()).isZero();
        JsonNode again = call("POST", "/v1/starter-pack-runs/" + done.get("id").asString() + "/resume", null, bank.toString(), "tenant-admin", 409);
        assertThat(again.get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(5)
    void resumeAfterAFailureReplaysTheSameIdempotencyKeyWithoutDuplicates() throws Exception {
        UUID t = tenant("sp-flaky");
        // the 3rd wallet fee rule is created by wallet-service but the response is lost (timeout after commit)
        fake.failOn("POST", p -> p.equals("/v1/wallet-fee-rules"), 3, true);
        JsonNode failed = call("POST", "/v1/platform/tenants/" + t + "/apply-starter-pack", "{\"pack_code\":\"GENERIC\"}",
                OPERATOR_TENANT, OPERATOR, 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").asString()).contains("fee_cash_out").contains("upstream_error");
        JsonNode pricing = step(failed, "wallet_pricing");
        assertThat(pricing.get("status").asString()).isEqualTo("FAILED");
        assertThat(outcomes(pricing)).containsExactly("CREATED", "CREATED", "FAILED", "NOT_RUN", "NOT_RUN", "NOT_RUN",
                "NOT_RUN", "NOT_RUN", "NOT_RUN", "NOT_RUN");
        assertThat(outcomes(step(failed, "end_of_day"))).containsOnly("NOT_RUN");
        assertThat(fake.calls(t)).noneMatch(c -> c.path().startsWith("/v1/risk/limits"));
        JsonNode registry = call("GET", "/v1/starter-pack-items?pack_code=GENERIC&item_key=fee_cash_out", null, t.toString(), "x", 200)
                .get("data").get(0);
        assertThat(registry.get("status").asString()).isEqualTo("FAILED");
        assertThat(registry.get("last_error").asString()).contains("upstream_error");
        String key = registry.get("idempotency_key").asString();

        JsonNode resumed = call("POST", "/v1/starter-pack-runs/" + failed.get("id").asString() + "/resume", null, t.toString(), OPERATOR, 200);
        assertThat(resumed.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(item(resumed, "wallet_pricing", "fee_cash_out").get("outcome").asString()).isEqualTo("CREATED");
        assertThat(item(resumed, "wallet_pricing", "fee_p2p").get("outcome").asString()).isEqualTo("UNCHANGED");
        // the lost create was replayed with the same key and body: the fake returned the stored response
        List<FakePlatform.Call> attempts = fake.calls(t).stream().filter(c -> key.equals(c.key())).toList();
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(1).replay()).isTrue();
        assertThat(attempts.get(1).body()).isEqualTo(attempts.get(0).body());
        assertThat(fake.all(t, "/v1/wallet-fee-rules")).hasSize(6)
                .extracting(r -> r.get("code").asString()).doesNotHaveDuplicates();
        assertThat(fake.all(t, "/v1/legal-entities")).hasSize(1);
        assertThat(fake.all(t, "/v1/ledger-accounts")).hasSize(37);
    }

    @Test
    @Order(6)
    void aNewerVersionUpgradesOnlyPackOwnedItemsThatChanged() throws Exception {
        UUID t = tenant("sp-upgrade");
        // the tenant already has its own config approval policy: the pack must leave it alone
        fake.seed(t, "/v1/approval-policies", """
                {"code":"MY-CONFIG","resource_type":"config_entry","action":"activate","expires_after_hours":4,"is_active":true}""");
        JsonNode v1 = call("POST", "/v1/platform/tenants/" + t + "/apply-starter-pack", "{\"pack_code\":\"TEST_MINI\",\"version\":\"2026.1.0\"}",
                OPERATOR_TENANT, OPERATOR, 200);
        assertThat(v1.get("pack_version").asString()).isEqualTo("2026.1.0");
        assertThat(item(v1, "approvals", "policy_config").get("outcome").asString()).isEqualTo("EXISTING");
        assertThat(v1.get("existing_count").asInt()).isEqualTo(1);
        String feeId = fake.all(t, "/v1/wallet-fee-rules").getFirst().get("id").asString();
        int configVersions = fake.all(t, "/v1/config/entries").size();

        JsonNode v2 = call("POST", "/v1/platform/tenants/" + t + "/apply-starter-pack", "{\"pack_code\":\"TEST_MINI\"}",
                OPERATOR_TENANT, OPERATOR, 200);
        assertThat(v2.get("pack_version").asString()).isEqualTo("2026.2.0");
        assertThat(item(v2, "organisation", "legal_entity").get("outcome").asString()).isEqualTo("UNCHANGED");
        assertThat(item(v2, "approvals", "policy_config").get("outcome").asString()).isEqualTo("EXISTING");
        assertThat(item(v2, "pricing", "fee_p2p").get("outcome").asString()).isEqualTo("UPDATED");
        assertThat(item(v2, "pricing", "tier0").get("outcome").asString()).isEqualTo("UNCHANGED");
        assertThat(item(v2, "pricing", "daily_limit").get("outcome").asString()).isEqualTo("UPGRADE_SKIPPED");
        assertThat(item(v2, "pricing", "daily_limit").get("message").asString()).contains("apply it manually");
        assertThat(item(v2, "pricing", "fee_cash_in").get("outcome").asString()).isEqualTo("CREATED");
        assertThat(item(v2, "routing", "routing_default").get("outcome").asString()).isEqualTo("UPDATED");
        assertThat(item(v2, "routing", "routing_default").get("status").asString()).isEqualTo("AWAITING_APPROVAL");

        // the tenant's policy untouched; the fee rule patched in place (If-Match); a new config version submitted
        assertThat(fake.all(t, "/v1/approval-policies")).singleElement()
                .satisfies(p -> assertThat(p.get("expires_after_hours").asInt()).isEqualTo(4));
        assertThat(fake.writes(t)).noneMatch(c -> c.path().startsWith("/v1/approval-policies") || c.path().startsWith("/v1/risk/limits/rules/"));
        JsonNode fee = fake.all(t, "/v1/wallet-fee-rules").stream().filter(r -> r.get("id").asString().equals(feeId)).findFirst().orElseThrow();
        assertThat(fee.get("percentage").asString()).isEqualTo("0.004");
        assertThat(fee.get("row_version").asInt()).isEqualTo(2);
        assertThat(fake.all(t, "/v1/risk/limits/rules")).singleElement()
                .satisfies(r -> assertThat(r.get("max_amount").asString()).isEqualTo("1000"));
        assertThat(fake.all(t, "/v1/config/entries")).hasSize(configVersions + 1);

        JsonNode v2again = call("POST", "/v1/platform/tenants/" + t + "/apply-starter-pack", "{\"pack_code\":\"TEST_MINI\"}",
                OPERATOR_TENANT, OPERATOR, 200);
        assertThat(v2again.get("created_count").asInt() + v2again.get("updated_count").asInt()).isZero();
    }

    @Test
    @Order(7)
    void dryRunPredictsWithoutCallingAnything() throws Exception {
        UUID t = tenant("sp-dry");
        JsonNode dry = call("POST", "/v1/platform/tenants/" + t + "/apply-starter-pack", "{\"pack_code\":\"KE\",\"dry_run\":true}",
                OPERATOR_TENANT, OPERATOR, 200);
        assertThat(dry.get("dry_run").asBoolean()).isTrue();
        assertThat(dry.get("created_count").asInt()).isGreaterThan(100);
        assertThat(outcomes(step(dry, "ledger"))).containsOnly("WOULD_CREATE");
        assertThat(fake.calls(t)).isEmpty();
        assertThat(call("GET", "/v1/starter-pack-items?pack_code=KE", null, t.toString(), "x", 200).get("data")).isEmpty();
        JsonNode notResumable = call("POST", "/v1/starter-pack-runs/" + dry.get("id").asString() + "/resume", null, t.toString(), "x", 409);
        assertThat(notResumable.get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(8)
    void provisioningAppliesTheRequestedPackAfterCommit() throws Exception {
        call("POST", "/v1/platform/cells", """
                {"code":"sp-cell","region":"af-south-1","cloud_provider":"AWS","status":"ACTIVE","capacity_tenants":5}""",
                OPERATOR_TENANT, OPERATOR, 201);
        JsonNode unknown = call("POST", "/v1/platform/tenants/provision-from-template", provision("sp-nope", "NOPE"), OPERATOR_TENANT, OPERATOR, 422);
        assertThat(unknown.get("code").asString()).isEqualTo("unknown_starter_pack");

        JsonNode tenant = call("POST", "/v1/platform/tenants/provision-from-template", provision("sp-ke", "ke"), OPERATOR_TENANT, OPERATOR, 200);
        assertThat(tenant.get("starter_pack_code").asString()).isEqualTo("KE");
        String id = tenant.get("id").asString();
        JsonNode run = null;
        for (int i = 0; i < 120 && (run == null || "RUNNING".equals(run.get("status").asString())); i++) {
            Thread.sleep(250);
            JsonNode page = call("GET", "/v1/starter-pack-runs?pack_code=KE", null, id, "x", 200);
            run = page.get("data").isEmpty() ? null : page.get("data").get(0);
        }
        assertThat(run).isNotNull();
        assertThat(run.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.get("trigger").asString()).isEqualTo("PROVISIONING");
        UUID t = UUID.fromString(id);
        assertThat(fake.all(t, "/v1/legal-entities")).singleElement().satisfies(le -> {
            assertThat(le.get("base_currency").asString()).isEqualTo("KES");
            assertThat(le.get("country").asString()).isEqualTo("KE");
            assertThat(le.get("timezone").asString()).isEqualTo("Africa/Nairobi");
        });
        assertThat(fake.all(t, "/v1/wallet-fee-rules")).anySatisfy(r -> {
            assertThat(r.get("code").asString()).isEqualTo("SP-P2P");
            assertThat(r.get("basis").asString()).isEqualTo("TIERED");
        });
        // KE national account numbering (CBK bank + branch code), never the GENERIC / platform defaults
        assertThat(fake.all(t, "/v1/config/entries").stream().filter(e -> e.get("namespace").asString().equals("account_numbering")))
                .singleElement().satisfies(e -> {
                    assertThat(e.at("/value/scheme").asString()).isEqualTo("NATIONAL");
                    assertThat(e.at("/value/bank_code").asString()).matches("[0-9]{2}");
                    assertThat(e.at("/value/branch_code").asString()).matches("[0-9]{3}");
                });
        assertThat(fake.all(t, "/v1/onboarding/journeys")).singleElement().satisfies(j -> {
            assertThat(j.get("code").asString()).isEqualTo("RETAIL_ONLINE");
            assertThat(j.get("country").asString()).isEqualTo("KE");
            assertThat(j.get("definition").toString()).contains("national_id_number");
        });
        assertThat(fake.calls(t)).allSatisfy(c -> assertThat(c.principal()).isEqualTo(OPERATOR));
        String runId = run.get("id").asString();
        JsonNode stored = call("GET", "/v1/platform/tenants/" + id, null, OPERATOR_TENANT, OPERATOR, 200);
        for (int i = 0; i < 40 && stored.path("starter_pack_run_id").isNull(); i++) {
            Thread.sleep(100);
            stored = call("GET", "/v1/platform/tenants/" + id, null, OPERATOR_TENANT, OPERATOR, 200);
        }
        assertThat(stored.get("starter_pack_run_id").asString()).isEqualTo(runId);
    }

    // ------------------------------------------------------------------ helpers

    private UUID tenant(String code) throws Exception {
        return UUID.fromString(call("POST", "/v1/platform/tenants", """
                {"code":"%s","name":"%s","legal_name":"%s Ltd","tenant_type":"FINTECH","home_region":"eu-west-1",
                 "default_currency":"EUR","default_country":"IE","primary_contact_email":"ops@%s.example"}""".formatted(code, code, code, code),
                OPERATOR_TENANT, OPERATOR, 201).get("id").asString());
    }

    private static String provision(String code, String pack) {
        return """
                {"template_code":"WALLET_PROVIDER","code":"%s","name":"%s","legal_name":"%s Kenya Ltd","tenant_type":"FINTECH",
                 "default_currency":"KES","default_country":"KE","primary_contact_email":"ops@%s.example","starter_pack":"%s"}"""
                .formatted(code, code, code, code, pack);
    }

    private static void assertBefore(List<String> calls, String first, String then) {
        int a = calls.indexOf(first);
        int b = calls.indexOf(then);
        assertThat(a).as(first + " was called").isGreaterThanOrEqualTo(0);
        assertThat(b).as(then + " was called").isGreaterThanOrEqualTo(0);
        assertThat(a).as(first + " before " + then).isLessThan(b);
    }

    private static JsonNode step(JsonNode run, String code) {
        return find(run.get("results"), "step", code);
    }

    private static JsonNode item(JsonNode run, String step, String key) {
        return find(step(run, step).get("items"), "key", key);
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asString())) {
                return n;
            }
        }
        throw new AssertionError("no " + field + "=" + value + " in " + array);
    }

    private static List<String> outcomes(JsonNode step) {
        List<String> out = new ArrayList<>();
        step.get("items").forEach(i -> out.add(i.get("outcome").asString()));
        return out;
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(i -> out.add(i.asString()));
        return out;
    }

    private JsonNode call(String method, String path, String body, String tenant, String user, int expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-User", user).header("X-Tenant-Id", tenant);
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? ("POST".equals(method) ? HttpRequest.BodyPublishers.ofString("{}")
                : HttpRequest.BodyPublishers.noBody()) : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
}
