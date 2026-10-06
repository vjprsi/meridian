package io.meridian.insights.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic merchant clean-name derivation for card and payment descriptors when no merchant rule matches:
 * strips payment-processor prefixes ("SQ *", "PAYPAL *", "POS ", "CRV*"), references after '*', tokens holding
 * digits (store, terminal and card numbers), trailing country codes, then title-cases the rest.
 */
public final class MerchantNameCleaner {
    private MerchantNameCleaner() {}

    private static final List<String> PREFIXES = List.of(
            "CARD PURCHASE ", "PURCHASE ", "CONTACTLESS ", "VISA ", "VIS ", "MC ", "DD ", "POS ", "SQ *", "SQ*", "SQU*",
            "PAYPAL *", "PAYPAL*", "PP*", "PP *", "CRV*", "CRV *", "TST*", "TST *", "SP *", "SP*", "IZ *", "IZ*",
            "ZETTLE_*", "SUMUP *", "SUMUP*", "GOOGLE *", "APPLE PAY ", "ECOM ");
    private static final Set<String> COUNTRY_TOKENS = Set.of(
            "GB", "UK", "US", "USA", "IE", "FR", "DE", "ES", "IT", "NL", "BE", "PT", "LU", "CH", "AT", "SE", "NO", "DK",
            "FI", "PL", "CA", "AU", "NZ", "ZA", "KE", "NG", "GH", "IN", "SG", "AE", "GBR", "IRL", "FRA", "DEU", "ESP");
    private static final Set<String> NOISE = Set.of("LTD", "LIMITED", "INC", "LLC", "PLC", "GMBH", "SARL", "BV", "CO");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,;:/\\\\|_]+");

    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String p : PREFIXES) {
                if (s.startsWith(p) && s.length() > p.length()) {
                    s = s.substring(p.length()).trim();
                    stripped = true;
                }
            }
        }
        int star = s.indexOf('*');
        if (star >= 3) {
            s = s.substring(0, star);
        }
        s = s.replace("*", " ").replace("#", " ");
        String[] tokens = SEPARATORS.split(s.trim());
        List<String> kept = new ArrayList<>();
        for (String t : tokens) {
            String tok = t.replaceAll("^[^A-Z0-9&']+|[^A-Z0-9&']+$", "");
            if (tok.isEmpty() || HAS_DIGIT.matcher(tok).matches()) {
                continue;
            }
            kept.add(tok);
        }
        while (kept.size() > 1 && (COUNTRY_TOKENS.contains(kept.getLast()) || NOISE.contains(kept.getLast()))) {
            kept.removeLast();
        }
        if (kept.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String k : kept) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(titleCase(k));
        }
        String result = out.toString();
        return result.length() > 160 ? result.substring(0, 160) : result;
    }

    private static String titleCase(String token) {
        if (token.equals("&")) {
            return token;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
