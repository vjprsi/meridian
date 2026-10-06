package io.meridian.cardthreeds.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Issuer risk-based authentication (3DS 2.x): scores an AReq 0-1000 and decides frictionless, challenge or reject.
 * Score below 300 is frictionless (low-value purchases under 30 included), 300-699 challenges the cardholder,
 * 700 and above is rejected. Merchant-initiated (3RI) requests have no cardholder to challenge: they are
 * authenticated only when small and low-risk, otherwise rejected.
 */
public final class RiskScorer {
    public enum Outcome { FRICTIONLESS, CHALLENGE, REJECT }

    public record Input(BigDecimal amount, String mcc, String merchantCountry, String deviceChannel, String cardStatus) {}

    public record Assessment(int score, List<String> reasons, Outcome outcome) {}

    /** Gambling, money transfer, quasi-cash / crypto, securities, direct marketing, telemarketing. */
    public static final Set<String> HIGH_RISK_MCC = Set.of("7995", "4829", "6051", "6211", "5967", "5993");
    static final BigDecimal ELEVATED = new BigDecimal("500");
    static final BigDecimal HIGH = new BigDecimal("2000");
    static final BigDecimal LOW_VALUE = new BigDecimal("30");
    public static final int CHALLENGE_FROM = 300;
    public static final int REJECT_FROM = 700;

    private RiskScorer() {}

    /** Risk score 0-1000 and the factors that raised it; non-decreasing in the amount. */
    public static Assessment score(Input in) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (!"ACTIVE".equals(in.cardStatus())) {
            reasons.add("CARD_NOT_ACTIVE");
            return new Assessment(1000, reasons, Outcome.REJECT);
        }
        if (in.amount() != null) {
            if (in.amount().compareTo(ELEVATED) >= 0) {
                score += 250;
                reasons.add("ELEVATED_AMOUNT");
            }
            if (in.amount().compareTo(HIGH) >= 0) {
                score += 400;
                reasons.add("HIGH_AMOUNT");
            }
        }
        if (in.mcc() != null && HIGH_RISK_MCC.contains(in.mcc())) {
            score += 250;
            reasons.add("HIGH_RISK_MCC");
        }
        if (in.merchantCountry() == null || in.merchantCountry().isBlank()) {
            score += 50;
            reasons.add("UNKNOWN_MERCHANT_COUNTRY");
        }
        score = Math.min(1000, score);
        return new Assessment(score, List.copyOf(reasons), null);
    }

    /** Score plus the authentication decision. */
    public static Assessment assess(Input in, BigDecimal threeRiMaxAmount) {
        Assessment s = score(in);
        if (s.outcome() == Outcome.REJECT) {
            return s;
        }
        if ("3RI".equalsIgnoreCase(in.deviceChannel())) {
            boolean small = in.amount() != null && in.amount().compareTo(threeRiMaxAmount) <= 0;
            if (small && s.score() < CHALLENGE_FROM) {
                return new Assessment(s.score(), s.reasons(), Outcome.FRICTIONLESS);
            }
            List<String> reasons = new ArrayList<>(s.reasons());
            reasons.add("MERCHANT_INITIATED_NOT_LOW_RISK");
            return new Assessment(s.score(), List.copyOf(reasons), Outcome.REJECT);
        }
        if (s.score() < CHALLENGE_FROM) {
            return new Assessment(s.score(), s.reasons(), Outcome.FRICTIONLESS);
        }
        if (s.score() < REJECT_FROM) {
            return new Assessment(s.score(), s.reasons(), Outcome.CHALLENGE);
        }
        return new Assessment(s.score(), s.reasons(), Outcome.REJECT);
    }

    /** True when the low-value exemption applies (amount under 30 and low risk). */
    public static boolean lowValueExempt(Input in) {
        return in.amount() != null && in.amount().compareTo(LOW_VALUE) < 0 && score(in).score() < CHALLENGE_FROM;
    }

    /** Challenge method: the merchant's / cardholder's preference when it is a challenge method, else SMS OTP. */
    public static String challengeType(String preferred) {
        if (preferred != null && Set.of("OTP_SMS", "OTP_EMAIL", "APP_PUSH", "BIOMETRIC", "DECOUPLED").contains(preferred)) {
            return preferred;
        }
        return "OTP_SMS";
    }

    /** ECI per scheme: authenticated 05 (02 Mastercard), attempted 06 (01 Mastercard), otherwise none. */
    public static String eci(String scheme, String result) {
        boolean mc = "MASTERCARD".equals(scheme);
        return switch (result) {
            case "AUTHENTICATED" -> mc ? "02" : "05";
            case "ATTEMPTED" -> mc ? "01" : "06";
            default -> null;
        };
    }
}
