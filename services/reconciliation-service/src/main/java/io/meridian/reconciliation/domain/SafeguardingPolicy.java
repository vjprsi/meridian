package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Pure trust / e-money safeguarding stock-reconciliation policy (WE-RECON-1, decision D-ED-12). Every business day
 * it proves that the funds in the trust / safeguarding bank account <b>A</b> fully back the outstanding e-money
 * liabilities <b>B + C + D + E</b>: the regulatory 100%-backing check (Kenya daily, MAS PSA, FCA CASS 15).
 *
 * <pre>
 *   result = A - (B + C + D + E)
 * </pre>
 *
 * <ul>
 *   <li><b>A</b> = trust / safeguarding bank balance (a recon source / provided figure).</li>
 *   <li><b>B</b> = customer e-money wallet liability (GL 2700 / IND04).</li>
 *   <li><b>C</b> = e-money suspense (GL 2710 / IND02 — unregistered / voucher / cash-out holds).</li>
 *   <li><b>D</b> = e-money unclaimed (GL 2720 / IND06 — decision D-ED-07: stays a LIABILITY, never income / capital,
 *       so it is a backing component).</li>
 *   <li><b>E</b> = agent / distributor float liability (agent float IS part of the e-money liability).</li>
 * </ul>
 *
 * The exact set of liability components is configurable per tenant (B..E are the Wallet-Edition defaults); this class
 * is given the already-resolved component figures and holds only the arithmetic and classification, with no DB or
 * network dependency, so it unit-tests cleanly.
 *
 * <h2>Classification</h2>
 * <ul>
 *   <li><b>BALANCED</b> — {@code |result| <= tolerance} (exactly or de-minimis backed; 100%+ backed). No break.</li>
 *   <li><b>SHORTFALL</b> — {@code result < -tolerance} (A &lt; liabilities beyond tolerance): the e-money is not
 *       fully backed. Raises a CRITICAL, regulator-reportable alarm <i>and</i> a high-priority exception. A negative
 *       result always alarms, even when a funding credit is believed in flight — unbacked e-money is a reportable
 *       condition regardless of the reason.</li>
 *   <li><b>TIMING</b> — a small positive surplus {@code tolerance < result <= timingTolerance} that has not yet been
 *       seen to persist: a difference attributable to in-flight postings, held and expected to auto-clear when the
 *       offsetting entry arrives next cycle. No exception is escalated.</li>
 *   <li><b>AMOUNT</b> — a surplus beyond {@code timingTolerance}, or any surplus that was already seen on a prior
 *       cycle and did not clear (a persistent / unexplained difference): escalated to the existing reconciliation
 *       exception queue with ageing.</li>
 * </ul>
 */
public final class SafeguardingPolicy {

    /** Reconciliation outcome, ordered by severity (forward-only within a business day). */
    public enum Status { BALANCED, TIMING, AMOUNT, SHORTFALL }

    /** Alarm severity for the emitted event / exception. */
    public enum Severity { NONE, LOW, HIGH, CRITICAL }

    /** The idempotent re-run decision for a (tenant, config, as-of date) key. */
    public enum RunDecision { CREATE, RETURN_EXISTING, ADVANCE }

    /** One resolved backing component (a liability figure that must be covered by A). */
    public record Component(String role, String label, BigDecimal amount) {
        public Component {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** Immutable result of one assessment. */
    public record Assessment(BigDecimal trustBalance, BigDecimal liabilityTotal, BigDecimal difference,
                             Status status, boolean backed, boolean alarm, boolean exceptionRequired,
                             Severity severity) {}

    private SafeguardingPolicy() {}

    /** Sum of the liability components (B + C + D + E...); empty sums to zero. */
    public static BigDecimal totalLiabilities(List<Component> liabilities) {
        BigDecimal total = BigDecimal.ZERO;
        for (Component c : liabilities) {
            total = total.add(c.amount());
        }
        return total;
    }

    /** Assess with no prior-persistence signal (a first sighting this cycle). */
    public static Assessment assess(BigDecimal trustBalance, List<Component> liabilities, BigDecimal tolerance,
                                    BigDecimal timingTolerance) {
        return assess(trustBalance, liabilities, tolerance, timingTolerance, false);
    }

    /**
     * Assess {@code A - (B + C + D + E)} and classify the result.
     *
     * @param trustBalance       A, the safeguarding bank balance
     * @param liabilities        the B..E backing components (per-tenant configurable set)
     * @param tolerance          de-minimis absolute band treated as BALANCED (rounding); {@code >= 0}
     * @param timingTolerance    surplus band treated as an auto-clearing TIMING break; {@code >= 0}
     * @param priorUnclearedBreak whether a surplus break for this key was already open from a previous cycle; a
     *                            surplus that did not clear is a persistent AMOUNT break regardless of its size
     */
    public static Assessment assess(BigDecimal trustBalance, List<Component> liabilities, BigDecimal tolerance,
                                    BigDecimal timingTolerance, boolean priorUnclearedBreak) {
        Objects.requireNonNull(trustBalance, "trustBalance");
        BigDecimal tol = tolerance == null ? BigDecimal.ZERO : tolerance;
        BigDecimal timing = timingTolerance == null ? BigDecimal.ZERO : timingTolerance;
        if (tol.signum() < 0 || timing.signum() < 0) {
            throw new IllegalArgumentException("tolerances must not be negative");
        }
        BigDecimal liability = totalLiabilities(liabilities);
        BigDecimal diff = trustBalance.subtract(liability);

        // within the de-minimis band (exact zero or rounding) -> fully backed, no break
        if (diff.abs().compareTo(tol) <= 0) {
            return new Assessment(trustBalance, liability, diff, Status.BALANCED, true, false, false, Severity.NONE);
        }
        // negative beyond tolerance -> e-money not fully backed -> critical, regulator-reportable
        if (diff.signum() < 0) {
            return new Assessment(trustBalance, liability, diff, Status.SHORTFALL, false, true, true,
                    Severity.CRITICAL);
        }
        // positive surplus beyond tolerance: timing (transient, small) vs amount (persistent / large)
        boolean persistentOrLarge = priorUnclearedBreak || diff.compareTo(timing) > 0;
        if (persistentOrLarge) {
            return new Assessment(trustBalance, liability, diff, Status.AMOUNT, true, false, true, Severity.HIGH);
        }
        return new Assessment(trustBalance, liability, diff, Status.TIMING, true, false, false, Severity.LOW);
    }

    /**
     * Ageing bucket of an open break, matching the reconciliation metrics buckets ({@code 0-1, 2-7, 8-30, 31+}).
     * Negative ages clamp to zero.
     */
    public static String ageingBucket(long ageDays) {
        long age = Math.max(0, ageDays);
        if (age <= 1) {
            return "0-1";
        }
        if (age <= 7) {
            return "2-7";
        }
        if (age <= 30) {
            return "8-30";
        }
        return "31+";
    }

    /**
     * Idempotent re-run decision for a (tenant, config, business date) key. A record is created once with its
     * immutable component snapshot; a re-run for the same business day returns that record rather than duplicating it,
     * and only advances the stored status when the recomputed outcome is strictly more severe (forward-only: a
     * same-or-less-severe recomputation never regresses the audited status within the day).
     *
     * @param existsForDate whether a record already exists for the key
     * @param existing      the status already recorded (ignored when {@code existsForDate} is false)
     * @param recomputed    the status just computed
     */
    public static RunDecision decide(boolean existsForDate, Status existing, Status recomputed) {
        Objects.requireNonNull(recomputed, "recomputed");
        if (!existsForDate) {
            return RunDecision.CREATE;
        }
        Objects.requireNonNull(existing, "existing");
        return recomputed.ordinal() > existing.ordinal() ? RunDecision.ADVANCE : RunDecision.RETURN_EXISTING;
    }
}
