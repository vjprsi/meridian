package io.meridian.statement.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Tax certificate rules: the reporting period and the interest/tax totals from capitalisations. */
public final class TaxCertificates {
    public static final Set<String> NEEDS_ACCOUNT = Set.of("BALANCE_CONFIRMATION");

    /** One interest posting as reported by deposit-service. */
    public record Posting(LocalDate bookedOn, String kind, String currency, BigDecimal gross, BigDecimal tax) {}

    public record Totals(BigDecimal grossInterest, BigDecimal taxWithheld, int postings) {
        public BigDecimal net() {
            return grossInterest.subtract(taxWithheld);
        }
    }

    private TaxCertificates() {}

    public static void validate(int taxYear, LocalDate start, LocalDate end, LocalDate today) {
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        if (end.getYear() != taxYear) {
            throw ApiException.unprocessable("tax_year_mismatch", "period_end must fall in tax_year " + taxYear);
        }
        if (start.plusYears(1).isBefore(end.plusDays(1))) {
            throw ApiException.unprocessable("period_too_long", "A tax certificate covers at most one year");
        }
        if (start.isAfter(today)) {
            throw ApiException.unprocessable("period_in_future", "The period has not started yet");
        }
    }

    /** Sums credit-interest postings booked within the period in the certificate currency. */
    public static Totals sum(List<Posting> postings, LocalDate start, LocalDate end, String currency) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        int n = 0;
        for (Posting p : postings) {
            if (!"CREDIT".equals(p.kind()) || !currency.equals(p.currency())
                    || p.bookedOn().isBefore(start) || p.bookedOn().isAfter(end)) {
                continue;
            }
            gross = gross.add(p.gross());
            tax = tax.add(p.tax());
            n++;
        }
        return new Totals(gross, tax, n);
    }
}
