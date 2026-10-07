package io.meridian.cardvault;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.cardvault.domain.PinBlock;
import io.meridian.cardvault.infrastructure.SoftwareHsm;
import io.meridian.test.MeridianTestContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * card-vault-service end to end over HTTP against PostgreSQL 18 (RLS enforced): tokenisation and de-duplication,
 * reveal (clear and RSA-wrapped) with audit, lookup, CVV / expiry / PIN verification with try counting, PIN set,
 * change and unblock from wrapped ISO 9564 format 0 blocks, reissue, PAN DEK / PIN key rotation and the master key
 * re-wrap (GAP-01). GAP-03: CVV2 is derived under the CVK, never stored; PINs are stored only as PIN blocks under the
 * PIN encryption key. Asserts that no PAN / CVV ever appears in clear in the database or the outbox.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CardVaultFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000c0001";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000c0002";
    static final String MASTER = "bWVyaWRpYW4tZGV2LXZhdWx0LW1hc3Rlci1rZXktMDE=";
    static final String PAN = "4000123412341234".substring(0, 15);
    static final String PAN_FULL = PAN + io.meridian.cardvault.domain.CardData.luhnCheckDigit(PAN);
    static final String PAN2 = "5412751234123456".substring(0, 15);
    static final String PAN2_FULL = PAN2 + io.meridian.cardvault.domain.CardData.luhnCheckDigit(PAN2);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    final HttpClient http = HttpClient.newHttpClient();
    final UUID card = UUID.randomUUID();
    final UUID card2 = UUID.randomUUID();
    String token;
    String token2;
    String cvv1;

    @BeforeAll
    void setUp() throws Exception {
        JsonNode r = call(TENANT, "POST", "/v1/card-vault", "tok-1", """
                {"pan":"%s","expiry_month":3,"expiry_year":2031,"card_id":"%s"}""".formatted(PAN_FULL, card), 201);
        token = r.get("pan_token").asString();
        // GAP-03: the CVV2 is the one derived under the tenant's CVK (software key management: the documented KDF)
        cvv1 = new SoftwareHsm(MASTER).deriveCvv(new io.meridian.cardvault.application.Hsm.KeyRef("cvk", 1), PAN_FULL, 3,
                2031);
        JsonNode r2 = call(TENANT, "POST", "/v1/card-vault", "tok-2", """
                {"pan":"%s","expiry_month":7,"expiry_year":2030,"card_id":"%s"}""".formatted(PAN2_FULL, card2), 201);
        token2 = r2.get("pan_token").asString();
    }

    @Test
    @Order(1)
    void tokenizeReturnsMaskedDataOnlyAndDeduplicates() throws Exception {
        JsonNode r = call(TENANT, "GET", "/v1/card-vault/" + id(token), null, null, 200);
        assertThat(r.get("pan_token").asString()).startsWith("tok_").hasSize(36);
        assertThat(r.get("last4").asString()).isEqualTo(PAN_FULL.substring(12));
        assertThat(r.get("bin").asString()).isEqualTo(PAN_FULL.substring(0, 6));
        assertThat(r.get("pan_fingerprint").asString()).hasSize(64);
        assertThat(r.has("pan")).isFalse();
        assertThat(r.has("cvv")).isFalse();
        assertThat(r.toString()).doesNotContain(PAN_FULL).doesNotContain("pan_encrypted");

        // idempotent replay (same key + body) returns the stored response
        HttpResponse<String> replay = raw(TENANT, "POST", "/v1/card-vault", "tok-1", """
                {"pan":"%s","expiry_month":3,"expiry_year":2031,"card_id":"%s"}""".formatted(PAN_FULL, card));
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("pan_token").asString()).isEqualTo(token);

        // same PAN + same card with a new key: the existing token (dedupe by fingerprint)
        JsonNode same = call(TENANT, "POST", "/v1/card-vault", "tok-1b", """
                {"pan":"%s","expiry_month":3,"expiry_year":2031,"card_id":"%s"}""".formatted(PAN_FULL, card), 201);
        assertThat(same.get("pan_token").asString()).isEqualTo(token);

        // same PAN for another card: conflict
        problem(TENANT, "POST", "/v1/card-vault", "tok-1c", """
                {"pan":"%s","expiry_month":3,"expiry_year":2031,"card_id":"%s"}""".formatted(PAN_FULL, UUID.randomUUID()),
                409, "pan_already_vaulted");
        problem(TENANT, "POST", "/v1/card-vault", "tok-bad", """
                {"pan":"4111111111111112","expiry_month":3,"expiry_year":2031}""", 422, "invalid_pan");
        problem(TENANT, "POST", "/v1/card-vault", "tok-bad2", """
                {"pan":"4111111111111111","expiry_month":13,"expiry_year":2031}""", 422, "invalid_expiry");
        // key versions are recorded per record: DEK and card verification key
        assertThat(r.get("dek_key_id").isNull()).isFalse();
        assertThat(r.get("cvk_key_id").isNull()).isFalse();
        assertThat(r.has("cvv_encrypted")).isFalse();
    }

    @Test
    @Order(2)
    void revealInClearAndAudits() throws Exception {
        JsonNode r = call(TENANT, "POST", "/v1/card-vault/reveal", "rev-1", """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(card), 200);
        assertThat(r.get("pan").asString()).isEqualTo(PAN_FULL);
        assertThat(r.get("cvv").asString()).isEqualTo(cvv1);   // derived on demand, never stored
        assertThat(r.get("expiry_month").asInt()).isEqualTo(3);
        assertThat(r.get("expiry_year").asInt()).isEqualTo(2031);
        assertThat(r.get("last_detokenized_at").isNull()).isFalse();

        // generated CVV for the second card: 3 digits, stable
        JsonNode r2 = call(TENANT, "POST", "/v1/card-vault/reveal", "rev-2", """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(card2), 200);
        assertThat(r2.get("cvv").asString()).matches("\\d{3}");

        JsonNode logs = call(TENANT, "GET", "/v1/card-vault/access-logs?purpose=REVEAL&card_id=" + card, null, null, 200);
        assertThat(logs.get("data")).hasSize(1);
        assertThat(logs.get("data").get(0).get("success").asBoolean()).isTrue();
        assertThat(logs.get("data").get(0).get("pan_token").asString()).isEqualTo(token);
        // SEC-32: the client-chosen X-Caller-Service header is not believed; the actor is recorded instead
        assertThat(logs.get("data").get(0).get("caller_service").asString()).isEqualTo("alice");
        assertThat(logs.get("data").get(0).get("actor_id").asString()).isEqualTo("alice");

        // unknown card: 404 and a failed access row that survived the rollback
        UUID unknown = UUID.randomUUID();
        problem(TENANT, "POST", "/v1/card-vault/reveal", "rev-x", """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(unknown), 404, "not_found");
        JsonNode failed = call(TENANT, "GET", "/v1/card-vault/access-logs?card_id=" + unknown, null, null, 200);
        assertThat(failed.get("data")).hasSize(1);
        assertThat(failed.get("data").get(0).get("success").asBoolean()).isFalse();

        // purpose is required
        HttpResponse<String> noPurpose = raw(TENANT, "POST", "/v1/card-vault/reveal", "rev-np", """
                {"card_id":"%s"}""".formatted(card));
        assertThat(noPurpose.statusCode()).isEqualTo(400);
        // Idempotency-Key is required too
        HttpResponse<String> noKey = raw(TENANT, "POST", "/v1/card-vault/reveal", null, """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(card));
        assertThat(noKey.statusCode()).isEqualTo(400);

        // the revealed event carries the audit row, never card data
        List<String> payloads = outbox("cards.card_vault.revealed");
        assertThat(payloads).hasSize(2);
        assertThat(payloads.getFirst()).contains("\"purpose\": \"REVEAL\"").doesNotContain(PAN_FULL);
    }

    @Test
    @Order(3)
    void revealEncryptedToTheAppsPublicKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        JsonNode r = call(TENANT, "POST", "/v1/card-vault/reveal", "rev-rsa", json.writeValueAsString(
                java.util.Map.of("card_id", card.toString(), "purpose", "REVEAL", "public_key", pem)), 200);
        assertThat(r.has("pan")).isFalse();
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.DECRYPT_MODE, kp.getPrivate(), new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT));
        JsonNode secret = json.readTree(c.doFinal(Base64.getDecoder().decode(r.get("encrypted_payload").asString())));
        assertThat(secret.get("pan").asString()).isEqualTo(PAN_FULL);
        assertThat(secret.get("cvv").asString()).isEqualTo(cvv1);
        assertThat(secret.get("expiry_month").asInt()).isEqualTo(3);
        problem(TENANT, "POST", "/v1/card-vault/reveal", "rev-badkey", """
                {"card_id":"%s","purpose":"REVEAL","public_key":"not-a-key"}""".formatted(card), 422, "invalid_public_key");
    }

    @Test
    @Order(4)
    void lookupByPan() throws Exception {
        JsonNode r = call(TENANT, "POST", "/v1/card-vault/lookup", "lk-1", """
                {"pan":"%s","purpose":"THREEDS"}""".formatted(PAN_FULL), 200);
        assertThat(r.get("pan_token").asString()).isEqualTo(token);
        assertThat(r.get("card_id").asString()).isEqualTo(card.toString());
        assertThat(r.has("pan")).isFalse();
        problem(TENANT, "POST", "/v1/card-vault/lookup", "lk-2", """
                {"pan":"4111111111111111","purpose":"THREEDS"}""", 404, "not_found");
        JsonNode logs = call(TENANT, "GET", "/v1/card-vault/access-logs?purpose=THREEDS", null, null, 200);
        assertThat(logs.get("data")).hasSize(2);
    }

    @Test
    @Order(5)
    void pinSetVerifyTriesAndUnblock() throws Exception {
        // no PIN yet
        JsonNode v0 = verify(null, null, null, pinBlock("2580", PAN_FULL, "zpk-default", 1), "zpk-default");
        assertThat(v0.get("pin_result").asString()).isEqualTo("NOT_SET");

        JsonNode set = call(TENANT, "POST", "/v1/card-vault/pin", "pin-1", body(token, pinBlock("2580", PAN_FULL,
                "zpk-default", 1), "zpk-default"), 200);
        assertThat(set.get("pin_set").asBoolean()).isTrue();
        assertThat(set.get("event_type").asString()).isEqualTo("SET");

        // weak PIN, undecodable block, unknown zone key
        problem(TENANT, "POST", "/v1/card-vault/pin", "pin-weak", body(token, pinBlock("1234", PAN_FULL, "zpk-default", 1),
                "zpk-default"), 422, "weak_pin");
        problem(TENANT, "POST", "/v1/card-vault/pin", "pin-bad", body(token, pinBlock("7391", PAN_FULL, "zpk-other", 1),
                "zpk-default"), 422, "invalid_pin_block");
        problem(TENANT, "POST", "/v1/card-vault/pin", "pin-nokey", body(token, pinBlock("7391", PAN_FULL, "zpk-x", 1),
                "zpk-x"), 404, "not_found");

        JsonNode change = call(TENANT, "POST", "/v1/card-vault/pin", "pin-2", body(token, pinBlock("7391", PAN_FULL,
                "zpk-default", 1), "zpk-default"), 200);
        assertThat(change.get("event_type").asString()).isEqualTo("CHANGE");
        // GAP-03: the PIN is stored only as a PIN block under the PIN encryption key, named per record
        JsonNode rec = call(TENANT, "GET", "/v1/card-vault/" + id(token), null, null, 200);
        assertThat(rec.get("pin_key_id").isNull()).isFalse();
        assertThat(rec.has("pin_encrypted")).isFalse();

        JsonNode ok = verify(cvv1, 3, 2031, pinBlock("7391", PAN_FULL, "zpk-default", 1), "zpk-default");
        assertThat(ok.get("pin_result").asString()).isEqualTo("VERIFIED");
        assertThat(ok.get("cvv_result").asString()).isEqualTo("M");
        assertThat(ok.get("expiry_result").asString()).isEqualTo("M");
        JsonNode bad = verify(wrongCvv(1), 4, 2031, null, null);
        assertThat(bad.get("cvv_result").asString()).isEqualTo("N");
        assertThat(bad.get("expiry_result").asString()).isEqualTo("N");
        assertThat(bad.get("pin_result").asString()).isEqualTo("NOT_PROCESSED");
        JsonNode none = verify(null, null, null, null, null);
        assertThat(none.get("cvv_result").asString()).isEqualTo("P");
        // SEC-24: a correct CVV2 resets the counter; 5 consecutive failures block CVV2 checks, even for the right value
        assertThat(verify(cvv1, null, null, null, null).get("cvv_result").asString()).isEqualTo("M");
        for (int i = 1; i <= 5; i++) {
            JsonNode miss = verify(wrongCvv(i), null, null, null, null);
            assertThat(miss.get("cvv_result").asString()).isEqualTo("N");
            assertThat(miss.get("cvv_blocked").asBoolean()).isEqualTo(i == 5);
        }
        JsonNode cvvBlocked = verify(cvv1, null, null, null, null);
        assertThat(cvvBlocked.get("cvv_result").asString()).isEqualTo("N");
        assertThat(cvvBlocked.get("cvv_blocked").asBoolean()).isTrue();
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian")) {
            c.createStatement().execute("update card_vault.card_data_vault set cvv_fail_count = 0, cvv_blocked_at = null");
        }

        for (int i = 1; i <= 3; i++) {
            JsonNode wrong = verify(null, null, null, pinBlock("2580", PAN_FULL, "zpk-default", 1), "zpk-default");
            assertThat(wrong.get("pin_result").asString()).isEqualTo("INVALID");
            assertThat(wrong.get("pin_try_count").asInt()).isEqualTo(i);
        }
        JsonNode blocked = verify(null, null, null, pinBlock("7391", PAN_FULL, "zpk-default", 1), "zpk-default");
        assertThat(blocked.get("pin_result").asString()).isEqualTo("TRIES_EXCEEDED");
        assertThat(call(TENANT, "GET", "/v1/card-vault/" + id(token), null, null, 200).get("pin_try_count").asInt())
                .isEqualTo(3);

        JsonNode unblock = call(TENANT, "POST", "/v1/card-vault/pin/unblock", "unb-1", """
                {"pan_token":"%s","reason":"customer verified at branch"}""".formatted(token), 200);
        assertThat(unblock.get("pin_try_count").asInt()).isZero();
        assertThat(verify(null, null, null, pinBlock("7391", PAN_FULL, "zpk-default", 1), "zpk-default")
                .get("pin_result").asString()).isEqualTo("VERIFIED");

        JsonNode pinLogs = call(TENANT, "GET", "/v1/card-vault/access-logs?purpose=PIN_MANAGEMENT&limit=200", null, null, 200);
        assertThat(pinLogs.get("data").size()).isGreaterThanOrEqualTo(6); // 2 ok + 3 failures + unblock
    }

    @Test
    @Order(6)
    void reissueKeepsPanAndPinUnderANewToken() throws Exception {
        UUID newCard = UUID.randomUUID();
        JsonNode r = call(TENANT, "POST", "/v1/card-vault/reissue", "reis-1", """
                {"pan_token":"%s","card_id":"%s","expiry_month":9,"expiry_year":2035}""".formatted(token, newCard), 200);
        String newToken = r.get("pan_token").asString();
        assertThat(newToken).isNotEqualTo(token);
        assertThat(r.get("pin_set").asBoolean()).isTrue();
        assertThat(call(TENANT, "GET", "/v1/card-vault/" + id(token), null, null, 200).get("is_active").asBoolean()).isFalse();
        JsonNode rev = call(TENANT, "POST", "/v1/card-vault/reveal", "rev-new", """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(newCard), 200);
        assertThat(rev.get("pan").asString()).isEqualTo(PAN_FULL);
        assertThat(rev.get("expiry_month").asInt()).isEqualTo(9);
        assertThat(rev.get("cvv").asString()).matches("\\d{3}");
        // the PIN followed the PAN
        JsonNode v = call(TENANT, "POST", "/v1/card-vault/verify", "ver-new", """
                {"pan_token":"%s","encrypted_pin_block":"%s","pin_key_id":"zpk-default"}"""
                .formatted(newToken, pinBlock("7391", PAN_FULL, "zpk-default", 1)), 200);
        assertThat(v.get("pin_result").asString()).isEqualTo("VERIFIED");
        // lookup now finds the reissued record
        assertThat(call(TENANT, "POST", "/v1/card-vault/lookup", "lk-new", """
                {"pan":"%s","purpose":"TOKENIZATION"}""".formatted(PAN_FULL), 200).get("pan_token").asString())
                .isEqualTo(newToken);
        token = newToken;
        problem(TENANT, "POST", "/v1/card-vault/reissue", "reis-x", """
                {"pan_token":"tok_unknown","card_id":"%s","expiry_month":9,"expiry_year":2035}""".formatted(newCard),
                404, "not_found");
    }

    @Test
    @Order(7)
    void rotatePanDekReEncryptsEverything() throws Exception {
        byte[] before = panCiphertext(token);
        JsonNode r = call(TENANT, "POST", "/v1/card-vault/rotate-keys", "rot-1", """
                {"key_type":"PAN_DEK"}""", 200);
        assertThat(r.get("version").asInt()).isEqualTo(2);
        assertThat(r.get("re_encrypted").asInt()).isEqualTo(3); // original, reissued and second card
        byte[] after = panCiphertext(token);
        assertThat(after).isNotEqualTo(before);

        JsonNode keys = call(TENANT, "GET", "/v1/card-vault/keys?key_type=PAN_DEK", null, null, 200);
        assertThat(keys.get("data")).hasSize(2);
        long active = 0;
        for (JsonNode k : keys.get("data")) {
            active += k.get("is_active").asBoolean() ? 1 : 0;
            assertThat(k.get("kcv").asString()).hasSize(6);
        }
        assertThat(active).isEqualTo(1);
        JsonNode vault = call(TENANT, "GET", "/v1/card-vault/" + id(token), null, null, 200);
        assertThat(vault.get("dek_key_id").asString()).isEqualTo(r.get("new_key_id").asString());

        // everything still decrypts
        JsonNode rev = call(TENANT, "POST", "/v1/card-vault/reveal", "rev-rot", """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(card2), 200);
        assertThat(rev.get("pan").asString()).isEqualTo(PAN2_FULL);
        assertThat(call(TENANT, "POST", "/v1/card-vault/verify", "ver-rot", """
                {"pan_token":"%s","encrypted_pin_block":"%s","pin_key_id":"zpk-default"}"""
                .formatted(token, pinBlock("7391", PAN_FULL, "zpk-default", 1)), 200).get("pin_result").asString())
                .isEqualTo("VERIFIED");
        assertThat(outbox("cards.card_vault.keys_rotated")).hasSize(1);

        // a newly registered ZPK becomes the active zone key: blocks wrapped under it are accepted by uuid
        JsonNode zpk = call(TENANT, "POST", "/v1/card-vault/keys", "key-zpk", """
                {"key_type":"ZPK","hsm_key_label":"zpk-partner"}""", 201);
        assertThat(zpk.get("version").asInt()).isEqualTo(1);
        assertThat(call(TENANT, "POST", "/v1/card-vault/verify", "ver-zpk2", """
                {"pan_token":"%s","encrypted_pin_block":"%s","pin_key_id":"%s"}"""
                .formatted(token, pinBlock("7391", PAN_FULL, "zpk-partner", 1), zpk.get("id").asString()), 200)
                .get("pin_result").asString()).isEqualTo("VERIFIED");

        // SEC-37: data keys are tenant-scoped; platform labels and the scope separator are reserved
        for (JsonNode k : call(TENANT, "GET", "/v1/card-vault/keys?key_type=PAN_DEK", null, null, 200).get("data")) {
            assertThat(k.get("hsm_key_label").asString()).endsWith("@" + TENANT);
        }
        problem(TENANT, "POST", "/v1/card-vault/keys", "key-res1", """
                {"key_type":"ZPK","hsm_key_label":"pan-dek"}""", 422, "reserved_key_label");
        problem(TENANT, "POST", "/v1/card-vault/keys", "key-res2", """
                {"key_type":"PAN_DEK","hsm_key_label":"x@0199a000-0000-7000-8000-0000000c0002"}""", 422, "reserved_key_label");
    }

    @Test
    @Order(8)
    void noCardDataInClearAnywhere() throws Exception {
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian")) {
            c.createStatement().execute("set search_path to card_vault");
            for (String sql : List.of(
                    "select string_agg(to_jsonb(v)::text, '') from card_data_vault v",
                    "select string_agg(encode(pan_encrypted, 'escape') || encode(expiry_encrypted, 'escape'), '')"
                            + " from card_data_vault",
                    "select string_agg(to_jsonb(l)::text, '') from card_vault_access_log l",
                    "select string_agg(payload::text, '') from outbox",
                    "select string_agg(coalesce(response_body::text, '') || request_hash, '') from idempotency_key")) {
                try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                    rs.next();
                    String all = rs.getString(1) == null ? "" : rs.getString(1);
                    assertThat(all).as(sql).doesNotContain(PAN_FULL).doesNotContain(PAN2_FULL)
                            .doesNotContain("\"cvv\"");
                }
            }
        }
    }

    @Test
    @Order(9)
    void tenantIsolation() throws Exception {
        problem(OTHER_TENANT, "POST", "/v1/card-vault/reveal", "iso-1", """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(card2), 404, "not_found");
        problem(OTHER_TENANT, "POST", "/v1/card-vault/lookup", "iso-2", """
                {"pan":"%s","purpose":"THREEDS"}""".formatted(PAN2_FULL), 404, "not_found");
        assertThat(raw(OTHER_TENANT, "GET", "/v1/card-vault/" + id(token2), null, null).statusCode()).isEqualTo(404);
        assertThat(call(OTHER_TENANT, "GET", "/v1/card-vault/access-logs", null, null, 200).get("data").toString())
                .doesNotContain(token2);
        // the other tenant can vault the same PAN independently (keys and fingerprints are per tenant data)
        JsonNode r = call(OTHER_TENANT, "POST", "/v1/card-vault", "iso-tok", """
                {"pan":"%s","expiry_month":7,"expiry_year":2030,"card_id":"%s"}""".formatted(PAN2_FULL, UUID.randomUUID()), 201);
        assertThat(r.get("pan_token").asString()).isNotEqualTo(token2);
    }

    @Test
    @Order(10)
    void pinKeyRotationReTranslatesStoredPinBlocks() throws Exception {
        JsonNode r = call(TENANT, "POST", "/v1/card-vault/rotate-keys", "rot-pin", """
                {"key_type":"PIN_KEY"}""", 200);
        assertThat(r.get("version").asInt()).isEqualTo(2);
        assertThat(r.get("re_encrypted").asInt()).isEqualTo(2); // the reissued record and its inactive source
        JsonNode rec = call(TENANT, "GET", "/v1/card-vault/" + id(token), null, null, 200);
        assertThat(rec.get("pin_key_id").asString()).isEqualTo(r.get("new_key_id").asString());
        // (zpk-partner is the active zone key since the previous test)
        assertThat(verify(null, null, null, pinBlock("7391", PAN_FULL, "zpk-partner", 1), "zpk-partner")
                .get("pin_result").asString()).isEqualTo("VERIFIED");
        problem(TENANT, "POST", "/v1/card-vault/rotate-keys", "rot-fp", """
                {"key_type":"FINGERPRINT"}""", 422, "key_not_rotatable");
    }

    @Test
    @Order(11)
    void masterKeyRewrapAdoptsLegacyKeysWithoutTouchingRecords() throws Exception {
        // nothing to do: every key is already wrapped under the current master key
        JsonNode first = call(TENANT, "POST", "/v1/card-vault/keys/rewrap", "rw-1", "{}", 200);
        assertThat(first.get("rewrapped").asInt()).isZero();
        assertThat(first.get("adopted").asInt()).isZero();
        assertThat(first.get("unchanged").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(first.get("master_key_ref").asString()).startsWith("software:");

        // keys from before envelope encryption (no wrapped data key) are adopted; records stay untouched
        byte[] before = panCiphertext(token);
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian")) {
            var ps = c.prepareStatement("update card_vault.card_vault_key set wrapped_key = null, master_key_ref = null"
                    + " where tenant_id = ?::uuid and key_type in ('PAN_DEK', 'CVK')");
            ps.setString(1, TENANT);
            assertThat(ps.executeUpdate()).isGreaterThanOrEqualTo(3);
        }
        JsonNode second = call(TENANT, "POST", "/v1/card-vault/keys/rewrap", "rw-2", "{}", 200);
        assertThat(second.get("adopted").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(panCiphertext(token)).isEqualTo(before);
        JsonNode rev = call(TENANT, "POST", "/v1/card-vault/reveal", "rev-rw", """
                {"card_id":"%s","purpose":"REVEAL"}""".formatted(card2), 200);
        assertThat(rev.get("pan").asString()).isEqualTo(PAN2_FULL);
        assertThat(outbox("cards.card_vault.keys_rewrapped")).hasSize(1)
                .allSatisfy(p -> assertThat(p).contains("adopted").doesNotContain("wrapped_key"));
        // the keys API and key events never carry wrapped key material
        assertThat(call(TENANT, "GET", "/v1/card-vault/keys?limit=50", null, null, 200).toString())
                .doesNotContain("wrapped_key");
        assertThat(String.join("", outbox("cards.card_vault.keys_rotated"))).doesNotContain("wrapped_key");
    }

    @Test
    @Order(12)
    void storedCvv2IsGone() throws Exception {
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
             ResultSet rs = c.createStatement().executeQuery("select count(*) from information_schema.columns"
                     + " where table_schema = 'card_vault' and column_name = 'cvv_encrypted'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A CVV2 that is not the card's. */
    String wrongCvv(int i) {
        return String.format("%03d", (Integer.parseInt(cvv1) + i) % 1000);
    }

    JsonNode verify(String cvv, Integer month, Integer year, String block, String keyId) throws Exception {
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("pan_token", token);
        if (cvv != null) {
            b.put("cvv", cvv);
        }
        if (month != null) {
            b.put("expiry_month", month);
            b.put("expiry_year", year);
        }
        if (block != null) {
            b.put("encrypted_pin_block", block);
            b.put("pin_key_id", keyId);
        }
        return call(TENANT, "POST", "/v1/card-vault/verify", "ver-" + UUID.randomUUID(), json.writeValueAsString(b), 200);
    }

    static String body(String token, String block, String keyId) {
        return """
                {"pan_token":"%s","encrypted_pin_block":"%s","pin_key_id":"%s"}""".formatted(token, block, keyId);
    }

    /** ISO 9564 format 0 block wrapped with AES-GCM under the zone key, derived with the vault's documented KDF. */
    static String pinBlock(String pin, String pan, String zpkLabel, int version) throws Exception {
        byte[] key = SoftwareHsm.deriveKey(Base64.getDecoder().decode(MASTER), zpkLabel, version);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(PinBlock.encode(pin, pan));
        byte[] all = new byte[12 + ct.length];
        System.arraycopy(iv, 0, all, 0, 12);
        System.arraycopy(ct, 0, all, 12, ct.length);
        return Base64.getEncoder().encodeToString(all);
    }

    String id(String panToken) throws Exception {
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian")) {
            var ps = c.prepareStatement("select id from card_vault.card_data_vault where pan_token = ?");
            ps.setString(1, panToken);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    byte[] panCiphertext(String panToken) throws Exception {
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian")) {
            var ps = c.prepareStatement("select pan_encrypted from card_vault.card_data_vault where pan_token = ?");
            ps.setString(1, panToken);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBytes(1);
            }
        }
    }

    List<String> outbox(String type) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian")) {
            var ps = c.prepareStatement("select payload::text from card_vault.outbox where event_type = ? and tenant_id = ?::uuid order by created_at");
            ps.setString(1, type);
            ps.setString(2, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    HttpResponse<String> raw(String tenant, String method, String path, String key, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", tenant)
                .header("X-User", "alice").header("X-Caller-Service", "cardholder-app");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode call(String tenant, String method, String path, String key, String body, int expected) throws Exception {
        HttpResponse<String> r = raw(tenant, method, path, key, body);
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    void problem(String tenant, String method, String path, String key, String body, int status, String code)
            throws Exception {
        JsonNode p = call(tenant, method, path, key, body, status);
        assertThat(p.get("code").asString()).as(p.toString()).isEqualTo(code);
    }
}
