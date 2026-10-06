package io.meridian.crm.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic lexicon-based sentiment and intent detection for interactions and case classification. A stand-in for
 * the model-gateway classifier: same inputs always give the same result, so it is auditable and testable.
 */
public final class TextAnalysis {
    private TextAnalysis() {}

    private static final List<String> STRONG_NEGATIVE = List.of("furious", "disgusted", "unacceptable", "outrageous",
            "scam", "fraud", "stolen", "ombudsman", "lawyer", "worst", "complaint");
    private static final List<String> NEGATIVE = List.of("unhappy", "angry", "upset", "frustrated", "disappointed",
            "problem", "issue", "wrong", "error", "failed", "delay", "late", "charged", "not working", "declined", "poor");
    private static final List<String> POSITIVE = List.of("thanks", "thank you", "happy", "pleased", "resolved",
            "helpful", "good", "appreciate", "satisfied");
    private static final List<String> STRONG_POSITIVE = List.of("excellent", "fantastic", "amazing", "outstanding",
            "brilliant", "delighted");

    /** Intent -> keywords, in priority order (the first intent with a hit wins). */
    private static final Map<String, List<String>> INTENTS = new LinkedHashMap<>();
    /** Intent -> service case type it maps to. */
    private static final Map<String, String> INTENT_CASE_TYPE = new LinkedHashMap<>();

    static {
        intent("FRAUD_REPORT", "FRAUD_REPORT", "fraud", "scam", "stolen", "unauthorised", "unauthorized", "phishing");
        intent("COMPLAINT", "OTHER", "complain", "complaint", "ombudsman", "unacceptable");
        intent("CARD_ISSUE", "CARD_ISSUE", "card", "pin", "atm", "contactless");
        intent("PAYMENT_ISSUE", "PAYMENT_ISSUE", "payment", "transfer", "refund", "charged", "direct debit", "standing order");
        intent("LOAN_SERVICING", "LOAN_SERVICING", "loan", "mortgage", "repayment", "instalment", "installment");
        intent("DOCUMENT_REQUEST", "DOCUMENT_REQUEST", "statement", "certificate", "letter", "document");
        intent("KYC_UPDATE", "KYC_UPDATE", "address change", "change of address", "passport", "id expired", "update my details");
        intent("CLOSURE_REQUEST", "CLOSURE_REQUEST", "close my account", "closure", "cancel my account");
        intent("TECHNICAL_ISSUE", "TECHNICAL_ISSUE", "app", "login", "password", "crash", "website", "error message");
        intent("ACCOUNT_MAINTENANCE", "ACCOUNT_MAINTENANCE", "account", "overdraft", "limit", "balance");
    }

    private static void intent(String intent, String caseType, String... words) {
        INTENTS.put(intent, List.of(words));
        INTENT_CASE_TYPE.put(intent, caseType);
    }

    /** VERY_NEGATIVE .. VERY_POSITIVE; NEUTRAL for empty text. */
    public static String sentiment(String text) {
        if (text == null || text.isBlank()) {
            return "NEUTRAL";
        }
        String t = text.toLowerCase(Locale.ROOT);
        int score = 0;
        score -= 3 * hits(t, STRONG_NEGATIVE);
        score -= hits(t, NEGATIVE);
        score += hits(t, POSITIVE);
        score += 3 * hits(t, STRONG_POSITIVE);
        if (t.contains("!!")) {
            score -= 1;
        }
        if (score <= -3) {
            return "VERY_NEGATIVE";
        }
        if (score < 0) {
            return "NEGATIVE";
        }
        if (score >= 3) {
            return "VERY_POSITIVE";
        }
        return score > 0 ? "POSITIVE" : "NEUTRAL";
    }

    /** Detected intent code, or GENERAL_ENQUIRY. */
    public static String intent(String text) {
        if (text == null || text.isBlank()) {
            return "GENERAL_ENQUIRY";
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : INTENTS.entrySet()) {
            if (hits(t, e.getValue()) > 0) {
                return e.getKey();
            }
        }
        return "GENERAL_ENQUIRY";
    }

    /** Suggested service case type for an intent. */
    public static String caseType(String intent) {
        return INTENT_CASE_TYPE.getOrDefault(intent, "INQUIRY");
    }

    /** Suggested priority from intent and sentiment. */
    public static String priority(String intent, String sentiment) {
        if ("FRAUD_REPORT".equals(intent)) {
            return "URGENT";
        }
        if ("VERY_NEGATIVE".equals(sentiment) || "COMPLAINT".equals(intent)) {
            return "HIGH";
        }
        return "NEGATIVE".equals(sentiment) ? "MEDIUM" : "LOW";
    }

    private static int hits(String text, List<String> words) {
        int n = 0;
        for (String w : words) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(text);
            while (m.find()) {
                n++;
            }
        }
        return n;
    }
}
