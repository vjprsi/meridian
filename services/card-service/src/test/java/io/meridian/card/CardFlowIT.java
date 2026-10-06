package io.meridian.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.meridian.card.domain.Luhn;
import io.meridian.card.infrastructure.Signatures;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * card-service end to end over HTTP against PostgreSQL 18 (RLS enforced). The vault, tokenisation, authorisation,
 * clearing and ledger services are stateful fakes behind a mocked {@link ServiceClient} ({@link FakePeers}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.cards.callback-secret=test-bureau-secret", "meridian.cards.replacement-fee=5.00"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CardFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000ca01";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000ca02";
    static final String MAKER = "maker@bank";
    static final String CHECKER = "checker@bank";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c001";
    static final String LEDGER_ACCOUNT = "0199a000-0000-7000-8000-00000000a001";
    static final String DEPOSIT_ACCOUNT = "0199a000-0000-7000-8000-00000000d001";
    static final String CREDIT_LINE = "0199a000-0000-7000-8000-00000000e001";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakePeers peers;
    String bureau;
    String program;
    String product;
    String virtualCard;
    String physicalCard;
    String physicalOrder;

    record Res(int status, JsonNode body, HttpResponse<String> raw) {
        String text(String f) {
            JsonNode v = body.get(f);
            return v == null || v.isNull() ? null : v.asString();
        }

        String code() {
            return text("code");
        }
    }

    /** @MockitoBean mocks are reset after every test, so the routing to the (shared, stateful) fakes is re-stubbed. */
    @BeforeEach
    void stub() {
        doAnswer(inv -> peers.post(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)))
                .when(serviceClient).post(anyString(), anyString(), any(), any());
        doAnswer(inv -> peers.get(inv.getArgument(0), inv.getArgument(1))).when(serviceClient).get(anyString(), anyString());
    }

    @BeforeAll
    void setUp() throws Exception {
        peers = new FakePeers(json);
        stub();

        Res b = post("/v1/card-bureaus", """
                {"name":"IDEMIA EU","protocol":"SFTP_FILE","file_format":"P3","supported_countries":["GB","DE","FR"],
                 "cutoff_time":"16:00"}""", MAKER);
        assertThat(b.status()).isEqualTo(201);
        assertThat(post("/v1/card-bureaus", "{\"name\":\"X\",\"protocol\":\"API\",\"cutoff_time\":\"25:00\"}", MAKER)
                .code()).isEqualTo("invalid_cutoff_time");
        bureau = b.text("id");
    }

    // ------------------------------------------------------------------ programs & products

    @Test
    @Order(1)
    void programCertificationAndFourEyesGoLive() throws Exception {
        Res bad = post("/v1/card-programs", programBody("BAD", "DEBIT", "453910", "5539100", "5539109"), MAKER);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.code()).isEqualTo("invalid_bin_range");
        Res jit = post("/v1/card-programs", programBody("JIT1", "JIT", "453911", "4539110", "4539119"), MAKER);
        assertThat(jit.code()).isEqualTo("jit_webhook_required");

        Res p = post("/v1/card-programs", programBody("EU-DEBIT", "DEBIT", "453910", "4539100", "4539109"), MAKER);
        assertThat(p.status()).isEqualTo(201);
        assertThat(p.text("status")).isEqualTo("DRAFT");
        program = p.text("id");

        assertThat(post("/v1/card-programs/" + program + "/activate", "{}", CHECKER).status()).isEqualTo(409);
        assertThat(post("/v1/card-programs/" + program + "/certify", null, MAKER).text("status")).isEqualTo("CERTIFICATION");
        Res self = post("/v1/card-programs/" + program + "/activate", "{\"reason\":\"go live\"}", MAKER);
        assertThat(self.status()).isEqualTo(422);
        assertThat(self.code()).isEqualTo("four_eyes_violation");
        Res live = post("/v1/card-programs/" + program + "/activate", "{\"reason\":\"go live\"}", CHECKER);
        assertThat(live.status()).isEqualTo(200);
        assertThat(live.text("status")).isEqualTo("ACTIVE");
        assertThat(live.text("activated_by")).isEqualTo(CHECKER);
        assertThat(live.text("launched_at")).isNotNull();

        // BIN fields are frozen once live; other fields stay editable
        Res frozen = patch("/v1/card-programs/" + program, "{\"bin_range_end\":\"4539108\"}", live.body().get("row_version").asString());
        assertThat(frozen.code()).isEqualTo("field_not_updatable");
        Res renamed = patch("/v1/card-programs/" + program, "{\"name\":\"EU Debit Classic\"}", live.body().get("row_version").asString());
        assertThat(renamed.status()).isEqualTo(200);

        Res prod = post("/v1/card-products", """
                {"card_program_id":"%s","code":"CLASSIC","name":"Classic debit","form_factors":["VIRTUAL","PHYSICAL"],
                 "card_bureau_id":"%s","expiry_years":4,"max_cards_per_customer":10}""".formatted(program, bureau), MAKER);
        assertThat(prod.status()).isEqualTo(201);
        assertThat(prod.text("status")).isEqualTo("DRAFT");
        product = prod.text("id");
        assertThat(post("/v1/card-products", """
                {"card_program_id":"%s","code":"BAD","name":"Bad","form_factors":["VIRTUAL"],"expiry_years":20}"""
                .formatted(program), MAKER).code()).isEqualTo("invalid_expiry_years");
        assertThat(post("/v1/card-products/" + product + "/activate", null, MAKER).text("status")).isEqualTo("ACTIVE");
        assertThat(post("/v1/card-products/" + product + "/activate", null, MAKER).status()).isEqualTo(409);
        assertThat(events("cards.card_program.activated")).isEqualTo(1);
        assertThat(events("cards.card_product.status_changed")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ issuing

    @Test
    @Order(2)
    void issueVirtualCardInstantlyWithVaultedPan() throws Exception {
        peers.collideNext.set(1); // first PAN collides in the vault: card-service must retry with a new PAN
        String body = cardBody("VIRTUAL", "JANE A DOE") ;
        Res c = post("/v1/cards", body, MAKER, "issue-virtual-1");
        assertThat(c.status()).isEqualTo(201);
        virtualCard = c.text("id");
        assertThat(c.text("status")).isEqualTo("ACTIVE");
        assertThat(c.text("activated_at")).isNotNull();
        assertThat(c.text("pan_token")).startsWith("tok_");
        assertThat(c.text("bin")).isEqualTo("453910");
        String pan = peers.panOfToken(c.text("pan_token"));
        assertThat(Luhn.isValid(pan)).isTrue();
        assertThat(pan).hasSize(16).startsWith("453910");
        assertThat(c.text("last4")).isEqualTo(pan.substring(12));
        assertThat(c.body().get("expiry_year").asInt()).isEqualTo(LocalDate.now().getYear() + 4);
        assertThat(peers.calls("card-vault-service", "/v1/card-vault")).hasSize(2);
        assertThat(peers.luhnFailures).isEmpty();
        assertThat(c.body().has("pan")).isFalse();

        // idempotent replay: same card, no new vault call
        Res replay = post("/v1/cards", body, MAKER, "issue-virtual-1");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.raw().headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(replay.text("id")).isEqualTo(virtualCard);
        assertThat(peers.calls("card-vault-service", "/v1/card-vault")).hasSize(2);

        // program default control copied onto the card
        Res controls = get("/v1/card-controls?card_id=" + virtualCard);
        assertThat(controls.body().get("data")).hasSize(1);
        assertThat(controls.body().get("data").get(0).get("values").get(0).asString()).isEqualTo("7995");
        assertThat(eventsFor("cards.card.created", virtualCard)).isEqualTo(1);
        assertThat(eventsFor("cards.card.activated", virtualCard)).isEqualTo(1);

        // validation
        Res mismatch = post("/v1/cards", cardBody("VIRTUAL", "JANE DOE").replace("DEPOSIT_ACCOUNT", "CREDIT_LINE"), MAKER);
        assertThat(mismatch.code()).isEqualTo("linked_account_type_mismatch");
        Res name = post("/v1/cards", cardBody("VIRTUAL", "J@NE"), MAKER);
        assertThat(name.code()).isEqualTo("invalid_cardholder_name");
        Res noProduct = post("/v1/cards", cardBody("VIRTUAL", "JANE DOE").replace(product, UUID.randomUUID().toString()), MAKER);
        assertThat(noProduct.code()).isEqualTo("product_not_found");
    }

    @Test
    @Order(3)
    void issueWithInitialPinAndPhysicalCardWithOrder() throws Exception {
        ObjectNode withPin = (ObjectNode) json.readTree(cardBody("VIRTUAL", "JOHN DOE"));
        withPin.put("encrypted_pin_block", "base64-wrapped-pin-block");
        withPin.put("pin_key_id", "zpk-default");
        Res c = post("/v1/cards", json.writeValueAsString(withPin), MAKER);
        assertThat(c.status()).isEqualTo(201);
        assertThat(c.body().get("pin_set").asBoolean()).isTrue();
        assertThat(c.body().has("encrypted_pin_block")).isFalse();
        assertThat(get("/v1/cards/" + c.text("id") + "/pin-events").body().get("data").get(0).get("event_type").asString())
                .isEqualTo("SET");

        Res noAddress = post("/v1/cards", cardBody("PHYSICAL", "JANE A DOE"), MAKER);
        assertThat(noAddress.code()).isEqualTo("address_required");
        ObjectNode phys = (ObjectNode) json.readTree(cardBody("PHYSICAL", "JANE A DOE"));
        phys.set("shipping_address", json.readTree("""
                {"address_line1":"1 High Street","city":"London","postal_code":"N1 1AA","country":"GB"}"""));
        phys.put("shipping_method", "EXPRESS");
        Res p = post("/v1/cards", json.writeValueAsString(phys), MAKER);
        assertThat(p.status()).isEqualTo(201);
        physicalCard = p.text("id");
        assertThat(p.text("status")).isEqualTo("CREATED");
        Res orders = get("/v1/card-orders?card_id=" + physicalCard);
        JsonNode order = orders.body().get("data").get(0);
        physicalOrder = order.get("id").asString();
        assertThat(order.get("status").asString()).isEqualTo("PENDING");
        assertThat(order.get("shipping_method").asString()).isEqualTo("EXPRESS");
        assertThat(order.get("recipient_name").asString()).isEqualTo("JANE A DOE");
        assertThat(order.get("card_bureau_id").asString()).isEqualTo(bureau);

        Res wrong = post("/v1/cards/" + physicalCard + "/activate", "{\"activation_code\":\"0000\"}", MAKER);
        assertThat(wrong.code()).isEqualTo(p.text("last4").equals("0000") ? null : "invalid_activation_code");
        Res ok = post("/v1/cards/" + physicalCard + "/activate", "{\"activation_code\":\"" + p.text("last4") + "\"}", MAKER);
        assertThat(ok.text("status")).isEqualTo("ACTIVE");
        assertThat(eventsFor("cards.card.activated", physicalCard)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    @Order(4)
    void freezeUnfreezeBlockCloseAndInvalidTransitions() throws Exception {
        Res f = post("/v1/cards/" + virtualCard + "/freeze", "{\"reason\":\"phone lost\"}", MAKER);
        assertThat(f.text("status")).isEqualTo("FROZEN");
        assertThat(f.text("frozen_at")).isNotNull();
        assertThat(post("/v1/cards/" + virtualCard + "/freeze", "{}", MAKER).status()).isEqualTo(409);
        assertThat(post("/v1/cards/" + virtualCard + "/unfreeze", null, MAKER).text("status")).isEqualTo("ACTIVE");
        assertThat(post("/v1/cards/" + virtualCard + "/unfreeze", null, MAKER).code()).isEqualTo("invalid_transition");
        assertThat(peers.calls("card-tokenization-service", "/v1/card-tokens/card-status-sync").stream()
                .map(c -> c.body().get("card_status").asString()).toList()).contains("FROZEN", "ACTIVE");

        Res throwaway = post("/v1/cards", cardBody("VIRTUAL", "TEMP CARD"), MAKER);
        String t = throwaway.text("id");
        assertThat(post("/v1/cards/" + t + "/block", "{\"status\":\"FROZEN\",\"reason\":\"x\"}", MAKER).code())
                .isEqualTo("invalid_block_status");
        assertThat(post("/v1/cards/" + t + "/block", "{\"status\":\"STOLEN\",\"reason\":\"reported\"}", MAKER)
                .text("status")).isEqualTo("STOLEN");
        assertThat(post("/v1/cards/" + t + "/unfreeze", null, MAKER).status()).isEqualTo(409);
        Res closed = post("/v1/cards/" + t + "/close", "{\"reason\":\"customer request\"}", MAKER);
        assertThat(closed.text("status")).isEqualTo("CLOSED");
        assertThat(closed.text("closed_at")).isNotNull();
        assertThat(post("/v1/cards/" + t + "/close", "{}", MAKER).status()).isEqualTo(409);
        JsonNode history = get("/v1/cards/" + t + "/status-history").body().get("data");
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("to_status").asString()).isEqualTo("CLOSED");
        assertThat(history.get(0).get("from_status").asString()).isEqualTo("STOLEN");
        assertThat(history.get(0).get("actor_id").asString()).isEqualTo(MAKER);

        // PATCH cannot touch lifecycle / funding fields
        Res card = get("/v1/cards/" + virtualCard);
        Res guarded = patch("/v1/cards/" + virtualCard, "{\"ledger_account_id\":\"" + UUID.randomUUID() + "\"}",
                card.body().get("row_version").asString());
        assertThat(guarded.code()).isEqualTo("field_not_updatable");
        Res nick = patch("/v1/cards/" + virtualCard, "{\"nickname\":\"Groceries\"}", card.body().get("row_version").asString());
        assertThat(nick.text("nickname")).isEqualTo("Groceries");
    }

    // ------------------------------------------------------------------ orders

    @Test
    @Order(5)
    void bureauOrderLifecycleWithSignedCallbacks() throws Exception {
        Res sub = post("/v1/card-orders/" + physicalOrder + "/submit", null, MAKER);
        assertThat(sub.text("status")).isEqualTo("SENT_TO_BUREAU");
        String ref = sub.text("bureau_reference");
        assertThat(ref).startsWith("FILE-");
        assertThat(sub.text("sent_to_bureau_at")).isNotNull();
        assertThat(post("/v1/card-orders/" + physicalOrder + "/cancel", "{}", MAKER).status()).isEqualTo(409);

        String inProd = "{\"bureau_reference\":\"" + ref + "\",\"status\":\"IN_PRODUCTION\"}";
        Res forged = callback(inProd, "deadbeef");
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.code()).isEqualTo("invalid_signature");
        assertThat(callback(inProd, null).status()).isEqualTo(401);
        assertThat(callback(inProd, Signatures.sign("test-bureau-secret", bytes(inProd))).text("status")).isEqualTo("IN_PRODUCTION");
        String shipped = "{\"bureau_reference\":\"" + ref + "\",\"status\":\"SHIPPED\",\"tracking_number\":\"1Z999\",\"carrier\":\"UPS\"}";
        Res s = callback(shipped, Signatures.sign("test-bureau-secret", bytes(shipped)));
        assertThat(s.text("status")).isEqualTo("SHIPPED");
        assertThat(s.text("tracking_number")).isEqualTo("1Z999");
        assertThat(s.text("shipped_at")).isNotNull();
        // duplicate delivery of the same callback is a no-op
        assertThat(callback(shipped, Signatures.sign("test-bureau-secret", bytes(shipped))).text("status")).isEqualTo("SHIPPED");
        String delivered = "{\"bureau_reference\":\"" + ref + "\",\"status\":\"DELIVERED\"}";
        assertThat(callback(delivered, Signatures.sign("test-bureau-secret", bytes(delivered))).text("delivered_at")).isNotNull();
        String failed = "{\"bureau_reference\":\"" + ref + "\",\"status\":\"FAILED\"}";
        assertThat(callback(failed, Signatures.sign("test-bureau-secret", bytes(failed))).status()).isEqualTo(409);
        assertThat(eventsFor("cards.card_order.shipped", physicalOrder)).isEqualTo(1);
        assertThat(eventsFor("cards.card_order.delivered", physicalOrder)).isEqualTo(1);

        // order a physical card for the virtual one; edit the address while pending; cancel
        Res op = post("/v1/cards/" + virtualCard + "/order-physical", """
                {"shipping_method":"STANDARD","address":{"address_line1":"2 Low Road","city":"Berlin","country":"DE"}}""", MAKER);
        assertThat(op.status()).isEqualTo(200);
        assertThat(get("/v1/cards/" + virtualCard).text("form_factor")).isEqualTo("PHYSICAL");
        Res edited = patch("/v1/card-orders/" + op.text("id"), "{\"postal_code\":\"10115\"}", op.body().get("row_version").asString());
        assertThat(edited.text("postal_code")).isEqualTo("10115");
        Res notEditable = patch("/v1/card-orders/" + op.text("id"), "{\"card_bureau_id\":\"" + bureau + "\"}",
                edited.body().get("row_version").asString());
        assertThat(notEditable.code()).isEqualTo("field_not_updatable");
        assertThat(post("/v1/card-orders/" + op.text("id") + "/cancel", "{\"reason\":\"changed mind\"}", MAKER)
                .text("status")).isEqualTo("CANCELLED");
        Res notShippable = post("/v1/cards/" + virtualCard + "/order-physical",
                "{\"address\":{\"address_line1\":\"x\",\"city\":\"Paris\",\"country\":\"FR\"}}", MAKER);
        assertThat(notShippable.code()).isEqualTo("already_physical");
        Res us = post("/v1/cards/" + post("/v1/cards", cardBody("VIRTUAL", "US SHIP"), MAKER).text("id") + "/order-physical",
                "{\"address\":{\"address_line1\":\"x\",\"city\":\"NYC\",\"country\":\"US\"}}", MAKER);
        assertThat(us.code()).isEqualTo("country_not_supported");
    }

    // ------------------------------------------------------------------ PIN

    @Test
    @Order(6)
    void pinSetChangeWeakAndUnblockThroughTheVault() throws Exception {
        assertThat(post("/v1/cards/" + virtualCard + "/pin/unblock", "{}", MAKER).code()).isEqualTo("pin_not_set");
        Res set = post("/v1/cards/" + virtualCard + "/pin", "{\"encrypted_pin_block\":\"AAAA\",\"key_id\":\"zpk-default\"}", MAKER);
        assertThat(set.status()).isEqualTo(200);
        assertThat(set.text("event_type")).isEqualTo("SET");
        Res change = post("/v1/cards/" + virtualCard + "/pin", "{\"encrypted_pin_block\":\"BBBB\",\"key_id\":\"zpk-default\"}", MAKER);
        assertThat(change.text("event_type")).isEqualTo("CHANGE");
        Res weak = post("/v1/cards/" + virtualCard + "/pin", "{\"encrypted_pin_block\":\"WEAK1\",\"key_id\":\"zpk-default\"}", MAKER);
        assertThat(weak.code()).isEqualTo("weak_pin");
        Res unblock = post("/v1/cards/" + virtualCard + "/pin/unblock", "{\"reason\":\"verified caller\"}", MAKER);
        assertThat(unblock.text("event_type")).isEqualTo("UNBLOCK");
        JsonNode pinEvents = get("/v1/cards/" + virtualCard + "/pin-events").body().get("data");
        assertThat(pinEvents).hasSize(4);
        assertThat(pinEvents.findValues("success").stream().filter(v -> !v.asBoolean()).count()).isEqualTo(1);
        assertThat(get("/v1/cards/" + virtualCard).body().get("pin_set").asBoolean()).isTrue();
        assertThat(eventsFor("cards.card_pin.changed", null)).isGreaterThanOrEqualTo(4);
        // the PIN block was sent only to the vault and is not stored anywhere in card_db
        assertThat(dbText()).doesNotContain("BBBB").doesNotContain("base64-wrapped-pin-block");
    }

    // ------------------------------------------------------------------ controls & limits

    @Test
    @Order(7)
    void controlsAndLimitsCrudWithValidationEventsAndUsage() throws Exception {
        long before = events("cards.card_control.changed");
        assertThat(post("/v1/card-controls", "{\"control_type\":\"MCC_DENY\",\"values\":[\"7995\"]}", MAKER).code())
                .isEqualTo("invalid_scope");
        assertThat(post("/v1/card-controls", "{\"card_id\":\"" + virtualCard + "\",\"control_type\":\"COUNTRY_DENY\",\"values\":[\"Russia\"]}", MAKER)
                .code()).isEqualTo("invalid_control_values");
        Res amount = post("/v1/card-controls", "{\"card_id\":\"" + virtualCard
                + "\",\"control_type\":\"AMOUNT_MAX\",\"channel\":\"ECOM\",\"max_amount\":\"250.00\",\"currency\":\"EUR\"}", MAKER);
        assertThat(amount.status()).isEqualTo(201);
        Res window = post("/v1/card-controls", """
                {"card_program_id":"%s","control_type":"TIME_WINDOW","time_window":{"days":["MON","TUE"],"start":"08:00","end":"20:00"},
                 "timezone":"Europe/Berlin"}""".formatted(program), MAKER);
        assertThat(window.status()).isEqualTo(201);
        Res upd = patch("/v1/card-controls/" + amount.text("id"), "{\"max_amount\":\"300.00\"}", amount.body().get("row_version").asString());
        assertThat(upd.text("max_amount")).startsWith("300");
        Res badUpd = patch("/v1/card-controls/" + amount.text("id"), "{\"max_amount\":\"-1\"}", upd.body().get("row_version").asString());
        assertThat(badUpd.status()).isEqualTo(422);
        JsonNode cardControls = get("/v1/cards/" + virtualCard + "/controls").body().get("data");
        assertThat(cardControls).hasSize(3); // default MCC_DENY + AMOUNT_MAX + program TIME_WINDOW
        assertThat(delete("/v1/card-controls/" + amount.text("id"))).isEqualTo(204);
        assertThat(get("/v1/cards/" + virtualCard + "/controls").body().get("data")).hasSize(2);
        assertThat(events("cards.card_control.changed") - before).isEqualTo(4);

        assertThat(post("/v1/card-spend-limits", "{\"card_id\":\"" + virtualCard + "\",\"period\":\"DAILY\",\"currency\":\"EUR\"}", MAKER)
                .code()).isEqualTo("limit_required");
        Res daily = post("/v1/card-spend-limits", "{\"card_id\":\"" + virtualCard
                + "\",\"period\":\"DAILY\",\"max_amount\":\"1000\",\"max_count\":20,\"currency\":\"EUR\"}", MAKER);
        assertThat(daily.status()).isEqualTo(201);
        Res monthly = post("/v1/card-spend-limits", "{\"card_program_id\":\"" + program
                + "\",\"period\":\"MONTHLY\",\"max_amount\":\"5000\",\"currency\":\"EUR\"}", MAKER);
        assertThat(monthly.status()).isEqualTo(201);
        peers.usageByLimit.put(daily.text("id"), (ObjectNode) json.readTree("""
                {"limit_ref":"%s","period":"DAILY","channel":"ANY","period_start":"2026-10-06T00:00:00Z",
                 "used_amount":"120.50","used_count":3,"currency":"EUR"}""".formatted(daily.text("id"))));
        JsonNode limits = get("/v1/cards/" + virtualCard + "/limits").body().get("data");
        assertThat(limits).hasSize(2);
        JsonNode d = limits.get(0).get("id").asString().equals(daily.text("id")) ? limits.get(0) : limits.get(1);
        assertThat(d.get("used_amount").asString()).isEqualTo("120.50");
        assertThat(d.get("used_count").asInt()).isEqualTo(3);
        assertThat(events("cards.card_spend_limit.changed")).isEqualTo(2);

        // auth-context: everything the authorisation hot path needs in one call
        JsonNode ctx = get("/v1/cards/" + virtualCard + "/auth-context").body();
        assertThat(ctx.get("card").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(ctx.get("card").get("ledger_account_id").asString()).isEqualTo(LEDGER_ACCOUNT);
        assertThat(ctx.get("program").get("funding_model").asString()).isEqualTo("DEBIT");
        assertThat(ctx.get("program").get("fraud_policy").asString()).isEqualTo("FAIL_OPEN");
        assertThat(ctx.get("program").get("fx_markup_rate").asString()).startsWith("0.02");
        assertThat(ctx.get("product").get("ecom_enabled").asBoolean()).isTrue();
        assertThat(ctx.get("controls")).hasSize(2);
        assertThat(ctx.get("limits")).hasSize(2);
        assertThat(ctx.get("as_of").asString()).isNotBlank();
        assertThat(ctx.get("card").has("pan")).isFalse();
        assertThat(get("/v1/cards/" + UUID.randomUUID() + "/auth-context").status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ replace & renew

    @Test
    @Order(8)
    void replaceLostCardWithNewPanAndDamagedCardKeepingPan() throws Exception {
        Res lostCard = post("/v1/cards", cardBody("VIRTUAL", "LOST CARD"), MAKER);
        String oldId = lostCard.text("id");
        post("/v1/card-spend-limits", "{\"card_id\":\"" + oldId + "\",\"period\":\"DAILY\",\"max_amount\":\"50\",\"currency\":\"EUR\"}", MAKER);
        assertThat(post("/v1/cards/" + oldId + "/replace", "{\"reason\":\"STOLEN\",\"keep_pan\":true}", MAKER).code())
                .isEqualTo("keep_pan_not_allowed");
        Res rep = post("/v1/cards/" + oldId + "/replace", "{\"reason\":\"LOST\"}", MAKER);
        assertThat(rep.status()).isEqualTo(200);
        assertThat(rep.text("old_card_id")).isEqualTo(oldId);
        assertThat(rep.body().get("fee_charged").asBoolean()).isTrue();
        assertThat(rep.text("fee_id")).isNotNull();
        String newId = rep.text("new_card_id");
        Res newCard = get("/v1/cards/" + newId);
        assertThat(newCard.text("replaces_card_id")).isEqualTo(oldId);
        assertThat(newCard.text("status")).isEqualTo("ACTIVE");
        assertThat(newCard.text("pan_token")).isNotEqualTo(lostCard.text("pan_token"));
        assertThat(peers.panOfToken(newCard.text("pan_token"))).isNotEqualTo(peers.panOfToken(lostCard.text("pan_token")));
        assertThat(get("/v1/cards/" + oldId).text("status")).isEqualTo("LOST");
        assertThat(get("/v1/card-spend-limits?card_id=" + newId).body().get("data")).hasSize(1);
        FakePeers.Call fee = peers.calls("card-clearing-service", "/v1/card-fees").getLast();
        assertThat(fee.body().get("fee_type").asString()).isEqualTo("REPLACEMENT");
        assertThat(fee.body().get("amount").asString()).isEqualTo("5.00");
        assertThat(fee.body().get("ledger_account_id").asString()).isEqualTo(LEDGER_ACCOUNT);
        assertThat(eventsFor("cards.card.replaced", rep.text("id"))).isEqualTo(1);
        assertThat(post("/v1/cards/" + newId + "/replace", "{\"reason\":\"LOST\"}", MAKER).status()).isEqualTo(200);
        assertThat(post("/v1/cards/" + oldId + "/close", "{}", MAKER).text("status")).isEqualTo("CLOSED");
        assertThat(post("/v1/cards/" + oldId + "/replace", "{\"reason\":\"OTHER\"}", MAKER).status()).isEqualTo(409);

        Res damaged = post("/v1/cards", cardBody("VIRTUAL", "DAMAGED CARD"), MAKER);
        int feesBefore = peers.calls("card-clearing-service", "/v1/card-fees").size();
        Res keep = post("/v1/cards/" + damaged.text("id") + "/replace", "{\"reason\":\"EXPIRING\",\"keep_pan\":true}", MAKER);
        assertThat(keep.status()).isEqualTo(200);
        assertThat(keep.body().get("keep_pan").asBoolean()).isTrue();
        assertThat(keep.body().get("fee_charged").asBoolean()).isFalse();
        assertThat(peers.calls("card-clearing-service", "/v1/card-fees")).hasSize(feesBefore);
        Res kept = get("/v1/cards/" + keep.text("new_card_id"));
        assertThat(peers.panOfToken(kept.text("pan_token"))).isEqualTo(peers.panOfToken(damaged.text("pan_token")));
        assertThat(kept.text("last4")).isEqualTo(damaged.text("last4"));
        assertThat(kept.body().get("sequence_number").asInt()).isEqualTo(1);
        assertThat(peers.tokenActive(damaged.text("pan_token"))).isFalse();
        assertThat(get("/v1/cards/" + damaged.text("id")).text("status")).isEqualTo("REPLACED");
        assertThat(peers.calls("card-vault-service", "/v1/card-vault/reissue")).isNotEmpty();
    }

    @Test
    @Order(9)
    void renewKeepsPanUnderNewTokenAndExpiry() throws Exception {
        Res before = get("/v1/cards/" + physicalCard);
        Res renewed = post("/v1/cards/" + physicalCard + "/renew", null, MAKER);
        assertThat(renewed.status()).isEqualTo(200);
        assertThat(renewed.text("pan_token")).isNotEqualTo(before.text("pan_token"));
        assertThat(peers.panOfToken(renewed.text("pan_token"))).isEqualTo(peers.panOfToken(before.text("pan_token")));
        assertThat(renewed.body().get("sequence_number").asInt()).isEqualTo(before.body().get("sequence_number").asInt() + 1);
        assertThat(eventsFor("cards.card.renewed", physicalCard)).isEqualTo(1);
        String closed = post("/v1/cards", cardBody("VIRTUAL", "TO CLOSE"), MAKER).text("id");
        post("/v1/cards/" + closed + "/close", "{}", MAKER);
        assertThat(post("/v1/cards/" + closed + "/renew", null, MAKER).status()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ expiry scan

    @Test
    @Order(10)
    void expiryScanNotifiesOncePerExpiryAndExpiresPastCards() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        String expiring = post("/v1/cards", cardBody("VIRTUAL", "SOON EXPIRING"), MAKER).text("id");
        String past = post("/v1/cards", cardBody("VIRTUAL", "LONG EXPIRED"), MAKER).text("id");
        asTenant(() -> jdbc.sql("update card set expiry_month = ?, expiry_year = ? where id = ?::uuid")
                .params(today.getMonthValue(), today.getYear(), expiring).update());
        asTenant(() -> jdbc.sql("update card set expiry_month = 1, expiry_year = ? where id = ?::uuid")
                .params(today.getYear() - 1, past).update());
        Res scan = post("/v1/cards/expiry-scan?as_of=" + today, null, MAKER);
        assertThat(scan.status()).isEqualTo(200);
        assertThat(scan.body().get("expiring").asInt()).isEqualTo(1);
        assertThat(scan.body().get("expired").asInt()).isEqualTo(1);
        assertThat(get("/v1/cards/" + past).text("status")).isEqualTo("EXPIRED");
        assertThat(eventsFor("cards.card.expiring", expiring)).isEqualTo(1);
        Res again = post("/v1/cards/expiry-scan?as_of=" + today, null, MAKER);
        assertThat(again.body().get("expiring").asInt()).isZero();
        assertThat(again.body().get("expired").asInt()).isZero();
        assertThat(post("/v1/cards/expiry-scan?window_days=900", null, MAKER).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ credit card statements

    @Test
    @Order(11)
    void creditCardStatementCycleCloseFromLedgerEntries() throws Exception {
        Res cp = post("/v1/card-programs", programBody("EU-CREDIT", "CREDIT", "453920", "4539200", "4539209"), MAKER);
        String creditProgram = cp.text("id");
        post("/v1/card-programs/" + creditProgram + "/certify", null, MAKER);
        post("/v1/card-programs/" + creditProgram + "/activate", "{}", CHECKER);
        String creditProduct = post("/v1/card-products", """
                {"card_program_id":"%s","code":"GOLD","name":"Gold credit","form_factors":["VIRTUAL"]}""".formatted(creditProgram), MAKER)
                .text("id");
        post("/v1/card-products/" + creditProduct + "/activate", null, MAKER);
        String body = cardBody("VIRTUAL", "CREDIT HOLDER").replace(program, creditProgram).replace(product, creditProduct)
                .replace("DEPOSIT_ACCOUNT", "CREDIT_LINE");
        assertThat(post("/v1/cards", body, MAKER).code()).isEqualTo("credit_line_required");
        ObjectNode b = (ObjectNode) json.readTree(body);
        b.put("credit_line_id", CREDIT_LINE);
        b.remove("deposit_account_id");
        String card = post("/v1/cards", json.writeValueAsString(b), MAKER).text("id");
        JsonNode cycle = get("/v1/card-statement-cycles?card_id=" + card).body().get("data").get(0);
        assertThat(cycle.get("status").asString()).isEqualTo("OPEN");
        assertThat(post("/v1/card-statement-cycles/" + cycle.get("id").asString() + "/close", null, MAKER).code())
                .isEqualTo("cycle_not_ended");
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        asTenant(() -> jdbc.sql("update card_statement_cycle set cycle_start = ?, cycle_end = ?, due_date = ?, opening_balance = 100 where id = ?::uuid")
                .params(today.minusDays(31), today.minusDays(1), today.plusDays(20), cycle.get("id").asString()).update());
        peers.ledgerEntries.add((ObjectNode) json.readTree("{\"direction\":\"DEBIT\",\"amount\":\"900.00\",\"currency\":\"EUR\",\"description\":\"Purchase ACME\"}"));
        peers.ledgerEntries.add((ObjectNode) json.readTree("{\"direction\":\"DEBIT\",\"amount\":\"100.00\",\"currency\":\"EUR\",\"description\":\"ATM cash\"}"));
        peers.ledgerEntries.add((ObjectNode) json.readTree("{\"direction\":\"DEBIT\",\"amount\":\"3.50\",\"currency\":\"EUR\",\"description\":\"Cash advance fee\"}"));
        peers.ledgerEntries.add((ObjectNode) json.readTree("{\"direction\":\"CREDIT\",\"amount\":\"200.00\",\"currency\":\"EUR\",\"description\":\"Payment thank you\"}"));
        Res closed = post("/v1/card-statement-cycles/" + cycle.get("id").asString() + "/close", null, MAKER);
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.text("status")).isEqualTo("CLOSED");
        assertThat(closed.text("purchases")).startsWith("900");
        assertThat(closed.text("cash_advances")).startsWith("100");
        assertThat(closed.text("fees")).startsWith("3.5");
        assertThat(closed.text("payments")).startsWith("200");
        assertThat(closed.text("closing_balance")).startsWith("903.5");
        assertThat(closed.text("minimum_due")).startsWith("45.18");
        assertThat(post("/v1/card-statement-cycles/" + cycle.get("id").asString() + "/close", null, MAKER, "other-key").status())
                .isEqualTo(409);
        JsonNode cycles = get("/v1/card-statement-cycles?card_id=" + card + "&status=OPEN").body().get("data");
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).get("opening_balance").asString()).startsWith("903.5");
        assertThat(cycles.get(0).get("cycle_start").asString()).isEqualTo(today.toString());
        assertThat(eventsFor("cards.card_statement_cycle.closed", cycle.get("id").asString())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ cross-service views, isolation, PCI

    @Test
    @Order(12)
    void authorizationsProxyAndProgramCards() throws Exception {
        Res auths = get("/v1/cards/" + virtualCard + "/authorizations?limit=10");
        assertThat(auths.status()).isEqualTo(200);
        assertThat(auths.body().get("data").get(0).get("card_id").asString()).isEqualTo(virtualCard);
        assertThat(peers.calls.stream().anyMatch(c -> c.path().equals("/v1/card-authorizations?card_id=" + virtualCard + "&limit=10")))
                .isTrue();
        assertThat(get("/v1/card-programs/" + program + "/cards").body().get("data").size()).isGreaterThan(5);
        assertThat(get("/v1/cards?customer_id=" + CUSTOMER + "&status=CLOSED").body().get("data").size()).isGreaterThan(0);
        assertThat(post("/v1/card-programs/" + program + "/suspend", "{\"reason\":\"scheme review\"}", MAKER).text("status"))
                .isEqualTo("SUSPENDED");
        assertThat(post("/v1/cards", cardBody("VIRTUAL", "AFTER SUSPEND"), MAKER).code()).isEqualTo("program_not_active");
        assertThat(post("/v1/card-programs/" + program + "/activate", "{}", CHECKER).text("status")).isEqualTo("ACTIVE");
    }

    @Test
    @Order(13)
    void tenantIsolation() throws Exception {
        assertThat(send("GET", "/v1/cards/" + virtualCard, null, MAKER, null, OTHER_TENANT, null).status()).isEqualTo(404);
        assertThat(send("GET", "/v1/cards", null, MAKER, null, OTHER_TENANT, null).body().get("data")).isEmpty();
        assertThat(send("GET", "/v1/cards/" + virtualCard + "/auth-context", null, MAKER, null, OTHER_TENANT, null).status())
                .isEqualTo(404);
        assertThat(send("POST", "/v1/cards/" + virtualCard + "/freeze", "{}", MAKER, "iso-1", OTHER_TENANT, null).status())
                .isEqualTo(404);
        assertThat(send("GET", "/v1/cards", null, MAKER, null, null, null).status()).isEqualTo(401);
    }

    @Test
    @Order(14)
    void noPanEverStoredOrPublishedByCardService() throws Exception {
        assertThat(peers.pansSeen).hasSizeGreaterThan(10);
        String all = dbText();
        for (String pan : peers.pansSeen) {
            assertThat(all).doesNotContain(pan);
        }
        // generic scan: no Luhn-valid 13-19 digit run anywhere in card_db (rows, outbox payloads, idempotency store)
        Matcher m = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)").matcher(all);
        List<String> suspicious = new ArrayList<>();
        while (m.find()) {
            if (Luhn.isValid(m.group())) {
                suspicious.add(m.group().substring(0, 4) + "...");
            }
        }
        assertThat(suspicious).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    String programBody(String code, String funding, String bin, String start, String end) {
        return """
                {"code":"%s","name":"%s program","legal_entity_id":"0199a000-0000-7000-8000-0000000000e1","scheme":"VISA",
                 "funding_model":"%s","bin":"%s","bin_range_start":"%s","bin_range_end":"%s","pan_length":16,
                 "currency":"EUR","country":"DE","fx_markup_rate":"0.02","stand_in_max_amount":"100.00",
                 "default_controls":[{"control_type":"MCC_DENY","channel":"ANY","values":["7995"]}]}"""
                .formatted(code, code, funding, bin, start, end);
    }

    String cardBody(String formFactor, String name) {
        return """
                {"card_program_id":"%s","card_product_id":"%s","customer_id":"%s","cardholder_name":"%s",
                 "form_factor":"%s","linked_account_type":"DEPOSIT_ACCOUNT","ledger_account_id":"%s",
                 "deposit_account_id":"%s"}""".formatted(program, product, CUSTOMER, name, formFactor, LEDGER_ACCOUNT,
                DEPOSIT_ACCOUNT);
    }

    long events(String type) {
        return eventsFor(type, null);
    }

    long eventsFor(String type, String aggregateId) {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where event_type = ?"
                        + (aggregateId == null ? "" : " and aggregate_id = '" + UUID.fromString(aggregateId) + "'::uuid"))
                .param(type).query(Long.class).single());
    }

    /** Every row of every card_db table as JSON text (RLS-scoped to the test tenant). */
    String dbText() {
        return asTenant(() -> {
            List<String> tables = jdbc.sql("select table_name from information_schema.tables where table_schema = 'card' "
                    + "and table_type = 'BASE TABLE' and table_name not like 'flyway%'").query(String.class).list();
            StringBuilder sb = new StringBuilder();
            for (String t : tables) {
                jdbc.sql("select row_to_json(x)::text from " + t + " x").query(String.class).list().forEach(sb::append);
            }
            return sb.toString();
        });
    }

    <T> T asTenant(Callable<T> body) {
        try {
            return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    Res callback(String body, String signature) throws Exception {
        return send("POST", "/v1/card-orders/bureau-callbacks?tenant_id=" + TENANT, body, null, null, null, signature);
    }

    Res post(String path, String body, String user) throws Exception {
        return post(path, body, user, UUID.randomUUID().toString());
    }

    Res post(String path, String body, String user, String key) throws Exception {
        return send("POST", path, body, user, key, TENANT, null);
    }

    Res get(String path) throws Exception {
        return send("GET", path, null, MAKER, null, TENANT, null);
    }

    Res patch(String path, String body, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", TENANT).header("X-User", MAKER)
                .header("If-Match", ifMatch).method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        return wrap(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    int delete(String path) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", MAKER).DELETE().build();
        return http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    Res send(String method, String path, String body, String user, String key, String tenant, String signature)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        if (user != null) {
            b.header("X-User", user);
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (signature != null) {
            b.header("X-Signature", signature);
        }
        b.method(method, body == null ? (method.equals("POST") ? HttpRequest.BodyPublishers.ofString("")
                : HttpRequest.BodyPublishers.noBody()) : HttpRequest.BodyPublishers.ofString(body));
        return wrap(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    Res wrap(HttpResponse<String> r) {
        JsonNode node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        return new Res(r.statusCode(), node, r);
    }
}
