package io.meridian.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * statement-service end to end over HTTP against PostgreSQL 18; deposit-service and ledger-service are stubbed
 * behind a mocked {@link ServiceClient} that serves an account, its ledger balances, entries and journal entries,
 * and interest capitalisations. Every downstream call is recorded and asserted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.statement.download-secret=test-secret", "meridian.statement.link-ttl-seconds=600"})
@Import({MeridianTestContainers.class, StatementFlowIT.ClockConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatementFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000e001";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000e002";
    static final String ACCOUNT = "0199a000-0000-7000-8000-0000000000a1";
    static final String LEDGER_ACCOUNT = "0199a000-0000-7000-8000-0000000000b1";
    static final String CUSTOMER = "0199a000-0000-7000-8000-0000000000c1";
    static final String JE_TRANSFER = "0199a000-0000-7000-8000-0000000000d1";
    static final String JE_FEE = "0199a000-0000-7000-8000-0000000000d2";
    static final String JE_INTEREST = "0199a000-0000-7000-8000-0000000000d3";
    static final String DOCUMENT = "0199a000-0000-7000-8000-0000000000f1";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<JsonNode> documents = new CopyOnWriteArrayList<>();
    final List<String> keys = new CopyOnWriteArrayList<>();
    volatile BigDecimal closingOverride;
    volatile boolean ledgerDown;
    String statementId;

    @BeforeEach
    void stubs() {
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> route(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(eq("document-service"), eq("/v1/documents"), any(), anyString())).thenAnswer(i -> {
            JsonNode body = json.valueToTree(i.getArgument(2));
            documents.add(body);
            keys.add(i.getArgument(3));
            return json.readTree("{\"id\":\"" + DOCUMENT + "\"}");
        });
    }

    // ------------------------------------------------------------------ downstream stubs

    private JsonNode route(String service, String path) {
        calls.add(service + " " + path);
        if (service.equals("deposit-service")) {
            if (path.equals("/v1/deposit-accounts/" + ACCOUNT)) {
                return account();
            }
            if (path.startsWith("/v1/deposit-accounts/")) {
                throw ApiException.notFound("deposit account", path);
            }
            if (path.startsWith("/v1/deposit-accounts?customer_id=" + CUSTOMER)) {
                ObjectNode page = json.createObjectNode();
                page.putArray("data").add(account());
                return page.put("has_more", false);
            }
            if (path.startsWith("/v1/interest-capitalisations?deposit_account_id=" + ACCOUNT)) {
                return json.readTree("""
                        {"data":[
                          {"id":"%s","deposit_account_id":"%s","kind":"CREDIT","gross_amount":"3.10","tax_amount":"0.62",
                           "net_amount":"2.48","currency":"EUR","created_at":"2026-02-01T00:30:00Z"},
                          {"id":"%s","deposit_account_id":"%s","kind":"CREDIT","gross_amount":"2.80","tax_amount":"0.56",
                           "net_amount":"2.24","currency":"EUR","created_at":"2026-03-01T00:30:00Z"},
                          {"id":"%s","deposit_account_id":"%s","kind":"DEBIT","gross_amount":"4.00","tax_amount":"0",
                           "net_amount":"4.00","currency":"EUR","created_at":"2026-03-01T00:30:00Z"},
                          {"id":"%s","deposit_account_id":"%s","kind":"CREDIT","gross_amount":"9.99","tax_amount":"2.00",
                           "net_amount":"7.99","currency":"EUR","created_at":"2025-12-01T00:30:00Z"}],
                         "has_more":false}""".formatted(UUID.randomUUID(), ACCOUNT, UUID.randomUUID(), ACCOUNT,
                        UUID.randomUUID(), ACCOUNT, UUID.randomUUID(), ACCOUNT));
            }
        }
        if (service.equals("ledger-service")) {
            if (ledgerDown) {
                throw new ApiException(502, "upstream_error", "ledger-service is not reachable");
            }
            String[] pq = path.split("\\?", 2);
            if (pq[0].equals("/v1/ledger-accounts/" + LEDGER_ACCOUNT + "/balance-at")) {
                OffsetDateTime at = OffsetDateTime.parse(URLDecoder.decode(pq[1].substring(3), StandardCharsets.UTF_8));
                BigDecimal bal = at.isBefore(OffsetDateTime.parse("2026-09-01T00:00:00Z")) ? new BigDecimal("1000.00")
                        : closingOverride != null ? closingOverride : new BigDecimal("1481.25");
                return json.readTree("{\"ledger_account_id\":\"" + LEDGER_ACCOUNT + "\",\"currency\":\"EUR\","
                        + "\"posted_balance\":\"" + bal + "\",\"available_balance\":\"" + bal + "\"}");
            }
            if (pq[0].equals("/v1/ledger-accounts/" + LEDGER_ACCOUNT + "/entries")) {
                return entries();
            }
            if (pq[0].startsWith("/v1/journal-entries/")) {
                String id = pq[0].substring("/v1/journal-entries/".length());
                String type = id.equals(JE_FEE) ? "FEE" : id.equals(JE_INTEREST) ? "INTEREST" : "TRANSFER";
                return json.readTree("{\"id\":\"" + id + "\",\"entry_type\":\"" + type + "\",\"description\":\"" + type
                        + " entry\",\"correlation_id\":\"corr-" + type.toLowerCase() + "\"}");
            }
        }
        throw new IllegalArgumentException("unexpected call " + service + " " + path);
    }

    private ObjectNode account() {
        return (ObjectNode) json.readTree("""
                {"id":"%s","account_number":"12345678","iban":"GB82WEST12345698765432","currency":"EUR",
                 "customer_id":"%s","ledger_account_id":"%s","status":"ACTIVE"}""".formatted(ACCOUNT, CUSTOMER, LEDGER_ACCOUNT));
    }

    /** Page 1 of entries newest-first, as ledger-service returns them. */
    private JsonNode entries() {
        ObjectNode page = json.createObjectNode();
        ArrayNode data = page.putArray("data");
        data.add(entry(UUID.randomUUID(), JE_INTEREST, "CREDIT", "1.25", "1481.25", 13, "2026-09-30T23:00:00Z", null));
        data.add(entry(UUID.randomUUID(), JE_FEE, "DEBIT", "20.00", "1480.00", 12, "2026-09-10T08:00:00Z", "Monthly fee"));
        data.add(entry(UUID.randomUUID(), JE_TRANSFER, "CREDIT", "500.00", "1500.00", 11, "2026-09-03T09:30:00Z",
                "Salary, ACME"));
        return page.put("has_more", false);
    }

    private ObjectNode entry(UUID id, String je, String dir, String amount, String after, int version, String at, String desc) {
        ObjectNode e = json.createObjectNode();
        e.put("id", id.toString());
        e.put("journal_entry_id", je);
        e.put("ledger_account_id", LEDGER_ACCOUNT);
        e.put("direction", dir);
        e.put("amount", amount);
        e.put("currency", "EUR");
        e.put("balance_after", after);
        e.put("account_version", version);
        e.put("created_at", at);
        e.put("effective_at", at);
        if (desc != null) {
            e.put("description", desc);
        }
        return e;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void generatesAStatementFromLedgerEntries() throws Exception {
        JsonNode s = post("/v1/statements", body("CSV"), 201);
        statementId = s.get("id").asString();
        assertThat(s.get("status").asString()).isEqualTo("READY");
        assertThat(s.get("statement_no").asInt()).isEqualTo(1);
        assertThat(s.hasNonNull("failure_reason")).isFalse();
        assertThat(s.get("document_id").asString()).isEqualTo(DOCUMENT);
        JsonNode doc = documents.getFirst();
        assertThat(doc.get("owner_type").asString()).isEqualTo("ACCOUNT");
        assertThat(doc.get("owner_id").asString()).isEqualTo(ACCOUNT);
        assertThat(doc.get("document_type").asString()).isEqualTo("ACCOUNT_STATEMENT");
        assertThat(doc.get("file_name").asString()).isEqualTo("statement-1-2026-09-30.csv");
        assertThat(doc.get("metadata").get("sha256").asString()).isEqualTo(s.get("checksum").asString());
        assertThat(keys.getFirst()).isEqualTo("statement-document:" + statementId + ":" + s.get("checksum").asString());
        assertThat(dec(s, "opening_balance")).isEqualByComparingTo("1000.00");
        assertThat(dec(s, "total_credits")).isEqualByComparingTo("501.25");
        assertThat(dec(s, "total_debits")).isEqualByComparingTo("20.00");
        assertThat(dec(s, "closing_balance")).isEqualByComparingTo("1481.25");
        assertThat(dec(s, "interest_earned")).isEqualByComparingTo("1.25");
        assertThat(dec(s, "fees_charged")).isEqualByComparingTo("20.00");
        assertThat(s.get("line_count").asInt()).isEqualTo(3);
        assertThat(s.get("checksum").asString()).hasSize(64);
        assertThat(calls).anyMatch(c -> c.startsWith("ledger-service /v1/ledger-accounts/" + LEDGER_ACCOUNT
                + "/balance-at?at=2026-08-31T23%3A59%3A59.999999Z"));
        assertThat(calls).anyMatch(c -> c.startsWith("ledger-service /v1/ledger-accounts/" + LEDGER_ACCOUNT
                + "/entries?from=2026-09-01T00%3A00Z&to=2026-10-01T00%3A00Z"));
        assertThat(calls.stream().filter(c -> c.contains("/v1/journal-entries/")).count()).isEqualTo(3);
        assertThat(events("accounts.statement.generated")).isEqualTo(1);

        JsonNode lines = get("/v1/statements/" + statementId + "/lines?limit=2", TENANT);
        assertThat(lines.get("data")).hasSize(2);
        assertThat(lines.get("has_more").asBoolean()).isTrue();
        assertThat(lines.get("data").get(0).get("line_no").asInt()).isEqualTo(1);
        assertThat(lines.get("data").get(0).get("transaction_code").asString()).isEqualTo("PMNT-RCDT-BOOK");
        assertThat(lines.get("data").get(1).get("transaction_code").asString()).isEqualTo("ACMT-MDOP-CHRG");
        JsonNode rest = get("/v1/statements/" + statementId + "/lines?limit=2&cursor="
                + lines.get("next_cursor").asString(), TENANT);
        assertThat(rest.get("data")).hasSize(1);
        assertThat(dec(rest.get("data").get(0), "running_balance")).isEqualByComparingTo("1481.25");
    }

    @Test
    @Order(2)
    void downloadsVerifiedContentAndSignedLinks() throws Exception {
        HttpResponse<String> file = send("GET", "/v1/statements/" + statementId + "/download", null, null, TENANT);
        assertThat(file.statusCode()).as(file.body()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(file.headers().firstValue("Content-Disposition").orElse("")).contains("statement-1-2026-09-30.csv");
        assertThat(file.body()).contains("Opening balance").contains("\"Salary, ACME\"").contains("1481.25");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(file.headers().firstValue("X-Content-SHA256")).contains(sha);
        assertThat(get("/v1/statements/" + statementId, TENANT).get("checksum").asString()).isEqualTo(sha);

        JsonNode link = get("/v1/statements/" + statementId + "/download-link", TENANT);
        assertThat(link.get("checksum").asString()).isEqualTo(sha);
        assertThat(link.get("expires_at").asString()).isNotBlank();
        String url = link.get("url").asString();
        assertThat(url).contains("expires=").contains("signature=");
        assertThat(send("GET", url, null, null, TENANT).statusCode()).isEqualTo(200);
        assertThat(send("GET", url.replaceAll("signature=.{4}", "signature=0000"), null, null, TENANT).statusCode())
                .isEqualTo(403);
        assertThat(send("GET", url, null, null, OTHER_TENANT).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/statements/" + statementId + "/download?expires=soon&signature=x", null, null, TENANT)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @Order(3)
    void otherFormatsAndValidation() throws Exception {
        JsonNode camt = post("/v1/statements", body("CAMT_053"), 201);
        assertThat(camt.get("statement_no").asInt()).isEqualTo(1);
        HttpResponse<String> xml = send("GET", "/v1/statements/" + camt.get("id").asString() + "/download", null, null, TENANT);
        assertThat(xml.headers().firstValue("Content-Type").orElse("")).startsWith("application/xml");
        assertThat(xml.body()).contains("<Cd>CLBD</Cd>").contains("<NbOfNtries>3</NbOfNtries>");
        JsonNode pdf = post("/v1/statements", body("PDF"), 201);
        HttpResponse<String> p = send("GET", "/v1/statements/" + pdf.get("id").asString() + "/download", null, null, TENANT);
        assertThat(p.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(p.body()).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        String pdfSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.body().getBytes(StandardCharsets.US_ASCII)));
        assertThat(pdfSha).isEqualTo(pdf.get("checksum").asString());

        assertThat(post("/v1/statements", body("CSV"), 409).get("code").asString()).isEqualTo("duplicate_statement");
        assertThat(post("/v1/statements", body("CSV").replace("\"EUR\"", "\"USD\""), 422).get("code").asString())
                .isEqualTo("currency_mismatch");
        assertThat(post("/v1/statements", body("CSV").replace("2026-09-30", "2026-10-06"), 422).get("code").asString())
                .isEqualTo("period_not_closed");
        assertThat(post("/v1/statements", body("CSV").replace(ACCOUNT, UUID.randomUUID().toString()), 422)
                .get("code").asString()).isEqualTo("account_not_found");
        HttpResponse<String> noKey = send("POST", "/v1/statements", body("CSV"), null, TENANT);
        assertThat(noKey.statusCode()).isEqualTo(400);
        HttpResponse<String> first = send("POST", "/v1/statements", body("JSON"), "stm-4", TENANT);
        HttpResponse<String> replay = send("POST", "/v1/statements", body("JSON"), "stm-4", TENANT);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(json.readTree(first.body()).get("id").asString());
        JsonNode august = post("/v1/statements", body("CSV").replace("2026-09-01", "2026-08-01")
                .replace("2026-09-30", "2026-08-31"), 201);
        assertThat(august.get("statement_no").asInt()).isEqualTo(2); // a new period gets the next number
        assertThat(august.get("status").asString()).isEqualTo("FAILED"); // the stub ledger only has September data
    }

    @Test
    @Order(4)
    void inconsistentOrUnavailableLedgerFailsAndCanBeRegenerated() throws Exception {
        closingOverride = new BigDecimal("1481.24");
        JsonNode failed = post("/v1/statements", body("OFX"), 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failure_reason").asString()).contains("Ledger data is inconsistent");
        assertThat(failed.hasNonNull("document_id")).isFalse();
        assertThat(events("accounts.statement.failed")).isEqualTo(2);
        String id = failed.get("id").asString();
        assertThat(send("GET", "/v1/statements/" + id + "/download", null, null, TENANT).statusCode()).isEqualTo(409);
        assertThat(post("/v1/statements/" + statementId + "/regenerate", null, 409).get("code").asString())
                .isEqualTo("invalid_transition");
        closingOverride = null;
        ledgerDown = true;
        JsonNode down = post("/v1/statements/" + id + "/regenerate", null, 200);
        assertThat(down.get("status").asString()).isEqualTo("FAILED");
        assertThat(down.get("failure_reason").asString()).startsWith("upstream_error");
        ledgerDown = false;
        JsonNode ok = post("/v1/statements/" + id + "/regenerate", null, 200);
        assertThat(ok.get("status").asString()).isEqualTo("READY");
        assertThat(ok.get("line_count").asInt()).isEqualTo(3);
        assertThat(ok.hasNonNull("failure_reason")).isFalse();
        assertThat(events("accounts.statement.failed")).isEqualTo(3);
    }

    @Test
    @Order(5)
    void resendDeliversOnTheRequestedChannel() throws Exception {
        assertThat(post("/v1/statements/" + statementId + "/resend", "{\"delivery_channel\":\"EMAIL\"}", 422)
                .get("code").asString()).isEqualTo("email_required");
        JsonNode delivered = post("/v1/statements/" + statementId + "/resend",
                "{\"delivery_channel\":\"EMAIL\",\"email\":\"jane@example.com\"}", 200);
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.get("delivered_at").isNull()).isFalse();
        String payload = asTenant(() -> jdbc.sql("""
                select payload::text from outbox where tenant_id = ?::uuid and event_type = 'accounts.statement.delivered'""")
                .param(TENANT).query(String.class).single());
        assertThat(json.readTree(payload).get("delivery").get("recipient").asString()).isEqualTo("jane@example.com");
        post("/v1/statements/" + statementId + "/resend", "{\"delivery_channel\":\"IN_APP\"}", 200);
        assertThat(events("accounts.statement.delivered")).isEqualTo(2);
    }

    @Test
    @Order(6)
    void schedulesValidateAndDriveTheCycle() throws Exception {
        assertThat(post("/v1/statement-schedules", """
                {"deposit_account_id":"%s","formats":["PDF","XLS"]}""".formatted(ACCOUNT), 422)
                .get("code").asString()).isEqualTo("invalid_format");
        assertThat(post("/v1/statement-schedules", """
                {"deposit_account_id":"%s","formats":{"formats":["PDF"]}}""".formatted(ACCOUNT), 400)
                .get("code").asString()).isEqualTo("malformed_request");
        assertThat(post("/v1/statement-schedules", """
                {"deposit_account_id":"%s","formats":[]}""".formatted(ACCOUNT), 422)
                .get("code").asString()).isEqualTo("formats_required");
        assertThat(post("/v1/statement-schedules", """
                {"deposit_account_id":"%s","formats":["CSV"],"delivery_channel":"EMAIL"}""".formatted(ACCOUNT), 422)
                .get("code").asString()).isEqualTo("email_required");
        assertThat(post("/v1/statement-schedules", """
                {"deposit_account_id":"%s","formats":["CSV"],"cycle_day":32}""".formatted(ACCOUNT), 422)
                .get("code").asString()).isEqualTo("invalid_cycle_day");
        JsonNode schedule = post("/v1/statement-schedules", """
                {"deposit_account_id":"%s","formats":["CSV","MT940"],"frequency":"MONTHLY","cycle_day":30,
                 "delivery_channel":"EMAIL","email":"jane@example.com"}""".formatted(ACCOUNT), 201);
        assertThat(schedule.get("is_active").asBoolean()).isTrue();
        assertThat(schedule.get("formats")).hasSize(2);
        assertThat(schedule.get("formats").get(1).asString()).isEqualTo("MT940");

        assertThat(send("POST", "/v1/statements/cycle", null, UUID.randomUUID().toString(), TENANT).statusCode())
                .isEqualTo(400);
        JsonNode cycle = post("/v1/statements/cycle?business_date=2026-09-30", null, 200);
        assertThat(cycle.get("produced").asInt()).isEqualTo(2); // cycle period 2026-08-31..2026-09-30, both formats
        assertThat(cycle.get("statements").get(0).get("statement_no").asInt())
                .isEqualTo(cycle.get("statements").get(1).get("statement_no").asInt());
        JsonNode list = get("/v1/statements?deposit_account_id=" + ACCOUNT + "&format=MT940", TENANT);
        assertThat(list.get("data")).hasSize(1);
        JsonNode mt = list.get("data").get(0);
        assertThat(mt.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(mt.get("statement_no").asInt()).isEqualTo(3); // next number after September (1) and August (2)
        assertThat(post("/v1/statements/cycle?business_date=2026-09-30", null, 200).get("produced").asInt())
                .isZero(); // idempotent re-run
        assertThat(post("/v1/statements/cycle?business_date=2026-09-29", null, 200).get("produced").asInt())
                .isZero(); // not a cycle end
    }

    @Test
    @Order(7)
    void taxCertificatesSumInterestForTheYear() throws Exception {
        JsonNode cert = post("/v1/tax-certificates", """
                {"customer_id":"%s","certificate_type":"INTEREST_CERTIFICATE","tax_year":2026,"period_start":"2026-01-01",
                 "period_end":"2026-12-31","currency":"EUR"}""".formatted(CUSTOMER), 201);
        assertThat(cert.get("status").asString()).isEqualTo("READY");
        assertThat(dec(cert, "gross_interest")).isEqualByComparingTo("5.90");
        assertThat(dec(cert, "tax_withheld")).isEqualByComparingTo("1.18");
        assertThat(events("accounts.tax_certificate.generated")).isEqualTo(1);
        assertThat(post("/v1/tax-certificates", """
                {"customer_id":"%s","certificate_type":"INTEREST_CERTIFICATE","tax_year":2026,"period_start":"2026-01-01",
                 "period_end":"2026-12-31","currency":"EUR"}""".formatted(CUSTOMER), 409).get("code").asString())
                .isEqualTo("certificate_exists");
        JsonNode perAccount = post("/v1/tax-certificates", """
                {"customer_id":"%s","deposit_account_id":"%s","certificate_type":"WITHHOLDING_TAX_CERTIFICATE","tax_year":2025,
                 "period_start":"2025-01-01","period_end":"2025-12-31","currency":"EUR"}""".formatted(CUSTOMER, ACCOUNT), 201);
        assertThat(dec(perAccount, "gross_interest")).isEqualByComparingTo("9.99");
        assertThat(post("/v1/tax-certificates", """
                {"customer_id":"%s","deposit_account_id":"%s","certificate_type":"INTEREST_CERTIFICATE","tax_year":2026,
                 "period_start":"2026-01-01","period_end":"2026-12-31","currency":"EUR"}"""
                .formatted(UUID.randomUUID(), ACCOUNT), 422).get("code").asString()).isEqualTo("account_not_owned");
        assertThat(post("/v1/tax-certificates", """
                {"customer_id":"%s","certificate_type":"INTEREST_CERTIFICATE","tax_year":2025,"period_start":"2026-01-01",
                 "period_end":"2026-12-31","currency":"EUR"}""".formatted(CUSTOMER), 422).get("code").asString())
                .isEqualTo("tax_year_mismatch");
        HttpResponse<String> text = send("GET", "/v1/tax-certificates/" + cert.get("id").asString() + "/download", null,
                null, TENANT);
        assertThat(text.statusCode()).isEqualTo(200);
        assertThat(text.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(text.body()).contains("Gross interest : 5.90 EUR").contains("Tax withheld   : 1.18 EUR");
        JsonNode certLink = get("/v1/tax-certificates/" + cert.get("id").asString() + "/download-link", TENANT);
        assertThat(send("GET", certLink.get("url").asString(), null, null, TENANT).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(8)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/statements/" + statementId, null, null, OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/statements/" + statementId + "/lines", null, null, OTHER_TENANT).statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/statements", OTHER_TENANT).get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static String body(String format) {
        return """
                {"deposit_account_id":"%s","period_start":"2026-09-01","period_end":"2026-09-30",
                 "format":"%s","currency":"EUR","is_on_demand":true}""".formatted(ACCOUNT, format);
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "batch", body);
    }

    private int events(String type) throws Exception {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where tenant_id = ?::uuid and event_type = ?")
                .params(TENANT, type).query(Integer.class).single());
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return new BigDecimal(n.get(f).asString());
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "ops").header("Content-Type", "application/json");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
