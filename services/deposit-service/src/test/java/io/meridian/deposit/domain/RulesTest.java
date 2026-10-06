package io.meridian.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.domain.MandateEvaluator.Holder;
import io.meridian.deposit.domain.MandateEvaluator.Mandate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import org.junit.jupiter.api.Test;

/** Mandates, sweeps, savings rules, account restrictions and state machines. */
class RulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    static final UUID ANN = UUID.randomUUID();
    static final UUID BOB = UUID.randomUUID();
    static final UUID KID = UUID.randomUUID();
    static final UUID GUARD = UUID.randomUUID();
    static final UUID CFO = UUID.randomUUID();

    static Holder holder(UUID id, String role, String group, String limit) {
        return new Holder(id, role, "ACTIVE", true, limit == null ? null : new BigDecimal(limit), group, null, null);
    }

    @Test
    void jointAnyAndAllRules() {
        List<Holder> joint = List.of(holder(ANN, "PRIMARY", null, null), holder(BOB, "JOINT", null, null));
        var any = MandateEvaluator.evaluate(new Mandate("ANY_ONE", 1, List.of()), joint, Set.of(BOB), null, TODAY);
        assertThat(any.allowed()).isTrue();
        var all = MandateEvaluator.evaluate(new Mandate("ALL", 1, List.of()), joint, Set.of(BOB), null, TODAY);
        assertThat(all.allowed()).isFalse();
        assertThat(all.requiredApprovals()).isEqualTo(2);
        assertThat(MandateEvaluator.evaluate(new Mandate("ALL", 1, List.of()), joint, Set.of(ANN, BOB), null, TODAY).allowed())
                .isTrue();
        assertThat(MandateEvaluator.evaluate(new Mandate("PRIMARY_ONLY", 1, List.of()), joint, Set.of(BOB), null, TODAY)
                .allowed()).isFalse();
        assertThat(MandateEvaluator.evaluate(new Mandate("PRIMARY_PLUS_ONE", 1, List.of()), joint, Set.of(ANN, BOB), null,
                TODAY).allowed()).isTrue();
    }

    @Test
    void minorNeedsGuardianAndLimitsAndStrangersDoNotCount() {
        List<Holder> minor = List.of(holder(KID, "MINOR", null, null), holder(GUARD, "GUARDIAN", null, null));
        var kidAlone = MandateEvaluator.evaluate(new Mandate("ANY_ONE", 1, List.of()), minor, Set.of(KID), null, TODAY);
        assertThat(kidAlone.allowed()).isFalse();
        assertThat(kidAlone.ignoredSignatories()).containsExactly(KID);
        assertThat(MandateEvaluator.evaluate(new Mandate("ANY_ONE", 1, List.of()), minor, Set.of(GUARD), null, TODAY)
                .allowed()).isTrue();

        List<Holder> limited = List.of(holder(ANN, "PRIMARY", null, null), holder(BOB, "AUTHORISED_USER", null, "500"));
        assertThat(MandateEvaluator.evaluate(new Mandate("ANY_ONE", 1, List.of()), limited, Set.of(BOB),
                new BigDecimal("499"), TODAY).allowed()).isTrue();
        assertThat(MandateEvaluator.evaluate(new Mandate("ANY_ONE", 1, List.of()), limited, Set.of(BOB),
                new BigDecimal("501"), TODAY).allowed()).isFalse();
        assertThat(MandateEvaluator.evaluate(new Mandate("ANY_ONE", 1, List.of()), limited, Set.of(UUID.randomUUID()),
                null, TODAY).allowed()).isFalse();
    }

    @Test
    void customSigningGroups() {
        List<Holder> company = List.of(holder(CFO, "SIGNATORY", "A", null), holder(ANN, "SIGNATORY", "B", null),
                holder(BOB, "SIGNATORY", "B", null));
        Mandate m = new Mandate("CUSTOM", 1, List.of(List.of("A"), List.of("B", "B")));
        assertThat(MandateEvaluator.evaluate(m, company, Set.of(CFO), null, TODAY).allowed()).isTrue();
        assertThat(MandateEvaluator.evaluate(m, company, Set.of(ANN), null, TODAY).allowed()).isFalse();
        assertThat(MandateEvaluator.evaluate(m, company, Set.of(ANN, BOB), null, TODAY).allowed()).isTrue();
        Mandate two = new Mandate("CUSTOM", 2, List.of(List.of("A", "B")));
        assertThat(MandateEvaluator.evaluate(two, company, Set.of(CFO, BOB), null, TODAY).allowed()).isTrue();
        assertThat(MandateEvaluator.evaluate(two, company, Set.of(ANN, BOB), null, TODAY).allowed()).isFalse();
    }

    @Test
    void sweepDecisions() {
        var out = SweepCalculator.decide("TARGET_BALANCE", new BigDecimal("1500"), new BigDecimal("0"), null,
                new BigDecimal("1000"), BigDecimal.ZERO);
        assertThat(out.direction()).isEqualTo(SweepCalculator.Direction.TO_TARGET);
        assertThat(out.amount()).isEqualByComparingTo("500");
        var in = SweepCalculator.decide("THRESHOLD_IN", new BigDecimal("50"), new BigDecimal("80"), new BigDecimal("100"),
                new BigDecimal("300"), null);
        assertThat(in.direction()).isEqualTo(SweepCalculator.Direction.FROM_TARGET);
        assertThat(in.amount()).isEqualByComparingTo("80"); // capped by what the target holds
        var small = SweepCalculator.decide("ZERO_BALANCE", new BigDecimal("5"), BigDecimal.ZERO, null, null, new BigDecimal("10"));
        assertThat(small.direction()).isEqualTo(SweepCalculator.Direction.NONE);
        assertThatThrownBy(() -> SweepCalculator.decide("THRESHOLD_OUT", BigDecimal.ONE, BigDecimal.ONE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** After a TARGET_BALANCE sweep the source holds the target balance unless the target could not cover it. */
    @Property(tries = 300)
    void targetBalanceSweepConverges(@ForAll @BigRange(min = "-1000", max = "100000") BigDecimal source,
                                     @ForAll @BigRange(min = "0", max = "100000") BigDecimal target,
                                     @ForAll @BigRange(min = "0", max = "50000") BigDecimal keep) {
        var d = SweepCalculator.decide("TARGET_BALANCE", source, target, null, keep, BigDecimal.ZERO);
        BigDecimal after = switch (d.direction()) {
            case TO_TARGET -> source.subtract(d.amount());
            case FROM_TARGET -> source.add(d.amount());
            case NONE -> source;
        };
        if (source.compareTo(keep) >= 0 || target.compareTo(keep.subtract(source)) >= 0) {
            assertThat(after).isEqualByComparingTo(keep);
        } else {
            assertThat(d.amount()).isLessThanOrEqualTo(target);
        }
    }

    @Test
    void savingsRoundUps() {
        assertThat(SavingsRules.roundUp(new BigDecimal("3.40"), 1, "EUR")).isEqualByComparingTo("0.60");
        assertThat(SavingsRules.roundUp(new BigDecimal("3.40"), 2, "EUR")).isEqualByComparingTo("1.20");
        assertThat(SavingsRules.roundUp(new BigDecimal("7.00"), 3, "EUR")).isZero();
        assertThat(SavingsRules.percentOf(new BigDecimal("2500.55"), new BigDecimal("0.10"), "EUR")).isEqualByComparingTo("250.05");
    }

    @Test
    void restrictionsAndLifecycle() {
        assertThat(AccountRestrictions.blockedStatus(List.of("DEBIT", "CREDIT"))).isEqualTo("BLOCKED");
        assertThat(AccountRestrictions.statusFor("ACTIVE", List.of("DEBIT"))).isEqualTo("BLOCKED_DEBIT");
        assertThat(AccountRestrictions.statusFor("FROZEN", List.of("DEBIT"))).isEqualTo("FROZEN");
        assertThat(AccountRestrictions.ledgerStatusFor("BLOCKED_CREDIT")).isEqualTo("FROZEN_CREDIT");
        assertThat(Lifecycle.DEPOSIT_ACCOUNT.allows("PENDING_APPROVAL", "ACTIVE")).isFalse();
        assertThat(Lifecycle.DEPOSIT_ACCOUNT.isTerminal("CLOSED")).isTrue();
        assertThatThrownBy(() -> Lifecycle.TERM_DEPOSIT.require("BROKEN", "ACTIVE"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("invalid_transition");
    }
}
