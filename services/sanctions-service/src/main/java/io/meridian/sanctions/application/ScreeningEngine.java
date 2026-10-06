package io.meridian.sanctions.application;

import io.meridian.sanctions.domain.EntryIndex;
import io.meridian.sanctions.domain.NameMatcher;
import io.meridian.sanctions.domain.NameNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Runs subjects and payments against the visible lists' indexes; keeps the best hit per list entry. */
@Component
public class ScreeningEngine {
    static final List<String> PARTIES = List.of("debtor", "creditor", "ultimate_debtor", "ultimate_creditor",
            "debtor_agent", "creditor_agent", "instructing_agent", "instructed_agent");

    private final ListCatalog catalog;

    public ScreeningEngine(ListCatalog catalog) {
        this.catalog = catalog;
    }

    /** A hit above the threshold, with the payment party it came from (null for subject screening). */
    public record Hit(NameMatcher.Match match, String party) {}

    public List<Hit> screen(NameMatcher.Subject subject, Set<String> listCodes, double threshold) {
        Map<UUID, Hit> best = new LinkedHashMap<>();
        screenInto(best, subject, listCodes, threshold, null);
        return sorted(best);
    }

    /**
     * Screens every party of an ISO 20022-style payment (debtor, creditor, ultimate parties, agents with their BIC
     * country, intermediary agents), embargoed countries and the remittance free text.
     */
    public List<Hit> screenPayment(JsonNode payload, Set<String> listCodes, double threshold) {
        Map<UUID, Hit> best = new LinkedHashMap<>();
        Set<String> countries = new LinkedHashSet<>();
        List<Map.Entry<String, JsonNode>> parties = new ArrayList<>();
        for (String p : PARTIES) {
            if (payload.hasNonNull(p)) {
                parties.add(Map.entry(p, payload.get(p)));
            }
        }
        int i = 0;
        for (JsonNode a : payload.path("intermediary_agents")) {
            parties.add(Map.entry("intermediary_agent_" + (++i), a));
        }
        for (Map.Entry<String, JsonNode> p : parties) {
            NameMatcher.Subject s = subject(p.getValue());
            countries.addAll(s.countries());
            if (s.name() != null && !s.name().isBlank()) {
                screenInto(best, s, listCodes, threshold, p.getKey());
            }
        }
        for (ListCatalog.ListInfo l : catalog.activeLists(listCodes)) {
            EntryIndex idx = catalog.index(l);
            for (NameMatcher.Entry e : idx.embargoed(countries)) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("countries", List.copyOf(countries));
                keep(best, new Hit(new NameMatcher.Match(e, e.primaryName(), 1.0, "COUNTRY_EMBARGO", f), "country"));
            }
            String text = payload.path("remittance_information").asString("");
            if (!text.isBlank()) {
                List<String> tokens = NameNormalizer.tokens(text);
                for (NameMatcher.Entry e : idx.candidates(tokens, List.of())) {
                    NameMatcher.Match m = NameMatcher.scoreFreeText(tokens, e);
                    if (m.score() >= threshold) {
                        keep(best, new Hit(m, "remittance_information"));
                    }
                }
            }
        }
        return sorted(best);
    }

    private void screenInto(Map<UUID, Hit> best, NameMatcher.Subject s, Set<String> listCodes, double threshold, String party) {
        List<String> tokens = s.tokens();
        for (ListCatalog.ListInfo l : catalog.activeLists(listCodes)) {
            EntryIndex idx = catalog.index(l);
            for (NameMatcher.Entry e : idx.candidates(tokens, s.identifiers())) {
                if ("COUNTRY".equals(e.entryType())) {
                    continue;
                }
                NameMatcher.Match m = NameMatcher.score(s, tokens, e);
                if (m.score() >= threshold) {
                    keep(best, new Hit(m, party));
                }
            }
        }
    }

    private static void keep(Map<UUID, Hit> best, Hit h) {
        Hit prev = best.get(h.match().entry().id());
        if (prev == null || h.match().score() > prev.match().score()) {
            best.put(h.match().entry().id(), h);
        }
    }

    private static List<Hit> sorted(Map<UUID, Hit> best) {
        List<Hit> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble((Hit h) -> -h.match().score()).thenComparing(h -> h.match().entry().primaryName()));
        return out;
    }

    /** Builds a subject from a payment party or batch item: name, date of birth, country / BIC country, ids. */
    public static NameMatcher.Subject subject(JsonNode p) {
        String name = text(p, "name", text(p, "full_name", null));
        Set<String> countries = new LinkedHashSet<>();
        for (String f : List.of("country", "nationality", "country_of_residence")) {
            String c = text(p, f, null);
            if (c != null && c.length() == 2) {
                countries.add(c.toUpperCase(Locale.ROOT));
            }
        }
        String bic = text(p, "bic", null);
        if (bic != null && bic.length() >= 6) {
            countries.add(bic.substring(4, 6).toUpperCase(Locale.ROOT));
        }
        Set<String> ids = new LinkedHashSet<>();
        p.path("identifiers").forEach(i -> {
            String v = i.isObject() ? i.path("value").asString(null) : i.asString(null);
            if (v != null && !NameNormalizer.identifier(v).isEmpty()) {
                ids.add(NameNormalizer.identifier(v));
            }
        });
        return new NameMatcher.Subject(name, text(p, "date_of_birth", null), countries, ids);
    }

    private static String text(JsonNode n, String f, String dflt) {
        return n != null && n.hasNonNull(f) && !n.get(f).asString("").isBlank() ? n.get(f).asString() : dflt;
    }
}
