package io.meridian.fraud.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PseudonymsTest {
    static final byte[] SECRET = "unit-test-secret-0123456789abcdefghijklmnop".getBytes(StandardCharsets.UTF_8);
    static final UUID T1 = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    static final UUID T2 = UUID.fromString("0199a000-0000-7000-8000-000000000002");
    static final Pseudonyms P1 = Pseudonyms.of(Pseudonyms.tenantKey(SECRET, T1));
    static final Pseudonyms P2 = Pseudonyms.of(Pseudonyms.tenantKey(SECRET, T2));
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void canonicalDigestIsTheOnePaymentServiceSends() {
        // same vector as payment-service FraudContextDigestTest
        assertThat(ListValues.hash("BENEFICIARY_ACCOUNT", "gb33 bukb-2020 1555 5555 55"))
                .isEqualTo("f7b0e9c9cc3e4af959b5ff7a40db8f9495d5f0ebf236fffeb551c105d2574047");
    }

    @Test
    void sameValueSameTenantSamePseudonymWhateverTheFormatting() {
        String a = P1.value("BENEFICIARY_ACCOUNT", "GB33 BUKB 2020 1555 5555 55");
        assertThat(a).isEqualTo(P1.value("BENEFICIARY_ACCOUNT", "gb33bukb20201555555555")).startsWith("psn:").hasSize(36);
        assertThat(a).isEqualTo(P1.valueOfDigest(ListValues.hash("BENEFICIARY_ACCOUNT", "GB33BUKB20201555555555")));
        assertThat(a).isNotEqualTo(P2.value("BENEFICIARY_ACCOUNT", "GB33BUKB20201555555555"));
        assertThat(P1.value("EMAIL", "A@B.co")).isNotEqualTo(P1.value("PHONE", "A@B.co"));
        // a different secret gives unrelated pseudonyms (no unkeyed hash an attacker can enumerate)
        Pseudonyms other = Pseudonyms.of(Pseudonyms.tenantKey("another-secret-0123456789abcdefghijklmn".getBytes(), T1));
        assertThat(other.value("BENEFICIARY_ACCOUNT", "GB33BUKB20201555555555")).isNotEqualTo(a);
        assertThat(P1.listHash(ListValues.hash("EMAIL", "x@y.io"))).startsWith("h1:").hasSize(67);
        assertThat(P1.accountKey(ListValues.hash("BENEFICIARY_ACCOUNT", "123"), "abcdkenx"))
                .isEqualTo(P1.accountKey(ListValues.hash("BENEFICIARY_ACCOUNT", "123"), " ABCDKENX")).startsWith("acct:h1:");
        assertThat(P1.msisdnKey("+254 712 000111")).isEqualTo(P1.msisdnKey("254712000111")).startsWith("msisdn:h1:");
    }

    @Test
    void contextIdentifiersArePseudonymisedAndEverythingElseIsKept() {
        JsonNode ctx = JSON.readTree("""
                {"beneficiary_account":"0012345678","payee_msisdn":"+254712000111","email":"jo@example.com",
                 "device_id":"abc","card_last4":"1234","card_bin":"41111111","merchant_id":"0199a000-0000-7000-8000-00000000000a",
                 "amount_band":"HIGH","count":3,"requested_execution_date":"2026-10-08","free":"pay 5500 0000 0000 0004 now",
                 "contact":"call +44 20 7946 0958","mail":"x@y.io","beneficiary_account_digest":"%s",
                 "list":[{"iban":"DE89370400440532013000"}]}""".formatted(ListValues.hash("BENEFICIARY_ACCOUNT", "DE89370400440532013000")));
        JsonNode out = ContextPseudonymisation.sanitise(ctx, P1);
        String text = out.toString();
        for (String raw : new String[] {"0012345678", "254712000111", "jo@example.com", "\"abc\"", "\"1234\"",
                "5500 0000 0000 0004", "7946", "x@y.io", "DE8937"}) {
            assertThat(text).as(raw).doesNotContain(raw);
        }
        assertThat(out.get("card_bin").asString()).isEqualTo("41111111");
        assertThat(out.get("merchant_id").asString()).isEqualTo("0199a000-0000-7000-8000-00000000000a");
        assertThat(out.get("amount_band").asString()).isEqualTo("HIGH");
        assertThat(out.get("count").asInt()).isEqualTo(3);
        assertThat(out.get("requested_execution_date").asString()).isEqualTo("2026-10-08");
        assertThat(out.get("free").asString()).startsWith("pay psn:").endsWith(" now");
        assertThat(out.get("beneficiary_account").asString()).isEqualTo(P1.value("BENEFICIARY_ACCOUNT", "0012345678"));
        // a digest field carries the pseudonym of the clear value (under its base name unless that is present too)
        assertThat(out.get("beneficiary_account_digest").asString())
                .isEqualTo(P1.value("BENEFICIARY_ACCOUNT", "DE89370400440532013000"));
        assertThat(out.get("list").get(0).get("iban").asString())
                .isEqualTo(P1.value("BENEFICIARY_ACCOUNT", "DE89370400440532013000"));
        // idempotent: pseudonyms are recognised and kept
        assertThat(ContextPseudonymisation.sanitise(out, P1).toString()).isEqualTo(text);
    }
}
