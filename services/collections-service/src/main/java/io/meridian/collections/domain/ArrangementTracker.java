package io.meridian.collections.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks promises to pay and payment plans against the repayments the servicing system received. Only POSTED
 * repayments count, and neither a reversal row nor a repayment that was later reversed.
 */
public final class ArrangementTracker {
    private ArrangementTracker() {}

    /** Days after the promised / instalment date during which a payment still counts. */
    public static final int GRACE_DAYS = 1;
    /** A promise paid at least this share is PARTIALLY_KEPT rather than BROKEN. */
    public static final BigDecimal PARTIAL_THRESHOLD = new BigDecimal("0.5");

    public record Payment(UUID id, BigDecimal amount, LocalDate valueDate, String status, UUID reversalOfId) {}

    /** Sum of effective payments with value date in [from, to]. */
    public static BigDecimal paid(List<Payment> payments, LocalDate from, LocalDate to) {
        Set<UUID> reversed = new HashSet<>();
        for (Payment p : payments) {
            if (p.reversalOfId() != null) {
                reversed.add(p.reversalOfId());
            }
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Payment p : payments) {
            if (!"POSTED".equals(p.status()) || p.reversalOfId() != null || reversed.contains(p.id())
                    || p.valueDate() == null || p.valueDate().isBefore(from) || (to != null && p.valueDate().isAfter(to))) {
                continue;
            }
            sum = sum.add(p.amount());
        }
        return sum;
    }

    /**
     * Outcome of a promise on {@code businessDate}: KEPT as soon as the promised amount is paid; after the promised
     * date plus grace, PARTIALLY_KEPT (at least half paid) or BROKEN; empty while still pending.
     */
    public static Optional<String> promiseOutcome(BigDecimal promised, BigDecimal paid, LocalDate promisedDate,
                                                  LocalDate businessDate) {
        if (paid.compareTo(promised) >= 0) {
            return Optional.of("KEPT");
        }
        if (businessDate.isBefore(promisedDate.plusDays(GRACE_DAYS))) {
            return Optional.empty();
        }
        return Optional.of(paid.compareTo(promised.multiply(PARTIAL_THRESHOLD)) >= 0 ? "PARTIALLY_KEPT" : "BROKEN");
    }

    public record PlanInstalment(int no, LocalDate dueDate, BigDecimal amount, BigDecimal paid) {}

    public record PlanState(String status, List<PlanInstalment> instalments, BigDecimal paid) {}

    /**
     * Allocates {@code paid} to the plan instalments in order; the plan is COMPLETED once the total is paid and
     * BROKEN when an instalment past its date plus grace is not covered by the cumulative payments.
     */
    public static PlanState plan(List<PlanInstalment> instalments, BigDecimal paid, LocalDate businessDate) {
        List<PlanInstalment> out = new ArrayList<>();
        BigDecimal remaining = paid;
        BigDecimal total = BigDecimal.ZERO;
        boolean broken = false;
        for (PlanInstalment i : instalments) {
            total = total.add(i.amount());
            BigDecimal alloc = remaining.min(i.amount()).max(BigDecimal.ZERO);
            remaining = remaining.subtract(alloc);
            out.add(new PlanInstalment(i.no(), i.dueDate(), i.amount(), alloc));
            if (alloc.compareTo(i.amount()) < 0 && !businessDate.isBefore(i.dueDate().plusDays(GRACE_DAYS))) {
                broken = true;
            }
        }
        String status = paid.compareTo(total) >= 0 ? "COMPLETED" : broken ? "BROKEN" : "ACTIVE";
        return new PlanState(status, out, paid);
    }
}
