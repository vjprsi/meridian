package io.meridian.customer.domain;

import io.meridian.common.error.ApiException.FieldError;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KYC tiers: what evidence each tier needs and which limits it unlocks. Upgrades need evidence (and a checker);
 * downgrades are risk-reducing and apply immediately.
 */
public final class KycTierPolicy {
    private KycTierPolicy() {}

    public static final List<String> TIERS = List.of("TIER_0", "TIER_1", "TIER_2", "TIER_3");

    /** Limits in units of the customer's base currency; null means not limited by the tier. */
    public record Limits(BigDecimal maxBalance, BigDecimal dailyDebitLimit, BigDecimal monthlyInflowLimit,
                         boolean internationalTransfers) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("max_balance", maxBalance == null ? null : maxBalance.toPlainString());
            m.put("daily_debit_limit", dailyDebitLimit == null ? null : dailyDebitLimit.toPlainString());
            m.put("monthly_inflow_limit", monthlyInflowLimit == null ? null : monthlyInflowLimit.toPlainString());
            m.put("international_transfers", internationalTransfers);
            return m;
        }
    }

    /** Evidence held by customer-service that tier requirements are checked against. */
    public record Evidence(boolean individual, int verifiedContacts, int validIdentifiers, boolean hasBirthOrRegistration,
                          int currentAddresses, String riskLevel) {}

    public static int rank(String tier) {
        int i = TIERS.indexOf(tier);
        if (i < 0) {
            throw new IllegalArgumentException("unknown tier " + tier);
        }
        return i;
    }

    public static Limits limits(String tier) {
        return switch (tier) {
            case "TIER_0" -> new Limits(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
            case "TIER_1" -> new Limits(new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("2000"), false);
            case "TIER_2" -> new Limits(new BigDecimal("50000"), new BigDecimal("10000"), new BigDecimal("100000"), true);
            case "TIER_3" -> new Limits(null, null, null, true);
            default -> throw new IllegalArgumentException("unknown tier " + tier);
        };
    }

    /** Requirements of {@code toTier} not met by the evidence; empty when the tier may be granted. */
    public static List<FieldError> unmet(String toTier, Evidence e) {
        List<FieldError> errors = new ArrayList<>();
        int target = rank(toTier);
        if ("PROHIBITED".equals(e.riskLevel()) && target > 0) {
            errors.add(new FieldError("risk_level", "prohibited_risk", "Customer risk is PROHIBITED"));
        }
        if (target >= 1 && e.verifiedContacts() == 0) {
            errors.add(new FieldError("contacts", "verified_contact_required", "At least one verified phone or email is required"));
        }
        if (target >= 2) {
            if (e.validIdentifiers() == 0) {
                errors.add(new FieldError("identifiers", "identifier_required", "A current (unexpired) identity document is required"));
            }
            if (!e.hasBirthOrRegistration()) {
                errors.add(e.individual()
                        ? new FieldError("date_of_birth", "required", "Date of birth is required")
                        : new FieldError("registration_number", "required", "Registration number is required"));
            }
        }
        if (target >= 3) {
            if (e.currentAddresses() == 0) {
                errors.add(new FieldError("addresses", "address_required", "A current residential or registered address is required"));
            }
            if ("HIGH".equals(e.riskLevel())) {
                errors.add(new FieldError("risk_level", "high_risk", "HIGH risk customers are capped at TIER_2 until EDD"));
            }
        }
        return errors;
    }
}
