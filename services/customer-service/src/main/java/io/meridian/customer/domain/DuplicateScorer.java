package io.meridian.customer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scores how likely an existing customer is the same party as a prospective one. Strong identifiers dominate;
 * name and date of birth reinforce each other; contact points are supporting evidence. Score 0..100.
 */
public final class DuplicateScorer {
    private DuplicateScorer() {}

    public static final int THRESHOLD = 30;

    /** Normalised probe (what the caller is about to create). Null fields are not compared. */
    public record Probe(String firstName, String lastName, String dateOfBirth, String phone, String email,
                        String identifierHash, String registrationNumber) {}

    /** Normalised candidate (an existing customer and its contact points). */
    public record Candidate(String firstName, String lastName, String displayName, String dateOfBirth,
                            String registrationNumber, Set<String> phones, Set<String> emails, Set<String> identifierHashes) {}

    public record Score(int score, List<String> reasons) {}

    public static Score score(Probe p, Candidate c) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (p.identifierHash() != null && c.identifierHashes().contains(p.identifierHash())) {
            score += 70;
            reasons.add("IDENTIFIER");
        }
        if (notBlank(p.registrationNumber()) && p.registrationNumber().equals(c.registrationNumber())) {
            score += 70;
            reasons.add("REGISTRATION_NUMBER");
        }
        boolean lastMatch = notBlank(p.lastName()) && p.lastName().equals(c.lastName());
        boolean firstMatch = notBlank(p.firstName()) && p.firstName().equals(c.firstName());
        boolean fullName = lastMatch && (firstMatch || p.firstName() == null);
        if (!fullName && notBlank(p.lastName()) && c.displayName() != null) {
            String probeName = ((p.firstName() == null ? "" : p.firstName() + " ") + p.lastName()).trim();
            fullName = probeName.equals(c.displayName());
        }
        boolean dob = notBlank(p.dateOfBirth()) && p.dateOfBirth().equals(c.dateOfBirth());
        if (fullName) {
            score += 30;
            reasons.add("NAME");
        } else if (lastMatch) {
            score += 15;
            reasons.add("LAST_NAME");
        }
        if (dob) {
            score += (fullName || lastMatch) ? 30 : 10;
            reasons.add("DATE_OF_BIRTH");
        }
        if (notBlank(p.email()) && c.emails().contains(p.email())) {
            score += 25;
            reasons.add("EMAIL");
        }
        if (notBlank(p.phone()) && c.phones().contains(p.phone())) {
            score += 25;
            reasons.add("PHONE");
        }
        return new Score(Math.min(100, score), reasons);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
