package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BCBS 248 intraday monitoring: the net cumulative position (received - sent) after each settled movement in time
 * order. The largest negative net cumulative position is the intraday liquidity used; throughput is the share of
 * the day's expected payments already sent.
 */
public final class IntradayCalculator {
    public record Movement(OffsetDateTime at, boolean sent, BigDecimal amount) {}

    public record Point(OffsetDateTime at, BigDecimal netCumulative) {}

    public record Result(BigDecimal sent, BigDecimal received, BigDecimal largestNegative, BigDecimal largestPositive,
                         BigDecimal usedLiquidity, BigDecimal throughput, List<Point> profile) {}

    private IntradayCalculator() {}

    public static Result calculate(List<Movement> movements, OffsetDateTime snapshotAt, BigDecimal expectedDailyPayments) {
        List<Movement> sorted = new ArrayList<>(movements);
        sorted.sort(Comparator.comparing(Movement::at));
        BigDecimal sent = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal min = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        List<Point> profile = new ArrayList<>();
        for (Movement m : sorted) {
            if (m.amount() == null || m.amount().signum() <= 0) {
                throw new DomainException("invalid_movement", "movement amounts must be positive");
            }
            if (m.at().isAfter(snapshotAt)) {
                throw new DomainException("movement_after_snapshot",
                        "movement at " + m.at() + " is after snapshot_at " + snapshotAt);
            }
            if (m.sent()) {
                sent = sent.add(m.amount());
                net = net.subtract(m.amount());
            } else {
                received = received.add(m.amount());
                net = net.add(m.amount());
            }
            min = min.min(net);
            max = max.max(net);
            profile.add(new Point(m.at(), Amounts.money(net)));
        }
        BigDecimal throughput = null;
        if (expectedDailyPayments != null) {
            if (expectedDailyPayments.signum() <= 0) {
                throw new DomainException("invalid_expected_daily_payments", "expected_daily_payments must be positive");
            }
            throughput = Amounts.ratio(sent, expectedDailyPayments);
        }
        BigDecimal largestNegative = Amounts.money(min.negate());
        return new Result(Amounts.money(sent), Amounts.money(received), largestNegative, Amounts.money(max),
                largestNegative, throughput, profile);
    }
}
