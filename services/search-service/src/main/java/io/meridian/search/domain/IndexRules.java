package io.meridian.search.domain;

import io.meridian.common.error.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** Index lifecycle and naming rules. */
public final class IndexRules {
    public static final Set<String> ENTITY_TYPES = Set.of("CUSTOMER", "DEPOSIT_ACCOUNT", "WALLET", "CARD", "LOAN", "PAYMENT",
            "WALLET_TRANSACTION", "SERVICE_CASE", "AML_CASE", "MERCHANT", "AGENT", "DOCUMENT", "LEDGER_ACCOUNT",
            "LOAN_APPLICATION", "FRAUD_ALERT", "CARD_DISPUTE", "COMPLAINT"); // = spec enum SearchEntityType
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private IndexRules() {}

    /** Physical index name behind an alias: {@code <alias>_v<mapping version>_<timestamp>}. */
    public static String physicalName(String alias, int mappingVersion) {
        return alias + "_v" + mappingVersion + "_" + LocalDateTime.now(ZoneOffset.UTC).format(TS);
    }

    public static void requireAlias(String alias) {
        if (alias == null || !alias.matches("[a-z][a-z0-9_-]{1,100}")) {
            throw ApiException.unprocessable("invalid_alias_name", "alias_name must be lower-case letters, digits, _ or -");
        }
    }

    public static String entityType(String t) {
        String u = t == null ? null : t.trim().toUpperCase(java.util.Locale.ROOT);
        if (u == null || !ENTITY_TYPES.contains(u)) {
            throw ApiException.unprocessable("unknown_entity_type", "Unknown entity type " + t);
        }
        return u;
    }

    /** Indexes that serve reads. */
    public static boolean searchable(String status) {
        return "ACTIVE".equals(status) || "REINDEXING".equals(status) || "DEGRADED".equals(status);
    }

    public static void requireReindexable(String status) {
        if (!"ACTIVE".equals(status) && !"DEGRADED".equals(status) && !"BUILDING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Index in status " + status + " cannot be reindexed");
        }
    }

    public static void requireCancellable(String status) {
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Job in status " + status + " cannot be cancelled");
        }
    }
}
