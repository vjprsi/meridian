package io.meridian.collateral.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collateral policy: type-specific attribute validation, default haircuts, lending value and revaluation
 * frequency, and the collateral lifecycle. Pure (no Spring, no SQL).
 */
public final class CollateralRules {
    private CollateralRules() {}

    /** A violated attribute rule: field path (e.g. asset_details.vin), code and message. */
    public record Violation(String field, String code, String message) {}

    public enum Status { PROPOSED, VERIFIED, PLEDGED, RELEASED, LIQUIDATED, REJECTED }

    private static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.PROPOSED, Set.of(Status.VERIFIED, Status.REJECTED),
            Status.VERIFIED, Set.of(Status.PLEDGED, Status.RELEASED, Status.REJECTED),
            Status.PLEDGED, Set.of(Status.RELEASED, Status.LIQUIDATED),
            Status.RELEASED, Set.of(),
            Status.LIQUIDATED, Set.of(),
            Status.REJECTED, Set.of());

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(Status.valueOf(from), Set.of()).contains(Status.valueOf(to));
    }

    /** Statuses in which the collateral may secure (be linked to) an exposure. */
    public static boolean linkable(String status) {
        return "VERIFIED".equals(status) || "PLEDGED".equals(status);
    }

    private static final Map<String, BigDecimal> HAIRCUTS = Map.ofEntries(
            Map.entry("DEPOSIT", new BigDecimal("0")),
            Map.entry("GOLD", new BigDecimal("0.20")),
            Map.entry("SECURITIES", new BigDecimal("0.25")),
            Map.entry("PROPERTY", new BigDecimal("0.30")),
            Map.entry("VEHICLE", new BigDecimal("0.40")),
            Map.entry("EQUIPMENT", new BigDecimal("0.50")),
            Map.entry("INVENTORY", new BigDecimal("0.50")),
            Map.entry("RECEIVABLES", new BigDecimal("0.50")),
            Map.entry("CROP", new BigDecimal("0.50")),
            Map.entry("GUARANTEE", new BigDecimal("0.10")),
            Map.entry("OTHER", new BigDecimal("0.50")));

    public static BigDecimal defaultHaircut(String type) {
        return HAIRCUTS.getOrDefault(type, new BigDecimal("0.50"));
    }

    /** Months between revaluations by type. */
    public static int revaluationMonths(String type) {
        return switch (type) {
            case "VEHICLE" -> 6;
            case "GOLD", "SECURITIES" -> 1;
            default -> 12;
        };
    }

    /** market value x (1 - haircut), rounded to the currency's minor unit (never negative). */
    public static BigDecimal lendingValue(BigDecimal marketValue, BigDecimal haircut, String currency) {
        BigDecimal h = haircut == null ? BigDecimal.ZERO : haircut;
        BigDecimal v = marketValue.multiply(BigDecimal.ONE.subtract(h));
        return Currencies.round(v.max(BigDecimal.ZERO), currency);
    }

    public static List<Violation> validateHaircut(BigDecimal haircut) {
        List<Violation> v = new ArrayList<>();
        if (haircut != null && (haircut.signum() < 0 || haircut.compareTo(BigDecimal.ONE) >= 0)) {
            v.add(new Violation("haircut_pct", "out_of_range", "haircut_pct must be in [0, 1)"));
        }
        return v;
    }

    private static final Pattern VIN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    /**
     * Validates the type-specific attributes; {@code assetDetails} is a JSON-like map (null treated as empty).
     * DEPOSIT collateral needs the top-level deposit_account_id ({@code depositAccountPresent}).
     */
    public static List<Violation> validateAssetDetails(String type, Map<String, Object> assetDetails,
                                                       boolean depositAccountPresent) {
        Map<String, Object> d = assetDetails == null ? Map.of() : assetDetails;
        List<Violation> v = new ArrayList<>();
        switch (type) {
            case "PROPERTY" -> requireText(d, "title_number", v);
            case "VEHICLE" -> {
                String vin = text(d, "vin");
                if (vin == null) {
                    v.add(missing("vin"));
                } else if (!VIN.matcher(vin.toUpperCase(Locale.ROOT)).matches()) {
                    v.add(new Violation("asset_details.vin", "invalid_vin",
                            "vin must be 17 characters (letters and digits, no I, O or Q)"));
                }
                requireText(d, "registration_number", v);
            }
            case "GOLD" -> {
                BigDecimal carat = number(d, "carat", v);
                if (carat != null && (carat.compareTo(BigDecimal.ONE) < 0 || carat.compareTo(BigDecimal.valueOf(24)) > 0)) {
                    v.add(new Violation("asset_details.carat", "out_of_range", "carat must be between 1 and 24"));
                }
                positive(d, "weight_grams", v);
            }
            case "SECURITIES" -> {
                String isin = text(d, "isin");
                if (isin == null) {
                    v.add(missing("isin"));
                } else if (!isValidIsin(isin)) {
                    v.add(new Violation("asset_details.isin", "invalid_isin", "isin is not a valid ISIN (check digit)"));
                }
                positive(d, "quantity", v);
            }
            case "DEPOSIT" -> {
                if (!depositAccountPresent) {
                    v.add(new Violation("deposit_account_id", "required",
                            "deposit_account_id is required for DEPOSIT collateral"));
                }
            }
            case "GUARANTEE" -> requireText(d, "guarantor_name", v);
            default -> { }
        }
        return v;
    }

    /** ISIN: 12 characters, letters expanded to two digits, Luhn check over the result. */
    public static boolean isValidIsin(String isin) {
        if (isin == null || !ISIN.matcher(isin).matches()) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : isin.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (dbl) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static Violation missing(String field) {
        return new Violation("asset_details." + field, "required", field + " is required for this collateral type");
    }

    private static String text(Map<String, Object> d, String f) {
        Object o = d.get(f);
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static void requireText(Map<String, Object> d, String f, List<Violation> v) {
        if (text(d, f) == null) {
            v.add(missing(f));
        }
    }

    private static BigDecimal number(Map<String, Object> d, String f, List<Violation> v) {
        String s = text(d, f);
        if (s == null) {
            v.add(missing(f));
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            v.add(new Violation("asset_details." + f, "not_a_number", f + " must be a number"));
            return null;
        }
    }

    private static void positive(Map<String, Object> d, String f, List<Violation> v) {
        BigDecimal n = number(d, f, v);
        if (n != null && n.signum() <= 0) {
            v.add(new Violation("asset_details." + f, "must_be_positive", f + " must be greater than zero"));
        }
    }

    /** Ratio helper for the LTV reports. */
    public static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.divide(whole, 6, RoundingMode.HALF_UP);
    }
}
