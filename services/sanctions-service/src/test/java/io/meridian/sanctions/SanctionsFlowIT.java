package io.meridian.sanctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sanctions end to end: list sync / import, fuzzy screening of customers and payments (parties, embargoed BIC
 * country, free text), match dispositions with four-eyes, confirmation opening a case, false positives and the
 * good-guy whitelist, delisting, batches, global lists and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SanctionsFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000d2";

    static final String LIST_CSV = """
            external_id,entry_type,primary_name,aliases,date_of_birth,nationality,countries,identifiers,programs
            SDN-1,INDIVIDUAL,Osama bin Laden,Usama bin Muhammad bin Awad BIN LADIN;Abu Abdallah,1957-03-10,SA,SA;AF,,SDGT
            SDN-2,ENTITY,Bank Melli Iran,Melli Bank PLC,,IR,IR,BIC:BMJIIRTH,IRAN
            SDN-3,COUNTRY,North Korea,DPRK,,,KP,,DPRK
            SDN-4,VESSEL,Ocean Star,,,,,IMO9123456,IRAN
            SDN-5,INDIVIDUAL,Ivan Petrovich Sidorov,,1966-02-02,RU,RU,,UKRAINE-EO13662
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    String list;
    String binLadenResult;
    String sidorovCustomer = UUID.randomUUID().toString();
    String sidorovResult;

    @BeforeAll
    void setUp() throws Exception {
        JsonNode l = post("/v1/risk/sanctions/lists", """
                {"source":"INTERNAL","code":"BANK_WATCH","name":"Bank watch list"}""", 201, "compliance");
        list = l.get("id").asString();
        assertThat(l.get("owner_tenant_id").asString()).isEqualTo(TENANT);
        JsonNode synced = post("/v1/risk/sanctions/lists/" + list + "/sync",
                json.writeValueAsString(java.util.Map.of("content", LIST_CSV, "list_version", "2026-10-01")), 200, "compliance");
        assertThat(synced.get("entry_count").asInt()).isEqualTo(5);
        assertThat(synced.get("list_version").asString()).isEqualTo("2026-10-01");
    }

    @Test
    @Order(1)
    void fuzzyCustomerScreeningRaisesPotentialMatch() throws Exception {
        JsonNode req = post("/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"ONBOARDING","full_name":"Usama Bin Ladin",
                 "date_of_birth":"1957-03-10","nationality":"SA","requested_by_service":"onboarding-service"}"""
                .formatted(UUID.randomUUID()), 201, "onboarding");
        assertThat(req.get("status").asString()).isEqualTo("POTENTIAL_MATCH");
        binLadenResult = req.get("screening_result_id").asString();
        JsonNode result = get("/v1/risk/sanctions/results/" + binLadenResult, TENANT);
        assertThat(result.get("match_count").asInt()).isEqualTo(1);
        assertThat(result.get("highest_score").asDouble()).isGreaterThanOrEqualTo(0.95);
        JsonNode match = get("/v1/risk/sanctions/results/" + binLadenResult + "/matches", TENANT).get("data").get(0);
        assertThat(match.get("matched_fields").get("date_of_birth").asString()).isEqualTo("EXACT");
        assertThat(match.get("matched_fields").get("list_code").asString()).isEqualTo("BANK_WATCH");

        JsonNode clear = post("/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"ONBOARDING","full_name":"Margaret Thompson",
                 "requested_by_service":"onboarding-service"}""".formatted(UUID.randomUUID()), 201, "onboarding");
        assertThat(clear.get("status").asString()).isEqualTo("CLEAR");
        assertThat(get("/v1/risk/sanctions/results/" + clear.get("screening_result_id").asString(), TENANT)
                .get("rescreen_due_at").asString()).isNotBlank();

        JsonNode byId = post("/v1/risk/sanctions/screenings", """
                {"subject_type":"PAYEE","subject_id":"%s","trigger":"MANUAL","full_name":"Seagull Shipping",
                 "identifiers":["IMO 9123456"],"requested_by_service":"payment-service"}""".formatted(UUID.randomUUID()), 201, "ops");
        assertThat(byId.get("status").asString()).isEqualTo("POTENTIAL_MATCH");
    }

    @Test
    @Order(2)
    void paymentScreeningCoversPartiesEmbargoesAndFreeText() throws Exception {
        long t = System.nanoTime();
        JsonNode res = post("/v1/risk/sanctions/screenings/screen-payment", """
                {"payment_id":"%s","payload":{
                   "debtor":{"name":"Alice Walker","country":"GB"},
                   "creditor":{"name":"Bank Meli Iran","country":"AE"},
                   "creditor_agent":{"name":"Korea Trade Bank","bic":"KTBKKPPYXXX"},
                   "remittance_information":"Invoice 77 for usama bin ladin consulting"}}""".formatted(UUID.randomUUID()),
                200, "payment-service");
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertThat(res.get("status").asString()).isEqualTo("POTENTIAL_MATCH");
        assertThat(res.get("subject_type").asString()).isEqualTo("PAYMENT");
        List<String> parties = new ArrayList<>();
        get("/v1/risk/sanctions/results/" + res.get("id").asString() + "/matches", TENANT).get("data")
                .forEach(m -> parties.add(m.get("matched_fields").get("party").asString() + ":" + m.get("match_algorithm").asString()));
        assertThat(parties).contains("creditor:JARO_WINKLER", "country:COUNTRY_EMBARGO", "remittance_information:TOKEN_SET");
        assertThat(ms).isLessThan(500);

        JsonNode clean = post("/v1/risk/sanctions/screenings/screen-payment", """
                {"payment_id":"%s","payload":{"debtor":{"name":"Alice Walker","country":"GB"},
                 "creditor":{"name":"Robert Brown","country":"FR"}}}""".formatted(UUID.randomUUID()), 200, "payment-service");
        assertThat(clean.get("status").asString()).isEqualTo("CLEAR");
    }

    @Test
    @Order(3)
    void confirmingAMatchNeedsTwoPeopleAndOpensACase() throws Exception {
        when(serviceClient.post(eq("case-management-service"), eq("/v1/risk/cases"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"OPEN\"}"));
        when(serviceClient.post(eq("case-management-service"), org.mockito.ArgumentMatchers.startsWith("/v1/risk/cases/"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
        JsonNode match = get("/v1/risk/sanctions/results/" + binLadenResult + "/matches", TENANT).get("data").get(0);
        String matchId = match.get("id").asString();
        assertThat(post("/v1/risk/sanctions/results/" + binLadenResult + "/review",
                "{\"status\":\"CONFIRMED_MATCH\",\"decision_reason\":\"x\"}", 422, "analyst-1").get("code").asString())
                .isEqualTo("no_true_match");
        JsonNode noReason = patch("/v1/risk/sanctions/matches/" + matchId, "{\"disposition\":\"TRUE_MATCH\"}",
                match.get("row_version").asString(), 422, "analyst-1");
        assertThat(noReason.get("code").asString()).isEqualTo("disposition_reason_required");
        JsonNode tm = patch("/v1/risk/sanctions/matches/" + matchId,
                "{\"disposition\":\"TRUE_MATCH\",\"disposition_reason\":\"DOB, nationality and name agree\"}",
                match.get("row_version").asString(), 200, "analyst-1");
        assertThat(tm.get("dispositioned_by").asString()).isEqualTo("analyst-1");

        JsonNode self = post("/v1/risk/sanctions/results/" + binLadenResult + "/review",
                "{\"status\":\"CONFIRMED_MATCH\",\"decision_reason\":\"confirmed\"}", 422, "analyst-1");
        assertThat(self.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode clearTrue = post("/v1/risk/sanctions/results/" + binLadenResult + "/review",
                "{\"status\":\"FALSE_POSITIVE\",\"decision_reason\":\"x\"}", 409, "mlro");
        assertThat(clearTrue.get("code").asString()).isEqualTo("true_match_present");

        JsonNode confirmed = post("/v1/risk/sanctions/results/" + binLadenResult + "/review",
                "{\"status\":\"CONFIRMED_MATCH\",\"decision_reason\":\"confirmed by MLRO\"}", 200, "mlro");
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED_MATCH");
        assertThat(confirmed.get("reviewed_by").asString()).isEqualTo("mlro");
        assertThat(confirmed.get("aml_case_id").asString()).isNotBlank();
        assertThat(post("/v1/risk/sanctions/results/" + binLadenResult + "/review",
                "{\"status\":\"FALSE_POSITIVE\",\"decision_reason\":\"x\"}", 409, "mlro").get("code").asString())
                .isEqualTo("invalid_transition");
    }

    @Test
    @Order(4)
    void clearingATrueMatchNeedsASecondReviewerAndWhitelistSuppressesRepeats() throws Exception {
        JsonNode req = post("/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"ONBOARDING","full_name":"Ivan Petrovich Sidorov",
                 "date_of_birth":"1966-02-02","requested_by_service":"onboarding-service"}""".formatted(sidorovCustomer),
                201, "onboarding");
        sidorovResult = req.get("screening_result_id").asString();
        JsonNode m = get("/v1/risk/sanctions/results/" + sidorovResult + "/matches", TENANT).get("data").get(0);
        JsonNode tm = patch("/v1/risk/sanctions/matches/" + m.get("id").asString(),
                "{\"disposition\":\"TRUE_MATCH\",\"disposition_reason\":\"same name and DOB\"}", m.get("row_version").asString(), 200, "analyst-1");
        JsonNode same = patch("/v1/risk/sanctions/matches/" + m.get("id").asString(),
                "{\"disposition\":\"FALSE_POSITIVE\",\"disposition_reason\":\"different passport\"}", tm.get("row_version").asString(), 422, "analyst-1");
        assertThat(same.get("code").asString()).isEqualTo("four_eyes_violation");
        patch("/v1/risk/sanctions/matches/" + m.get("id").asString(),
                "{\"disposition\":\"FALSE_POSITIVE\",\"disposition_reason\":\"different passport number\"}", tm.get("row_version").asString(), 200, "analyst-2");
        JsonNode cleared = post("/v1/risk/sanctions/results/" + sidorovResult + "/review",
                "{\"status\":\"FALSE_POSITIVE\",\"decision_reason\":\"namesake\",\"whitelist_false_positives\":true}", 200, "analyst-2");
        assertThat(cleared.get("status").asString()).isEqualTo("FALSE_POSITIVE");
        assertThat(get("/v1/risk/sanctions/whitelist?subject_id=" + sidorovCustomer, TENANT).get("data")).hasSize(1);

        JsonNode again = post("/v1/risk/sanctions/results/" + sidorovResult + "/rescreen", "", 200, "scheduler");
        assertThat(again.get("id").asString()).isNotEqualTo(sidorovResult);
        assertThat(again.get("status").asString()).isEqualTo("CLEAR");
        assertThat(again.get("suppressed_count").asInt()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void resyncDelistsMissingEntriesAndImportAdds() throws Exception {
        String reduced = LIST_CSV.lines().filter(l -> !l.startsWith("SDN-5")).reduce("", (a, b) -> a + b + "\n");
        JsonNode synced = post("/v1/risk/sanctions/lists/" + list + "/sync",
                json.writeValueAsString(java.util.Map.of("content", reduced, "list_version", "2026-10-05")), 200, "compliance");
        assertThat(synced.get("entry_count").asInt()).isEqualTo(4);
        JsonNode delisted = get("/v1/risk/sanctions/list-entries?sanctions_list_id=" + list + "&external_id=SDN-5", TENANT).get("data").get(0);
        assertThat(delisted.get("delisted_on").asString()).isNotBlank();
        JsonNode after = post("/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"LIST_UPDATE","full_name":"Ivan Petrovich Sidorov",
                 "requested_by_service":"onboarding-service"}""".formatted(UUID.randomUUID()), 201, "onboarding");
        assertThat(after.get("status").asString()).isEqualTo("CLEAR");

        JsonNode imported = post("/v1/risk/sanctions/lists/" + list + "/import", json.writeValueAsString(java.util.Map.of(
                "format", "JSON", "content", """
                        [{"external_id":"INT-1","entry_type":"INDIVIDUAL","primary_name":"Carlos Alberto Mendoza",
                          "aliases":["Carlos Mendosa"],"nationality":"CO"},
                         {"external_id":"INT-2","entry_type":"INDIVIDUAL"},
                         {"external_id":"SDN-1","entry_type":"INDIVIDUAL","primary_name":"Osama bin Laden","nationality":"SA",
                          "countries":["SA","AF"],"programs":["SDGT"],"date_of_birth":"1957-03-10",
                          "aliases":["Usama bin Muhammad bin Awad BIN LADIN","Abu Abdallah"]}]""")), 200, "compliance");
        assertThat(imported.get("added").asInt()).isEqualTo(1);
        assertThat(imported.get("rejected").asInt()).isEqualTo(1);
        assertThat(imported.get("errors").get(0).get("error").asString()).contains("primary_name");
        JsonNode hit = post("/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"MANUAL","full_name":"Carlos Mendoza",
                 "requested_by_service":"crm"}""".formatted(UUID.randomUUID()), 201, "ops");
        assertThat(hit.get("status").asString()).isEqualTo("POTENTIAL_MATCH");
    }

    @Test
    @Order(6)
    void batchScreeningRunsInTheBackground() throws Exception {
        JsonNode batch = post("/v1/risk/sanctions/screenings/batch", """
                {"trigger":"PERIODIC_RESCREEN","subjects":[
                  {"subject_type":"CUSTOMER","subject_id":"%s","full_name":"Osama Bin Laden"},
                  {"subject_type":"CUSTOMER","subject_id":"%s","full_name":"Peter Jones"},
                  {"subject_type":"CUSTOMER","subject_id":"%s","full_name":"Emma Clarke"}]}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), 200, "scheduler");
        String id = batch.get("id").asString();
        assertThat(batch.get("total").asInt()).isEqualTo(3);
        assertThat(batch.has("subjects")).isFalse();
        JsonNode done = null;
        for (int i = 0; i < 100; i++) {
            done = get("/v1/risk/sanctions/batches/" + id, TENANT);
            if ("SUCCEEDED".equals(done.get("status").asString())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("processed").asInt()).isEqualTo(3);
        assertThat(done.get("potential_matches").asInt()).isEqualTo(1);
        assertThat(get("/v1/risk/sanctions/screenings?trigger=PERIODIC_RESCREEN", TENANT).get("data")).hasSize(3);
        JsonNode tooBig = post("/v1/risk/sanctions/screenings/batch", "{\"trigger\":\"MANUAL\",\"subjects\":[{}]}", 422, "x");
        assertThat(tooBig.get("code").asString()).isEqualTo("invalid_subject");
    }

    @Test
    @Order(7)
    void globalListsAreSharedButReadOnlyAndTenantListsArePrivate() throws Exception {
        String globalList = UUID.randomUUID().toString();
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
             Statement st = c.createStatement()) {
            st.execute("set search_path to sanctions");
            st.execute("insert into sanctions_list (id, source, code, name, is_active, entry_count) values ('" + globalList
                    + "', 'UN', 'UN_CONSOLIDATED', 'UN consolidated list', true, 1)");
            st.execute("insert into sanctions_list_entry (sanctions_list_id, external_id, entry_type, primary_name) values ('"
                    + globalList + "', 'UN-1', 'INDIVIDUAL', 'Hassan Ali Kareem')");
        }
        assertThat(get("/v1/risk/sanctions/lists/" + globalList, OTHER_TENANT).get("code").asString()).isEqualTo("UN_CONSOLIDATED");
        JsonNode otherScreen = send("POST", "/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"MANUAL","full_name":"Hasan Ali Karim",
                 "requested_by_service":"crm"}""".formatted(UUID.randomUUID()), OTHER_TENANT, "x");
        assertThat(otherScreen.get("status").asString()).isEqualTo("POTENTIAL_MATCH");
        JsonNode privateMiss = send("POST", "/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"MANUAL","full_name":"Osama bin Laden",
                 "requested_by_service":"crm"}""".formatted(UUID.randomUUID()), OTHER_TENANT, "x");
        assertThat(privateMiss.get("status").asString()).isEqualTo("CLEAR");

        HttpResponse<String> ro = raw("POST", "/v1/risk/sanctions/lists/" + globalList + "/sync", "{\"content\":\"x\"}", TENANT, "x");
        assertThat(ro.statusCode()).isEqualTo(403);
        assertThat(raw("GET", "/v1/risk/sanctions/lists/" + list, null, OTHER_TENANT, "x").statusCode()).isEqualTo(404);
        JsonNode otherLists = get("/v1/risk/sanctions/lists", OTHER_TENANT).get("data");
        assertThat(otherLists).hasSize(1);
        assertThat(raw("GET", "/v1/risk/sanctions/results/" + binLadenResult, null, OTHER_TENANT, "x").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = raw("POST", path, body, TENANT, user);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode send(String method, String path, String body, String tenant, String user) throws Exception {
        HttpResponse<String> r = raw(method, path, body, tenant, user);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isLessThan(300);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, String version, int expected, String user) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", TENANT).header("X-User", user)
                .header("If-Match", version).method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = raw("GET", path, null, tenant, "reader");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String tenant, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant)
                .header("X-User", user)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
