package io.meridian.crm.domain;

import java.util.Map;

/**
 * Default complaint-handling deadlines per jurisdiction, used when no active regulatory {@code sla_policy} matches.
 * Acknowledgement is counted in business days, the final response in calendar or business days as the rule says.
 *
 * <pre>
 * Country  Category        Acknowledge  Final response          Reference
 * GB       any             3 BD         56 calendar days         FCA DISP 1.6.2R (8 weeks)
 * GB/EU    PAYMENTS        3 BD         15 business days         FCA DISP 1.6.2A / PSD2 Art. 101
 * IE       any             5 BD         40 business days         CBI Consumer Protection Code 10.9
 * AU       any             1 BD         30 calendar days         ASIC RG 271
 * IN       any             1 BD         30 calendar days         RBI Integrated Ombudsman Scheme
 * US       any             15 CD (as BD) 60 calendar days        CFPB complaint response
 * other    any             5 BD         30 calendar days         internal default
 * </pre>
 */
public final class RegulatoryTimelines {
    private RegulatoryTimelines() {}

    public record Rule(int acknowledgeBusinessDays, int finalResponseDays, boolean finalInBusinessDays, String reference) {}

    private static final Rule DEFAULT = new Rule(5, 30, false, "Internal default");
    private static final Rule PAYMENT_SERVICES = new Rule(3, 15, true, "PSD2 Art. 101 / FCA DISP 1.6.2A");
    private static final Map<String, Rule> BY_COUNTRY = Map.of(
            "GB", new Rule(3, 56, false, "FCA DISP 1.6.2R"),
            "IE", new Rule(5, 40, true, "CBI Consumer Protection Code 10.9"),
            "AU", new Rule(1, 30, false, "ASIC RG 271"),
            "IN", new Rule(1, 30, false, "RBI Integrated Ombudsman Scheme"),
            "US", new Rule(15, 60, false, "CFPB complaint response"));
    private static final java.util.Set<String> PSD_COUNTRIES = java.util.Set.of("GB", "IE", "DE", "FR", "ES", "IT",
            "NL", "BE", "AT", "PT", "FI", "SE", "DK", "PL", "LU");

    public static Rule rule(String country, String category) {
        if ("PAYMENTS".equals(category) && country != null && PSD_COUNTRIES.contains(country)) {
            return PAYMENT_SERVICES;
        }
        return country == null ? DEFAULT : BY_COUNTRY.getOrDefault(country, DEFAULT);
    }

    /** Name of the ombudsman the complainant can refer the complaint to. */
    public static String ombudsman(String country) {
        return switch (country == null ? "" : country) {
            case "GB" -> "Financial Ombudsman Service";
            case "IE" -> "Financial Services and Pensions Ombudsman";
            case "AU" -> "Australian Financial Complaints Authority";
            case "IN" -> "RBI Ombudsman";
            default -> "the financial ombudsman";
        };
    }
}
