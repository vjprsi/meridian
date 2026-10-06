package io.meridian.pricingrules.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed, validated decision table (DMN decisionTable subset).
 *
 * <p>Stored JSON shape (JSON arrays; the COLLECT aggregation is a separate column):
 * <pre>
 * inputs:  [{"name": "credit_score", "type": "number", "expression": "applicant.score"}]
 * outputs: [{"name": "rate", "type": "number", "default": "0.12"}]
 * rules:   [{"inputs": ["[700..850]", "-"], "outputs": ["0.05"], "annotation": "prime", "priority": 1}]
 * </pre>
 * Rule inputs/outputs may also be objects keyed by column name (missing input = "-").
 */
public record DecisionTableModel(HitPolicy hitPolicy, Aggregation aggregation, List<InputColumn> inputs,
                                 List<OutputColumn> outputs, List<Rule> rules) {

    public enum HitPolicy { UNIQUE, FIRST, PRIORITY, ANY, COLLECT, RULE_ORDER, OUTPUT_ORDER }

    public enum Aggregation { NONE, SUM, MIN, MAX, COUNT }

    public record InputColumn(String name, ValueType type, String expression) {
        /** Path used to read the value from the evaluation inputs. */
        public String path() {
            return expression == null || expression.isBlank() ? name : expression.trim();
        }
    }

    public record OutputColumn(String name, ValueType type, Object defaultValue) {}

    /** A rule row; {@code number} is 1-based as in DMN tooling. */
    public record Rule(int number, List<UnaryTest> inputEntries, List<Object> outputValues, List<String> outputSources,
                       String annotation, int priority) {}

    /** Parses the stored JSON (as plain Java maps/lists) and reports every problem at once. */
    public static DecisionTableModel parse(String hitPolicy, String aggregation, List<?> inputs, List<?> outputs,
                                           List<?> rules) {
        List<DecisionTableException.Violation> v = new ArrayList<>();
        HitPolicy hp = HitPolicy.FIRST;
        if (hitPolicy != null) {
            try {
                hp = HitPolicy.valueOf(hitPolicy.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                v.add(new DecisionTableException.Violation("hit_policy", "invalid_hit_policy", "Unknown hit policy " + hitPolicy));
            }
        }
        Aggregation agg = Aggregation.NONE;
        String aggText = aggregation;
        if (aggText != null && !aggText.isBlank()) {
            try {
                agg = Aggregation.valueOf(aggText.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                v.add(new DecisionTableException.Violation("aggregation", "invalid_aggregation", "Unknown aggregation " + aggText));
            }
        }
        if (agg != Aggregation.NONE && hp != HitPolicy.COLLECT) {
            v.add(new DecisionTableException.Violation("aggregation", "invalid_aggregation",
                    "Aggregation is only allowed with hit policy COLLECT"));
        }

        List<InputColumn> in = new ArrayList<>();
        List<Object> inCols = columns(inputs, "inputs", v);
        for (int i = 0; i < inCols.size(); i++) {
            String f = "inputs[" + i + "]";
            if (!(inCols.get(i) instanceof Map<?, ?> c)) {
                v.add(new DecisionTableException.Violation(f, "invalid_column", "Column must be an object"));
                continue;
            }
            String name = str(c.get("name"));
            if (name == null || name.isBlank()) {
                v.add(new DecisionTableException.Violation(f + ".name", "required", "Input column name is required"));
                continue;
            }
            if (in.stream().anyMatch(x -> x.name().equals(name))) {
                v.add(new DecisionTableException.Violation(f + ".name", "duplicate_column", "Duplicate input column " + name));
            }
            ValueType t = type(c.get("type") != null ? c.get("type") : c.get("type_ref"), f + ".type", v);
            in.add(new InputColumn(name, t, str(c.get("expression"))));
        }

        List<OutputColumn> out = new ArrayList<>();
        List<Object> outCols = columns(outputs, "outputs", v);
        if (outCols.isEmpty()) {
            v.add(new DecisionTableException.Violation("outputs", "required", "At least one output column is required"));
        }
        for (int i = 0; i < outCols.size(); i++) {
            String f = "outputs[" + i + "]";
            if (!(outCols.get(i) instanceof Map<?, ?> c)) {
                v.add(new DecisionTableException.Violation(f, "invalid_column", "Column must be an object"));
                continue;
            }
            String name = str(c.get("name"));
            if (name == null || name.isBlank()) {
                v.add(new DecisionTableException.Violation(f + ".name", "required", "Output column name is required"));
                continue;
            }
            if (out.stream().anyMatch(x -> x.name().equals(name))) {
                v.add(new DecisionTableException.Violation(f + ".name", "duplicate_column", "Duplicate output column " + name));
            }
            ValueType t = type(c.get("type") != null ? c.get("type") : c.get("type_ref"), f + ".type", v);
            Object def = null;
            if (c.get("default") != null) {
                try {
                    def = t.coerce(name, c.get("default"));
                } catch (DecisionTableException e) {
                    v.add(new DecisionTableException.Violation(f + ".default", "invalid_default", e.getMessage()));
                }
            }
            out.add(new OutputColumn(name, t, def));
        }
        if ((agg == Aggregation.SUM || agg == Aggregation.MIN || agg == Aggregation.MAX)
                && out.stream().anyMatch(o -> o.type() != ValueType.NUMBER)) {
            v.add(new DecisionTableException.Violation("aggregation", "invalid_aggregation",
                    agg + " needs numeric output columns"));
        }

        List<Rule> parsed = new ArrayList<>();
        List<Object> rows = rows(rules, v);
        for (int r = 0; r < rows.size(); r++) {
            String f = "rules[" + r + "]";
            if (!(rows.get(r) instanceof Map<?, ?> row)) {
                v.add(new DecisionTableException.Violation(f, "invalid_rule", "Rule must be an object"));
                continue;
            }
            List<String> inTexts = entries(row.get("inputs") != null ? row.get("inputs") : row.get("input_entries"),
                    in.stream().map(InputColumn::name).toList(), "-", f + ".inputs", v);
            List<String> outTexts = entries(row.get("outputs") != null ? row.get("outputs") : row.get("output_entries"),
                    out.stream().map(OutputColumn::name).toList(), null, f + ".outputs", v);
            List<UnaryTest> tests = new ArrayList<>();
            for (int i = 0; i < inTexts.size() && i < in.size(); i++) {
                try {
                    tests.add(UnaryTest.parse(inTexts.get(i), in.get(i).type()));
                } catch (DecisionTableException e) {
                    v.add(new DecisionTableException.Violation(f + ".inputs[" + i + "]", e.code(), e.getMessage()));
                }
            }
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < outTexts.size() && i < out.size(); i++) {
                String text = outTexts.get(i);
                if (text == null) {
                    v.add(new DecisionTableException.Violation(f + ".outputs[" + i + "]", "required",
                            "Output entry for " + out.get(i).name() + " is required"));
                    continue;
                }
                try {
                    values.add(UnaryTest.literal(text, out.get(i).type()));
                } catch (DecisionTableException e) {
                    v.add(new DecisionTableException.Violation(f + ".outputs[" + i + "]", e.code(), e.getMessage()));
                }
            }
            int priority = r + 1;
            if (row.get("priority") != null) {
                try {
                    priority = ValueType.toNumber(row.get("priority")).intValueExact();
                } catch (RuntimeException e) {
                    v.add(new DecisionTableException.Violation(f + ".priority", "invalid_priority", "Priority must be an integer"));
                }
            }
            parsed.add(new Rule(r + 1, tests, values, outTexts, str(row.get("annotation")), priority));
        }
        if (!v.isEmpty()) {
            throw new DecisionTableException("invalid_decision_table", "The decision table definition is invalid", v);
        }
        return new DecisionTableModel(hp, agg, List.copyOf(in), List.copyOf(out), List.copyOf(parsed));
    }

    /** Back to the stored JSON shape (plain maps), e.g. after a DMN import. */
    public List<Object> inputsJson() {
        List<Object> cols = new ArrayList<>();
        for (InputColumn c : inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("type", c.type().typeRef());
            if (c.expression() != null && !c.expression().equals(c.name())) {
                m.put("expression", c.expression());
            }
            cols.add(m);
        }
        return cols;
    }

    public List<Object> outputsJson() {
        List<Object> cols = new ArrayList<>();
        for (OutputColumn c : outputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("type", c.type().typeRef());
            if (c.defaultValue() != null) {
                m.put("default", plain(c.defaultValue()));
            }
            cols.add(m);
        }
        return cols;
    }

    public List<Object> rulesJson() {
        List<Object> rows = new ArrayList<>();
        for (Rule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inputs", r.inputEntries().stream().map(UnaryTest::source).toList());
            m.put("outputs", r.outputSources());
            if (r.annotation() != null) {
                m.put("annotation", r.annotation());
            }
            if (r.priority() != r.number()) {
                m.put("priority", r.priority());
            }
            rows.add(m);
        }
        return rows;
    }

    static Object plain(Object v) {
        if (v instanceof java.math.BigDecimal b) {
            return b.toPlainString();
        }
        return v instanceof java.time.LocalDate d ? d.toString() : v;
    }

    private static List<Object> columns(List<?> cols, String field, List<DecisionTableException.Violation> v) {
        if (cols == null) {
            v.add(new DecisionTableException.Violation(field, "required", field + " is required"));
            return List.of();
        }
        return new ArrayList<>(cols);
    }

    private static List<Object> rows(List<?> rules, List<DecisionTableException.Violation> v) {
        if (rules == null) {
            v.add(new DecisionTableException.Violation("rules", "required", "rules is required"));
            return List.of();
        }
        return new ArrayList<>(rules);
    }

    private static List<String> entries(Object raw, List<String> columns, String missing, String field,
                                        List<DecisionTableException.Violation> v) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            if (l.size() != columns.size()) {
                v.add(new DecisionTableException.Violation(field, "column_count_mismatch",
                        "Expected " + columns.size() + " entries but got " + l.size()));
            }
            for (Object o : l) {
                out.add(o == null ? missing : entryText(o));
            }
        } else if (raw instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                if (!columns.contains(String.valueOf(k))) {
                    v.add(new DecisionTableException.Violation(field + "." + k, "unknown_column", "Unknown column " + k));
                }
            }
            for (String c : columns) {
                Object o = m.get(c);
                out.add(o == null ? missing : entryText(o));
            }
        } else if (raw == null && columns.isEmpty()) {
            return out;
        } else if (raw == null && missing != null) {
            columns.forEach(c -> out.add(missing));
        } else {
            v.add(new DecisionTableException.Violation(field, "invalid_entries", "Entries must be an array or an object"));
        }
        return out;
    }

    /** JSON scalars become entry text: strings verbatim, numbers plain, booleans as true/false. */
    private static String entryText(Object o) {
        if (o instanceof java.math.BigDecimal b) {
            return b.toPlainString();
        }
        return o.toString();
    }

    private static ValueType type(Object raw, String field, List<DecisionTableException.Violation> v) {
        try {
            return ValueType.of(raw == null ? null : raw.toString());
        } catch (DecisionTableException e) {
            v.add(new DecisionTableException.Violation(field, e.code(), e.getMessage()));
            return ValueType.ANY;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
