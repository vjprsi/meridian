package io.meridian.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PiiMaskerTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID T1 = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    static final UUID T2 = UUID.fromString("0199a000-0000-7000-8000-0000000000a2");
    final PiiMasker masker = new PiiMasker(Map.of("customer", Set.of("first_name", "date_of_birth", "custom_blob")),
            "test-audit-pseudonym-key-0123456789abcdef");

    @Test
    void specPiiColumnsAndPersonalFieldNamesArePseudonymisedAtAnyDepth() {
        JsonNode in = JSON.readTree("""
                {"id":"0199a000-0000-7000-8000-000000000001","first_name":"Jane","date_of_birth":"1990-01-01",
                 "custom_blob":{"a":1},"status":"ACTIVE","customer_id":"0199a000-0000-7000-8000-000000000002",
                 "national_id":"AB123456","lookup_hash":"9f2c","phone_masked":"+44*****789","middle_name":null,
                 "collected_data":{"email":"Jane@Example.org","note":"kept","addresses":[{"line1":"1 High St","city":"Leeds"}]}}""");
        JsonNode out = masker.mask(T1, "customer", in);
        assertThat(out.get("first_name").asString()).startsWith("psn:").hasSize(36);
        assertThat(out.get("date_of_birth").asString()).startsWith("psn:");
        assertThat(out.get("custom_blob").asString()).startsWith("psn:");       // spec pii column of any JSON type
        assertThat(out.get("national_id").asString()).startsWith("psn:");       // by name, though not in the map
        assertThat(out.get("collected_data").get("email").asString()).startsWith("psn:");
        assertThat(out.get("collected_data").get("addresses").get(0).get("line1").asString()).startsWith("psn:");
        assertThat(out.get("collected_data").get("addresses").get(0).get("city").asString()).isEqualTo("Leeds");
        assertThat(out.get("collected_data").get("note").asString()).isEqualTo("kept");
        assertThat(out.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(out.get("customer_id").asString()).isEqualTo("0199a000-0000-7000-8000-000000000002");
        assertThat(out.get("lookup_hash").asString()).isEqualTo("9f2c");
        assertThat(out.get("phone_masked").asString()).isEqualTo("+44*****789");
        assertThat(out.get("middle_name").isNull()).isTrue();
        assertThat(out.toString()).doesNotContain("Jane").doesNotContain("AB123456").doesNotContain("High St");
    }

    @Test
    void pseudonymsAreStablePerTenantNormalisedAndNotReMasked() {
        String a = masker.mask(T1, "x", JSON.readTree("{\"email\":\"jane@example.org\"}")).get("email").asString();
        String b = masker.mask(T1, "x", JSON.readTree("{\"email\":\" JANE@example.org \"}")).get("email").asString();
        String other = masker.mask(T2, "x", JSON.readTree("{\"email\":\"jane@example.org\"}")).get("email").asString();
        assertThat(a).isEqualTo(b).isNotEqualTo(other);
        JsonNode twice = masker.mask(T1, "x", JSON.readTree("{\"email\":\"" + a + "\"}"));
        assertThat(twice.get("email").asString()).isEqualTo(a);
        assertThat(masker.mask(T1, "x", null)).isNull();
    }

    @Test
    void namesDecideOnlyWhenTheSpecIsSilent() {
        assertThat(PiiMasker.isPersonalField("contact_email")).isTrue();
        assertThat(PiiMasker.isPersonalField("beneficiary_account_number")).isTrue();
        assertThat(PiiMasker.isPersonalField("tax_id")).isTrue();
        assertThat(PiiMasker.isPersonalField("employer_tax_id")).isTrue();
        assertThat(PiiMasker.isPersonalField("email_masked")).isFalse();
        assertThat(PiiMasker.isPersonalField("beneficiary_account_digest")).isFalse();
        assertThat(PiiMasker.isPersonalField("product_name")).isFalse();
        assertThat(PiiMasker.isPersonalField("customer_id")).isFalse();
        assertThatThrownBy(() -> new PiiMasker(Map.of(), " ")).isInstanceOf(IllegalStateException.class);
    }
}
