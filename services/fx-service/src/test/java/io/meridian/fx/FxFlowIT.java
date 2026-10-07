package io.meridian.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * fx-service end to end over HTTP against PostgreSQL 18 (RLS enforced). reference-data-service (MID rates) and
 * ledger-service (journal entries) are stateful fakes behind a mocked {@link ServiceClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.fx.ledger-id=0199a000-0000-7000-8000-0000000000aa",
        "meridian.fx.quote-ttl-seconds=120",
        "meridian.fx.default-margin-bps=75",
        "meridian.fx.position-accounts.EUR=0199a000-0000-7000-8000-0000000000e1",
        "meridian.fx.position-accounts.USD=0199a000-0000-7000-8000-0000000000e2",
        "meridian.fx.clearing-accounts.EUR=0199a000-0000-7000-8000-0000000000c1",
        "meridian.fx.clearing-accounts.USD=0199a000-0000-7000-8000-0000000000c2"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FxFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000f001";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000f002";
    static final String POS_EUR = "0199a000-0000-7000-8000-0000000000e1";
    static final String POS_USD = "0199a000-0000-7000-8000-0000000000e2";
    static final String CLR_EUR = "0199a000-0000-7000-8000-0000000000c1";
    static final String CLR_USD = "0199a000-0000-7000-8000-0000000000c2";

    record Res(int status, JsonNode body, HttpResponse<String> raw) {}

    /** In-memory reference-data rates and ledger journal entries. */
    static final class Fakes {
        final Map<String, String> rates = new HashMap<>();
        final Map<String, JsonNode> journals = new HashMap<>();
        final List<JsonNode> postings = Collections.synchronizedList(new ArrayList<>());
        final JsonMapper json;
        /** Business date reported by business-day-service (null: none, the service clock applies). */
        volatile LocalDate businessDate;

        Fakes(JsonMapper json) {
            this.json = json;
        }

        synchronized JsonNode get(String service, String path) {
            if ("config-service".equals(service)) { // no tenant ledger settings: the deployment properties apply
                return json.readTree("{\"namespace\":\"ledger\",\"values\":{}}");
            }
            if ("business-day-service".equals(service)) {
                return businessDate == null ? null
                        : json.readTree("{\"data\":[{\"business_date\":\"" + businessDate + "\",\"is_current\":true}]}");
            }
            assertThat(service).isEqualTo("reference-data-service");
            assertThat(path).startsWith("/v1/reference/fx-rates/latest?");
            Map<String, String> q = new HashMap<>();
            for (String kv : path.substring(path.indexOf('?') + 1).split("&")) {
                String[] p = kv.split("=", 2);
                q.put(p[0], p[1]);
            }
            assertThat(q.get("type")).isEqualTo("MID");
            String rate = rates.get(q.get("base") + "/" + q.get("quote"));
            if (rate == null) {
                throw new ApiException(404, "rate_not_found", "no rate");
            }
            ObjectNode n = json.createObjectNode();
            n.put("id", UUID.randomUUID().toString());
            n.put("base_currency", q.get("base"));
            n.put("quote_currency", q.get("quote"));
            n.put("rate_type", "MID");
            n.put("rate", rate);
            return n;
        }

        synchronized JsonNode post(String service, String path, Object body, String key) {
            assertThat(service).isEqualTo("ledger-service");
            assertThat(path).isEqualTo("/v1/journal-entries");
            assertThat(key).isNotBlank();
            if (journals.containsKey(key)) {
                return journals.get(key);
            }
            JsonNode b = json.valueToTree(body);
            Map<String, BigDecimal> net = new HashMap<>();
            for (JsonNode e : b.get("entries")) {
                BigDecimal amt = new BigDecimal(e.get("amount").asString());
                assertThat(amt.signum()).isPositive();
                net.merge(e.get("currency").asString(),
                        "DEBIT".equals(e.get("direction").asString()) ? amt : amt.negate(), BigDecimal::add);
            }
            net.forEach((ccy, n) -> {
                if (n.signum() != 0) {
                    throw ApiException.unprocessable("unbalanced_entry", ccy + " does not balance");
                }
            });
            ObjectNode entry = ((ObjectNode) b).deepCopy();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("idempotency_key", key);
            journals.put(key, entry);
            postings.add(entry);
            return entry;
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

    @Autowired
    io.meridian.web.time.BusinessDates businessDates;

    final HttpClient http = HttpClient.newHttpClient();
    Fakes fakes;
    String ruleId;
    String quoteId;
    String dealId;
    String primaryPartner;
    String fallbackPartner;
    String corridorId;

    @BeforeEach
    void stub() {
        if (fakes == null) {
            fakes = new Fakes(json);
            fakes.rates.put("EUR/USD", "1.0850");
            fakes.rates.put("USD/EUR", "0.9216589862");
            fakes.rates.put("GBP/KES", "164.25");
        }
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> fakes.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> fakes.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    // ------------------------------------------------------------------ 1. margin rules

    @Test
    @Order(1)
    void marginRulesAreValidated() throws Exception {
        assertThat(post("/v1/fx/margin-rules", Map.of("currency_pair", "EURUSD", "margin_bps", 50,
                "valid_from", "2026-01-01"), key()).status()).isEqualTo(422);
        assertThat(post("/v1/fx/margin-rules", Map.of("currency_pair", "EUR/USD", "margin_bps", 50,
                "valid_from", "2026-01-01", "min_amount", "5000", "max_amount", "100"), key()).body()
                .get("code").asString()).isEqualTo("invalid_amount_band");
        Res r = post("/v1/fx/margin-rules", Map.of("currency_pair", "EUR/USD", "margin_bps", 50, "min_fee", "2.50",
                "priority", 10, "valid_from", "2026-01-01"), key());
        assertThat(r.status()).isEqualTo(201);
        ruleId = r.body().get("id").asString();
        // a wildcard rule with a better priority must not beat the exact pair
        assertThat(post("/v1/fx/margin-rules", Map.of("currency_pair", "*/*", "margin_bps", 10, "priority", 1,
                "valid_from", "2026-01-01"), key()).status()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ 2. quote -> accept -> settle

    @Test
    @Order(2)
    void quoteIsPricedWithMarginFeeAndExpiryAndIsIdempotent() throws Exception {
        String k = key();
        Map<String, Object> body = Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "1000.00",
                "channel", "API");
        Res r = post("/v1/fx/quotes", body, k);
        assertThat(r.status()).isEqualTo(201);
        JsonNode q = r.body();
        quoteId = q.get("id").asString();
        assertThat(q.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(q.get("margin_bps").asInt()).isEqualTo(50);
        assertThat(q.get("fx_margin_rule_id").asString()).isEqualTo(ruleId);
        assertThat(new BigDecimal(q.get("mid_rate").asString())).isEqualByComparingTo("1.0850");
        assertThat(new BigDecimal(q.get("customer_rate").asString())).isEqualByComparingTo("1.079575");
        assertThat(new BigDecimal(q.get("buy_amount").asString())).isEqualByComparingTo("1079.58");
        assertThat(new BigDecimal(q.get("fee_amount").asString())).isEqualByComparingTo("2.50");
        assertThat(q.get("fee_currency").asString()).isEqualTo("EUR");
        assertThat(new BigDecimal(q.get("total_debit").asString())).isEqualByComparingTo("1002.50");
        assertThat(q.get("rate_source").asString()).isEqualTo("REFERENCE_DATA");
        assertThat(q.get("expires_at").asString()).isNotBlank();

        Res replay = post("/v1/fx/quotes", body, k);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("id").asString()).isEqualTo(quoteId);
        assertThat(replay.raw().headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "5.00"), k)
                .body().get("code").asString()).isEqualTo("idempotency_key_reused");

        // buy side fixed, no rule for USD/EUR exact -> the */* rule (10 bps) applies
        Res buy = post("/v1/fx/quotes", Map.of("sell_currency", "USD", "buy_currency", "EUR", "buy_amount", "500.00"), key());
        assertThat(buy.status()).isEqualTo(201);
        assertThat(buy.body().get("margin_bps").asInt()).isEqualTo(10);
        assertThat(new BigDecimal(buy.body().get("buy_amount").asString())).isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(buy.body().get("sell_amount").asString())).isEqualByComparingTo("543.04");
    }

    @Test
    @Order(3)
    void quoteValidation() throws Exception {
        assertThat(code(post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "EUR", "sell_amount", "1"),
                key()))).isEqualTo("same_currency");
        assertThat(code(post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "1",
                "buy_amount", "1"), key()))).isEqualTo("one_side_required");
        assertThat(code(post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD"), key())))
                .isEqualTo("one_side_required");
        assertThat(code(post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "-5"),
                key()))).isEqualTo("invalid_amount");
        assertThat(code(post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "1.001"),
                key()))).isEqualTo("invalid_amount_precision");
        assertThat(code(post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "CHF", "sell_amount", "10"),
                key()))).isEqualTo("rate_unavailable");
    }

    @Test
    @Order(4)
    void acceptBooksTheDealOnceAndSettlementPostsBothCurrencyLegs() throws Exception {
        String k = key();
        Res r = post("/v1/fx/quotes/" + quoteId + "/accept", Map.of(), k);
        assertThat(r.status()).isEqualTo(200);
        JsonNode d = r.body();
        dealId = d.get("id").asString();
        assertThat(d.get("status").asString()).isEqualTo("BOOKED");
        assertThat(d.get("side").asString()).isEqualTo("BUY");
        assertThat(d.get("fx_quote_id").asString()).isEqualTo(quoteId);
        assertThat(new BigDecimal(d.get("sell_amount").asString())).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(d.get("buy_amount").asString())).isEqualByComparingTo("1079.58");
        assertThat(new BigDecimal(d.get("rate").asString())).isEqualByComparingTo("1.079575");
        LocalDate valueDate = LocalDate.parse(d.get("value_date").asString());
        assertThat(valueDate).isAfter(LocalDate.now(ZoneOffset.UTC));
        assertThat(valueDate.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(get("/v1/fx/quotes/" + quoteId, TENANT).body().get("status").asString()).isEqualTo("ACCEPTED");

        assertThat(post("/v1/fx/quotes/" + quoteId + "/accept", Map.of(), k).body().get("id").asString()).isEqualTo(dealId);
        assertThat(code(post("/v1/fx/quotes/" + quoteId + "/accept", Map.of(), key()))).isEqualTo("quote_already_accepted");
        assertThat(code(post("/v1/fx/quotes/" + quoteId + "/cancel", null, key()))).isEqualTo("invalid_transition");

        Res settled = post("/v1/fx/deals/" + dealId + "/settle", null, key());
        assertThat(settled.status()).isEqualTo(200);
        assertThat(settled.body().get("status").asString()).isEqualTo("SETTLED");
        String je = settled.body().get("journal_entry_id").asString();
        JsonNode posting = fakes.journals.get("fxd-settle:" + dealId);
        assertThat(posting).isNotNull();
        assertThat(posting.get("id").asString()).isEqualTo(je);
        assertThat(posting.get("entry_type").asString()).isEqualTo("FX");
        assertThat(posting.get("ledger_id").asString()).isEqualTo("0199a000-0000-7000-8000-0000000000aa");
        assertThat(posting.get("source_id").asString()).isEqualTo(dealId);
        List<String> legs = new ArrayList<>();
        posting.get("entries").forEach(e -> legs.add(e.get("direction").asString() + " " + e.get("ledger_account_id").asString()
                + " " + new BigDecimal(e.get("amount").asString()).toPlainString() + " " + e.get("currency").asString()));
        assertThat(legs).containsExactly(
                "DEBIT " + CLR_EUR + " 1000.00 EUR", "CREDIT " + POS_EUR + " 1000.00 EUR",
                "DEBIT " + POS_USD + " 1079.58 USD", "CREDIT " + CLR_USD + " 1079.58 USD");

        assertThat(code(post("/v1/fx/deals/" + dealId + "/settle", null, key()))).isEqualTo("deal_already_settled");
        assertThat(code(post("/v1/fx/deals/" + dealId + "/cancel", Map.of("reason", "late"), key())))
                .isEqualTo("invalid_transition");
        assertThat(fakes.postings).hasSize(1);
    }

    @Test
    @Order(11)
    void spotValueDateCountsFromTheBusinessDate() throws Exception {
        try {
            fakes.businessDate = LocalDate.parse("2030-01-04"); // a Friday: T+2 skips the weekend
            businessDates.invalidate();
            String id = post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "20.00"),
                    key()).body().get("id").asString();
            JsonNode deal = post("/v1/fx/quotes/" + id + "/accept", Map.of(), key()).body();
            assertThat(deal.get("value_date").asString()).isEqualTo("2030-01-08");
        } finally {
            fakes.businessDate = null;
            businessDates.invalidate();
        }
    }

    @Test
    @Order(5)
    void expiredQuoteCannotBeAcceptedAndIsMarkedExpired() throws Exception {
        String id = post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "10.00"),
                key()).body().get("id").asString();
        TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "update fx_quote set expires_at = now() - interval '1 second' where id = ?::uuid").param(id).update());
        Res r = post("/v1/fx/quotes/" + id + "/accept", Map.of(), key());
        assertThat(r.status()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("quote_expired");
        assertThat(get("/v1/fx/quotes/" + id, TENANT).body().get("status").asString()).isEqualTo("EXPIRED");
        assertThat(code(post("/v1/fx/quotes/" + id + "/accept", Map.of(), key()))).isEqualTo("quote_not_active");
    }

    @Test
    @Order(6)
    void cancelPaths() throws Exception {
        String q1 = post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "20.00"),
                key()).body().get("id").asString();
        Res c = post("/v1/fx/quotes/" + q1 + "/cancel", null, key());
        assertThat(c.body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(code(post("/v1/fx/quotes/" + q1 + "/cancel", null, key()))).isEqualTo("invalid_transition");

        String q2 = post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "30.00"),
                key()).body().get("id").asString();
        String paymentId = UUID.randomUUID().toString();
        assertThat(code(post("/v1/fx/quotes/" + q2 + "/accept", Map.of("payment_id", paymentId), key())))
                .isEqualTo("fx_binding_required");
        JsonNode deal = post("/v1/fx/quotes/" + q2 + "/accept", Map.of("payment_id", paymentId, "sell_currency", "EUR",
                "sell_amount", "30.00", "buy_currency", "USD"), key()).body();
        assertThat(deal.get("payment_id").asString()).isEqualTo(paymentId);
        assertThat(deal.get("value_date").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        Res cancelled = post("/v1/fx/deals/" + deal.get("id").asString() + "/cancel", Map.of("reason", "payment cancelled"),
                key());
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().get("cancellation_reason").asString()).isEqualTo("payment cancelled");
        assertThat(code(post("/v1/fx/deals/" + deal.get("id").asString() + "/settle", null, key())))
                .isEqualTo("invalid_transition");
        assertThat(fakes.postings).hasSize(1);
    }

    // ------------------------------------------------------------------ 3. remittance

    @Test
    @Order(7)
    void partnersAndCorridorsAreValidated() throws Exception {
        assertThat(code(post("/v1/remittance/partners", Map.of("name", "Bad", "partner_type", "MTO",
                "countries", List.of(), "payout_methods", List.of("MOBILE_WALLET")), key()))).isEqualTo("countries_required");
        assertThat(code(post("/v1/remittance/partners", Map.of("name", "Bad", "partner_type", "MTO",
                "countries", List.of("KE"), "payout_methods", List.of("TELEPORT")), key()))).isEqualTo("invalid_payout_method");
        primaryPartner = post("/v1/remittance/partners", Map.of("name", "M-Pay", "partner_type", "WALLET_OPERATOR",
                "countries", List.of("KE", "UG"), "payout_methods", List.of("MOBILE_WALLET"), "sla_minutes", 15), key())
                .body().get("id").asString();
        fallbackPartner = post("/v1/remittance/partners", Map.of("name", "AggreGate", "partner_type", "AGGREGATOR",
                "countries", List.of("KE"), "payout_methods", List.of("MOBILE_WALLET", "BANK_ACCOUNT"), "sla_minutes", 60),
                key()).body().get("id").asString();

        Map<String, Object> corridor = new HashMap<>(Map.of("source_country", "GB", "destination_country", "KE",
                "source_currency", "GBP", "destination_currency", "KES", "payout_method", "MOBILE_WALLET",
                "remittance_partner_id", UUID.randomUUID().toString(), "min_amount", "10", "max_amount", "5000",
                "fixed_fee", "3.99", "percent_fee", "0.01"));
        assertThat(code(post("/v1/remittance/corridors", corridor, key()))).isEqualTo("partner_not_found");
        corridor.put("remittance_partner_id", primaryPartner);
        corridor.put("fallback_partner_id", fallbackPartner);
        corridor.put("margin_bps", 100);
        corridor.put("delivery_eta_minutes", 10);
        Res r = post("/v1/remittance/corridors", corridor, key());
        assertThat(r.status()).isEqualTo(201);
        corridorId = r.body().get("id").asString();
        assertThat(r.body().get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(8)
    void corridorQuoteIncludesFeesFxPartnerAndEta() throws Exception {
        Res r = post("/v1/remittance/corridors/" + corridorId + "/quote", Map.of("send_amount", "250.00"), key());
        assertThat(r.status()).isEqualTo(200);
        JsonNode q = r.body();
        assertThat(q.get("remittance_corridor_id").asString()).isEqualTo(corridorId);
        assertThat(q.get("remittance_partner_id").asString()).isEqualTo(primaryPartner);
        assertThat(q.get("delivery_eta_minutes").asInt()).isEqualTo(10);
        assertThat(q.get("margin_bps").asInt()).isEqualTo(100);
        // 164.25 * 0.99 = 162.6075; 250 * 162.6075 = 40651.875 -> 40651.88
        assertThat(new BigDecimal(q.get("customer_rate").asString())).isEqualByComparingTo("162.6075");
        assertThat(new BigDecimal(q.get("buy_amount").asString())).isEqualByComparingTo("40651.88");
        assertThat(new BigDecimal(q.get("fee_amount").asString())).isEqualByComparingTo("6.49");
        assertThat(new BigDecimal(q.get("total_debit").asString())).isEqualByComparingTo("256.49");
        assertThat(q.get("sell_currency").asString()).isEqualTo("GBP");

        JsonNode byReceive = post("/v1/remittance/corridors/" + corridorId + "/quote", Map.of("receive_amount", "10000.00"),
                key()).body();
        assertThat(new BigDecimal(byReceive.get("sell_amount").asString())).isEqualByComparingTo("61.50");
        assertThat(code(post("/v1/remittance/corridors/" + corridorId + "/quote", Map.of("send_amount", "9000.00"), key())))
                .isEqualTo("amount_out_of_corridor_range");
        assertThat(code(post("/v1/remittance/corridors/" + corridorId + "/quote", Map.of(), key())))
                .isEqualTo("one_side_required");

        // primary partner deactivated -> fallback partner and its SLA is not used because the corridor has an ETA
        JsonNode p = get("/v1/remittance/partners/" + primaryPartner, TENANT).body();
        assertThat(patch("/v1/remittance/partners/" + primaryPartner, Map.of("is_active", false),
                p.get("row_version").asString()).status()).isEqualTo(200);
        JsonNode viaFallback = post("/v1/remittance/corridors/" + corridorId + "/quote", Map.of("send_amount", "100.00"),
                key()).body();
        assertThat(viaFallback.get("remittance_partner_id").asString()).isEqualTo(fallbackPartner);

        // the corridor quote can be accepted like any quote; remittance deals value today
        JsonNode deal = post("/v1/fx/quotes/" + q.get("id").asString() + "/accept", Map.of(), key()).body();
        assertThat(deal.get("value_date").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
    }

    @Test
    @Order(9)
    void corridorSuspendAndResume() throws Exception {
        Res s = post("/v1/remittance/corridors/" + corridorId + "/suspend", Map.of("reason", "partner outage"), key());
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.body().get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(s.body().get("status_reason").asString()).isEqualTo("partner outage");
        assertThat(code(post("/v1/remittance/corridors/" + corridorId + "/quote", Map.of("send_amount", "50.00"), key())))
                .isEqualTo("corridor_not_active");
        assertThat(code(post("/v1/remittance/corridors/" + corridorId + "/suspend", Map.of("reason", "again"), key())))
                .isEqualTo("invalid_transition");
        Res resumed = post("/v1/remittance/corridors/" + corridorId + "/resume", Map.of(), key());
        assertThat(resumed.body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(code(post("/v1/remittance/corridors/" + corridorId + "/resume", Map.of(), key())))
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/remittance/corridors/" + corridorId + "/quote", Map.of("send_amount", "50.00"), key())
                .status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 4. tenancy and events

    @Test
    @Order(10)
    void tenantIsolationAndEvents() throws Exception {
        assertThat(get("/v1/fx/quotes/" + quoteId, OTHER_TENANT).status()).isEqualTo(404);
        assertThat(get("/v1/fx/deals/" + dealId, OTHER_TENANT).status()).isEqualTo(404);
        assertThat(get("/v1/fx/quotes", OTHER_TENANT).body().get("data")).isEmpty();
        assertThat(send("POST", "/v1/fx/deals/" + dealId + "/settle", null, key(), OTHER_TENANT, null).status())
                .isEqualTo(404);

        List<String> types = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select distinct event_type from outbox").query(String.class).list());
        assertThat(types).contains("payments.fx_quote.created", "payments.fx_quote.cancelled", "payments.fx_deal.booked",
                "payments.fx_deal.settled", "payments.fx_deal.cancelled", "payments.remittance_corridor.status_changed");
    }

    // ------------------------------------------------------------------ SEC-04 binding to the payment

    @Test
    @Order(12)
    void quoteAcceptedForAPaymentMustMatchItAndIsSingleUse() throws Exception {
        String customer = UUID.randomUUID().toString();
        String q = post("/v1/fx/quotes", Map.of("sell_currency", "EUR", "buy_currency", "USD", "sell_amount", "100.00",
                "customer_id", customer), key()).body().get("id").asString();
        String pay = UUID.randomUUID().toString();
        Map<String, Object> ok = new HashMap<>(Map.of("payment_id", pay, "customer_id", customer, "sell_currency", "EUR",
                "sell_amount", "100.00", "buy_currency", "USD"));
        Map<String, Object> small = new HashMap<>(ok);
        small.put("sell_amount", "1.00");
        assertThat(code(post("/v1/fx/quotes/" + q + "/accept", small, key()))).isEqualTo("fx_quote_amount_mismatch");
        Map<String, Object> pair = new HashMap<>(ok);
        pair.put("buy_currency", "GBP");
        assertThat(code(post("/v1/fx/quotes/" + q + "/accept", pair, key()))).isEqualTo("fx_quote_mismatch");
        Map<String, Object> other = new HashMap<>(ok);
        other.put("customer_id", UUID.randomUUID().toString());
        assertThat(code(post("/v1/fx/quotes/" + q + "/accept", other, key()))).isEqualTo("fx_quote_customer_mismatch");
        Map<String, Object> buy = new HashMap<>(ok);
        buy.put("buy_amount", "9999.00");
        assertThat(code(post("/v1/fx/quotes/" + q + "/accept", buy, key()))).isEqualTo("fx_quote_amount_mismatch");
        assertThat(get("/v1/fx/quotes/" + q, TENANT).body().get("status").asString()).isEqualTo("ACTIVE");

        // two payments race for the quote: exactly one deal is booked; the winner's replay returns the same deal
        String pay2 = UUID.randomUUID().toString();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Res> results = Collections.synchronizedList(new ArrayList<>());
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (String p : List.of(pay, pay2, pay, pay2)) {
                Map<String, Object> b = new HashMap<>(ok);
                b.put("payment_id", p);
                fs.add(pool.submit(() -> {
                    start.await();
                    results.add(post("/v1/fx/quotes/" + q + "/accept", b, key()));
                    return null;
                }));
            }
            start.countDown();
            for (var f : fs) {
                f.get();
            }
        }
        List<String> winners = results.stream().filter(r -> r.status() == 200)
                .map(r -> r.body().get("payment_id").asString()).distinct().toList();
        assertThat(winners).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 200).map(r -> r.body().get("id").asString()).distinct())
                .hasSize(1);
        assertThat(results.stream().filter(r -> r.status() != 200).map(FxFlowIT::code)).allMatch("quote_already_accepted"::equals);
        Long deals = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select count(*) from fx_deal where fx_quote_id = ?::uuid").param(q).query(Long.class).single());
        assertThat(deals).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ http helpers

    static String key() {
        return "k-" + UUID.randomUUID();
    }

    static String code(Res r) {
        return r.body().path("code").asString(null);
    }

    Res post(String path, Object body, String key) throws Exception {
        return send("POST", path, body, key, TENANT, null);
    }

    Res get(String path, String tenant) throws Exception {
        return send("GET", path, null, null, tenant, null);
    }

    Res patch(String path, Object body, String ifMatch) throws Exception {
        return send("PATCH", path, body, null, TENANT, ifMatch);
    }

    Res send(String method, String path, Object body, String key, String tenant, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", tenant).header("X-User", "fx-dealer");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode n = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        return new Res(r.statusCode(), n, r);
    }
}
