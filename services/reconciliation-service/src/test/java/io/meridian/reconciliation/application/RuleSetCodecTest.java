package io.meridian.reconciliation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.reconciliation.domain.matching.MatchRule;
import io.meridian.reconciliation.domain.matching.RuleSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Stored rule set form: round trip of the standard template and validation of tenant configurations. */
class RuleSetCodecTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void standardTemplateRoundTrips() {
        ArrayNode encoded = RuleSetCodec.encode(RuleSet.standard().rules(), JSON);
        List<MatchRule> decoded = RuleSetCodec.rules(JSON.readTree(encoded.toString()));
        assertThat(decoded).isEqualTo(RuleSet.standard().rules());
        ObjectNode row = JSON.createObjectNode();
        row.set("rules", encoded);
        row.put("auto_match_threshold", 0.9);
        row.put("suggest_threshold", 0.5);
        RuleSetCodec.validate(row);
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThat(problem("[]")).contains("non-empty");
        assertThat(problem("[{\"code\":\"x1\",\"type\":\"NOPE\"}]")).contains("type must be one of");
        assertThat(problem("[{\"code\":\"Bad Code\",\"type\":\"FUZZY\"}]")).contains("code must match");
        assertThat(problem("[{\"code\":\"k1\",\"type\":\"EXACT_KEY\"}]")).contains("needs keys");
        assertThat(problem("[{\"code\":\"k1\",\"type\":\"EXACT_KEY\",\"keys\":[\"remittance\"]}]")).contains("not allowed");
        assertThat(problem("[{\"code\":\"r1\",\"type\":\"REFERENCE_EXTRACT\",\"patterns\":[\"(a+)+$\"]}]"))
                .contains("rejected");
        assertThat(problem("[{\"code\":\"f1\",\"type\":\"FUZZY\",\"date_window_days\":99}]")).contains("0..31");
        assertThat(problem("[{\"code\":\"f1\",\"type\":\"FUZZY\",\"fx_tolerance_pct\":\"50\"}]")).contains("at most");
        assertThat(problem("[{\"code\":\"f1\",\"type\":\"FUZZY\",\"fee_tolerance\":\"-1\"}]")).contains("negative");
        assertThat(problem("[{\"code\":\"b1\",\"type\":\"ONE_TO_MANY\",\"max_search_nodes\":999999999}]")).contains("1..");
        assertThat(problem("[{\"code\":\"aa\",\"type\":\"FUZZY\"},{\"code\":\"aa\",\"type\":\"FUZZY\"}]")).contains("twice");
        ObjectNode row = JSON.createObjectNode();
        row.set("rules", RuleSetCodec.encode(RuleSet.standard().rules(), JSON));
        row.put("auto_match_threshold", 0.95);
        row.put("suggest_threshold", 0.97);
        assertThatThrownBy(() -> RuleSetCodec.validate(row)).isInstanceOf(ApiException.class);
    }

    static String problem(String rules) {
        JsonNode arr = JSON.readTree(rules);
        try {
            RuleSetCodec.rules(arr);
            return "accepted";
        } catch (ApiException e) {
            return e.getMessage() + " " + e;
        }
    }
}
