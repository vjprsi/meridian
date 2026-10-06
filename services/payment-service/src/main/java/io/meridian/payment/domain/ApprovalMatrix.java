package io.meridian.payment.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Business approval matrix: each level applies from a minimum amount and needs a number of distinct approvers
 * (never the initiator). A payment needs every level whose threshold it reaches, approved in level order.
 */
public final class ApprovalMatrix {
    public record Level(BigDecimal minAmount, int approvals) {}

    /** Approvals required at one level of a concrete payment. */
    public record Step(int level, int requiredApprovals) {}

    private final List<Level> levels;

    public ApprovalMatrix(List<Level> levels) {
        this.levels = levels.stream().filter(l -> l.approvals() > 0)
                .sorted(Comparator.comparing(Level::minAmount)).toList();
    }

    /**
     * @param amount        payment amount
     * @param minimumLevels levels forced regardless of amount (e.g. a limits REVIEW decision or a bulk payment)
     */
    public List<Step> stepsFor(BigDecimal amount, int minimumLevels) {
        List<Step> steps = new ArrayList<>();
        int n = 0;
        for (Level l : levels) {
            if (amount.compareTo(l.minAmount()) >= 0) {
                steps.add(new Step(++n, l.approvals()));
            }
        }
        while (steps.size() < minimumLevels) {
            steps.add(new Step(steps.size() + 1, 1));
        }
        return steps;
    }
}
