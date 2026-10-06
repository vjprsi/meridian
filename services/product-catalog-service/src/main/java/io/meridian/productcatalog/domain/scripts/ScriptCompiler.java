package io.meridian.productcatalog.domain.scripts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic static "compilation" of a product script to WASM module metadata. No WASM toolchain or runtime is
 * involved: the module identity is the SHA-256 of (compiler id, language, entrypoint, normalised source), so the
 * same source always yields the same {@code wasm_sha256}; any later source change is detected by recomputing it.
 *
 * <p>Static checks: source present and within size limits, the entrypoint is exported, no capabilities that break
 * sandbox determinism (filesystem, network, processes, threads, clocks, randomness), resource budgets within
 * platform limits. The test step validates inputs against the hook's input contract and the resource budget.
 */
public final class ScriptCompiler {
    public static final String COMPILER = "meridian-wasm-static/1.0";
    public static final int MAX_SOURCE_BYTES = 256 * 1024;
    public static final long MAX_FUEL = 1_000_000_000L;
    public static final int MAX_MEMORY_MB = 256;
    public static final int MAX_TIMEOUT_MS = 1_000;

    private ScriptCompiler() {}

    public record Check(String name, boolean passed, String message) {}

    public record CompileReport(boolean passed, String sourceSha256, String moduleSha256, long moduleSizeBytes,
                                List<Check> checks, List<String> errors, List<String> warnings) {}

    public record TestReport(boolean passed, String moduleSha256, String inputSha256, long estimatedFuel,
                             List<Check> checks, List<String> errors) {}

    /** Forbidden capabilities per language (sandbox determinism). */
    static final Map<String, List<String>> FORBIDDEN = Map.of(
            "RUST", List.of("std::fs", "std::net", "std::process", "std::thread", "std::time::SystemTime",
                    "std::time::Instant", "rand::", "std::env", "unsafe "),
            "TINYGO", List.of("\"os\"", "\"net", "\"os/exec\"", "time.Now", "math/rand", "\"syscall\"", "go func"),
            "ASSEMBLYSCRIPT", List.of("Date.now", "Math.random", "process.", "WASI", "fetch("),
            "TYPESCRIPT", List.of("Date.now", "Math.random", "process.", "require(", "fetch(", "XMLHttpRequest", "eval("));

    /** Float types are flagged (money must be integer minor units or decimal strings in scripts). */
    static final Map<String, Pattern> FLOATS = Map.of(
            "RUST", Pattern.compile("\\bf(32|64)\\b"),
            "TINYGO", Pattern.compile("\\bfloat(32|64)\\b"),
            "ASSEMBLYSCRIPT", Pattern.compile("\\bf(32|64)\\b"),
            "TYPESCRIPT", Pattern.compile("\\bparseFloat\\b"));

    /** Required input fields per hook. */
    public static final Map<String, Set<String>> HOOK_INPUTS = Map.of(
            "PRE_POSTING", Set.of("amount", "currency", "direction"),
            "POST_POSTING", Set.of("amount", "currency", "direction"),
            "ACCRUAL", Set.of("balance", "rate", "days"),
            "FEE_CALCULATION", Set.of("amount", "fee_code"),
            "SCHEDULE_GENERATION", Set.of("principal", "term_months", "rate"),
            "ELIGIBILITY", Set.of("applicant"),
            "END_OF_DAY", Set.of("balance", "date"),
            "END_OF_MONTH", Set.of("balance", "date"),
            "ON_MATURITY", Set.of("balance", "maturity_date"),
            "ON_EVENT", Set.of("event_type"));

    public static CompileReport compile(String language, String source, String entrypoint, long maxFuel,
                                        int maxMemoryMb, int timeoutMs) {
        List<Check> checks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String src = source == null ? "" : source;
        String normalised = normalise(src);
        byte[] bytes = src.getBytes(StandardCharsets.UTF_8);

        check(checks, errors, "source_present", !normalised.isBlank(), "source_code is required");
        check(checks, errors, "source_size", bytes.length <= MAX_SOURCE_BYTES,
                "source is " + bytes.length + " bytes; limit " + MAX_SOURCE_BYTES);
        String entry = entrypoint == null || entrypoint.isBlank() ? "run" : entrypoint;
        check(checks, errors, "entrypoint_name", entry.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"),
                "entrypoint must be an identifier");
        check(checks, errors, "entrypoint_exported", exportsEntrypoint(language, normalised, entry),
                "entrypoint '" + entry + "' is not exported for " + language);
        List<String> found = new ArrayList<>();
        for (String f : FORBIDDEN.getOrDefault(language, List.of())) {
            if (normalised.contains(f)) {
                found.add(f.trim());
            }
        }
        check(checks, errors, "sandbox_capabilities", found.isEmpty(), "forbidden capabilities used: " + found);
        Pattern floats = FLOATS.get(language);
        if (floats != null && floats.matcher(normalised).find()) {
            warnings.add("floating-point types used; money should be handled as integer minor units or decimal strings");
        }
        check(checks, errors, "fuel_budget", maxFuel >= 1 && maxFuel <= MAX_FUEL, "max_fuel must be 1.." + MAX_FUEL);
        check(checks, errors, "memory_budget", maxMemoryMb >= 1 && maxMemoryMb <= MAX_MEMORY_MB,
                "max_memory_mb must be 1.." + MAX_MEMORY_MB);
        check(checks, errors, "timeout_budget", timeoutMs >= 1 && timeoutMs <= MAX_TIMEOUT_MS,
                "timeout_ms must be 1.." + MAX_TIMEOUT_MS);
        check(checks, errors, "balanced_delimiters", balanced(normalised), "unbalanced brackets or braces");

        String sourceSha = sha256(src);
        String moduleSha = sha256(COMPILER + "\n" + language + "\n" + entry + "\n" + normalised);
        long size = 8 + bytes.length / 2 + 64L * entry.length();
        boolean passed = errors.isEmpty();
        return new CompileReport(passed, sourceSha, passed ? moduleSha : null, passed ? size : 0, List.copyOf(checks),
                List.copyOf(errors), List.copyOf(warnings));
    }

    /** Module hash the source should currently compile to (for integrity checks after compile). */
    public static String moduleSha(String language, String source, String entrypoint) {
        String entry = entrypoint == null || entrypoint.isBlank() ? "run" : entrypoint;
        return sha256(COMPILER + "\n" + language + "\n" + entry + "\n" + normalise(source == null ? "" : source));
    }

    /** Validates a test invocation: inputs match the hook contract and the estimated fuel fits the budget. */
    public static TestReport test(String hook, String moduleSha, String language, String source, String entrypoint,
                                  long maxFuel, Map<String, Object> inputs, String canonicalInputs) {
        List<Check> checks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String expected = moduleSha(language, source, entrypoint);
        check(checks, errors, "module_integrity", expected.equals(moduleSha),
                "source changed since the last compile; compile again");
        Set<String> required = HOOK_INPUTS.getOrDefault(hook, Set.of());
        List<String> missing = required.stream().filter(k -> inputs == null || !inputs.containsKey(k)).sorted().toList();
        check(checks, errors, "hook_input_contract", missing.isEmpty(), "missing inputs for " + hook + ": " + missing);
        long fuel = 1_000L + (source == null ? 0 : normalise(source).length()) * 10L + canonicalInputs.length() * 4L;
        check(checks, errors, "fuel_estimate", fuel <= maxFuel, "estimated fuel " + fuel + " exceeds max_fuel " + maxFuel);
        return new TestReport(errors.isEmpty(), moduleSha, sha256(canonicalInputs), fuel, List.copyOf(checks),
                List.copyOf(errors));
    }

    /** Report as plain maps (stored in {@code validation_report}). */
    public static Map<String, Object> toMap(CompileReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("compiler", COMPILER);
        m.put("passed", r.passed());
        m.put("source_sha256", r.sourceSha256());
        if (r.moduleSha256() != null) {
            m.put("module_sha256", r.moduleSha256());
            m.put("module_size_bytes", r.moduleSizeBytes());
        }
        m.put("checks", r.checks().stream().map(ScriptCompiler::check).toList());
        m.put("errors", r.errors());
        m.put("warnings", r.warnings());
        return m;
    }

    public static Map<String, Object> toMap(TestReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passed", r.passed());
        m.put("module_sha256", r.moduleSha256());
        m.put("input_sha256", r.inputSha256());
        m.put("estimated_fuel", r.estimatedFuel());
        m.put("checks", r.checks().stream().map(ScriptCompiler::check).toList());
        m.put("errors", r.errors());
        m.put("execution", "static-validation");
        return m;
    }

    private static Map<String, Object> check(Check c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("passed", c.passed());
        if (!c.passed()) {
            m.put("message", c.message());
        }
        return m;
    }

    static boolean exportsEntrypoint(String language, String src, String entry) {
        String e = Pattern.quote(entry);
        Pattern p = switch (language) {
            case "RUST" -> Pattern.compile("pub\\s+(extern\\s+\"C\"\\s+)?fn\\s+" + e + "\\s*\\(");
            case "TINYGO" -> Pattern.compile("//export\\s+" + e + "\\b|//go:wasmexport\\s+" + e + "\\b");
            case "ASSEMBLYSCRIPT", "TYPESCRIPT" -> Pattern.compile("export\\s+(async\\s+)?function\\s+" + e + "\\s*\\(");
            default -> null;
        };
        return p != null && p.matcher(src).find();
    }

    /** Line endings unified, trailing whitespace stripped, so formatting-only differences keep the same module. */
    static String normalise(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            sb.append(line.stripTrailing()).append('\n');
        }
        return sb.toString().strip();
    }

    static boolean balanced(String src) {
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        boolean inString = false;
        char prev = 0;
        for (char c : src.toCharArray()) {
            if (c == '"' && prev != '\\') {
                inString = !inString;
            } else if (!inString) {
                switch (c) {
                    case '(' -> paren++;
                    case ')' -> paren--;
                    case '{' -> brace++;
                    case '}' -> brace--;
                    case '[' -> bracket++;
                    case ']' -> bracket--;
                    default -> { }
                }
                if (paren < 0 || brace < 0 || bracket < 0) {
                    return false;
                }
            }
            prev = c;
        }
        return paren == 0 && brace == 0 && bracket == 0 && !inString;
    }

    private static void check(List<Check> checks, List<String> errors, String name, boolean ok, String message) {
        checks.add(new Check(name, ok, message));
        if (!ok) {
            errors.add(message);
        }
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
