package io.meridian.reconciliation.domain.matching;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * One rule of a {@link RuleSet}. Types:
 * <ul>
 *   <li>{@code EXACT_KEY}: a key ({@link #KEYS}) of the line equals a key of the internal item (UETR to UETR;
 *       end-to-end / instruction id to the same field or the internal reference; bank reference to the reference);</li>
 *   <li>{@code REFERENCE_EXTRACT}: references extracted from the remittance text (tenant regex {@code patterns},
 *       group 1 or the whole match, run under SafeRegex limits, plus normalised alphanumeric n-grams containing a
 *       digit) equal a reference of the internal item;</li>
 *   <li>{@code FUZZY}: no shared reference: amount (and fee / FX tolerance), value date window and counterparty name
 *       similarity combine into a confidence;</li>
 *   <li>{@code ONE_TO_MANY} / {@code MANY_TO_ONE}: one line against several internal items (batch / bulk payment,
 *       card settlement net of fees) or several lines against one item, grouped by a shared key or, without one,
 *       found by bounded subset-sum (suggestion unless {@code autoWithoutKey});</li>
 *   <li>{@code REVERSAL_PAIR}: a reversal line with its original (same key and amount, opposite direction), and an
 *       internal reversal with its original, so neither raises a break.</li>
 * </ul>
 * Amount fit: exact; within {@code amountTolerance} / {@code amountTolerancePct}; explained by stated charges or the
 * stated original amount; a deducted fee up to {@code feeTolerance} / {@code feeTolerancePct}; an FX difference up
 * to {@code fxTolerancePct}. {@code autoThreshold} overrides the rule set's threshold for this rule;
 * {@code minMargin} is the lead the best candidate needs over the runner-up (on both sides) to auto-match.
 */
public record MatchRule(String code, Type type, List<String> keys, List<String> patterns,
                        BigDecimal amountTolerance, BigDecimal amountTolerancePct, BigDecimal feeTolerance,
                        BigDecimal feeTolerancePct, BigDecimal fxTolerancePct, int dateWindowDays,
                        double nameThreshold, Double autoThreshold, double minMargin, int maxGroupSize,
                        int maxCandidates, long maxSearchNodes, boolean autoWithoutKey, boolean requireReversalFlag,
                        boolean enabled) {

    public enum Type { EXACT_KEY, REFERENCE_EXTRACT, FUZZY, ONE_TO_MANY, MANY_TO_ONE, REVERSAL_PAIR }

    /** Line keys a rule may use; {@code remittance} (references in the remittance text) only for grouping rules. */
    public static final Set<String> KEYS = Set.of("uetr", "end_to_end_id", "instruction_id", "bank_ref", "batch_ref",
            "remittance");
    public static final int MAX_DATE_WINDOW = 31;
    public static final int MAX_GROUP = 500;
    public static final int MAX_CANDIDATES = 60;
    public static final long MAX_NODES = 2_000_000;

    public MatchRule {
        keys = keys == null ? List.of() : List.copyOf(keys);
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        amountTolerance = nz(amountTolerance);
        amountTolerancePct = nz(amountTolerancePct);
        feeTolerance = nz(feeTolerance);
        feeTolerancePct = nz(feeTolerancePct);
        fxTolerancePct = nz(fxTolerancePct);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static Builder builder(String code, Type type) {
        return new Builder(code, type);
    }

    public Builder toBuilder() {
        Builder b = new Builder(code, type);
        b.keys = keys;
        b.patterns = patterns;
        b.amountTolerance = amountTolerance;
        b.amountTolerancePct = amountTolerancePct;
        b.feeTolerance = feeTolerance;
        b.feeTolerancePct = feeTolerancePct;
        b.fxTolerancePct = fxTolerancePct;
        b.dateWindowDays = dateWindowDays;
        b.nameThreshold = nameThreshold;
        b.autoThreshold = autoThreshold;
        b.minMargin = minMargin;
        b.maxGroupSize = maxGroupSize;
        b.maxCandidates = maxCandidates;
        b.maxSearchNodes = maxSearchNodes;
        b.autoWithoutKey = autoWithoutKey;
        b.requireReversalFlag = requireReversalFlag;
        b.enabled = enabled;
        return b;
    }

    public static final class Builder {
        private final String code;
        private final Type type;
        private List<String> keys = List.of();
        private List<String> patterns = List.of();
        private BigDecimal amountTolerance = BigDecimal.ZERO;
        private BigDecimal amountTolerancePct = BigDecimal.ZERO;
        private BigDecimal feeTolerance = BigDecimal.ZERO;
        private BigDecimal feeTolerancePct = BigDecimal.ZERO;
        private BigDecimal fxTolerancePct = BigDecimal.ZERO;
        private int dateWindowDays = 3;
        private double nameThreshold = 0.8;
        private Double autoThreshold;
        private double minMargin = 0.05;
        private int maxGroupSize = 50;
        private int maxCandidates = 25;
        private long maxSearchNodes = 200_000;
        private boolean autoWithoutKey;
        private boolean requireReversalFlag = true;
        private boolean enabled = true;

        private Builder(String code, Type type) {
            this.code = code;
            this.type = type;
        }

        public Builder keys(String... v) {
            keys = List.of(v);
            return this;
        }

        public Builder keys(List<String> v) {
            keys = v;
            return this;
        }

        public Builder patterns(List<String> v) {
            patterns = v;
            return this;
        }

        public Builder amountTolerance(String abs, String pct) {
            amountTolerance = new BigDecimal(abs);
            amountTolerancePct = new BigDecimal(pct);
            return this;
        }

        public Builder amountTolerance(BigDecimal abs, BigDecimal pct) {
            amountTolerance = abs;
            amountTolerancePct = pct;
            return this;
        }

        public Builder fee(String abs, String pct) {
            feeTolerance = new BigDecimal(abs);
            feeTolerancePct = new BigDecimal(pct);
            return this;
        }

        public Builder fee(BigDecimal abs, BigDecimal pct) {
            feeTolerance = abs;
            feeTolerancePct = pct;
            return this;
        }

        public Builder fxPct(String v) {
            fxTolerancePct = new BigDecimal(v);
            return this;
        }

        public Builder fxPct(BigDecimal v) {
            fxTolerancePct = v;
            return this;
        }

        public Builder window(int days) {
            dateWindowDays = days;
            return this;
        }

        public Builder nameThreshold(double v) {
            nameThreshold = v;
            return this;
        }

        public Builder autoThreshold(Double v) {
            autoThreshold = v;
            return this;
        }

        public Builder minMargin(double v) {
            minMargin = v;
            return this;
        }

        public Builder group(int maxGroup, int maxCand, long maxNodes) {
            maxGroupSize = maxGroup;
            maxCandidates = maxCand;
            maxSearchNodes = maxNodes;
            return this;
        }

        public Builder autoWithoutKey(boolean v) {
            autoWithoutKey = v;
            return this;
        }

        public Builder requireReversalFlag(boolean v) {
            requireReversalFlag = v;
            return this;
        }

        public Builder enabled(boolean v) {
            enabled = v;
            return this;
        }

        public MatchRule build() {
            return new MatchRule(code, type, keys, patterns, amountTolerance, amountTolerancePct, feeTolerance,
                    feeTolerancePct, fxTolerancePct, dateWindowDays, nameThreshold, autoThreshold, minMargin,
                    maxGroupSize, maxCandidates, maxSearchNodes, autoWithoutKey, requireReversalFlag, enabled);
        }
    }
}
