package io.meridian.collateral.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Distributes the net proceeds of a collateral sale over the links it secures: by charge rank (first charge
 * first), then link creation order; each exposure receives at most its outstanding amount (several links to the
 * same exposure share that cap). What is left is the surplus returned to the owner.
 */
public final class LiquidationAllocator {
    private LiquidationAllocator() {}

    public record Claim(UUID linkId, UUID exposureId, int chargeRank, int order, BigDecimal exposureOutstanding) {}

    public record Allocation(UUID linkId, UUID exposureId, BigDecimal amount) {}

    public record Result(List<Allocation> allocations, BigDecimal surplus) {}

    public static Result allocate(BigDecimal netProceeds, List<Claim> claims) {
        if (netProceeds.signum() < 0) {
            throw new IllegalArgumentException("net proceeds must not be negative");
        }
        List<Claim> ordered = new ArrayList<>(claims);
        ordered.sort(Comparator.comparingInt(Claim::chargeRank).thenComparingInt(Claim::order));
        Map<UUID, BigDecimal> remainingPerExposure = new HashMap<>();
        for (Claim c : ordered) {
            remainingPerExposure.putIfAbsent(c.exposureId(),
                    c.exposureOutstanding() == null ? BigDecimal.ZERO : c.exposureOutstanding().max(BigDecimal.ZERO));
        }
        BigDecimal left = netProceeds;
        List<Allocation> out = new ArrayList<>();
        for (Claim c : ordered) {
            BigDecimal cap = remainingPerExposure.get(c.exposureId());
            BigDecimal amount = left.min(cap);
            if (amount.signum() > 0) {
                out.add(new Allocation(c.linkId(), c.exposureId(), amount));
                left = left.subtract(amount);
                remainingPerExposure.put(c.exposureId(), cap.subtract(amount));
            }
        }
        return new Result(out, left);
    }
}
