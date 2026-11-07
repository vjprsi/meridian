package io.meridian.referencedata;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 (service connects as meridian_app, RLS enforced).
 * Reference data (currencies, countries, calendars, bank directory) is platform-global and shared by all tenants;
 * FX rates are tenant-scoped (tenant rates include margins) and isolated by row-level security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReferenceDataFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    String ecb;

    @BeforeAll
    void setUp() throws Exception {
        for (String[] c : new String[][] {{"USD", "US Dollar", "2"}, {"EUR", "Euro", "2"}, {"GBP", "Pound Sterling", "2"},
                {"JPY", "Yen", "0"}, {"KES", "Kenyan Shilling", "2"}}) {
            post("/v1/reference/currencies", """
                    {"code":"%s","name":"%s","minor_units":%s}""".formatted(c[0], c[1], c[2]), 201);
        }
        jdbc.sql("""
                insert into country (code, alpha3, numeric_code, name, currency_code, iban_length, is_eea)
                values ('DE','DEU','276','Germany','EUR',22,true), ('KE','KEN','404','Kenya','KES',null,false),
                       ('GB','GBR','826','United Kingdom','GBP',22,false)""").update();
        jdbc.sql("""
                insert into bank_directory (bic, bank_name, country, clearing_system, national_clearing_code, city, rail_participation)
                values ('COBADEFFXXX','Commerzbank','DE','DEBLZ','37040044','Koeln','["SEPA_INST"]'::jsonb),
                       ('WESTGB2LXXX','Westminster Test Bank','GB','GBDSC','999999','London',null),
                       ('CHASUS33XXX','JPMorgan Chase','US','USABA','021000021','New York',null)""").update();
        ecb = post("/v1/reference/fx-rate-sources", """
                {"code":"ECB","name":"European Central Bank","provider":"ECB"}""", 201).get("id").asString();
    }

    @Test
    @Order(1)
    void currencyValidationReplayAndEvents() throws Exception {
        HttpResponse<String> first = send("POST", "/v1/reference/currencies",
                "{\"code\":\"CHF\",\"name\":\"Swiss Franc\",\"numeric_code\":\"756\",\"cash_rounding_increment\":\"0.05\"}",
                "ccy-chf", TENANT);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        HttpResponse<String> replay = send("POST", "/v1/reference/currencies",
                "{\"code\":\"CHF\",\"name\":\"Swiss Franc\",\"numeric_code\":\"756\",\"cash_rounding_increment\":\"0.05\"}",
                "ccy-chf", TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(json.readTree(first.body()).get("id").asString());

        assertThat(code(post("/v1/reference/currencies", "{\"code\":\"XAU\",\"name\":\"Gold\",\"minor_units\":9}", 422)))
                .isEqualTo("invalid_minor_units");
        assertThat(code(post("/v1/reference/currencies",
                "{\"code\":\"XTS\",\"name\":\"Test\",\"minor_units\":0,\"cash_rounding_increment\":\"0.5\"}", 422)))
                .isEqualTo("invalid_cash_rounding_increment");
        assertThat(code(post("/v1/reference/currencies", "{\"code\":\"usd\",\"name\":\"bad\"}", 400))).isEqualTo("validation_failed");
        assertThat(code(post("/v1/reference/currencies", "{\"code\":\"EUR\",\"name\":\"Euro again\"}", 409))).isEqualTo("duplicate");

        String chf = json.readTree(first.body()).get("id").asString();
        assertThat(code(patch("/v1/reference/currencies/" + chf, "{\"code\":\"CHE\"}", "1", 422))).isEqualTo("immutable_field");
        JsonNode updated = patch("/v1/reference/currencies/" + chf, "{\"symbol\":\"Fr.\"}", "1", 200);
        assertThat(updated.get("symbol").asString()).isEqualTo("Fr.");
        assertThat(events("platform.currency.changed")).isEqualTo(7); // 6 creates + 1 update
    }

    @Test
    @Order(2)
    void countryRiskChangePublishesEventOnlyForRiskFields() throws Exception {
        String ke = get("/v1/reference/countries?code=KE", TENANT).get("data").get(0).get("id").asString();
        JsonNode upd = patch("/v1/reference/countries/" + ke, "{\"fatf_status\":\"INCREASED_MONITORING\"}", "1", 200);
        assertThat(upd.get("fatf_status").asString()).isEqualTo("INCREASED_MONITORING");
        assertThat(events("platform.country.risk_changed")).isEqualTo(1);
        patch("/v1/reference/countries/" + ke, "{\"official_name\":\"Republic of Kenya\"}", "2", 200);
        assertThat(events("platform.country.risk_changed")).isEqualTo(1);
        assertThat(code(patch("/v1/reference/countries/" + ke, "{\"is_sanctioned\":true}", "1", 409))).isEqualTo("version_conflict");
        assertThat(code(patch("/v1/reference/countries/" + ke, "{\"iban_length\":99}", "3", 422))).isEqualTo("invalid_iban_length");
    }

    @Test
    @Order(3)
    void businessDaysHonourWeekendsAndHolidays() throws Exception {
        assertThat(code(post("/v1/reference/holiday-calendars", calendar("BAD-CAL", "[\"FUNDAY\"]", "UTC"), 422)))
                .isEqualTo("invalid_weekend_days");
        assertThat(code(post("/v1/reference/holiday-calendars", calendar("BAD-TZ", "[\"SUNDAY\"]", "Mars/Olympus"), 422)))
                .isEqualTo("invalid_timezone");
        JsonNode cal = post("/v1/reference/holiday-calendars", calendar("KE-PUBLIC", "[\"sat\",\"SUNDAY\"]", "Africa/Nairobi"), 201);
        assertThat(cal.get("weekend_days")).hasSize(2);
        assertThat(cal.get("weekend_days").get(0).asString()).isEqualTo("SATURDAY");
        String id = cal.get("id").asString();
        post("/v1/reference/holidays", """
                {"holiday_calendar_id":"%s","holiday_date":"2026-12-25","name":"Christmas Day"}""".formatted(id), 201);
        post("/v1/reference/holidays", """
                {"holiday_calendar_id":"%s","holiday_date":"2026-12-24","name":"Christmas Eve","is_half_day":true}"""
                .formatted(id), 201);
        assertThat(code(post("/v1/reference/holidays", """
                {"holiday_calendar_id":"%s","holiday_date":"2026-12-25","name":"x"}""".formatted(UUID.randomUUID()), 422)))
                .isEqualTo("calendar_not_found");

        JsonNode xmas = get("/v1/reference/holiday-calendars/" + id + "/is-business-day?date=2026-12-25", TENANT);
        assertThat(xmas.get("is_business_day").asBoolean()).isFalse();
        assertThat(xmas.get("reason").asString()).isEqualTo("HOLIDAY");
        assertThat(xmas.get("holiday_name").asString()).isEqualTo("Christmas Day");
        JsonNode sat = get("/v1/reference/holiday-calendars/" + id + "/is-business-day?date=2026-12-26", TENANT);
        assertThat(sat.get("reason").asString()).isEqualTo("WEEKEND");
        JsonNode eve = get("/v1/reference/holiday-calendars/" + id + "/is-business-day?date=2026-12-24", TENANT);
        assertThat(eve.get("is_business_day").asBoolean()).isTrue();
        assertThat(eve.get("is_half_day").asBoolean()).isTrue();

        assertThat(get("/v1/reference/holiday-calendars/" + id + "/next-business-day?date=2026-12-24", TENANT)
                .get("business_day").asString()).isEqualTo("2026-12-28");
        assertThat(get("/v1/reference/holiday-calendars/" + id + "/next-business-day?date=2026-12-28&days=-1", TENANT)
                .get("business_day").asString()).isEqualTo("2026-12-24");
        assertThat(get("/v1/reference/holiday-calendars/" + id + "/next-business-day?date=2026-12-23&days=3", TENANT)
                .get("business_day").asString()).isEqualTo("2026-12-29");
        assertThat(send("GET", "/v1/reference/holiday-calendars/" + id + "/is-business-day", null, null, TENANT).statusCode())
                .isEqualTo(400);
        assertThat(send("GET", "/v1/reference/holiday-calendars/" + id + "/next-business-day?date=2026-01-01&days=5000",
                null, null, TENANT).statusCode()).isEqualTo(422);
        assertThat(events("platform.holiday_calendar.changed")).isEqualTo(3);
    }

    @Test
    @Order(4)
    void importTarget2HolidaysIsDeterministicAndIdempotent() throws Exception {
        String id = post("/v1/reference/holiday-calendars", calendar("TARGET2", "[\"SATURDAY\",\"SUNDAY\"]", "Europe/Brussels"), 201)
                .get("id").asString();
        // created from a rule-feed code: the current and next two years are generated up front (3 x 6 TARGET2 days)
        assertThat(get("/v1/reference/holidays?holiday_calendar_id=" + id + "&limit=50", TENANT).get("data")).hasSize(18);
        HttpResponse<String> first = send("POST", "/v1/reference/holiday-calendars/" + id + "/import", "{\"year\":2099}",
                "imp-2099", TENANT);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode r = json.readTree(first.body());
        assertThat(r.get("source").asString()).isEqualTo("TARGET2");
        assertThat(r.get("imported").asInt()).isEqualTo(6);
        HttpResponse<String> replay = send("POST", "/v1/reference/holiday-calendars/" + id + "/import", "{\"year\":2099}",
                "imp-2099", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        JsonNode again = post("/v1/reference/holiday-calendars/" + id + "/import", "{\"year\":2099,\"source\":\"TARGET2\"}", 200);
        assertThat(again.get("imported").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(6);
        assertThat(get("/v1/reference/holiday-calendars/" + id + "/is-business-day?date=2099-04-10", TENANT)
                .get("holiday_name").asString()).isEqualTo("Good Friday");
        assertThat(code(post("/v1/reference/holiday-calendars/" + id + "/import", "{\"year\":2099,\"source\":\"MARS\"}", 422)))
                .isEqualTo("unknown_holiday_source");
        assertThat(code(post("/v1/reference/holiday-calendars/" + id + "/import", "{\"year\":1066,\"source\":\"TARGET2\"}", 422)))
                .isEqualTo("invalid_year");
        assertThat(get("/v1/reference/holidays?holiday_calendar_id=" + id + "&limit=50", TENANT).get("data")).hasSize(24);
    }

    @Test
    @Order(5)
    void fxPublishLatestAndConvert() throws Exception {
        rate("EUR", "USD", "1.0850000000", "0.9216589862", 201);
        assertThat(code(rate("USD", "USD", "1", "1", 422))).isEqualTo("same_currency");
        // inverse_rate is read-only: a client value is ignored and 1 / rate is stored
        JsonNode gbp = rate("GBP", "USD", "1.2500000000", "1.5000000000", 201);
        assertThat(new BigDecimal(gbp.get("inverse_rate").asString())).isEqualByComparingTo("0.8");
        rate("USD", "JPY", "150.1230000000", "0.0066612038", 201);

        JsonNode latest = get("/v1/reference/fx-rates/latest?base=EUR&quote=USD", TENANT);
        assertThat(new BigDecimal(latest.get("rate").asString())).isEqualByComparingTo("1.085");
        JsonNode inverse = get("/v1/reference/fx-rates/latest?base=USD&quote=EUR&type=MID", TENANT);
        assertThat(new BigDecimal(inverse.get("rate").asString())).isEqualByComparingTo("0.9216589862");
        assertThat(send("GET", "/v1/reference/fx-rates/latest?base=EUR&quote=KES", null, null, TENANT).statusCode()).isEqualTo(404);

        JsonNode direct = post("/v1/reference/fx-rates/convert",
                "{\"amount\":\"100.00\",\"from_currency\":\"EUR\",\"to_currency\":\"USD\"}", 200);
        assertThat(direct.get("converted_amount").asString()).isEqualTo("108.50");
        assertThat(direct.get("path").asString()).isEqualTo("DIRECT");
        JsonNode cross = post("/v1/reference/fx-rates/convert",
                "{\"amount\":\"100.00\",\"from_currency\":\"GBP\",\"to_currency\":\"EUR\",\"rate_type\":\"MID\"}", 200);
        assertThat(cross.get("path").asString()).isEqualTo("CROSS");
        assertThat(cross.get("legs")).hasSize(2);
        assertThat(cross.get("converted_amount").asString()).isEqualTo("115.21");
        JsonNode yen = post("/v1/reference/fx-rates/convert",
                "{\"amount\":\"10.00\",\"from_currency\":\"USD\",\"to_currency\":\"JPY\"}", 200);
        assertThat(yen.get("converted_amount").asString()).isEqualTo("1501");
        JsonNode inv = post("/v1/reference/fx-rates/convert",
                "{\"amount\":\"108.50\",\"from_currency\":\"USD\",\"to_currency\":\"EUR\"}", 200);
        assertThat(inv.get("path").asString()).isEqualTo("INVERSE");
        assertThat(inv.get("converted_amount").asString()).isEqualTo("100.00");
        assertThat(code(post("/v1/reference/fx-rates/convert",
                "{\"amount\":\"1\",\"from_currency\":\"KES\",\"to_currency\":\"JPY\"}", 404))).isEqualTo("rate_not_found");
        assertThat(code(post("/v1/reference/fx-rates/convert",
                "{\"amount\":\"-1\",\"from_currency\":\"EUR\",\"to_currency\":\"USD\"}", 422))).isEqualTo("invalid_amount");
        assertThat(events("platform.fx_rate.published")).isEqualTo(3);
    }

    @Test
    @Order(6)
    void bulkLoadIsAllOrNothing() throws Exception {
        long before = rateCount();
        JsonNode bad = post("/v1/reference/fx-rates/bulk", """
                {"fx_rate_source_id":"%s","rate_type":"MID","rates":[
                  {"base_currency":"USD","quote_currency":"KES","rate":"129.5"},
                  {"base_currency":"USD","quote_currency":"XXX","rate":"1"},
                  {"base_currency":"EUR","quote_currency":"KES","rate":"-3"}]}""".formatted(ecb), 422);
        assertThat(code(bad)).isEqualTo("invalid_rates");
        List<String> fields = new ArrayList<>();
        bad.get("errors").forEach(e -> fields.add(e.get("field").asString()));
        assertThat(fields).contains("rates[1].quote_currency", "rates[2].rate");
        assertThat(rateCount()).isEqualTo(before);

        JsonNode ok = post("/v1/reference/fx-rates/bulk", """
                {"fx_rate_source_id":"%s","rates":[
                  {"base_currency":"USD","quote_currency":"KES","rate":"129.5"},
                  {"base_currency":"EUR","quote_currency":"KES","rate":"140.25","rate_type":"BUY"}]}""".formatted(ecb), 201);
        assertThat(ok.get("loaded").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(ok.get("rates").get(0).get("inverse_rate").asString()))
                .isEqualByComparingTo("0.0077220077");
        assertThat(rateCount()).isEqualTo(before + 2);
        assertThat(events("platform.fx_rate.published")).isEqualTo(5);
    }

    @Test
    @Order(7)
    void validateAccountChecksIbanAndResolvesBank() throws Exception {
        JsonNode de = post("/v1/reference/banks/validate-account",
                "{\"account_number\":\"DE89 3704 0044 0532 0130 00\",\"country\":\"DE\"}", 200);
        assertThat(de.get("valid").asBoolean()).isTrue();
        assertThat(de.get("account_type").asString()).isEqualTo("IBAN");
        assertThat(de.get("bank").get("bic").asString()).isEqualTo("COBADEFFXXX");
        assertThat(de.get("bank").get("bic_valid").asBoolean()).isTrue();
        assertThat(de.get("bank").get("rail_participation").get(0).asString()).isEqualTo("SEPA_INST");

        JsonNode bad = post("/v1/reference/banks/validate-account",
                "{\"account_number\":\"DE89370400440532013001\",\"country\":\"DE\"}", 200);
        assertThat(bad.get("valid").asBoolean()).isFalse();
        assertThat(bad.get("errors").get(0).get("code").asString()).isEqualTo("invalid_iban_checksum");

        JsonNode gb = post("/v1/reference/banks/validate-account",
                "{\"account_number\":\"GB82WEST12345698765432\",\"country\":\"GB\"}", 200);
        assertThat(gb.get("valid").asBoolean()).isTrue();
        assertThat(gb.get("bank").get("bank_name").asString()).isEqualTo("Westminster Test Bank");

        JsonNode us = post("/v1/reference/banks/validate-account",
                "{\"account_number\":\"000123456789\",\"country\":\"US\",\"clearing_code\":\"021000021\"}", 200);
        assertThat(us.get("account_type").asString()).isEqualTo("DOMESTIC");
        assertThat(us.get("valid").asBoolean()).isTrue();
        assertThat(us.get("bank").get("bic").asString()).isEqualTo("CHASUS33XXX");

        assertThat(code(post("/v1/reference/banks/validate-account",
                "{\"account_number\":\"DE89370400440532013000\",\"country\":\"Germany\"}", 400))).isEqualTo("validation_failed");
    }

    @Test
    @Order(8)
    void tenantIsolation() throws Exception {
        // FX rates are tenant-scoped: another tenant sees none of ours.
        assertThat(get("/v1/reference/fx-rates", OTHER_TENANT).get("data")).isEmpty();
        assertThat(send("GET", "/v1/reference/fx-rates/latest?base=EUR&quote=USD", null, null, OTHER_TENANT).statusCode())
                .isEqualTo(404);
        // Global reference data is shared.
        assertThat(get("/v1/reference/currencies?code=USD", OTHER_TENANT).get("data")).hasSize(1);
        // No tenant at all: rejected.
        assertThat(send("GET", "/v1/reference/currencies", null, null, null).statusCode()).isEqualTo(401);
        // Idempotency keys are per tenant: the same key in another tenant executes afresh.
        String body = "{\"account_number\":\"DE89370400440532013000\",\"country\":\"DE\"}";
        assertThat(send("POST", "/v1/reference/banks/validate-account", body, "shared-key", TENANT)
                .headers().firstValue("Idempotent-Replayed")).isEmpty();
        HttpResponse<String> other = send("POST", "/v1/reference/banks/validate-account", body, "shared-key", OTHER_TENANT);
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(other.headers().firstValue("Idempotent-Replayed")).isEmpty();
        HttpResponse<String> reused = send("POST", "/v1/reference/banks/validate-account",
                "{\"account_number\":\"GB82WEST12345698765432\",\"country\":\"GB\"}", "shared-key", TENANT);
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(code(json.readTree(reused.body()))).isEqualTo("idempotency_key_reused");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode rate(String base, String quote, String rate, String inverse, int expected) throws Exception {
        return post("/v1/reference/fx-rates", """
                {"fx_rate_source_id":"%s","base_currency":"%s","quote_currency":"%s","rate_type":"MID","rate":"%s",
                 "inverse_rate":"%s","as_of_date":"2020-01-02","valid_from":"2020-01-02T00:00:00Z"}"""
                .formatted(ecb, base, quote, rate, inverse), expected);
    }

    private static String calendar(String code, String weekend, String tz) {
        return """
                {"code":"%s","name":"%s calendar","calendar_type":"COUNTRY","timezone":"%s","weekend_days":%s}"""
                .formatted(code, code, tz, weekend);
    }

    private long events(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ?").param(type).query(Long.class).single();
    }

    private long rateCount() throws Exception {
        return get("/v1/reference/fx-rates?limit=200", TENANT).get("data").size();
    }

    private static String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, String ifMatch, int expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("Content-Type", "application/json").header("If-Match", ifMatch)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
