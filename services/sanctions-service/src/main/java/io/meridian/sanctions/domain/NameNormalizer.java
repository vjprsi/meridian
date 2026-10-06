package io.meridian.sanctions.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical form of names for screening: Cyrillic and Greek transliterated to Latin, diacritics stripped, case and
 * punctuation folded, honorifics and legal-form suffixes dropped, and common spelling variants of frequent name
 * tokens unified (Mohammed / Muhammad / Mohamed ...). Idempotent: normalise(normalise(x)) == normalise(x).
 */
public final class NameNormalizer {
    private NameNormalizer() {}

    private static final Map<Character, String> TRANSLIT = buildTranslit();

    /** Tokens with no identifying value (titles, honorifics, legal forms). */
    static final Set<String> DROP = Set.of("mr", "mrs", "ms", "miss", "dr", "prof", "sir", "madam", "hon", "rev",
            "sheikh", "shaikh", "haji", "hajji", "general", "gen", "col", "colonel", "capt", "captain",
            "ltd", "limited", "llc", "inc", "incorporated", "corp", "corporation", "co", "company", "plc", "gmbh",
            "ag", "sa", "sarl", "bv", "nv", "jsc", "pjsc", "ojsc", "ooo", "oao", "zao", "srl", "spa", "pte", "pty",
            "the", "and", "of");

    /** Particles kept for scoring but too frequent to be used as index keys. */
    public static final Set<String> PARTICLES = Set.of("al", "el", "bin", "ibn", "bint", "abu", "abd", "van", "von",
            "der", "de", "da", "di", "la", "le", "del", "dos", "das", "ben", "mac", "mc");

    /** Spelling variants mapped to one canonical token. */
    static final Map<String, String> VARIANTS = Map.ofEntries(
            Map.entry("muhammad", "mohammed"), Map.entry("mohammad", "mohammed"), Map.entry("mohamed", "mohammed"),
            Map.entry("muhammed", "mohammed"), Map.entry("mohamad", "mohammed"), Map.entry("mohamod", "mohammed"),
            Map.entry("mehmet", "mohammed"), Map.entry("mahomed", "mohammed"),
            Map.entry("abdel", "abdul"), Map.entry("abdal", "abdul"), Map.entry("abdoul", "abdul"),
            Map.entry("usama", "osama"), Map.entry("oussama", "osama"),
            Map.entry("husain", "hussein"), Map.entry("hussain", "hussein"), Map.entry("husayn", "hussein"),
            Map.entry("hosein", "hussein"), Map.entry("hossein", "hussein"),
            Map.entry("yusuf", "youssef"), Map.entry("yousef", "youssef"), Map.entry("yusef", "youssef"),
            Map.entry("josef", "joseph"), Map.entry("iosif", "joseph"),
            Map.entry("aleksandr", "alexander"), Map.entry("alexandr", "alexander"), Map.entry("aleksander", "alexander"),
            Map.entry("sergei", "sergey"), Map.entry("sergej", "sergey"),
            Map.entry("yuri", "yury"), Map.entry("iurii", "yury"), Map.entry("yuriy", "yury"),
            Map.entry("dmitri", "dmitry"), Map.entry("dmitrii", "dmitry"), Map.entry("dmitriy", "dmitry"),
            Map.entry("ladin", "laden"));

    public static String normalise(String raw) {
        return String.join(" ", tokens(raw));
    }

    public static List<String> tokens(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            String t = TRANSLIT.get(Character.toLowerCase(c));
            sb.append(t != null ? t : String.valueOf(c));
        }
        String s = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace("ß", "ss").replace("æ", "ae").replace("ø", "o").replace("đ", "d").replace("ł", "l")
                .replace("Æ", "ae").replace("Ø", "o").replace("Đ", "d").replace("Ł", "l")
                .toLowerCase(Locale.ROOT)
                .replace("'", "").replace("’", "").replace("`", "");
        for (String tok : s.split("[^a-z0-9]+")) {
            if (tok.isEmpty() || DROP.contains(tok)) {
                continue;
            }
            out.add(VARIANTS.getOrDefault(tok, tok));
        }
        return out;
    }

    /** Alphanumeric upper-case form of identifiers (passports, registration numbers, IMO numbers). */
    public static String identifier(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static Map<Character, String> buildTranslit() {
        Map<Character, String> m = new java.util.HashMap<>();
        String[][] cyr = {{"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"}, {"ё", "e"},
                {"ж", "zh"}, {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"},
                {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "kh"},
                {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "shch"}, {"ъ", ""}, {"ы", "y"}, {"ь", ""}, {"э", "e"},
                {"ю", "yu"}, {"я", "ya"}, {"і", "i"}, {"ї", "yi"}, {"є", "ye"}, {"ґ", "g"}, {"ў", "u"}};
        for (String[] p : cyr) {
            m.put(p[0].charAt(0), p[1]);
        }
        String[][] greek = {{"α", "a"}, {"β", "v"}, {"γ", "g"}, {"δ", "d"}, {"ε", "e"}, {"ζ", "z"}, {"η", "i"},
                {"θ", "th"}, {"ι", "i"}, {"κ", "k"}, {"λ", "l"}, {"μ", "m"}, {"ν", "n"}, {"ξ", "x"}, {"ο", "o"},
                {"π", "p"}, {"ρ", "r"}, {"σ", "s"}, {"ς", "s"}, {"τ", "t"}, {"υ", "y"}, {"φ", "f"}, {"χ", "ch"},
                {"ψ", "ps"}, {"ω", "o"}, {"ά", "a"}, {"έ", "e"}, {"ή", "i"}, {"ί", "i"}, {"ό", "o"}, {"ύ", "y"},
                {"ώ", "o"}};
        for (String[] p : greek) {
            m.put(p[0].charAt(0), p[1]);
        }
        return Map.copyOf(m);
    }
}
