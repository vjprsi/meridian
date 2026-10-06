package io.meridian.tenant.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform module catalogue and the dependencies between modules. */
public final class Modules {
    private Modules() {}

    public static final List<String> ALL = List.of("LEDGER", "DEPOSITS", "PAYMENTS", "WALLETS", "CARDS", "LENDING",
            "RISK", "REPORTING", "OPEN_BANKING", "AI", "ANALYTICS");

    /** Module -> modules that must be enabled before it can be enabled. */
    public static final Map<String, Set<String>> REQUIRES = Map.of(
            "DEPOSITS", Set.of("LEDGER"),
            "PAYMENTS", Set.of("LEDGER"),
            "WALLETS", Set.of("LEDGER"),
            "CARDS", Set.of("LEDGER", "DEPOSITS"),
            "LENDING", Set.of("LEDGER"),
            "OPEN_BANKING", Set.of("PAYMENTS"),
            "AI", Set.of("ANALYTICS"));

    public static Set<String> requires(String module) {
        return REQUIRES.getOrDefault(module, Set.of());
    }

    /** Modules that depend on {@code module} (directly). */
    public static List<String> dependents(String module) {
        return REQUIRES.entrySet().stream().filter(e -> e.getValue().contains(module)).map(Map.Entry::getKey)
                .sorted().toList();
    }

    /** Missing prerequisites of {@code module} given the set of currently enabled modules. */
    public static List<String> missingPrerequisites(String module, Set<String> enabled) {
        return requires(module).stream().filter(m -> !enabled.contains(m)).sorted().toList();
    }

    /** Enabled modules that would be left without their prerequisite if {@code module} were disabled. */
    public static List<String> blockingDependents(String module, Set<String> enabled) {
        return dependents(module).stream().filter(enabled::contains).toList();
    }
}
