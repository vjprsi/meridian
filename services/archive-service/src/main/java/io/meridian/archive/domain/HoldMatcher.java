package io.meridian.archive.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides how active legal holds affect a purge or archive selection. Holds override retention:
 * <ul>
 *   <li>a TENANT hold, or a TABLE hold naming the table, blocks the whole operation;</li>
 *   <li>a CUSTOMER / ACCOUNT / CASE / DOCUMENT hold blocks a selection that explicitly targets held ids
 *       (selector {@code customer_ids}, {@code account_ids}, {@code case_ids}, {@code document_ids});</li>
 *   <li>otherwise held ids are excluded from the operation (passed to the owning service as exclusions).</li>
 * </ul>
 */
public final class HoldMatcher {
    private HoldMatcher() {}

    public record Hold(String id, String name, String scopeType, Set<String> scopeIds, String customerId) {
        public Hold {
            Set<String> norm = new LinkedHashSet<>();
            scopeIds.forEach(s -> norm.add(s.trim()));
            scopeIds = norm;
        }

        /** True if the hold covers entity {@code id} of {@code scope} (customer holds also match customer_id). */
        public boolean covers(String scope, String entityId) {
            if ("TENANT".equals(scopeType)) {
                return true;
            }
            if (!scopeType.equals(scope)) {
                return false;
            }
            return scopeIds.contains(entityId) || entityId.equals(customerId);
        }
    }

    /** Result: the blocking hold (if any) and per-scope ids to exclude. */
    public record Decision(Hold blockedBy, Map<String, Set<String>> exclusions) {
        public boolean blocked() {
            return blockedBy != null;
        }
    }

    public static final Map<String, String> SELECTOR_FIELDS = Map.of(
            "CUSTOMER", "customer_ids", "ACCOUNT", "account_ids", "CASE", "case_ids", "DOCUMENT", "document_ids");

    public static Decision decide(List<Hold> activeHolds, String databaseName, String tableName,
                                  Map<String, Set<String>> selectorIds) {
        Map<String, Set<String>> exclusions = new LinkedHashMap<>();
        for (Hold h : activeHolds) {
            switch (h.scopeType()) {
                case "TENANT" -> {
                    return new Decision(h, Map.of());
                }
                case "TABLE" -> {
                    String t = tableName.toLowerCase(Locale.ROOT);
                    String qualified = (databaseName + "." + tableName).toLowerCase(Locale.ROOT);
                    if (h.scopeIds().stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(s -> s.equals(t) || s.equals(qualified))) {
                        return new Decision(h, Map.of());
                    }
                }
                default -> {
                    Set<String> targeted = selectorIds.getOrDefault(h.scopeType(), Set.of());
                    for (String id : targeted) {
                        if (h.covers(h.scopeType(), id)) {
                            return new Decision(h, Map.of());
                        }
                    }
                    Set<String> ex = exclusions.computeIfAbsent(h.scopeType(), k -> new LinkedHashSet<>());
                    ex.addAll(h.scopeIds());
                    if (h.customerId() != null && "CUSTOMER".equals(h.scopeType())) {
                        ex.add(h.customerId());
                    }
                }
            }
        }
        return new Decision(null, exclusions);
    }

    /** Active holds matching one entity (for the hold check API). */
    public static List<Hold> matching(List<Hold> activeHolds, String scopeType, String id) {
        List<Hold> out = new ArrayList<>();
        for (Hold h : activeHolds) {
            if (h.covers(scopeType, id)) {
                out.add(h);
            }
        }
        return out;
    }
}
