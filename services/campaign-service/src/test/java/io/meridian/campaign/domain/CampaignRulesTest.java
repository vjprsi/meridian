package io.meridian.campaign.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignRulesTest {

    @Test
    void assignmentStateMachine() {
        StateMachine sm = Lifecycles.ASSIGNMENT;
        assertThat(sm.apply("shown", "ASSIGNED")).isEqualTo("SHOWN");
        assertThat(sm.apply("shown", "CLICKED")).isEqualTo("CLICKED");
        assertThat(sm.apply("clicked", "ASSIGNED")).isEqualTo("CLICKED");
        assertThat(sm.apply("clicked", "SHOWN")).isEqualTo("CLICKED");
        assertThat(sm.apply("accept", "CLICKED")).isEqualTo("ACCEPTED");
        assertThat(sm.apply("decline", "SHOWN")).isEqualTo("DECLINED");
        assertThat(sm.apply("expire", "ASSIGNED")).isEqualTo("EXPIRED");
        for (String terminal : List.of("ACCEPTED", "DECLINED", "EXPIRED", "WITHDRAWN")) {
            for (String action : List.of("shown", "clicked", "accept", "decline", "expire", "withdraw")) {
                assertThatThrownBy(() -> sm.apply(action, terminal)).isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_transition");
            }
        }
    }

    @Test
    void campaignStateMachine() {
        StateMachine sm = Lifecycles.CAMPAIGN;
        assertThat(sm.apply("submit", "DRAFT")).isEqualTo("PENDING_APPROVAL");
        assertThat(sm.apply("approve", "PENDING_APPROVAL")).isEqualTo("SCHEDULED");
        assertThat(sm.apply("reject", "PENDING_APPROVAL")).isEqualTo("DRAFT");
        assertThat(sm.apply("launch", "SCHEDULED")).isEqualTo("RUNNING");
        assertThat(sm.apply("pause", "RUNNING")).isEqualTo("PAUSED");
        assertThat(sm.apply("complete", "PAUSED")).isEqualTo("COMPLETED");
        assertThat(sm.allows("launch", "DRAFT")).isFalse();
        assertThat(sm.allows("build-audience", "RUNNING")).isFalse();
        assertThat(sm.allows("cancel", "COMPLETED")).isFalse();
    }

    @Test
    void upliftAndRates() {
        assertThat(CampaignMath.rate(1, 4)).isEqualByComparingTo("0.25");
        assertThat(CampaignMath.rate(1, 0)).isNull();
        // treated 30/100 = 0.3, control 20/100 = 0.2 -> +50 %
        assertThat(CampaignMath.uplift(30, 100, 20, 100)).isEqualByComparingTo("0.5");
        // treated below control -> negative uplift
        assertThat(CampaignMath.uplift(10, 100, 20, 100)).isEqualByComparingTo("-0.5");
        assertThat(CampaignMath.uplift(10, 100, 0, 100)).isNull();
        assertThat(CampaignMath.uplift(10, 100, 0, 0)).isNull();
    }

    @Test
    void sendWindow() {
        Instant tenUtc = Instant.parse("2026-10-06T10:00:00Z");
        assertThat(CampaignMath.inSendWindow(8, 20, "UTC", tenUtc)).isTrue();
        assertThat(CampaignMath.inSendWindow(11, 20, "UTC", tenUtc)).isFalse();
        assertThat(CampaignMath.inSendWindow(22, 6, "UTC", Instant.parse("2026-10-06T23:30:00Z"))).isTrue();
        assertThat(CampaignMath.inSendWindow(22, 6, "UTC", tenUtc)).isFalse();
        assertThat(CampaignMath.inSendWindow(8, 9, "America/New_York", Instant.parse("2026-10-06T12:30:00Z"))).isTrue();
        assertThat(CampaignMath.inSendWindow(null, 9, null, tenUtc)).isTrue();
    }

    @Test
    void eligibility() {
        UUID gold = UUID.randomUUID();
        UUID risky = UUID.randomUUID();
        assertThat(Eligibility.segments(Set.of(gold), List.of(gold), List.of())).isNull();
        assertThat(Eligibility.segments(Set.of(), List.of(gold), List.of())).isEqualTo("not_in_segment");
        assertThat(Eligibility.segments(Set.of(gold, risky), List.of(gold), List.of(risky))).isEqualTo("excluded_segment");
        Eligibility.Rules rules = new Eligibility.Rules(List.of(gold), List.of(), new BigDecimal("0.5"), new BigDecimal("0.9"));
        assertThat(Eligibility.offer(rules, Set.of(gold), new BigDecimal("0.6"))).isNull();
        assertThat(Eligibility.offer(rules, Set.of(gold), new BigDecimal("0.95"))).isEqualTo("score_out_of_range");
        assertThat(Eligibility.offer(rules, Set.of(gold), null)).isEqualTo("score_required");
        assertThat(Eligibility.offer(rules, Set.of(), new BigDecimal("0.6"))).isEqualTo("not_in_segment");
    }
}
