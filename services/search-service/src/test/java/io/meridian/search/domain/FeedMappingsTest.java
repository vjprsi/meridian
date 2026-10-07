package io.meridian.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.search.infrastructure.FeedMappingsConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The shipped search-mappings.yaml: consistency with the event contracts, PII rules, lifecycle and rendering. */
class FeedMappingsTest {
    static final FeedMappings MAPPINGS = FeedMappingsConfig.load(new ClassPathResource("search-mappings.yaml"));
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void everyMappedEventAndFieldExistsInTheAsyncApiContract() throws IOException {
        Path file = repoRoot().resolve("generated/asyncapi/meridian.asyncapi.yaml");
        LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(256 * 1024 * 1024);
        opts.setMaxAliasesForCollections(1_000_000);
        Map<String, Object> doc;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            doc = new Yaml(opts).load(r);
        }
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        Map<String, Map<String, Object>> messages = (Map<String, Map<String, Object>>) components.get("messages");
        Map<String, Map<String, Object>> schemas = (Map<String, Map<String, Object>>) components.get("schemas");
        Map<String, String> payloadOf = new LinkedHashMap<>();
        messages.values().forEach(m -> {
            String ref = String.valueOf(((Map<String, Object>) m.get("payload")).get("$ref"));
            payloadOf.put((String) m.get("name"), ref.substring(ref.lastIndexOf('/') + 1));
        });
        List<String> problems = new ArrayList<>();
        for (FeedMappings.Mapping m : MAPPINGS.all()) {
            Map<String, Object> props = (Map<String, Object>) schemas.get(m.schema()).get("properties");
            for (String e : m.events()) {
                if (!m.schema().equals(payloadOf.get(e))) {
                    problems.add(m.docKind() + ": " + e + " carries " + payloadOf.get(e) + ", not " + m.schema());
                }
                if (!e.startsWith(m.topic().split("\\.")[1] + ".")) {
                    problems.add(m.docKind() + ": " + e + " is not published on " + m.topic());
                }
            }
            for (String path : m.fieldPaths()) {
                if ("status".equals(path) && "CustomerContact".equals(m.schema())) {
                    continue; // a contact point has no lifecycle status: its documents are upserted or deleted
                }
                if (!props.containsKey(path.split("\\.")[0])) {
                    problems.add(m.docKind() + ": " + m.schema() + " has no field " + path);
                }
            }
        }
        assertThat(problems).isEmpty();
        // no SAR event is mappable
        payloadOf.keySet().stream().filter(e -> e.startsWith("risk.sar_"))
                .forEach(e -> assertThat(MAPPINGS.resolve(e)).as(e).isEmpty());
    }

    @Test
    void coversTheOperatorEntities() {
        assertThat(MAPPINGS.all()).extracting(FeedMappings.Mapping::docKind).contains("customer", "deposit_account",
                "payment", "card", "loan", "loan_application", "wallet", "merchant", "agent", "aml_case", "fraud_alert",
                "card_dispute", "service_case", "complaint", "wallet_transaction", "document", "ledger_account",
                "customer_contact");
        // loans follow every status transition (arrears, cure, write-off)
        FeedMappings.Mapping loan = MAPPINGS.resolve("lending.loan.status_changed").orElseThrow();
        assertThat(loan.docKind()).isEqualTo("loan");
        assertThat(loan.operation("lending.loan.status_changed", json.readTree("{\"status\":\"IN_ARREARS\"}")))
                .isEqualTo(FeedMappings.Operation.UPSERT);
        assertThat(loan.operation("lending.loan.status_changed", json.readTree("{\"status\":\"WRITTEN_OFF\"}")))
                .isEqualTo(FeedMappings.Operation.CLOSE);
        assertThat(MAPPINGS.resolve("party.customer.created")).get().extracting(FeedMappings.Mapping::entityType)
                .isEqualTo("CUSTOMER");
        assertThat(MAPPINGS.resolve("treasury.alm_gap_run.completed")).isEmpty();
        assertThat(MAPPINGS.resolve("risk.aml_case.alert_linked")).isEmpty(); // payload is not the case
    }

    @Test
    void cardDocumentsCarryLast4OnlyNeverThePan() throws Exception {
        FeedMappings.Mapping card = MAPPINGS.resolve("cards.card.created").orElseThrow();
        JsonNode payload = json.readTree("""
                {"id":"c-1","customer_id":"u-1","pan":"4111111111111111","pan_token":"tok_abc","pan_fingerprint":"fp",
                 "cvv":"123","last4":"1111","cardholder_name":"Jane Doe","nickname":"travel 4111 1111 1111 1111",
                 "status":"ACTIVE","form_factor":"PHYSICAL","expiry_month":12,"expiry_year":2030}""");
        ObjectNode doc = card.document("cards.card.created", payload, card.operation("cards.card.created", payload));
        String s = doc.toString();
        assertThat(s).doesNotContain("4111111111111111", "4111 1111 1111 1111", "tok_abc", "fp\"", "123\"", "cvv", "pan");
        assertThat(doc.get("last4").asString()).isEqualTo("1111");
        assertThat(doc.get("title").asString()).isEqualTo("Jane Doe •••• 1111");
        assertThat(doc.get("keywords").toString()).contains("travel **** 1111");
        assertThat(doc.get("lifecycle").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void customerDocumentsKeepNamesAndReferencesOnly() throws Exception {
        FeedMappings.Mapping m = MAPPINGS.resolve("party.customer.updated").orElseThrow();
        JsonNode payload = json.readTree("""
                {"id":"u-1","customer_number":"CUS-1","first_name":"Amina","last_name":"Odhiambo","display_name":null,
                 "date_of_birth":"1990-01-01","annual_income":"90000","source_of_funds":"salary","status":"ACTIVE",
                 "customer_type":"INDIVIDUAL","is_pep":true}""");
        ObjectNode doc = m.document("party.customer.updated", payload, FeedMappings.Operation.UPSERT);
        assertThat(doc.get("title").asString()).isEqualTo("Amina Odhiambo");
        assertThat(doc.get("reference").asString()).isEqualTo("CUS-1");
        assertThat(doc.get("subtitle").asString()).isEqualTo("CUS-1 · INDIVIDUAL · ACTIVE");
        assertThat(doc.toString()).doesNotContain("1990-01-01", "90000", "salary", "is_pep");
    }

    @Test
    void contactDocumentsHoldTheMaskedValueAndLookupHashOnly() throws Exception {
        FeedMappings.Mapping m = MAPPINGS.resolve("party.customer_contact.verified").orElseThrow();
        assertThat(m.entityType()).isEqualTo("CUSTOMER_CONTACT");
        // even if a producer leaked the raw contact, the allow-list keeps it out of the document
        JsonNode payload = json.readTree("""
                {"id":"k-1","customer_id":"u-1","contact_type":"EMAIL","email":"amina@example.com","phone":"+254712345678",
                 "label":"Work","masked_value":"a***a@example.com","lookup_hash":"abc123","is_primary":true}""");
        ObjectNode doc = m.document("party.customer_contact.verified", payload, m.operation("party.customer_contact.verified", payload));
        assertThat(doc.toString()).doesNotContain("amina@example.com", "254712345678", "Work");
        assertThat(doc.get("title").asString()).isEqualTo("a***a@example.com");
        assertThat(doc.get("identifiers").toString()).isEqualTo("[\"abc123\"]");
        assertThat(doc.get("customer_id").asString()).isEqualTo("u-1");
        assertThat(m.operation("party.customer_contact.removed", payload)).isEqualTo(FeedMappings.Operation.DELETE);
    }

    @Test
    void lifecycleFollowsEventsStatusesAndErasure() throws Exception {
        FeedMappings.Mapping customer = MAPPINGS.resolve("party.customer.status_changed").orElseThrow();
        assertThat(customer.operation("party.customer.status_changed", json.readTree("{\"status\":\"ACTIVE\"}")))
                .isEqualTo(FeedMappings.Operation.UPSERT);
        assertThat(customer.operation("party.customer.status_changed", json.readTree("{\"status\":\"CLOSED\"}")))
                .isEqualTo(FeedMappings.Operation.CLOSE);
        assertThat(customer.operation("party.customer.merged", json.readTree("{\"status\":\"ACTIVE\"}")))
                .isEqualTo(FeedMappings.Operation.CLOSE);
        assertThat(customer.operation("party.customer.status_changed",
                json.readTree("{\"status\":\"CLOSED\",\"erased_at\":\"2026-10-01T00:00:00Z\"}")))
                .isEqualTo(FeedMappings.Operation.DELETE);
        FeedMappings.Mapping document = MAPPINGS.resolve("platform.document.deleted").orElseThrow();
        assertThat(document.operation("platform.document.deleted", json.readTree("{\"status\":\"AVAILABLE\"}")))
                .isEqualTo(FeedMappings.Operation.DELETE);
    }

    @Test
    void forbiddenFieldsAndSarEventsAreRefused() {
        assertThatThrownBy(() -> FeedMappings.from(yaml("""
                {"doc_kind":"card","entity_type":"CARD","topic":"meridian.cards.v1","events":["cards.card.created"],
                 "title":["{cardholder_name}"],"keywords":["pan"]}""")))
                .hasMessageContaining("pan must never be indexed");
        assertThatThrownBy(() -> FeedMappings.from(yaml("""
                {"doc_kind":"aml","entity_type":"AML_CASE","topic":"meridian.risk.v1","events":["risk.aml_case.opened"],
                 "title":["{title}"],"attributes":["description"]}""")))
                .hasMessageContaining("description must never be indexed");
        assertThatThrownBy(() -> FeedMappings.from(yaml("""
                {"doc_kind":"sar","entity_type":"AML_CASE","topic":"meridian.risk.v1","events":["risk.sar_report.filed"],
                 "title":["{id}"]}""")))
                .hasMessageContaining("must never be indexed");
        assertThatThrownBy(() -> FeedMappings.from(yaml("""
                {"doc_kind":"x","entity_type":"SPACESHIP","topic":"meridian.risk.v1","events":["a.b.c"],"title":["{id}"]}""")))
                .hasMessageContaining("Unknown entity type");
    }

    @Test
    void prefixMappingsHonourExclusions() {
        FeedMappings m = FeedMappings.from(yaml("""
                {"doc_kind":"aml_case","entity_type":"AML_CASE","topic":"meridian.risk.v1","prefix":"risk.aml_case.",
                 "exclude":["risk.aml_case.alert_linked"],"title":["{title}"]}"""));
        assertThat(m.resolve("risk.aml_case.opened")).isPresent();
        assertThat(m.resolve("risk.aml_case.alert_linked")).isEmpty();
    }

    @Test
    void redactsLuhnValidCardNumbersOnly() {
        assertThat(FeedMappings.redact("pay 4111-1111-1111-1111 now")).isEqualTo("pay **** 1111 now");
        assertThat(FeedMappings.redact("ref 1234567890123")).isEqualTo("ref 1234567890123"); // not Luhn-valid
        assertThat(FeedMappings.render(json.readTree("{\"a\":null}"), "Card {a}")).isEmpty();
    }

    private Map<String, Object> yaml(String mapping) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", Map.of("searchable_fields", List.of("title^3")));
        m.put("never_index", List.of("risk.sar_"));
        Object parsed = new Yaml().load(mapping);
        m.put("mappings", List.of(parsed));
        return m;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("generated/asyncapi"))) {
            p = p.getParent();
        }
        assertThat(p).as("repository root with generated/asyncapi").isNotNull();
        return p;
    }
}
