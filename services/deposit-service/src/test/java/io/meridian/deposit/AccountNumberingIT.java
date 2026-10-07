package io.meridian.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.application.AccountNumbering;
import io.meridian.deposit.domain.AccountNumbers;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigInteger;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account identifiers follow the booking legal entity: a Kenyan entity gets KE national numbers (bank + branch code,
 * Luhn check digit), a British one valid GB IBANs with its own bank code and BIC, a generic configurable format works
 * elsewhere, a misconfigured entity is refused instead of falling back to the deployment's (GB) values, and numbers
 * stay unique when many accounts are opened at once. tenant-service, config-service, reference-data-service and the
 * catalog are stubbed behind the mocked {@link ServiceClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        // deployment fallback for GB entities only: must never number the KE or US entities below
        "meridian.deposit.country=GB", "meridian.deposit.bank-code=MERI", "meridian.deposit.branch-code=000001"})
@Import(MeridianTestContainers.class)
class AccountNumberingIT {
    static final String KE_TENANT = "0199a000-0000-7000-8000-00000000e001";
    static final String GB_TENANT = "0199a000-0000-7000-8000-00000000e002";
    static final String US_TENANT = "0199a000-0000-7000-8000-00000000e003";
    static final String RACE_TENANT = "0199a000-0000-7000-8000-00000000e004";
    static final String KE_ENTITY = "0199a000-0000-7000-8000-00000000a001";
    static final String KE_BARE_ENTITY = "0199a000-0000-7000-8000-00000000a002";
    static final String GB_ENTITY = "0199a000-0000-7000-8000-00000000a003";
    static final String GB_BAD_BIC_ENTITY = "0199a000-0000-7000-8000-00000000a004";
    static final String US_ENTITY = "0199a000-0000-7000-8000-00000000a005";
    static final String RACE_ENTITY = "0199a000-0000-7000-8000-00000000a006";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c001";
    static final String PRODUCT = "0199a000-0000-7000-8000-00000000f001";
    static final String VERSION = "0199a000-0000-7000-8000-00000000f002";

    static final Map<String, String> ENTITIES = Map.of(
            KE_ENTITY, "{\"code\":\"KE01\",\"country\":\"KE\",\"swift_bic\":\"DEMOKENA\"}",
            KE_BARE_ENTITY, "{\"code\":\"KE02\",\"country\":\"KE\"}",
            GB_ENTITY, "{\"code\":\"UK01\",\"country\":\"GB\",\"swift_bic\":\"DEMOGB2LXXX\"}",
            GB_BAD_BIC_ENTITY, "{\"code\":\"UK02\",\"country\":\"GB\",\"swift_bic\":\"DEMOKENAXXX\"}",
            US_ENTITY, "{\"code\":\"US01\",\"country\":\"US\"}",
            RACE_ENTITY, "{\"code\":\"KE01\",\"country\":\"KE\"}");

    /** Namespace account_numbering per tenant (config-service resolve). */
    // keyed as stored by config-service (lower-case): entity code lower-cased, or "le-<legal entity id>"
    static final Map<String, String> SETTINGS = Map.of(
            KE_TENANT, "{\"ke01\":{\"bank_code\":\"68\",\"branch_code\":\"001\"}}",
            GB_TENANT, "{\"default\":{\"branch_code\":\"400515\"},\"uk02\":{\"branch_code\":\"400515\"}}",
            US_TENANT, "{\"us01\":{\"bank_code\":\"021\",\"pattern\":\"{bank}{serial}{check}\",\"serial_length\":8,\"check_digit\":\"MOD97\"}}",
            // 90 possible serials: 30 concurrent openings must collide and retry
            RACE_TENANT, "{\"default\":{\"bank_code\":\"68\",\"branch_code\":\"002\",\"serial_length\":2}}");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    AccountNumbering numbering;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void stub() {
        numbering.evict();
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> answer(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenReturn(json.readTree("{}"));
    }

    private JsonNode answer(String service, String path) {
        String tenant = TenantContext.current().map(UUID::toString).orElse("");
        switch (service) {
            case "tenant-service" -> {
                String id = path.substring(path.lastIndexOf('/') + 1);
                if (path.startsWith("/v1/legal-entities/") && ENTITIES.containsKey(id)) {
                    return json.readTree(ENTITIES.get(id).replace("{", "{\"id\":\"" + id + "\","));
                }
                throw new ApiException(404, "not_found", path);
            }
            case "config-service" -> {
                String values = path.contains("namespace=account_numbering") ? SETTINGS.getOrDefault(tenant, "{}") : "{}";
                return json.readTree("{\"namespace\":\"x\",\"values\":" + values + "}");
            }
            case "reference-data-service" -> {
                return json.readTree(path.contains("code=GB") ? "{\"data\":[{\"code\":\"GB\",\"iban_length\":22}]}"
                        : path.contains("code=KE") ? "{\"data\":[{\"code\":\"KE\",\"iban_length\":null}]}" : "{\"data\":[]}");
            }
            case "product-catalog-service" -> {
                if (path.equals("/v1/product-versions/" + VERSION)) {
                    return json.readTree("{\"id\":\"" + VERSION + "\",\"product_id\":\"" + PRODUCT + "\",\"status\":\"PUBLISHED\"}");
                }
                if (path.contains("/interest-rates")) {
                    return json.readTree("""
                            {"data":[{"rate_kind":"CREDIT_INTEREST","rate_type":"FIXED","fixed_rate":"0.02","day_count":"ACT_365",
                              "capitalisation_frequency":"MONTHLY"}],"has_more":false}""");
                }
                return json.readTree("{\"data\":[],\"has_more\":false}");
            }
            default -> throw new ApiException(404, "not_found", service + path);
        }
    }

    @Test
    void kenyanEntityGetsKenyanNumbersWithValidCheckDigits() throws Exception {
        for (int n = 0; n < 5; n++) {
            JsonNode acc = open(KE_TENANT, KE_ENTITY, "KES", 201);
            String number = acc.get("account_number").asString();
            assertThat(number).hasSize(13).startsWith("001").containsOnlyDigits();
            // Luhn over CBK bank code + branch + serial: the bank code is part of the check
            assertThat(AccountNumbers.isValidLuhn("68" + number)).isTrue();
            assertThat(acc.path("iban").isNull() || acc.path("iban").isMissingNode()).isTrue();
            assertThat(acc.get("bban").asString()).isEqualTo("68" + number);
            assertThat(acc.get("routing_code").asString()).isEqualTo("68001");
            assertThat(acc.get("bic").asString()).isEqualTo("DEMOKENAXXX");
            assertThat(acc.toString()).doesNotContain("MERI").doesNotContain("\"GB");
        }
    }

    @Test
    void kenyanEntityWithoutNumberingSettingsIsRefusedNotNumberedAsGb() throws Exception {
        JsonNode problem = open(KE_TENANT, KE_BARE_ENTITY, "KES", 422);
        assertThat(problem.get("code").asString()).isEqualTo("account_numbering_not_configured");
        assertThat(problem.get("detail").asString()).contains("bank_code").contains("KE");
        assertThat(open(KE_TENANT, "0199a000-0000-7000-8000-00000000a0ff", "KES", 422).get("code").asString())
                .isEqualTo("legal_entity_not_found");
    }

    @Test
    void britishEntityGetsValidGbIbansWithItsOwnBankCodeAndBic() throws Exception {
        JsonNode acc = open(GB_TENANT, GB_ENTITY, "GBP", 201);
        String iban = acc.get("iban").asString();
        assertThat(iban).hasSize(22).startsWith("GB").contains("DEMO400515");
        assertThat(AccountNumbers.isValidIban(iban)).isTrue();
        assertThat(AccountNumbers.isValidIban(iban, 22)).isTrue();
        assertThat(iban).endsWith(acc.get("account_number").asString());
        assertThat(AccountNumbers.isValidLuhn(acc.get("account_number").asString())).isTrue();
        assertThat(acc.get("bban").asString()).isEqualTo(iban.substring(4));
        assertThat(acc.get("bic").asString()).isEqualTo("DEMOGB2LXXX");
        assertThat(acc.get("routing_code").asString()).isEqualTo("400515");
        // a BIC of another country is a configuration error, not a silent GB/KE mix
        JsonNode bad = open(GB_TENANT, GB_BAD_BIC_ENTITY, "GBP", 422);
        assertThat(bad.get("code").asString()).isEqualTo("account_numbering_invalid");
        // a client-supplied IBAN is checked against the country length table
        JsonNode supplied = send(GB_TENANT, body(GB_ENTITY, "GBP").replace("}", ",\"iban\":\"GB82WEST1234569876543\"}"),
                UUID.randomUUID().toString());
        assertThat(supplied.get("code").asString()).isEqualTo("invalid_iban");
    }

    @Test
    void genericNationalFormatFollowsTheConfiguredPattern() throws Exception {
        JsonNode acc = open(US_TENANT, US_ENTITY, "USD", 201);
        String number = acc.get("account_number").asString();
        assertThat(number).hasSize(13).startsWith("021").containsOnlyDigits();
        assertThat(new BigInteger(number).mod(BigInteger.valueOf(97)).intValue()).isEqualTo(1); // ISO 7064 MOD 97-10
        assertThat(acc.path("iban").isNull() || acc.path("iban").isMissingNode()).isTrue();
        assertThat(acc.get("bban").asString()).isEqualTo(number);
    }

    @Test
    void numbersStayUniqueWhenAccountsAreOpenedConcurrently() throws Exception {
        int n = 30;
        List<Future<JsonNode>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> open(RACE_TENANT, RACE_ENTITY, "KES", 201)));
            }
            Set<String> numbers = new HashSet<>();
            for (Future<JsonNode> f : futures) {
                String number = f.get().get("account_number").asString();
                assertThat(number).hasSize(6).startsWith("002");
                assertThat(AccountNumbers.isValidLuhn("68" + number)).isTrue();
                numbers.add(number);
            }
            assertThat(numbers).hasSize(n);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String body(String entity, String currency) {
        return """
                {"account_type":"SAVINGS","customer_id":"%s","product_id":"%s","product_version_id":"%s",
                 "legal_entity_id":"%s","currency":"%s"}""".formatted(CUSTOMER, PRODUCT, VERSION, entity, currency);
    }

    private JsonNode open(String tenant, String entity, String currency, int expected) throws Exception {
        HttpResponse<String> r = raw(tenant, body(entity, currency), UUID.randomUUID().toString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode send(String tenant, String body, String key) throws Exception {
        return json.readTree(raw(tenant, body, key).body());
    }

    private HttpResponse<String> raw(String tenant, String body, String key) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/deposit-accounts"))
                .header("X-Tenant-Id", tenant).header("X-User", "maker").header("Content-Type", "application/json")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
