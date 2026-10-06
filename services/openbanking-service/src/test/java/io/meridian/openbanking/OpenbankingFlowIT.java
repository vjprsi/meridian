package io.meridian.openbanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * openbanking-service end to end over HTTP against PostgreSQL 18 (RLS enforced). deposit-service, ledger-service
 * and payment-service are stubbed behind a mocked {@link ServiceClient} ({@link FakeCore}). Covers TPP registration
 * with eIDAS certificates, AIS / PIS / CBPII consent journeys, payment initiation, funds confirmation, denial,
 * revocation, expiry, access logging, idempotency and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenbankingFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000b001";
    static final String OTHER = "0199a000-0000-7000-8000-00000000b002";
    static final String REDIRECT = "https://acme.example/callback";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeCore core;
    UUID customer = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    UUID acc1;
    UUID acc2;
    UUID strangerAcc;
    UUID eurAcc;
    String profileId;
    String tppId;
    TestCerts.Issued tppCert;

    @BeforeAll
    void setUp() {
        core = new FakeCore(json);
        acc1 = core.account(customer, "GBP", "ACTIVE", "500.00");
        acc2 = core.account(customer, "GBP", "ACTIVE", "20.00");
        strangerAcc = core.account(stranger, "GBP", "ACTIVE", "1000.00");
        eurAcc = core.account(customer, "EUR", "ACTIVE", "1000.00");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> core.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> core.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    // ------------------------------------------------------------------ 1. TPP onboarding

    @Test
    @Order(1)
    void profileAndTppRegistrationWithEidasCertificate() throws Exception {
        ObjectNode badProfile = obj("standard", "UK_OBIE", "version", "3.1.11", "base_path", "/open-banking/v3.1");
        badProfile.putArray("sca_approaches").add("SMS");
        assertThat(post("/v1/open-banking/profiles", badProfile, key(), TENANT, 422).get("code").asString())
                .isEqualTo("invalid_sca_approaches");
        ObjectNode profile = obj("standard", "UK_OBIE", "version", "3.1.11", "base_path", "/open-banking/v3.1");
        profile.putArray("sca_approaches").add("REDIRECT").add("DECOUPLED");
        profile.put("consent_max_days", 90);
        profileId = post("/v1/open-banking/profiles", profile, key(), TENANT, 201).get("id").asString();
        JsonNode discovery = get("/v1/open-banking/profiles/" + profileId + "/discovery", TENANT, 200);
        assertThat(discovery.at("/endpoints/accounts").asString()).isEqualTo("/open-banking/v3.1/accounts");

        tppCert = TestCerts.valid("PSDGB-FCA-123456", List.of("PSP_AI", "PSP_PI", "PSP_IC"), "GB-FCA");
        ObjectNode reg = obj("software_statement", ssa(tppCert, "sw-acme"), "certificate_pem", tppCert.pem());
        String regKey = key();
        HttpResponse<String> first = send("POST", "/v1/open-banking/tpps/register", reg, regKey, TENANT);
        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode tpp = json.readTree(first.body());
        tppId = tpp.get("id").asString();
        assertThat(tpp.get("status").asString()).isEqualTo("PENDING");
        assertThat(tpp.get("client_id").asString()).startsWith("tpp-");
        assertThat(tpp.get("competent_authority").asString()).isEqualTo("GB-FCA");
        assertThat(tpp.get("authorisation_number").asString()).isEqualTo("123456");
        assertThat(texts(tpp.get("roles"))).containsExactly("AISP", "PISP", "CBPII");

        HttpResponse<String> replay = send("POST", "/v1/open-banking/tpps/register", reg, regKey, TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(tppId);
        assertThat(post("/v1/open-banking/tpps/register", reg, key(), TENANT, 409).get("code").asString())
                .isEqualTo("software_already_registered");

        // statement signed with a key that is not the certificate's
        TestCerts.Issued other = TestCerts.valid("PSDGB-FCA-999", List.of("PSP_AI"), "GB-FCA");
        ObjectNode forged = obj("software_statement", ssa(other, "sw-forged"), "certificate_pem",
                TestCerts.valid("PSDGB-FCA-999", List.of("PSP_AI"), "GB-FCA").pem());
        assertThat(post("/v1/open-banking/tpps/register", forged, key(), TENANT, 422).get("code").asString())
                .isEqualTo("invalid_signature");

        // a PENDING TPP cannot request consents
        assertThat(post("/v1/open-banking/consents", aisConsent(null), key(), TENANT, 403).get("code").asString())
                .isEqualTo("tpp_not_active");

        JsonNode verified = post("/v1/open-banking/tpps/" + tppId + "/verify", null, key(), TENANT, 200);
        assertThat(verified.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(verified.get("last_verified_at").isNull()).isFalse();

        JsonNode certs = get("/v1/open-banking/tpps/" + tppId + "/certificates", TENANT, 200).get("data");
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).get("organization_identifier").asString()).isEqualTo("PSDGB-FCA-123456");
        assertThat(texts(certs.get(0).get("psd2_roles"))).containsExactly("PSP_AI", "PSP_PI", "PSP_IC");
        assertThat(certs.get(0).get("nca_id").asString()).isEqualTo("GB-FCA");
    }

    @Test
    @Order(2)
    void certificateValidationAndManualOnboarding() throws Exception {
        ObjectNode noPsd2 = obj("tpp_id", tppId, "certificate_type", "QWAC",
                "certificate_pem", TestCerts.valid(null, null, null).pem());
        assertThat(post("/v1/open-banking/tpp-certificates", noPsd2, key(), TENANT, 422).get("code").asString())
                .isEqualTo("psd2_statement_missing");
        Instant now = Instant.now();
        ObjectNode expired = obj("tpp_id", tppId, "certificate_type", "QWAC", "certificate_pem",
                TestCerts.issue("PSDGB-FCA-123456", List.of("PSP_AI", "PSP_PI", "PSP_IC"), "GB-FCA",
                        now.minusSeconds(30 * 86_400), now.minusSeconds(86_400)).pem());
        assertThat(post("/v1/open-banking/tpp-certificates", expired, key(), TENANT, 422).get("code").asString())
                .isEqualTo("certificate_expired");
        ObjectNode narrow = obj("tpp_id", tppId, "certificate_type", "QWAC", "certificate_pem",
                TestCerts.valid("PSDGB-FCA-123456", List.of("PSP_AI"), "GB-FCA").pem());
        assertThat(post("/v1/open-banking/tpp-certificates", narrow, key(), TENANT, 422).get("code").asString())
                .isEqualTo("roles_not_covered");
        ObjectNode dup = obj("tpp_id", tppId, "certificate_type", "QSEAL", "certificate_pem", tppCert.pem());
        assertThat(post("/v1/open-banking/tpp-certificates", dup, key(), TENANT, 409).get("code").asString())
                .isEqualTo("duplicate_certificate");
        ObjectNode qwac = obj("tpp_id", tppId, "certificate_type", "QWAC", "certificate_pem",
                TestCerts.valid("PSDGB-FCA-123456", List.of("PSP_AI", "PSP_PI", "PSP_IC"), "GB-FCA").pem());
        JsonNode added = post("/v1/open-banking/tpp-certificates", qwac, key(), TENANT, 201);
        assertThat(added.get("status").asString()).isEqualTo("VALID");
        JsonNode revoked = post("/v1/open-banking/tpp-certificates/" + added.get("id").asString() + "/revoke",
                obj("reason", "Key compromise"), key(), TENANT, 200);
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        post("/v1/open-banking/tpp-certificates/" + added.get("id").asString() + "/revoke",
                obj("reason", "again"), key(), TENANT, 409);

        ObjectNode bad = obj("name", "Manual TPP", "authorisation_number", "777", "competent_authority", "GB-FCA",
                "country", "GB");
        bad.putArray("roles").add("ASPSP");
        assertThat(post("/v1/open-banking/tpps", bad, key(), TENANT, 422).get("code").asString()).isEqualTo("invalid_roles");
        ObjectNode manual = obj("name", "Manual TPP", "authorisation_number", "777", "competent_authority", "GB-FCA",
                "country", "GB");
        manual.putArray("roles").add("AISP");
        String manualId = post("/v1/open-banking/tpps", manual, key(), TENANT, 201).get("id").asString();
        assertThat(post("/v1/open-banking/tpps/" + manualId + "/verify", null, key(), TENANT, 422).get("code").asString())
                .isEqualTo("no_valid_certificate");
        JsonNode suspended = post("/v1/open-banking/tpps/" + manualId + "/suspend", obj("reason", "Regulator request"),
                key(), TENANT, 200);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        post("/v1/open-banking/tpps/" + manualId + "/suspend", obj("reason", "x"), key(), TENANT, 409);
        assertThat(post("/v1/open-banking/tpps/" + manualId + "/reactivate", obj("reason", "ok"), key(), TENANT, 422)
                .get("code").asString()).isEqualTo("no_valid_certificate");
    }

    // ------------------------------------------------------------------ 2. AIS

    @Test
    @Order(3)
    void aisConsentAuthoriseSelectApproveAndRevoke() throws Exception {
        ObjectNode badPerms = aisConsent(null);
        badPerms.putArray("permissions").add("ReadTransactionsDetail");
        assertThat(post("/v1/open-banking/consents", badPerms, key(), TENANT, 422).get("code").asString())
                .isEqualTo("invalid_permissions");
        ObjectNode tooLong = aisConsent(OffsetDateTime.now(ZoneOffset.UTC).plusDays(200).toString());
        assertThat(post("/v1/open-banking/consents", tooLong, key(), TENANT, 422).get("code").asString())
                .isEqualTo("consent_too_long");

        JsonNode consent = post("/v1/open-banking/consents", aisConsent(null), key(), TENANT, 201);
        String consentId = consent.get("id").asString();
        assertThat(consent.get("status").asString()).isEqualTo("AWAITING_AUTHORISATION");
        assertThat(consent.get("external_consent_id").asString()).startsWith("ais-");
        assertThat(OffsetDateTime.parse(consent.get("expires_at").asString()))
                .isAfter(OffsetDateTime.now().plusDays(89)).isBefore(OffsetDateTime.now().plusDays(91));

        ObjectNode wrongRedirect = obj("sca_approach", "REDIRECT", "redirect_uri", "https://evil.example/cb",
                "code_challenge", "abc");
        assertThat(post("/v1/open-banking/consents/" + consentId + "/authorise", wrongRedirect, key(), TENANT, 422)
                .get("code").asString()).isEqualTo("redirect_uri_not_registered");
        assertThat(post("/v1/open-banking/consents/" + consentId + "/authorise", obj("sca_approach", "EMBEDDED"), key(),
                TENANT, 422).get("code").asString()).isEqualTo("sca_approach_not_supported");
        JsonNode auth = post("/v1/open-banking/consents/" + consentId + "/authorise",
                obj("sca_approach", "REDIRECT", "redirect_uri", REDIRECT, "state", "s1", "code_challenge", "E9Melhoa2Owv"),
                key(), TENANT, 200);
        String authId = auth.get("id").asString();
        assertThat(auth.get("status").asString()).isEqualTo("STARTED");

        assertThat(post("/v1/open-banking/authorisations/" + authId + "/approve", obj("authentication_method", "passkey"),
                key(), TENANT, 409).get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/open-banking/authorisations/" + authId + "/select-accounts",
                selection(customer, strangerAcc), key(), TENANT, 422).get("code").asString()).isEqualTo("account_not_owned");
        JsonNode selected = post("/v1/open-banking/authorisations/" + authId + "/select-accounts",
                selection(customer, acc1, acc2), key(), TENANT, 200);
        assertThat(selected.get("status").asString()).isEqualTo("ACCOUNTS_SELECTED");
        JsonNode approved = post("/v1/open-banking/authorisations/" + authId + "/approve",
                obj("authentication_method", "passkey"), key(), TENANT, 200);
        assertThat(approved.get("status").asString()).isEqualTo("FINALISED");
        JsonNode authorised = get("/v1/open-banking/consents/" + consentId, TENANT, 200);
        assertThat(authorised.get("status").asString()).isEqualTo("AUTHORISED");
        assertThat(authorised.get("customer_id").asString()).isEqualTo(customer.toString());
        JsonNode accounts = get("/v1/open-banking/consents/" + consentId + "/accounts", TENANT, 200).get("data");
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0).get("account_resource_id").asString()).startsWith("acc-");

        post("/v1/open-banking/consents/" + consentId + "/authorise", obj("sca_approach", "DECOUPLED"), key(), TENANT, 409);
        assertThat(post("/v1/open-banking/consents/" + consentId + "/revoke", obj("revoked_by", "BANK"), key(), TENANT, 422)
                .get("code").asString()).isEqualTo("invalid_revoked_by");
        JsonNode revoked = post("/v1/open-banking/consents/" + consentId + "/revoke",
                obj("revoked_by", "CUSTOMER", "reason", "No longer used"), key(), TENANT, 200);
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("revoked_by").asString()).isEqualTo("CUSTOMER");
        post("/v1/open-banking/consents/" + consentId + "/revoke", obj("revoked_by", "TPP"), key(), TENANT, 409);
        assertThat(count("openbanking.ob_consent.authorised")).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. PIS

    @Test
    @Order(4)
    void pisConsentInitiatesCorePaymentOnce() throws Exception {
        String consentId = authorisedConsent(pisConsent("100.00"), acc1);
        ObjectNode wrong = initiation(consentId, "150.00", null);
        assertThat(post("/v1/open-banking/payment-initiations", wrong, key(), TENANT, 422).get("code").asString())
                .isEqualTo("payment_does_not_match_consent");

        String k = key();
        JsonNode init = post("/v1/open-banking/payment-initiations", initiation(consentId, "100.00", null), k, TENANT, 201);
        String initId = init.get("id").asString();
        assertThat(init.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(init.get("external_payment_id").asString()).startsWith("pmt-");
        assertThat(init.get("debtor_account_id").asString()).isEqualTo(acc1.toString());
        UUID paymentId = UUID.fromString(init.get("payment_id").asString());
        FakeCore.Call call = core.calls("payment-service", "POST", "/v1/payments").getLast();
        assertThat(call.key()).isEqualTo("obi:" + initId);
        assertThat(call.body().get("channel").asString()).isEqualTo("OPEN_BANKING");
        assertThat(call.body().get("debtor_account_id").asString()).isEqualTo(acc1.toString());
        assertThat(call.body().get("creditor_account_id_type").asString()).isEqualTo("IBAN");
        assertThat(call.body().get("customer_id").asString()).isEqualTo(customer.toString());
        assertThat(call.body().get("ob_consent_id").asString()).isEqualTo(consentId);
        assertThat(call.body().get("payment_type").asString()).isEqualTo("INSTANT");

        // idempotent replay returns the same initiation without a second core payment
        int paymentsBefore = core.calls("payment-service", "POST", "/v1/payments").size();
        JsonNode replay = post("/v1/open-banking/payment-initiations", initiation(consentId, "100.00", null), k, TENANT, 201);
        assertThat(replay.get("id").asString()).isEqualTo(initId);
        assertThat(core.calls("payment-service", "POST", "/v1/payments")).hasSize(paymentsBefore);

        assertThat(get("/v1/open-banking/consents/" + consentId, TENANT, 200).get("status").asString()).isEqualTo("CONSUMED");
        assertThat(post("/v1/open-banking/payment-initiations", initiation(consentId, "100.00", null), key(), TENANT, 422)
                .get("code").asString()).isEqualTo("consent_not_authorised");

        core.setPaymentStatus(paymentId, "SETTLED");
        JsonNode refreshed = post("/v1/open-banking/payment-initiations/" + initId + "/refresh-status", null, key(), TENANT, 200);
        assertThat(refreshed.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(post("/v1/open-banking/payment-initiations/" + initId + "/cancel", obj("reason", "x"), key(), TENANT, 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        JsonNode log = get("/v1/open-banking/consents/" + consentId + "/access-log", TENANT, 200).get("data");
        assertThat(log.size()).isGreaterThanOrEqualTo(4);
        List<Integer> statuses = new java.util.ArrayList<>();
        log.forEach(e -> statuses.add(e.get("http_status").asInt()));
        assertThat(statuses).contains(201, 422, 200);
        assertThat(log.get(0).get("interaction_id").asString()).isEqualTo("fapi-1");
        assertThat(log.get(0).get("psu_present").asBoolean()).isTrue();
        assertThat(get("/v1/open-banking/consents/" + consentId, TENANT, 200).get("last_accessed_at").isNull()).isFalse();
    }

    @Test
    @Order(5)
    void futureDatedInitiationCanBeCancelled() throws Exception {
        String consentId = authorisedConsent(pisConsent("40.00"), acc2);
        String date = LocalDate.now(ZoneOffset.UTC).plusDays(5).toString();
        JsonNode init = post("/v1/open-banking/payment-initiations", initiation(consentId, "40.00", date), key(), TENANT, 201);
        assertThat(init.get("status").asString()).isEqualTo("PENDING");
        JsonNode cancelled = post("/v1/open-banking/payment-initiations/" + init.get("id").asString() + "/cancel",
                obj("reason", "Customer changed mind"), key(), TENANT, 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(core.payment(UUID.fromString(init.get("payment_id").asString())).get("status").asString())
                .isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ 4. CBPII

    @Test
    @Order(6)
    void fundsConfirmationAnswersFromLedgerAvailableBalance() throws Exception {
        ObjectNode c = obj("tpp_id", tppId, "ob_standard_profile_id", profileId, "consent_type", "CBPII");
        c.putArray("permissions").add("ReadFundsConfirmation");
        String consentId = authorisedConsent(c, acc1);
        JsonNode yes = post("/v1/open-banking/funds-confirmations", funds(consentId, acc1, "100.00", "GBP"), key(), TENANT, 201);
        assertThat(yes.get("funds_available").asBoolean()).isTrue();
        JsonNode no = post("/v1/open-banking/funds-confirmations", funds(consentId, acc1, "1000.00", "GBP"), key(), TENANT, 201);
        assertThat(no.get("funds_available").asBoolean()).isFalse();
        assertThat(post("/v1/open-banking/funds-confirmations", funds(consentId, acc2, "1.00", "GBP"), key(), TENANT, 403)
                .get("code").asString()).isEqualTo("account_not_in_consent");
        assertThat(post("/v1/open-banking/funds-confirmations", funds(consentId, acc1, "1.00", "EUR"), key(), TENANT, 422)
                .get("code").asString()).isEqualTo("currency_mismatch");
        // only the two successful checks read the ledger (rejected requests never reach it)
        assertThat(core.calls("ledger-service", "GET", "/v1/ledger-accounts/")).hasSize(2);
        assertThat(count("openbanking.ob_funds_confirmation.checked")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 5. deny, expiry, gateway log, isolation

    @Test
    @Order(7)
    void denyExpireAndSessionTimeout() throws Exception {
        String denied = post("/v1/open-banking/consents", aisConsent(null), key(), TENANT, 201).get("id").asString();
        String authId = post("/v1/open-banking/consents/" + denied + "/authorise", obj("sca_approach", "DECOUPLED"), key(),
                TENANT, 200).get("id").asString();
        JsonNode deny = post("/v1/open-banking/authorisations/" + authId + "/deny", obj("reason", "Not me"), key(), TENANT, 200);
        assertThat(deny.get("status").asString()).isEqualTo("FAILED");
        assertThat(get("/v1/open-banking/consents/" + denied, TENANT, 200).get("status").asString()).isEqualTo("REJECTED");
        post("/v1/open-banking/authorisations/" + authId + "/approve", obj("authentication_method", "otp"), key(), TENANT, 409);

        // session timeout
        String c2 = post("/v1/open-banking/consents", aisConsent(null), key(), TENANT, 201).get("id").asString();
        String a2 = post("/v1/open-banking/consents/" + c2 + "/authorise", obj("sca_approach", "DECOUPLED"), key(),
                TENANT, 200).get("id").asString();
        asTenant(() -> jdbc.sql("update ob_authorisation set expires_at = now() - interval '1 minute' where id = ?::uuid")
                .param(a2).update());
        assertThat(post("/v1/open-banking/authorisations/" + a2 + "/select-accounts", selection(customer, acc1), key(),
                TENANT, 422).get("code").asString()).isEqualTo("authorisation_expired");

        // consent expiry
        asTenant(() -> jdbc.sql("update ob_consent set expires_at = now() - interval '1 minute' where id = ?::uuid")
                .param(c2).update());
        JsonNode result = post("/v1/open-banking/consents/expire-due", null, key(), TENANT, 200);
        assertThat(result.get("consents_expired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(result.get("authorisations_expired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/open-banking/consents/" + c2, TENANT, 200).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(count("openbanking.ob_consent.expired")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(8)
    void gatewayAccessLogValidationIdempotencyAndTenantIsolation() throws Exception {
        ObjectNode entry = obj("tpp_id", UUID.randomUUID().toString(), "endpoint", "/accounts", "http_method", "GET");
        entry.put("http_status", 200);
        assertThat(post("/v1/open-banking/access-logs", entry, key(), TENANT, 422).get("code").asString())
                .isEqualTo("tpp_not_found");
        entry.put("tpp_id", tppId);
        JsonNode logged = post("/v1/open-banking/access-logs", entry, key(), TENANT, 201);
        assertThat(logged.get("http_method").asString()).isEqualTo("GET");

        HttpResponse<String> noKey = send("POST", "/v1/open-banking/consents", aisConsent(null), null, TENANT);
        assertThat(noKey.statusCode()).isEqualTo(400);

        get("/v1/open-banking/tpps/" + tppId, OTHER, 404);
        assertThat(get("/v1/open-banking/tpps", OTHER, 200).get("data")).isEmpty();
        assertThat(get("/v1/open-banking/consents", OTHER, 200).get("data")).isEmpty();
        assertThat(post("/v1/open-banking/tpps/" + tppId + "/verify", null, key(), OTHER, 404).get("code").asString())
                .isEqualTo("not_found");
    }

    // ------------------------------------------------------------------ helpers

    private String authorisedConsent(ObjectNode consentBody, UUID account) throws Exception {
        String consentId = post("/v1/open-banking/consents", consentBody, key(), TENANT, 201).get("id").asString();
        String authId = post("/v1/open-banking/consents/" + consentId + "/authorise", obj("sca_approach", "DECOUPLED"),
                key(), TENANT, 200).get("id").asString();
        post("/v1/open-banking/authorisations/" + authId + "/select-accounts", selection(customer, account), key(), TENANT, 200);
        post("/v1/open-banking/authorisations/" + authId + "/approve", obj("authentication_method", "app_push"), key(),
                TENANT, 200);
        return consentId;
    }

    private ObjectNode aisConsent(String expiresAt) {
        ObjectNode c = obj("tpp_id", tppId, "ob_standard_profile_id", profileId, "consent_type", "AIS");
        c.putArray("permissions").add("ReadAccountsDetail").add("ReadBalances").add("ReadTransactionsDetail")
                .add("ReadTransactionsCredits");
        if (expiresAt != null) {
            c.put("expires_at", expiresAt);
        }
        return c;
    }

    private ObjectNode pisConsent(String amount) {
        ObjectNode c = obj("tpp_id", tppId, "ob_standard_profile_id", profileId, "consent_type", "PIS");
        c.putArray("permissions").add("CreateDomesticPayment");
        ObjectNode i = c.putObject("initiation");
        i.put("instructed_amount", amount);
        i.put("currency", "GBP");
        i.put("creditor_name", "Bob Smith");
        i.put("creditor_account", "GB82 WEST 1234 5698 7654 32");
        return c;
    }

    private ObjectNode initiation(String consentId, String amount, String executionDate) {
        ObjectNode b = obj("ob_consent_id", consentId, "tpp_id", tppId, "payment_product", "instant-domestic-payments",
                "amount", amount, "currency", "GBP", "creditor_name", "Bob Smith",
                "creditor_account", "GB82WEST12345698765432", "remittance_information", "Invoice 42");
        if (executionDate != null) {
            b.put("requested_execution_date", executionDate);
            b.put("payment_product", "domestic-scheduled-payments");
        }
        return b;
    }

    private ObjectNode funds(String consentId, UUID account, String amount, String currency) {
        return obj("ob_consent_id", consentId, "tpp_id", tppId, "account_id", account.toString(), "amount", amount,
                "currency", currency);
    }

    private ObjectNode selection(UUID customerId, UUID... accounts) {
        ObjectNode s = obj("customer_id", customerId.toString());
        var arr = s.putArray("account_ids");
        for (UUID a : accounts) {
            arr.add(a.toString());
        }
        return s;
    }

    private String ssa(TestCerts.Issued cert, String softwareId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("software_id", softwareId);
        claims.put("org_name", "Acme Fintech Ltd");
        claims.put("org_id", "PSDGB-FCA-123456");
        claims.put("software_roles", List.of("AISP", "PISP", "CBPII"));
        claims.put("software_redirect_uris", List.of(REDIRECT));
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().getEpochSecond() + 600);
        return TestCerts.ssa(cert.keys(), claims);
    }

    private long count(String eventType) throws Exception {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where event_type = ?").param(eventType)
                .query(Long.class).single());
    }

    private <T> T asTenant(java.util.concurrent.Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private ObjectNode obj(String... kv) {
        ObjectNode n = json.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n;
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(x -> out.add(x.asString()));
        return out;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, Object body, String key, String tenant, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key, tenant);
        assertThat(r.statusCode()).as("POST %s -> %s", path, r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant, int expected) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as("GET %s -> %s", path, r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, Object body, String key, String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant)
                .header("X-User", "tpp-gateway")
                .header("x-fapi-interaction-id", "fapi-1")
                .header("PSU-IP-Address", "203.0.113.7");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null ? (method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("")) : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
