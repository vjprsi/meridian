package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Cash position arithmetic: closing balance, buffer shortfall and the day-by-day roll-forward of a projection. */
public final class CashPositions {
    private CashPositions() {}

    public record Position(LocalDate date, BigDecimal opening, BigDecimal inflows, BigDecimal outflows,
                           BigDecimal closing, BigDecimal minimumBuffer, BigDecimal shortfall,
                           Map<String, BigDecimal[]> byCategory) {}

    public static BigDecimal closing(BigDecimal opening, BigDecimal inflows, BigDecimal outflows) {
        return Amounts.money(opening.add(inflows).subtract(outflows));
    }

    /** Shortfall against the buffer, or null when the closing balance covers it (or there is no buffer). */
    public static BigDecimal shortfall(BigDecimal closing, BigDecimal minimumBuffer) {
        if (minimumBuffer == null) {
            return null;
        }
        BigDecimal s = minimumBuffer.subtract(closing);
        return s.signum() > 0 ? Amounts.money(s) : null;
    }

    /**
     * Rolls one currency forward from {@code opening} over {@code days} days starting {@code from}: each day's closing
     * becomes the next day's opening. Flows outside the window are ignored.
     */
    public static List<Position> project(LocalDate from, int days, BigDecimal opening, BigDecimal minimumBuffer,
                                         List<CashFlow> flows) {
        Map<LocalDate, List<CashFlow>> byDay = new TreeMap<>();
        for (CashFlow f : flows) {
            byDay.computeIfAbsent(f.valueDate(), k -> new ArrayList<>()).add(f);
        }
        List<Position> out = new ArrayList<>();
        BigDecimal open = Amounts.money(opening);
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            BigDecimal in = BigDecimal.ZERO;
            BigDecimal outflow = BigDecimal.ZERO;
            Map<String, BigDecimal[]> cat = new LinkedHashMap<>();
            for (CashFlow f : byDay.getOrDefault(d, List.of())) {
                BigDecimal[] cell = cat.computeIfAbsent(f.category(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                if (f.inflow()) {
                    in = in.add(f.amount());
                    cell[0] = cell[0].add(f.amount());
                } else {
                    outflow = outflow.add(f.amount());
                    cell[1] = cell[1].add(f.amount());
                }
            }
            in = Amounts.money(in);
            outflow = Amounts.money(outflow);
            BigDecimal close = closing(open, in, outflow);
            out.add(new Position(d, open, in, outflow, close, minimumBuffer, shortfall(close, minimumBuffer), cat));
            open = close;
        }
        return out;
    }
}
