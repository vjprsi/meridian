package io.meridian.businessday.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JobResultsTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void explicitCountersWin() {
        var res = json.readTree("{\"records_processed\":7,\"records_failed\":1,\"accounts_accrued\":99}");
        assertThat(JobResults.processed(res)).isEqualTo(7L);
        assertThat(JobResults.failed(res)).isEqualTo(1L);
    }

    @Test
    void countersAreInferredByNameAndAmountsIgnored() {
        var res = json.readTree("""
                {"business_date":"2026-10-06","accounts_accrued":12,"holds_expired":3,"posting_count":15,
                 "total_amount":"1234.56","interest_amount":42,"duration_ms":800,"postings_failed":2}""");
        assertThat(JobResults.processed(res)).isEqualTo(30L);
        assertThat(JobResults.failed(res)).isEqualTo(2L);
    }

    @Test
    void zeroWorkIsVisibleAndNoCountersIsNull() {
        assertThat(JobResults.processed(json.readTree("{\"accounts_accrued\":0}"))).isZero();
        assertThat(JobResults.processed(json.readTree("{\"status\":\"OK\"}"))).isNull();
        assertThat(JobResults.processed(null)).isNull();
    }

    @Test
    void summaryIsBounded() {
        String big = "x".repeat(JobResults.MAX_SUMMARY_CHARS + 10);
        var res = json.createObjectNode().put("note", big).put("matched", 4);
        String s = JobResults.summary(res, json);
        assertThat(s.length()).isLessThan(JobResults.MAX_SUMMARY_CHARS);
        assertThat(json.readTree(s).get("truncated").asBoolean()).isTrue();
        assertThat(json.readTree(s).get("matched").asInt()).isEqualTo(4);
        assertThat(JobResults.summary(json.readTree("[1,2]"), json)).isEqualTo("{\"value\":[1,2]}");
    }
}
