package io.meridian.deposit.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether a set of signatories satisfies an account's signing mandate for an operation.
 *
 * <p>Eligible signers are ACTIVE holders, valid on the date, with {@code can_transact}, in a signing role and within
 * their personal transaction limit. A MINOR never signs alone: while a guardian is linked to the account the minor's
 * signature does not count (the guardian signs on their behalf). Rules:
 * ANY_ONE (one eligible signer), ANY_TWO (two distinct), ALL (every eligible signer), PRIMARY_ONLY (the primary
 * holder), PRIMARY_PLUS_ONE (primary and one other), CUSTOM (at least {@code required_approvals} signers that also
 * cover one of the allowed signing-group combinations, e.g. [[A],[B,B]] = one A or two Bs).
 */
public final class MandateEvaluator {

    private static final Set<String> SIGNING_ROLES = Set.of("PRIMARY", "JOINT", "SIGNATORY", "POWER_OF_ATTORNEY",
            "TRUSTEE", "GUARDIAN", "AUTHORISED_USER", "MINOR");

    public record Holder(UUID customerId, String role, String status, boolean canTransact, BigDecimal transactionLimit,
                         String signingGroup, LocalDate validFrom, LocalDate validTo) {}

    public record Mandate(String rule, int requiredApprovals, List<List<String>> groupCombinations) {}

    public record Result(boolean allowed, String rule, int requiredApprovals, int approvalsCounted,
                         List<UUID> eligibleApprovers, List<UUID> countedSignatories, List<UUID> ignoredSignatories,
                         List<String> reasons) {}

    private MandateEvaluator() {}

    public static Result evaluate(Mandate mandate, List<Holder> holders, Set<UUID> signatories, BigDecimal amount,
                                  LocalDate today) {
        String rule = mandate.rule() == null ? "ANY_ONE" : mandate.rule().toUpperCase(Locale.ROOT);
        boolean hasGuardian = holders.stream().anyMatch(h -> "GUARDIAN".equals(h.role()) && active(h, today));
        List<Holder> eligible = new ArrayList<>();
        for (Holder h : holders) {
            if (canSign(h, amount, today, hasGuardian)) {
                eligible.add(h);
            }
        }
        Set<UUID> eligibleIds = new LinkedHashSet<>();
        eligible.forEach(h -> eligibleIds.add(h.customerId()));
        List<UUID> counted = new ArrayList<>();
        List<UUID> ignored = new ArrayList<>();
        for (UUID s : signatories) {
            (eligibleIds.contains(s) ? counted : ignored).add(s);
        }
        List<String> reasons = new ArrayList<>();
        if (!ignored.isEmpty()) {
            reasons.add("signatories without signing authority for this operation: " + ignored);
        }
        Set<UUID> primaries = new HashSet<>();
        eligible.stream().filter(h -> "PRIMARY".equals(h.role())).forEach(h -> primaries.add(h.customerId()));

        int required;
        boolean ok;
        switch (rule) {
            case "ANY_ONE" -> {
                required = 1;
                ok = !counted.isEmpty();
            }
            case "ANY_TWO" -> {
                required = 2;
                ok = counted.size() >= 2;
            }
            case "ALL" -> {
                required = eligibleIds.size();
                ok = required > 0 && counted.containsAll(eligibleIds);
                if (!ok) {
                    List<UUID> missing = eligibleIds.stream().filter(id -> !counted.contains(id)).toList();
                    reasons.add("all holders must sign; missing " + missing);
                }
            }
            case "PRIMARY_ONLY" -> {
                required = 1;
                ok = counted.stream().anyMatch(primaries::contains);
                if (!ok) {
                    reasons.add("the primary holder must sign");
                }
            }
            case "PRIMARY_PLUS_ONE" -> {
                required = 2;
                ok = counted.stream().anyMatch(primaries::contains) && counted.size() >= 2;
                if (!ok) {
                    reasons.add("the primary holder and one other signer must sign");
                }
            }
            case "CUSTOM" -> {
                required = Math.max(1, mandate.requiredApprovals());
                Map<UUID, String> groupOf = new HashMap<>();
                eligible.forEach(h -> groupOf.put(h.customerId(), h.signingGroup()));
                boolean groupsOk = mandate.groupCombinations() == null || mandate.groupCombinations().isEmpty()
                        || mandate.groupCombinations().stream().anyMatch(c -> covers(c, counted, groupOf));
                ok = counted.size() >= required && groupsOk;
                if (!groupsOk) {
                    reasons.add("signatories do not match any allowed signing-group combination "
                            + mandate.groupCombinations());
                }
            }
            default -> throw new IllegalArgumentException("unknown mandate rule " + rule);
        }
        if (!ok && counted.size() < required) {
            reasons.add(required + " eligible signature(s) required, " + counted.size() + " given");
        }
        if (eligibleIds.size() < required) {
            reasons.add("the account has only " + eligibleIds.size() + " eligible signer(s)");
        }
        return new Result(ok, rule, required, counted.size(), List.copyOf(eligibleIds), counted, ignored, reasons);
    }

    private static boolean covers(List<String> combination, List<UUID> counted, Map<UUID, String> groupOf) {
        Map<String, Integer> need = new HashMap<>();
        combination.forEach(g -> need.merge(g, 1, Integer::sum));
        Map<String, Integer> have = new HashMap<>();
        counted.forEach(id -> {
            String g = groupOf.get(id);
            if (g != null) {
                have.merge(g, 1, Integer::sum);
            }
        });
        return need.entrySet().stream().allMatch(e -> have.getOrDefault(e.getKey(), 0) >= e.getValue());
    }

    static boolean active(Holder h, LocalDate today) {
        return "ACTIVE".equals(h.status())
                && (h.validFrom() == null || !today.isBefore(h.validFrom()))
                && (h.validTo() == null || !today.isAfter(h.validTo()));
    }

    static boolean canSign(Holder h, BigDecimal amount, LocalDate today, boolean hasGuardian) {
        if (!active(h, today) || !h.canTransact() || !SIGNING_ROLES.contains(h.role())) {
            return false;
        }
        if ("MINOR".equals(h.role()) && hasGuardian) {
            return false;
        }
        return amount == null || h.transactionLimit() == null || amount.compareTo(h.transactionLimit()) <= 0;
    }
}
