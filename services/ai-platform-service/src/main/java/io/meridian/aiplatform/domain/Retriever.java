package io.meridian.aiplatform.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic retrieval-augmented answering over caller-provided context (no model call): passages are ranked by
 * BM25 against the prompt, the best sentence of each of the top passages is quoted with a citation marker, and write
 * tools whose keywords appear in the prompt are proposed (never executed). The same input always yields the same
 * answer, which keeps the audit trail reproducible.
 */
public final class Retriever {
    public static final int TOP_K = 3;
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "and",
            "or", "in", "on", "for", "with", "what", "which", "who", "how", "why", "when", "does", "do", "did", "this",
            "that", "it", "its", "as", "at", "by", "from", "please", "can", "could", "you", "me", "my", "i", "we", "our",
            "about", "tell", "give", "show", "there", "any", "has", "have", "been");

    private Retriever() {}

    public record Passage(String id, String type, String title, String text) {}

    public record Citation(int index, String sourceId, String sourceType, String title, BigDecimal score) {}

    public record Tool(String name, String mode, List<String> keywords) {}

    public record ProposedAction(String tool, Map<String, Object> arguments) {}

    public record Answer(String text, List<Citation> citations, List<String> readTools, ProposedAction action, boolean grounded) {}

    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (t.length() > 1 && !STOP.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static Answer answer(String prompt, List<Passage> passages, List<Tool> tools, String subjectType, String subjectId) {
        List<String> q = tokens(prompt);
        Set<String> qset = new HashSet<>(q);
        // BM25 over the passages.
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> docs = new ArrayList<>();
        double avg = 0;
        for (Passage p : passages) {
            List<String> d = tokens(p.title() + " " + p.text());
            docs.add(d);
            avg += d.size();
            new HashSet<>(d).forEach(t -> df.merge(t, 1, Integer::sum));
        }
        avg = passages.isEmpty() ? 0 : avg / passages.size();
        record Scored(int idx, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            List<String> d = docs.get(i);
            Map<String, Integer> tf = new HashMap<>();
            d.forEach(t -> tf.merge(t, 1, Integer::sum));
            double s = 0;
            for (String t : qset) {
                Integer f = tf.get(t);
                if (f == null) {
                    continue;
                }
                double idf = Math.log(1 + (passages.size() - df.get(t) + 0.5) / (df.get(t) + 0.5));
                s += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * d.size() / Math.max(avg, 1)));
            }
            if (s > 0) {
                scored.add(new Scored(i, s));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::idx));
        List<Citation> citations = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Scored s : scored.subList(0, Math.min(TOP_K, scored.size()))) {
            Passage p = passages.get(s.idx());
            int n = citations.size() + 1;
            citations.add(new Citation(n, p.id(), p.type(), p.title(),
                    BigDecimal.valueOf(s.score()).setScale(4, RoundingMode.HALF_EVEN)));
            text.append(citations.size() == 1 ? "Based on the provided context:\n" : "\n")
                    .append("- ").append(bestSentence(p.text(), qset)).append(" [").append(n).append(']');
        }
        List<String> readTools = new ArrayList<>();
        ProposedAction action = null;
        String lower = prompt.toLowerCase(Locale.ROOT);
        for (Tool t : tools) {
            boolean hit = lower.contains(t.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                    || t.keywords().stream().anyMatch(k -> !k.isBlank() && lower.contains(k.toLowerCase(Locale.ROOT)));
            if (!hit) {
                continue;
            }
            if ("WRITE".equals(t.mode())) {
                if (action == null) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (subjectType != null) {
                        args.put("subject_type", subjectType);
                    }
                    if (subjectId != null) {
                        args.put("subject_id", subjectId);
                    }
                    args.put("instruction", prompt.length() > 200 ? prompt.substring(0, 200) : prompt);
                    action = new ProposedAction(t.name(), args);
                }
            } else {
                readTools.add(t.name());
            }
        }
        boolean grounded = !citations.isEmpty();
        if (!grounded) {
            text.append("I could not find this in the provided context, so I will not guess.");
        }
        if (action != null) {
            text.append(grounded ? "\n\n" : " ").append("Proposed action: ").append(action.tool())
                    .append(" (requires human approval before anything is executed).");
        }
        return new Answer(text.toString(), citations, readTools, action, grounded);
    }

    static String bestSentence(String text, Set<String> q) {
        String best = null;
        long bestHits = -1;
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            long hits = tokens(sentence).stream().filter(q::contains).count();
            if (hits > bestHits) {
                best = sentence.trim();
                bestHits = hits;
            }
        }
        if (best == null) {
            best = text.trim();
        }
        return best.length() > 400 ? best.substring(0, 400) + "..." : best;
    }

    /** Rough token estimate (about four characters per token), never below one for non-empty text. */
    public static int estimateTokens(String s) {
        return s == null || s.isEmpty() ? 0 : Math.max(1, (s.length() + 3) / 4);
    }
}
