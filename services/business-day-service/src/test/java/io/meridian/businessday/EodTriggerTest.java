package io.meridian.businessday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.businessday.domain.BusinessDayState;
import io.meridian.businessday.domain.EodTrigger;
import io.meridian.businessday.domain.EodTrigger.Decision;
import io.meridian.businessday.domain.JobGraph;
import io.meridian.common.error.ApiException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure rules of the automatic EOD trigger and of plan graphs restricted through inactive jobs. */
class EodTriggerTest {
    static final EodTrigger.Policy ON = new EodTrigger.Policy(true, LocalTime.of(22, 0), ZoneId.of("Africa/Nairobi"),
            false, "TEST");
    static final EodTrigger.Policy ROLL = new EodTrigger.Policy(true, LocalTime.of(22, 0), ZoneId.of("UTC"), true, "TEST");

    @Test
    void dueAtIsTheLocalCutOffOfTheBusinessDate() {
        OffsetDateTime due = EodTrigger.dueAt(LocalDate.parse("2026-10-06"), LocalTime.of(22, 0), ZoneId.of("Africa/Nairobi"));
        assertThat(due.toInstant().toString()).isEqualTo("2026-10-06T19:00:00Z");
        OffsetDateTime ny = EodTrigger.dueAt(LocalDate.parse("2026-03-09"), LocalTime.of(18, 30), ZoneId.of("America/New_York"));
        assertThat(ny.toInstant().toString()).isEqualTo("2026-03-09T22:30:00Z"); // EDT
    }

    @Test
    void decisions() {
        assertThat(EodTrigger.decide(ON, BusinessDayState.OPEN, null, true, true)).isEqualTo(Decision.START);
        assertThat(EodTrigger.decide(ON, BusinessDayState.CUT_OFF, null, true, true)).isEqualTo(Decision.START);
        assertThat(EodTrigger.decide(ON, BusinessDayState.OPEN, null, true, false)).isEqualTo(Decision.NOT_DUE);
        assertThat(EodTrigger.decide(ON, BusinessDayState.OPEN, null, false, true)).isEqualTo(Decision.NO_JOBS);
        EodTrigger.Policy off = new EodTrigger.Policy(false, LocalTime.NOON, ZoneId.of("UTC"), false, "TEST");
        assertThat(EodTrigger.decide(off, BusinessDayState.OPEN, null, true, true)).isEqualTo(Decision.DISABLED);
        // idempotent per date: any run (running, failed, partial, succeeded) means nothing to start
        for (String s : List.of("RUNNING", "FAILED", "PARTIALLY_SUCCEEDED", "SUCCEEDED", "CANCELLED")) {
            assertThat(EodTrigger.decide(ON, BusinessDayState.EOD_RUNNING, s, true, true)).isEqualTo(Decision.ALREADY_RAN);
        }
        assertThat(EodTrigger.decide(ROLL, BusinessDayState.EOD_RUNNING, "SUCCEEDED", true, true)).isEqualTo(Decision.ROLL);
        assertThat(EodTrigger.decide(ROLL, BusinessDayState.EOD_RUNNING, "PARTIALLY_SUCCEEDED", true, true))
                .isEqualTo(Decision.ALREADY_RAN); // a forced roll needs four-eyes: never automatic
        assertThat(EodTrigger.decide(ROLL, BusinessDayState.EOD_FAILED, "FAILED", true, true)).isEqualTo(Decision.ALREADY_RAN);
    }

    @Test
    void parsing() {
        assertThat(EodTrigger.parseCutOff("07:05")).isEqualTo(LocalTime.of(7, 5));
        assertThatThrownBy(() -> EodTrigger.parseCutOff("24:00")).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "invalid_cut_off_time");
        assertThatThrownBy(() -> EodTrigger.parseCutOff("7pm")).isInstanceOf(ApiException.class);
        assertThat(EodTrigger.parseZone("Europe/London").getId()).isEqualTo("Europe/London");
        assertThatThrownBy(() -> EodTrigger.parseZone("Mars/Olympus")).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "invalid_time_zone");
    }

    @Test
    void restrictedGraphKeepsOrderingThroughJobsOutsideThePlan() {
        JobGraph full = JobGraph.of(List.of(
                new JobGraph.Job("A", List.of(), 10, true),
                new JobGraph.Job("B", List.of("A"), 20, true),     // inactive / other cycle
                new JobGraph.Job("C", List.of("B"), 30, true),
                new JobGraph.Job("D", List.of(), 10, true)));
        JobGraph plan = full.restrictTo(Set.of("A", "C", "D"));
        assertThat(plan.jobs().get("C").dependsOn()).containsExactly("A");
        assertThat(plan.levels()).containsExactly(List.of("A", "D"), List.of("C"));
    }
}
