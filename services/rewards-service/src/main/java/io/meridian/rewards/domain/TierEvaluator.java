package io.meridian.rewards.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tier qualification with soft landing. A tier qualifies when the spend in the tier period reaches its
 * {@code qualify_spend} or the points earned reach its {@code qualify_points}; a tier without thresholds (the base tier)
 * always qualifies. The highest qualifying rank wins. Upgrades apply immediately and are kept for one period;
 * requalifying at the same rank extends the period; a member who no longer qualifies keeps the tier until
 * {@code tier_qualified_until} has passed and then lands on the highest tier they do qualify for.
 */
public final class TierEvaluator {
    private TierEvaluator() {}

    public record Tier(UUID id, int rank, BigDecimal qualifySpend, Long qualifyPoints) {}

    public record Decision(UUID tierId, LocalDate qualifiedUntil, boolean changed) {}

    public static boolean qualifies(Tier t, BigDecimal spend, long points) {
        if (t.qualifySpend() == null && t.qualifyPoints() == null) {
            return true;
        }
        return (t.qualifySpend() != null && spend.compareTo(t.qualifySpend()) >= 0)
                || (t.qualifyPoints() != null && points >= t.qualifyPoints());
    }

    public static Decision evaluate(List<Tier> tiers, BigDecimal spend, long points, UUID currentTierId,
                                    LocalDate qualifiedUntil, LocalDate asOf, int periodMonths) {
        if (tiers.isEmpty()) {
            return new Decision(currentTierId, qualifiedUntil, false);
        }
        Tier best = tiers.stream().filter(t -> qualifies(t, spend, points))
                .max(Comparator.comparingInt(Tier::rank)).orElse(null);
        Tier current = currentTierId == null ? null
                : tiers.stream().filter(t -> t.id().equals(currentTierId)).findFirst().orElse(null);
        LocalDate newUntil = asOf.plusMonths(periodMonths);
        if (best == null) {
            if (current != null && qualifiedUntil != null && asOf.isBefore(qualifiedUntil)) {
                return new Decision(current.id(), qualifiedUntil, false);
            }
            return new Decision(null, null, current != null);
        }
        if (current == null || best.rank() > current.rank()) {
            return new Decision(best.id(), newUntil, true);
        }
        if (best.rank() == current.rank()) {
            return new Decision(current.id(), newUntil, false);
        }
        if (qualifiedUntil != null && asOf.isBefore(qualifiedUntil)) {
            return new Decision(current.id(), qualifiedUntil, false);
        }
        return new Decision(best.id(), newUntil, !Objects.equals(best.id(), current.id()));
    }
}
