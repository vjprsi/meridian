package io.meridian.customer.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ownership and control structure of a legal entity. Walks owners recursively (cycle-safe, depth-limited),
 * multiplies shares along each chain and aggregates the effective ownership of every natural person. Ultimate
 * beneficial owners are natural persons at or above the threshold (25% by default), plus declared UBOs; when
 * nobody qualifies, the controlling persons (directors, trustees, settlors) are reported instead, as the AML
 * rules for "senior managing officials" require.
 */
public final class OwnershipTree {
    private OwnershipTree() {}

    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("25");
    public static final int MAX_DEPTH = 10;
    public static final Set<String> OWNERSHIP_TYPES = Set.of("SHAREHOLDER", "PARENT_COMPANY");
    public static final Set<String> CONTROL_TYPES = Set.of("DIRECTOR", "TRUSTEE", "SETTLOR", "PARTNER");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Party(UUID id, String customerNumber, String displayName, String customerType) {
        public boolean naturalPerson() {
            return "INDIVIDUAL".equals(customerType) || "SOLE_TRADER".equals(customerType);
        }
    }

    /** A relationship pointing from a subject to one of its owners or controllers. */
    public record Edge(UUID relatedId, String relationshipType, BigDecimal percentage) {}

    public interface Graph {
        Party party(UUID id);

        /** Active relationships where {@code subject} is the customer_id. */
        List<Edge> edges(UUID subject);
    }

    public record Node(Party party, String relationshipType, BigDecimal directPercentage, BigDecimal effectivePercentage,
                       boolean cycle, boolean truncated, List<Node> owners) {}

    public record Ubo(Party party, BigDecimal effectivePercentage, List<String> basis) {}

    public record Result(Node root, List<Ubo> ubos, List<Party> controllers) {}

    public static Result build(UUID rootId, Graph graph, BigDecimal threshold) {
        Map<UUID, BigDecimal> effective = new LinkedHashMap<>();
        Map<UUID, Party> parties = new LinkedHashMap<>();
        Party rootParty = graph.party(rootId);
        Node root = walk(rootParty, null, null, HUNDRED, graph, new HashSet<>(Set.of(rootId)), 0, effective, parties);

        Map<UUID, Ubo> ubos = new LinkedHashMap<>();
        effective.forEach((id, pct) -> {
            Party p = parties.get(id);
            if (p.naturalPerson() && pct.compareTo(threshold) >= 0) {
                ubos.put(id, new Ubo(p, pct, new ArrayList<>(List.of("OWNERSHIP"))));
            }
        });
        List<Party> controllers = new ArrayList<>();
        for (Edge e : graph.edges(rootId)) {
            if ("ULTIMATE_BENEFICIAL_OWNER".equals(e.relationshipType())) {
                Party p = graph.party(e.relatedId());
                BigDecimal declared = e.percentage() == null ? BigDecimal.ZERO : e.percentage();
                Ubo existing = ubos.get(p.id());
                if (existing == null) {
                    ubos.put(p.id(), new Ubo(p, declared.max(effective.getOrDefault(p.id(), BigDecimal.ZERO)),
                            new ArrayList<>(List.of("DECLARED"))));
                } else {
                    existing.basis().add("DECLARED");
                }
            } else if (CONTROL_TYPES.contains(e.relationshipType())) {
                controllers.add(graph.party(e.relatedId()));
            }
        }
        if (ubos.isEmpty()) {
            for (Party c : controllers) {
                if (c.naturalPerson()) {
                    ubos.put(c.id(), new Ubo(c, effective.getOrDefault(c.id(), BigDecimal.ZERO),
                            new ArrayList<>(List.of("SENIOR_MANAGING_OFFICIAL"))));
                }
            }
        }
        List<Ubo> list = new ArrayList<>(ubos.values());
        list.sort(Comparator.comparing(Ubo::effectivePercentage).reversed());
        return new Result(root, list, controllers);
    }

    private static Node walk(Party party, String type, BigDecimal direct, BigDecimal pct, Graph graph, Set<UUID> path,
                             int depth, Map<UUID, BigDecimal> effective, Map<UUID, Party> parties) {
        List<Node> owners = new ArrayList<>();
        boolean truncated = false;
        if (depth >= MAX_DEPTH) {
            truncated = true;
        } else {
            for (Edge e : graph.edges(party.id())) {
                if (!OWNERSHIP_TYPES.contains(e.relationshipType())) {
                    continue;
                }
                Party owner = graph.party(e.relatedId());
                BigDecimal share = e.percentage() == null ? BigDecimal.ZERO : e.percentage();
                BigDecimal eff = pct.multiply(share, MathContext.DECIMAL128)
                        .divide(HUNDRED, 4, RoundingMode.HALF_EVEN);
                if (path.contains(owner.id())) {
                    owners.add(new Node(owner, e.relationshipType(), share, eff, true, false, List.of()));
                    continue;
                }
                parties.put(owner.id(), owner);
                effective.merge(owner.id(), eff, BigDecimal::add);
                path.add(owner.id());
                owners.add(walk(owner, e.relationshipType(), share, eff, graph, path, depth + 1, effective, parties));
                path.remove(owner.id());
            }
        }
        return new Node(party, type, direct, pct.setScale(4, RoundingMode.HALF_EVEN), false, truncated, owners);
    }
}
