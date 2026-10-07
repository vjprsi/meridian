package io.meridian.deposit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Interest-day rules found by the live EOD journey (e2e G): value dates and UTC dates, never the session offset. */
class InterestDatesTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void ledgerEntriesCountFromTheirValueDate() {
        // Posted late on 6 Oct UTC for business date 7 Oct (legal entity in UTC+3): interest counts from 7 Oct.
        assertThat(InterestService.bucketDate(json.readTree("""
                {"effective_at":"2026-10-06T22:30:00Z","value_date":"2026-10-07","amount":"25000","direction":"CREDIT"}""")))
                .isEqualTo(LocalDate.of(2026, 10, 7));
        // Without a value date: the UTC date of the effective time, whatever offset the row was serialised in.
        assertThat(InterestService.bucketDate(json.readTree("""
                {"effective_at":"2026-10-06T19:41:00-05:00","amount":"1","direction":"CREDIT"}""")))
                .isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    void openedDateIsTheUtcDateNotTheDatabaseSessionDate() {
        // row_to_json renders timestamptz in the session time zone (JVM default, e.g. America/Chicago): an account
        // opened at 00:41Z on 7 Oct reads back as 19:41-05:00 on 6 Oct and used to accrue a day before it existed.
        assertThat(InterestService.utcDate(json.readTree("{\"opened_at\":\"2026-10-06T19:41:00-05:00\"}"), "opened_at"))
                .isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(InterestService.utcDate(json.readTree("{\"opened_at\":null}"), "opened_at")).isNull();
    }
}
