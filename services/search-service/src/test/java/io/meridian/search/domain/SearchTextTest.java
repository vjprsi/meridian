package io.meridian.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchTextTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void parsesBoostedFieldsFromAnArray() {
        assertThat(SearchText.fields(JSON.readTree("[\"full_name^3\",\"email\"]")))
                .containsExactly(new SearchText.Field("full_name", 3), new SearchText.Field("email", 1));
        // searchable_fields is a JSON array in the contract; other shapes carry no fields
        assertThat(SearchText.fields(JSON.readTree("{\"fields\":[\"a^2\"]}"))).isEmpty();
    }

    @Test
    void weightsTextByBoostAndAddsDigitVariants() {
        JsonNode doc = JSON.readTree("""
                {"full_name":"Jane Doe","phone":"+254 700 123 456","address":{"city":"Nairobi"},"tags":["vip","retail"]}""");
        List<SearchText.Field> fields = SearchText.fields(JSON.readTree("[\"full_name^3\",\"address.city^2\",\"phone\",\"tags\"]"));
        String[] w = SearchText.weightedText(doc, fields);
        assertThat(w[0]).isEqualTo("Jane Doe");
        assertThat(w[1]).isEqualTo("Nairobi");
        assertThat(w[2]).contains("+254 700 123 456").contains("254700123456").contains("vip retail");
        assertThat(SearchText.title(doc, fields)).isEqualTo("Jane Doe");
    }

    @Test
    void buildsTsqueryWithSynonymsAndPrefix() {
        assertThat(SearchText.tsquery("Jane", List.of())).isEqualTo("(jane | jane:*)");
        assertThat(SearchText.tsquery("mohamed ali", List.of(Set.of("mohamed", "mohammed", "muhammad"))))
                .startsWith("(mohamed | ").contains("mohammed").contains("muhammad").endsWith("& (ali | ali:*)");
        assertThat(SearchText.tsquery("  ' & | ! ", List.of())).isNull(); // operators are never passed through
        assertThat(SearchText.tsquery("o'brien", List.of())).isEqualTo("o & (brien | brien:*)");
    }

    @Test
    void masksKeepingAHint() {
        assertThat(SearchText.mask("jane.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(SearchText.mask("+254700123456")).isEqualTo("********3456");
        assertThat(SearchText.mask("123")).isEqualTo("****");
    }

    @Test
    void indexRules() {
        assertThat(IndexRules.physicalName("customers", 2)).startsWith("customers_v2_");
        assertThat(IndexRules.entityType("customer")).isEqualTo("CUSTOMER");
        assertThat(IndexRules.searchable("REINDEXING")).isTrue();
        assertThat(IndexRules.searchable("RETIRED")).isFalse();
    }
}
