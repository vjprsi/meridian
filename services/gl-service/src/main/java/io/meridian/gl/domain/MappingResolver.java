package io.meridian.gl.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the GL mapping rule for a posting: among rules that match and are effective on the as-of date the lowest
 * priority wins, then the most specific rule, then the most recent validity start, then the lowest id
 * (fully deterministic).
 */
public final class MappingResolver {
    public static final Comparator<MappingRule> ORDER = Comparator.comparingInt(MappingRule::priority)
            .thenComparing(Comparator.comparingInt(MappingRule::specificity).reversed())
            .thenComparing(MappingRule::validFrom, Comparator.reverseOrder())
            .thenComparing(MappingRule::id);

    private MappingResolver() {}

    public static List<MappingRule> candidates(List<MappingRule> rules, MappingRule.Criteria c) {
        return rules.stream().filter(r -> r.matches(c)).sorted(ORDER).toList();
    }

    public static Optional<MappingRule> resolve(List<MappingRule> rules, MappingRule.Criteria c) {
        return candidates(rules, c).stream().findFirst();
    }
}
