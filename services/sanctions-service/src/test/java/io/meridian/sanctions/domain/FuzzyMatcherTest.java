package io.meridian.sanctions.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class FuzzyMatcherTest {

    static NameMatcher.Entry entry(String primary, String dob, Set<String> countries, Set<String> ids, String... aliases) {
        List<String> names = new ArrayList<>();
        names.add(primary);
        names.addAll(List.of(aliases));
        return new NameMatcher.Entry(UUID.randomUUID(), UUID.randomUUID(), "TEST", "OFAC_SDN", "INDIVIDUAL", primary,
                names, names.stream().map(NameNormalizer::tokens).toList(), dob, countries, ids, null);
    }

    static double score(String name, String dob, Set<String> countries, NameMatcher.Entry e) {
        NameMatcher.Subject s = new NameMatcher.Subject(name, dob, countries, Set.of());
        return NameMatcher.score(s, s.tokens(), e).score();
    }

    @Test
    void normalisationTransliteratesFoldsAndUnifiesVariants() {
        assertThat(NameNormalizer.normalise("Владимир Владимирович ПУТИН")).isEqualTo("vladimir vladimirovich putin");
        assertThat(NameNormalizer.normalise("José  Müller-Lüdenscheidt")).isEqualTo("jose muller ludenscheidt");
        assertThat(NameNormalizer.normalise("Dr. Muhammad AL-Hussain")).isEqualTo("mohammed al hussein");
        assertThat(NameNormalizer.normalise("ACME Trading Co., Ltd.")).isEqualTo("acme trading");
        assertThat(NameNormalizer.normalise("Αλέξανδρος")).isEqualTo("alexandros");
        assertThat(NameNormalizer.identifier("ab-123 456")).isEqualTo("AB123456");
    }

    @Test
    void knownVariantsMatchAndUnrelatedNamesDoNot() {
        var bl = entry("Osama bin Laden", "1957-03-10", Set.of("SA"), Set.of(), "Usama bin Muhammad bin Awad BIN LADIN");
        assertThat(score("Usama Bin Ladin", null, Set.of(), bl)).isGreaterThanOrEqualTo(0.95);
        assertThat(score("Osama B. Laden", null, Set.of(), bl)).isGreaterThan(0.85);
        assertThat(score("Jane Margaret Doe", null, Set.of(), bl)).isLessThan(0.7);

        var smith = entry("John Smith", null, Set.of(), Set.of());
        assertThat(score("Jon Smyth", null, Set.of(), smith)).isGreaterThan(0.85);
        assertThat(score("Smith, John", null, Set.of(), smith)).isGreaterThanOrEqualTo(0.99);
        assertThat(score("John", null, Set.of(), smith)).isLessThan(0.85); // one shared token is not a hit
    }

    @Test
    void dateOfBirthAndCountryAdjustTheScore() {
        var e = entry("Ivan Petrov", "1970-05-01", Set.of("RU"), Set.of());
        double base = score("Ivan Petrov", null, Set.of(), e);
        assertThat(score("Ivan Petrov", "1985-01-01", Set.of(), e)).isLessThan(base);
        assertThat(score("Ivan Petrov", "1985-01-01", Set.of(), e)).isLessThan(0.9);
        var fuzzy = entry("Ivan Petrov", "1970-05-01", Set.of("RU"), Set.of());
        double typo = score("Ivan Petrof", null, Set.of(), fuzzy);
        assertThat(score("Ivan Petrof", "1970-05-01", Set.of("RU"), fuzzy)).isGreaterThan(typo);
        assertThat(score("Ivan Petrof", null, Set.of("BR"), fuzzy)).isLessThan(typo);
    }

    @Test
    void exactIdentifierWinsRegardlessOfName() {
        var vessel = entry("Ocean Star", null, Set.of(), Set.of("IMO9123456"));
        NameMatcher.Subject s = new NameMatcher.Subject("Completely Different", null, Set.of(), Set.of("IMO9123456"));
        NameMatcher.Match m = NameMatcher.score(s, s.tokens(), vessel);
        assertThat(m.score()).isEqualTo(1.0);
        assertThat(m.algorithm()).isEqualTo("EXACT_ID");
    }

    @Test
    void indexFindsMisspelledAndTransliteratedCandidates() {
        var bl = entry("Osama bin Laden", null, Set.of(), Set.of());
        var other = entry("Maria Garcia", null, Set.of(), Set.of());
        EntryIndex idx = new EntryIndex(List.of(bl, other));
        assertThat(idx.candidates(NameNormalizer.tokens("Usama bin Ladin"), List.of())).contains(bl).doesNotContain(other);
        assertThat(idx.candidates(NameNormalizer.tokens("Осама Ладен"), List.of())).contains(bl);
        assertThat(idx.candidates(NameNormalizer.tokens("bin al"), List.of())).isEmpty(); // particles are not keys
    }

    @Test
    void csvParsingHandlesQuotesAndMultiValues() {
        String csv = """
                external_id,entry_type,primary_name,aliases,date_of_birth,nationality,countries,programs
                1,INDIVIDUAL,"Doe, John","JD;Johnny ""J"" Doe",1970-01-01,us,US;CA,SDGT
                2,SHIP,Bad Type,,,,,
                3,ENTITY,,,,,,
                """;
        var rows = ListFileParser.csv(csv);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).error()).isNull();
        assertThat(rows.get(0).fields().get("primary_name")).isEqualTo("Doe, John");
        assertThat(rows.get(0).fields().get("aliases")).isEqualTo(List.of("JD", "Johnny \"J\" Doe"));
        assertThat(rows.get(0).fields().get("nationality")).isEqualTo("US");
        assertThat(rows.get(0).fields().get("countries")).isEqualTo(List.of("US", "CA"));
        assertThat(rows.get(1).error()).contains("entry_type");
        assertThat(rows.get(2).error()).contains("primary_name");
    }

    // ------------------------------------------------------------------ properties

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz ").ofMinLength(1).ofMaxLength(30)
                .filter(s -> !s.isBlank());
    }

    @Property(tries = 400)
    void jaroWinklerIsBoundedSymmetricAndReflexive(@ForAll("names") String a, @ForAll("names") String b) {
        double ab = Similarity.jaroWinkler(a, b);
        assertThat(ab).isBetween(0.0, 1.0);
        assertThat(Similarity.jaroWinkler(b, a)).isCloseTo(ab, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(Similarity.jaroWinkler(a, a)).isEqualTo(1.0);
    }

    @Property(tries = 300)
    void tokenSetRatioIgnoresWordOrder(@ForAll("names") String a, @ForAll("names") String b) {
        List<String> ta = NameNormalizer.tokens(a);
        List<String> tb = NameNormalizer.tokens(b);
        List<String> shuffled = new ArrayList<>(ta);
        Collections.reverse(shuffled);
        assertThat(Similarity.tokenSetRatio(shuffled, tb)).isCloseTo(Similarity.tokenSetRatio(ta, tb),
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(Similarity.tokenSetRatio(ta, tb)).isBetween(0.0, 1.0);
        assertThat(Similarity.tokenSetRatio(ta, tb)).isCloseTo(Similarity.tokenSetRatio(tb, ta),
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Property(tries = 300)
    void normalisationIsIdempotent(@ForAll String raw) {
        String once = NameNormalizer.normalise(raw);
        assertThat(NameNormalizer.normalise(once)).isEqualTo(once);
        assertThat(once).matches("[a-z0-9 ]*");
    }

    @Property(tries = 300)
    void aNameAlwaysMatchesItselfPerfectly(@ForAll("names") String name) {
        var e = entry(name, null, Set.of(), Set.of());
        if (!NameNormalizer.tokens(name).isEmpty()) {
            assertThat(score(name, null, Set.of(), e)).isEqualTo(1.0);
        }
    }

    @Property(tries = 200)
    void scoreStaysWithinBounds(@ForAll("names") String a, @ForAll("names") String b) {
        var e = entry(b, "1980-01-01", Set.of("GB"), Set.of());
        assertThat(score(a, "1950-02-02", Set.of("FR"), e)).isBetween(0.0, 1.0);
        assertThat(score(a, "1980-01-01", Set.of("GB"), e)).isBetween(0.0, 1.0);
    }
}
