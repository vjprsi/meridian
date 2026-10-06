package io.meridian.pricingrules.domain;

import io.meridian.pricingrules.domain.DecisionTableModel.Aggregation;
import io.meridian.pricingrules.domain.DecisionTableModel.InputColumn;
import io.meridian.pricingrules.domain.DecisionTableModel.OutputColumn;
import io.meridian.pricingrules.domain.DecisionTableModel.Rule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evaluates a {@link DecisionTableModel} against inputs, applying the DMN hit policy. Pure and thread-safe. */
public final class DecisionEngine {
    private DecisionEngine() {}

    /**
     * @param matchedRules 1-based numbers of the rules whose input entries all matched (in rule order)
     * @param selectedRules rules whose outputs make up the result (after the hit policy)
     * @param outputs the decision result: column -> value (single-hit) or column -> list / aggregate (multi-hit)
     * @param results one output map per selected rule (explainability)
     * @param defaulted true when no rule matched and output defaults were used
     */
    public record Outcome(List<Integer> matchedRules, List<Integer> selectedRules, Map<String, Object> outputs,
                         List<Map<String, Object>> results, boolean defaulted) {}

    public static Outcome evaluate(DecisionTableModel table, Map<String, Object> rawInputs) {
        Map<String, Object> inputs = rawInputs == null ? Map.of() : rawInputs;
        List<Object> values = new ArrayList<>();
        for (InputColumn c : table.inputs()) {
            values.add(c.type().coerce(c.name(), lookup(inputs, c.path())));
        }
        List<Rule> matched = new ArrayList<>();
        for (Rule r : table.rules()) {
            boolean all = true;
            for (int i = 0; i < r.inputEntries().size() && all; i++) {
                all = r.inputEntries().get(i).matches(values.get(i));
            }
            if (all) {
                matched.add(r);
            }
        }
        List<Integer> matchedNos = matched.stream().map(Rule::number).toList();
        if (matched.isEmpty()) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            for (OutputColumn o : table.outputs()) {
                if (o.defaultValue() != null) {
                    defaults.put(o.name(), o.defaultValue());
                }
            }
            return new Outcome(List.of(), List.of(), defaults, List.of(), true);
        }
        List<Rule> selected = switch (table.hitPolicy()) {
            case UNIQUE -> {
                if (matched.size() > 1) {
                    throw new DecisionTableException("hit_policy_violation",
                            "Hit policy UNIQUE but rules " + matchedNos + " all matched");
                }
                yield matched;
            }
            case FIRST -> List.of(matched.getFirst());
            case PRIORITY -> List.of(matched.stream().min(Comparator.comparingInt(Rule::priority)
                    .thenComparingInt(Rule::number)).orElseThrow());
            case ANY -> {
                List<Object> first = matched.getFirst().outputValues();
                for (Rule r : matched) {
                    if (!sameValues(first, r.outputValues())) {
                        throw new DecisionTableException("hit_policy_violation",
                                "Hit policy ANY but matching rules " + matchedNos + " give different outputs");
                    }
                }
                yield List.of(matched.getFirst());
            }
            case COLLECT, RULE_ORDER -> matched;
            case OUTPUT_ORDER -> matched.stream()
                    .sorted(Comparator.comparingInt(Rule::priority).thenComparingInt(Rule::number)).toList();
        };
        List<Map<String, Object>> results = new ArrayList<>();
        for (Rule r : selected) {
            results.add(rowOutputs(table, r));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        boolean multi = switch (table.hitPolicy()) {
            case COLLECT, RULE_ORDER, OUTPUT_ORDER -> true;
            default -> false;
        };
        if (!multi) {
            outputs.putAll(results.getFirst());
        } else if (table.aggregation() == Aggregation.NONE) {
            for (OutputColumn o : table.outputs()) {
                outputs.put(o.name(), results.stream().map(m -> m.get(o.name())).toList());
            }
        } else {
            for (OutputColumn o : table.outputs()) {
                outputs.put(o.name(), aggregate(table.aggregation(), results.stream().map(m -> m.get(o.name())).toList()));
            }
        }
        return new Outcome(matchedNos, selected.stream().map(Rule::number).toList(), outputs, results, false);
    }

    static Object aggregate(Aggregation agg, List<Object> values) {
        List<BigDecimal> nums = values.stream().filter(Objects::nonNull).map(ValueType::toNumber).toList();
        return switch (agg) {
            case COUNT -> BigDecimal.valueOf(values.stream().filter(Objects::nonNull).count());
            case SUM -> nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case MIN -> nums.stream().min(Comparator.naturalOrder()).orElse(null);
            case MAX -> nums.stream().max(Comparator.naturalOrder()).orElse(null);
            case NONE -> values;
        };
    }

    private static Map<String, Object> rowOutputs(DecisionTableModel t, Rule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < t.outputs().size(); i++) {
            m.put(t.outputs().get(i).name(), r.outputValues().get(i));
        }
        return m;
    }

    private static boolean sameValues(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Object x = a.get(i);
            Object y = b.get(i);
            if (x instanceof BigDecimal bx && y instanceof BigDecimal by) {
                if (bx.compareTo(by) != 0) {
                    return false;
                }
            } else if (!Objects.equals(x, y)) {
                return false;
            }
        }
        return true;
    }

    /** Reads a value by name or dotted path ({@code applicant.age}) from nested maps. */
    @SuppressWarnings("unchecked")
    static Object lookup(Map<String, Object> inputs, String path) {
        if (inputs.containsKey(path)) {
            return inputs.get(path);
        }
        Object cur = inputs;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = ((Map<String, Object>) m).get(part);
        }
        return cur;
    }

    /** Compares an actual output with an expected value (typed: numbers by value, lists element-wise). */
    public static boolean outputEquals(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == null && expected == null;
        }
        if (actual instanceof List<?> la && expected instanceof List<?> le) {
            if (la.size() != le.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!outputEquals(la.get(i), le.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (actual instanceof BigDecimal a) {
            try {
                return a.compareTo(ValueType.toNumber(expected)) == 0;
            } catch (RuntimeException e) {
                return false;
            }
        }
        if (actual instanceof java.time.LocalDate d) {
            return d.toString().equals(expected.toString());
        }
        if (actual instanceof Boolean b) {
            return b.toString().equalsIgnoreCase(expected.toString());
        }
        return actual.toString().equals(expected.toString());
    }
}
