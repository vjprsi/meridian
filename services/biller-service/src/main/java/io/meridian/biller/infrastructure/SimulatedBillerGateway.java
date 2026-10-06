package io.meridian.biller.infrastructure;

import io.meridian.biller.application.BillerGateway;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic biller simulator used until a real adapter is configured (and in tests). Behaviour keyed on the
 * account reference / target number so flows are reproducible:
 * <ul>
 *   <li>inquiry: references ending in 0000 are unknown (response 14); otherwise the amount due (100.00-9099.00) and
 *       the customer name are derived from a SHA-256 of biller code and reference.</li>
 *   <li>payment: references ending in 8888 are rejected; ending in 9999 time out on the first attempt and succeed on
 *       retries; ELECTRICITY billers return a 20-digit prepaid token.</li>
 *   <li>top-up: numbers ending in 0000 fail at the operator; ending in 9999 time out on the first attempt.</li>
 * </ul>
 */
@Component
public class SimulatedBillerGateway implements BillerGateway {
    private static final String[] FIRST = {"Amina", "Brian", "Cynthia", "David", "Esther", "Felix", "Grace", "Hassan"};
    private static final String[] LAST = {"Otieno", "Wanjiru", "Kamau", "Mwangi", "Achieng", "Kiprop", "Njoroge", "Ali"};

    @Override
    public Inquiry inquire(JsonNode biller, String accountReference) {
        if (accountReference.endsWith("0000")) {
            return new Inquiry("14", "Account not found at the biller", null, null, null, null);
        }
        byte[] h = hash(code(biller) + ":" + accountReference);
        long n = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
        BigDecimal due = BigDecimal.valueOf(n % 9000 + 100).setScale(2);
        String name = FIRST[h[4] & 7] + " " + LAST[h[5] & 7];
        LocalDate dueDate = LocalDate.of(2026, 1, 1).plusDays(h[6] & 0x7f);
        return new Inquiry("00", "OK", name, due, dueDate, "INQ" + HexFormat.of().formatHex(h, 0, 6).toUpperCase());
    }

    @Override
    public Result pay(JsonNode biller, JsonNode payment, int attempt) {
        String ref = payment.path("account_reference").asString("");
        if (ref.endsWith("8888")) {
            return new Result(Outcome.REJECTED, null, null, null, "Account closed at the biller");
        }
        if (ref.endsWith("9999") && attempt < 2) {
            return new Result(Outcome.TIMEOUT, null, null, null, "Biller did not answer in time");
        }
        byte[] h = hash(payment.path("id").asString() + ":" + attempt);
        String hex = HexFormat.of().formatHex(h).toUpperCase();
        String token = null;
        if ("ELECTRICITY".equals(biller.path("category").asString())) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; sb.length() < 20; i++) {
                sb.append(Math.floorMod(h[i % h.length] + i, 10));
            }
            token = sb.toString();
        }
        return new Result(Outcome.SUCCESS, code(biller) + "-" + hex.substring(0, 12), token, "RCT" + hex.substring(12, 22),
                "Payment accepted");
    }

    @Override
    public Result topup(JsonNode biller, JsonNode topup, int attempt) {
        String msisdn = topup.path("target_msisdn").asString("");
        if (msisdn.endsWith("0000")) {
            return new Result(Outcome.REJECTED, null, null, null, "Number not served by the operator");
        }
        if (msisdn.endsWith("9999") && attempt < 2) {
            return new Result(Outcome.TIMEOUT, null, null, null, "Operator did not answer in time");
        }
        String hex = HexFormat.of().formatHex(hash(topup.path("id").asString() + ":" + attempt)).toUpperCase();
        return new Result(Outcome.SUCCESS, code(biller) + "-" + hex.substring(0, 12), null, null, "Top-up delivered");
    }

    private static String code(JsonNode biller) {
        return biller.path("biller_code").asString("BILLER");
    }

    private static byte[] hash(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
