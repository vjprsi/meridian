package io.meridian.loanorigination.infrastructure;

import io.meridian.loanorigination.application.BureauAdapter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic sandbox bureau for development and tests: the report is a pure function of the customer id, so the
 * same customer always gets the same file. The last hex digit of the customer id selects the profile:
 * <ul>
 *   <li>{@code 0} - NO_HIT (no record at the bureau)</li>
 *   <li>{@code f} - seriously delinquent (score 480, 120 DPD, 2 accounts in arrears)</li>
 *   <li>{@code e} - thin file (score 590, one account)</li>
 *   <li>{@code 1..d} - clean file, score {@code 560 + 20 * digit} (580..820); digits 1-3 show a 30 DPD episode</li>
 * </ul>
 * Tradeline lenders and balances are derived from SHA-256 of the customer id.
 */
public class SandboxBureauAdapter implements BureauAdapter {
    private final String provider;
    private final boolean async;

    public SandboxBureauAdapter(String provider, boolean async) {
        this.provider = provider;
        this.async = async;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public Result pull(Request request) {
        String reference = provider.toLowerCase() + "-" + hex(request.reportId().toString()).substring(0, 16);
        if (async) {
            return Result.requested(reference);
        }
        return report(request.customerId(), reference);
    }

    /** The sandbox file of a customer (also used to build callback payloads in tests). */
    public static Result report(UUID customerId, String reference) {
        String id = customerId.toString();
        int digit = Character.digit(id.charAt(id.length() - 1), 16);
        String h = hex(id);
        if (digit == 0) {
            return new Result("NO_HIT", reference, null, null, null, null, 0, 0, null, null, List.of(), null);
        }
        int score;
        int open;
        int delinquent = 0;
        int worst = 0;
        int inquiries = Integer.parseInt(h.substring(0, 1), 16) % 3;
        if (digit == 15) {
            score = 480;
            open = 4;
            delinquent = 2;
            worst = 120;
            inquiries = 5;
        } else if (digit == 14) {
            score = 590;
            open = 1;
        } else {
            score = 560 + 20 * digit;
            open = 1 + digit % 4;
            worst = digit <= 3 ? 30 : 0;
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal service = BigDecimal.ZERO;
        String[] lenders = {"NORTHBANK", "CITY_CARDS", "AUTO_FINANCE", "HOME_LOANS", "RETAIL_CREDIT"};
        for (int i = 0; i < open; i++) {
            int seed = Integer.parseInt(h.substring(2 + i * 2, 4 + i * 2), 16);
            BigDecimal balance = BigDecimal.valueOf(1000L + seed * 20L);
            BigDecimal payment = BigDecimal.valueOf(50L + seed / 2);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lender", lenders[(seed + i) % lenders.length]);
            line.put("account_type", i % 2 == 0 ? "INSTALMENT" : "REVOLVING");
            line.put("balance", balance.toPlainString());
            line.put("monthly_payment", payment.toPlainString());
            line.put("worst_dpd_24m", i < delinquent ? worst : (i == 0 ? worst : 0));
            line.put("status", i < delinquent ? "IN_ARREARS" : "UP_TO_DATE");
            lines.add(line);
            total = total.add(balance);
            service = service.add(payment);
        }
        return new Result("RECEIVED", reference, score, band(score), total, service, open, delinquent, worst, inquiries,
                lines, null);
    }

    static String band(int score) {
        return score >= 750 ? "EXCELLENT" : score >= 680 ? "GOOD" : score >= 620 ? "FAIR" : "POOR";
    }

    private static String hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
