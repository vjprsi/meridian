package io.meridian.kyc.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregates a customer's KYC evidence (documents, provider checks, screenings) into verification flags, the
 * achieved tier, a factor-based risk score and the resulting profile status. Pure and deterministic.
 */
public final class ProfileEvaluator {
    public static final String MODEL_VERSION = "kyc-risk-1.0";

    /** FATF high-risk / increased-monitoring jurisdictions used as a document-country risk factor. */
    static final Set<String> HIGH_RISK_COUNTRIES = Set.of("KP", "IR", "MM", "SY", "YE", "AF", "SS", "HT", "VE");
    static final Set<String> IDENTITY_DOCS = Set.of("NATIONAL_ID", "PASSPORT", "DRIVING_LICENCE", "RESIDENCE_PERMIT",
            "CERTIFICATE_OF_INCORPORATION");
    static final Set<String> ADDRESS_DOCS = Set.of("UTILITY_BILL", "BANK_STATEMENT");
    static final Set<String> IDENTITY_CHECKS = Set.of("DOCUMENT_AUTHENTICITY", "DATABASE_VERIFICATION", "REGISTRY_LOOKUP");
    static final Set<String> BIOMETRIC_CHECKS = Set.of("LIVENESS", "FACE_MATCH");

    public record Document(String type, String status, LocalDate expiryDate, String issuingCountry) {}

    public record Check(String type, String status) {}

    public record Screening(String type, String status) {}

    public record Evidence(List<Document> documents, List<Check> checks, List<Screening> screenings,
                           boolean eddRequired, boolean eddCompleted) {}

    public record Result(boolean identityVerified, boolean addressVerified, boolean screeningClear,
                         String achievedTier, int score, String riskLevel, Map<String, Object> factors,
                         String status, boolean eddRequired, LocalDate earliestExpiry) {}

    public static Result evaluate(Evidence e, LocalDate today) {
        List<Document> accepted = e.documents().stream()
                .filter(d -> "ACCEPTED".equals(d.status()))
                .filter(d -> d.expiryDate() == null || !d.expiryDate().isBefore(today))
                .toList();
        boolean identityDoc = accepted.stream().anyMatch(d -> IDENTITY_DOCS.contains(d.type()));
        boolean identityCheck = passed(e.checks(), IDENTITY_CHECKS);
        boolean identityVerified = identityDoc && identityCheck;
        boolean addressVerified = accepted.stream().anyMatch(d -> ADDRESS_DOCS.contains(d.type()))
                || passed(e.checks(), Set.of("ADDRESS_VERIFICATION"));
        boolean biometric = passed(e.checks(), BIOMETRIC_CHECKS);
        long openMatches = e.screenings().stream().filter(s -> "POTENTIAL_MATCH".equals(s.status())).count();
        boolean screened = !e.screenings().isEmpty();
        boolean confirmedSanctions = e.screenings().stream()
                .anyMatch(s -> "CONFIRMED_MATCH".equals(s.status()) && "SANCTIONS".equals(s.type()));
        boolean confirmedPep = e.screenings().stream()
                .anyMatch(s -> "CONFIRMED_MATCH".equals(s.status()) && "PEP".equals(s.type()));
        boolean confirmedOther = e.screenings().stream()
                .anyMatch(s -> "CONFIRMED_MATCH".equals(s.status()) && !"PEP".equals(s.type()) && !"SANCTIONS".equals(s.type()));
        boolean screeningClear = screened && openMatches == 0 && !confirmedSanctions && !confirmedOther;
        long failed = e.checks().stream().filter(c -> "FAILED".equals(c.status())).count();
        boolean inconclusive = e.checks().stream().anyMatch(c -> "INCONCLUSIVE".equals(c.status()));
        boolean highRiskCountry = e.documents().stream()
                .anyMatch(d -> d.issuingCountry() != null && HIGH_RISK_COUNTRIES.contains(d.issuingCountry().trim()));

        Map<String, Object> factors = new LinkedHashMap<>();
        int score = 0;
        score += factor(factors, "confirmed_pep", confirmedPep ? 300 : 0);
        score += factor(factors, "open_screening_matches", openMatches > 0 ? 150 : 0);
        score += factor(factors, "confirmed_watchlist_match", confirmedOther ? 300 : 0);
        score += factor(factors, "failed_checks", (int) Math.min(300, failed * 100));
        score += factor(factors, "identity_unverified", identityVerified ? 0 : 200);
        score += factor(factors, "address_unverified", addressVerified ? 0 : 100);
        score += factor(factors, "high_risk_country", highRiskCountry ? 200 : 0);
        score = Math.min(1000, score);
        String level = confirmedSanctions ? "PROHIBITED" : score >= 700 ? "HIGH" : score >= 350 ? "MEDIUM" : "LOW";
        factors.put("confirmed_sanctions", confirmedSanctions);

        boolean eddRequired = e.eddRequired() || "HIGH".equals(level) || confirmedPep;
        boolean eddSatisfied = !eddRequired || e.eddCompleted();
        boolean anyEvidence = !e.documents().isEmpty() || !e.checks().isEmpty() || screened;

        String tier = "TIER_0";
        if (anyEvidence) {
            tier = "TIER_1";
        }
        if (identityVerified) {
            tier = "TIER_2";
        }
        if (identityVerified && addressVerified && biometric && screeningClear) {
            tier = "TIER_3";
        }

        String status;
        if ("PROHIBITED".equals(level)) {
            status = "REJECTED";
        } else if (identityVerified && screeningClear && eddSatisfied) {
            status = "VERIFIED";
        } else if (openMatches > 0 || inconclusive || ("HIGH".equals(level) && !eddSatisfied) || confirmedOther) {
            status = "REFER";
        } else if (anyEvidence) {
            status = "IN_PROGRESS";
        } else {
            status = "NOT_STARTED";
        }
        LocalDate earliest = accepted.stream().map(Document::expiryDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        return new Result(identityVerified, addressVerified, screeningClear, tier, score, level, factors, status,
                eddRequired, earliest);
    }

    private static boolean passed(List<Check> checks, Set<String> types) {
        return checks.stream().anyMatch(c -> "PASSED".equals(c.status()) && types.contains(c.type()));
    }

    private static int factor(Map<String, Object> factors, String name, int points) {
        factors.put(name, points);
        return points;
    }

    /** Level implied by a 0..1000 score (manual assessments). */
    public static String levelFor(int score) {
        return score >= 700 ? "HIGH" : score >= 350 ? "MEDIUM" : "LOW";
    }
}
