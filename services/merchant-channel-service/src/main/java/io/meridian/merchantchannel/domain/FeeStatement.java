package io.meridian.merchantchannel.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fee (merchant discount rate) statement of one merchant over a date range, built from its settlements: per settlement
 * the payments, gross collections, MDR fees, refunds and net, and the totals. Settlements count when their period ends
 * within the range; REJECTED settlements are left out (their payments are settled again).
 */
public final class FeeStatement {
    /** One settlement line. */
    public record Line(String settlementId, LocalDate periodStart, LocalDate periodEnd, String status, int payments,
                       BigDecimal gross, BigDecimal mdr, BigDecimal refunds, BigDecimal net) {}

    /** Totals of the statement. */
    public record Totals(int settlements, int payments, BigDecimal gross, BigDecimal mdr, BigDecimal refunds,
                         BigDecimal net) {
        /** Effective MDR rate (mdr / gross, 4 decimals), zero without collections. */
        public BigDecimal effectiveRate() {
            return gross.signum() == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_EVEN)
                    : mdr.divide(gross, 4, RoundingMode.HALF_EVEN);
        }
    }

    private FeeStatement() {}

    /** Whether a settlement belongs in the statement for [from, to] (both inclusive; null = open). */
    public static boolean inRange(LocalDate periodEnd, String status, LocalDate from, LocalDate to) {
        if (periodEnd == null || "REJECTED".equals(status)) {
            return false;
        }
        return (from == null || !periodEnd.isBefore(from)) && (to == null || !periodEnd.isAfter(to));
    }

    public static Totals totals(List<Line> lines) {
        int payments = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal mdr = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (Line l : lines) {
            payments += l.payments();
            gross = gross.add(nz(l.gross()));
            mdr = mdr.add(nz(l.mdr()));
            refunds = refunds.add(nz(l.refunds()));
            net = net.add(nz(l.net()));
        }
        return new Totals(lines.size(), payments, money(gross), money(mdr), money(refunds), money(net));
    }

    /** The statement as CSV (header, one row per settlement, a TOTAL row). Values never start with a formula sign. */
    public static String csv(String merchantCode, String currency, List<Line> lines) {
        List<String> out = new ArrayList<>();
        out.add("merchant_code,settlement_id,period_start,period_end,status,currency,payments,gross,mdr,refunds,net");
        for (Line l : lines) {
            out.add(String.join(",", cell(merchantCode), cell(l.settlementId()), cell(str(l.periodStart())),
                    cell(str(l.periodEnd())), cell(l.status()), cell(currency), String.valueOf(l.payments()),
                    plain(l.gross()), plain(l.mdr()), plain(l.refunds()), plain(l.net())));
        }
        Totals t = totals(lines);
        out.add(String.join(",", cell(merchantCode), "TOTAL", "", "", "", cell(currency), String.valueOf(t.payments()),
                plain(t.gross()), plain(t.mdr()), plain(t.refunds()), plain(t.net())));
        return String.join("\n", out) + "\n";
    }

    static String cell(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;                            // CSV injection: never let a spreadsheet evaluate a value
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String plain(BigDecimal d) {
        return money(nz(d)).toPlainString();
    }

    private static BigDecimal nz(BigDecimal d) {
        return d == null ? BigDecimal.ZERO : d;
    }

    private static BigDecimal money(BigDecimal d) {
        return d.setScale(2, RoundingMode.HALF_EVEN);
    }
}
