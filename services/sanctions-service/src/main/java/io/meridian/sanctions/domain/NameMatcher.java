package io.meridian.sanctions.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scores a screened subject against a list entry.
 *
 * <p>Name score (best over the primary name and every alias) = max of
 * <ul>
 *   <li>Jaro-Winkler on the sorted tokens,</li>
 *   <li>token-set ratio and per-token alignment, each scaled by token coverage (0.6 + 0.4 * coverage) so a single
 *       shared token does not match a long name.</li>
 * </ul>
 * Then: date-of-birth boost (+0.05 exact date, +0.03 same year) or penalty (-0.15 when the years differ by more than
 * two), country boost (+0.03) or penalty (-0.05) when nationality / countries are known on both sides. An exact
 * identifier match (passport, registration, IMO) scores 1.0.
 */
public final class NameMatcher {
    private NameMatcher() {}

    /** A subject to screen (customer, party of a payment, ...). */
    public record Subject(String name, String dateOfBirth, Set<String> countries, Set<String> identifiers) {
        public List<String> tokens() {
            return NameNormalizer.tokens(name);
        }
    }

    /** A list entry prepared for matching (names already normalised into tokens). */
    public record Entry(UUID id, UUID listId, String listCode, String listSource, String entryType, String primaryName,
                        List<String> names, List<List<String>> nameTokens, String dateOfBirth, Set<String> countries,
                        Set<String> identifiers, String pepLevel) {}

    public record Match(Entry entry, String matchedName, double score, String algorithm, Map<String, Object> fields) {}

    public static Match score(Subject s, List<String> subjectTokens, Entry e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String id : s.identifiers()) {
            if (!id.isEmpty() && e.identifiers().contains(id)) {
                fields.put("identifier", id);
                fields.put("identifier_score", 1.0);
                return new Match(e, e.primaryName(), 1.0, "EXACT_ID", fields);
            }
        }
        double best = 0;
        String bestName = e.primaryName();
        String algorithm = "JARO_WINKLER";
        for (int i = 0; i < e.nameTokens().size(); i++) {
            List<String> cand = e.nameTokens().get(i);
            if (cand.isEmpty() || subjectTokens.isEmpty()) {
                continue;
            }
            double cov = 0.6 + 0.4 * Similarity.coverage(subjectTokens, cand);
            double jw = Similarity.sortedTokenJaroWinkler(subjectTokens, cand) * cov;
            double ts = Similarity.tokenSetRatio(subjectTokens, cand) * cov;
            double al = Similarity.tokenAlignment(subjectTokens, cand) * cov;
            // spacing variants ("Abdul Rahman" / "Abdulrahman"): compare the names without spaces, scaled by length ratio
            String ca = String.join("", subjectTokens);
            String cb = String.join("", cand);
            double compact = Similarity.jaroWinkler(ca, cb)
                    * (0.6 + 0.4 * Math.min(ca.length(), cb.length()) / Math.max(ca.length(), cb.length()));
            double sc = Math.max(Math.max(jw, compact), Math.max(ts, al));
            if (sc > best) {
                best = sc;
                bestName = e.names().get(i);
                algorithm = sc == jw || sc == compact ? "JARO_WINKLER" : "TOKEN_SET";
                if (sc >= 0.999 && !NameNormalizer.normalise(s.name()).equals(s.name().toLowerCase(java.util.Locale.ROOT).trim())) {
                    algorithm = "TRANSLITERATION";
                }
            }
        }
        fields.put("name_score", round(best));
        double score = best;
        if (s.dateOfBirth() != null && e.dateOfBirth() != null && s.dateOfBirth().length() >= 4 && e.dateOfBirth().length() >= 4) {
            String sy = s.dateOfBirth().substring(0, 4);
            String ey = e.dateOfBirth().substring(0, 4);
            if (s.dateOfBirth().length() >= 10 && e.dateOfBirth().length() >= 10
                    && s.dateOfBirth().substring(0, 10).equals(e.dateOfBirth().substring(0, 10))) {
                score += 0.05;
                fields.put("date_of_birth", "EXACT");
            } else if (sy.equals(ey)) {
                score += 0.03;
                fields.put("date_of_birth", "SAME_YEAR");
            } else if (isInt(sy) && isInt(ey) && Math.abs(Integer.parseInt(sy) - Integer.parseInt(ey)) > 2) {
                score -= 0.15;
                fields.put("date_of_birth", "MISMATCH");
            }
        }
        if (!s.countries().isEmpty() && !e.countries().isEmpty()) {
            boolean overlap = s.countries().stream().anyMatch(e.countries()::contains);
            score += overlap ? 0.03 : -0.05;
            fields.put("country", overlap ? "MATCH" : "MISMATCH");
        }
        score = Math.max(0.0, Math.min(1.0, score));
        fields.put("final_score", round(score));
        return new Match(e, bestName, round(score), algorithm, fields);
    }

    /**
     * Free-text screening (payment remittance information): an entry with at least two name tokens matches when its
     * tokens align with tokens of the text.
     */
    public static Match scoreFreeText(List<String> textTokens, Entry e) {
        double best = 0;
        String bestName = e.primaryName();
        for (int i = 0; i < e.nameTokens().size(); i++) {
            List<String> cand = e.nameTokens().get(i);
            if (cand.size() < 2) {
                continue;
            }
            // every token of the listed name must find a counterpart in the text
            double sum = 0;
            List<String> pool = new ArrayList<>(textTokens);
            for (String t : cand) {
                double b = 0;
                int idx = -1;
                for (int j = 0; j < pool.size(); j++) {
                    double sc = Similarity.jaroWinkler(t, pool.get(j));
                    if (sc > b) {
                        b = sc;
                        idx = j;
                    }
                }
                if (idx >= 0) {
                    pool.remove(idx);
                }
                sum += b;
            }
            double sc = sum / cand.size();
            if (sc > best) {
                best = sc;
                bestName = e.names().get(i);
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("free_text_score", round(best));
        return new Match(e, bestName, round(best), "TOKEN_SET", fields);
    }

    private static boolean isInt(String s) {
        return s.chars().allMatch(Character::isDigit);
    }

    static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
