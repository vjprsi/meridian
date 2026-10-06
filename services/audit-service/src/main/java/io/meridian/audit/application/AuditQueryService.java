package io.meridian.audit.application;

import io.meridian.audit.domain.AuditRecord;
import io.meridian.audit.domain.HashChain;
import io.meridian.audit.infrastructure.AuditEventStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.page.Cursor;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Hash-chain verification and structured search over the audit log. */
@Service
public class AuditQueryService {
    /** Largest range verified in one call; bigger ranges must be split by the caller. */
    public static final long MAX_VERIFY_RANGE = 100_000;

    private final AuditEventStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public AuditQueryService(AuditEventStore store, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public JsonNode verifyChain(Long from, Long to) {
        UUID tenant = TenantContext.require();
        if (from == null || to == null) {
            throw ApiException.badRequest("validation_failed", "from_sequence and to_sequence are required");
        }
        if (from < 1) {
            throw ApiException.unprocessable("invalid_range", "from_sequence must be >= 1");
        }
        if (to < from) {
            throw ApiException.unprocessable("invalid_range", "to_sequence must be >= from_sequence");
        }
        if (to - from + 1 > MAX_VERIFY_RANGE) {
            throw ApiException.unprocessable("range_too_large",
                    "At most " + MAX_VERIFY_RANGE + " events can be verified per request");
        }
        String anchor = from == 1 ? HashChain.GENESIS : store.hashAt(from - 1).orElse(null);
        List<AuditRecord> rows = store.range(from, to);
        HashChain.Verification v = HashChain.verify(rows, from, to, anchor);

        ObjectNode result = json.createObjectNode();
        result.put("valid", v.valid());
        result.put("from_sequence", from);
        result.put("to_sequence", to);
        result.put("checked", v.checked());
        result.put("last_sequence_checked", rows.isEmpty() || !v.valid() ? null
                : rows.getLast().sequenceNo());
        result.put("algorithm", "SHA-256/canonical-v" + HashChain.VERSION);
        result.put("verified_at", io.meridian.common.time.Clocks.now().toString());
        if (!v.valid()) {
            result.put("first_broken_sequence", v.firstBrokenSequence());
            result.put("reason", v.reason().name());
            if (v.brokenEventId() != null) {
                result.put("broken_event_id", v.brokenEventId().toString());
            }
            ObjectNode evt = result.deepCopy();
            evt.put("tenant_id", tenant.toString());
            evt.put("verified_by", TenantContext.principal());
            outbox.publish("platform.audit_chain.broken", "audit_event",
                    v.brokenEventId() == null ? Ids.newId() : v.brokenEventId(), evt);
        }
        return result;
    }

    /** Returns a page body ({data, has_more, next_cursor}) as JSON text. */
    @Transactional(readOnly = true)
    public String search(Map<String, Object> query, Integer limitParam, String cursor) {
        TenantContext.require();
        if (query == null) {
            throw ApiException.badRequest("validation_failed", "query is required");
        }
        int limit = Cursor.limit(limitParam);
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : query.entrySet()) {
            Object raw = e.getValue();
            if (raw == null) {
                continue;
            }
            String value = raw.toString();
            switch (e.getKey()) {
                case "actor_id", "resource_type", "resource_id", "request_id", "service_name", "session_id" -> {
                    where.append(" and t.").append(e.getKey()).append(" = ?");
                    args.add(value);
                }
                case "actor_type" -> {
                    enumValue("actor_type", value, AuditRecorder.ACTOR_TYPES);
                    where.append(" and t.actor_type = ?");
                    args.add(value);
                }
                case "outcome" -> {
                    enumValue("outcome", value, AuditRecorder.OUTCOMES);
                    where.append(" and t.outcome = ?");
                    args.add(value);
                }
                case "legal_entity_id" -> {
                    where.append(" and t.legal_entity_id = ?");
                    args.add(uuid("legal_entity_id", value));
                }
                case "action" -> {
                    if (value.endsWith("*")) {
                        where.append(" and t.action like ? escape '\\'");
                        args.add(escapeLike(value.substring(0, value.length() - 1)) + "%");
                    } else {
                        where.append(" and t.action = ?");
                        args.add(value);
                    }
                }
                case "from" -> {
                    where.append(" and t.occurred_at >= ?");
                    args.add(time("from", value));
                }
                case "to" -> {
                    where.append(" and t.occurred_at < ?");
                    args.add(time("to", value));
                }
                case "text" -> {
                    String p = "%" + escapeLike(value) + "%";
                    where.append(" and (t.action ilike ? escape '\\' or t.resource_type ilike ? escape '\\'"
                            + " or t.resource_id ilike ? escape '\\' or t.reason ilike ? escape '\\'"
                            + " or t.actor_display ilike ? escape '\\' or t.actor_id ilike ? escape '\\')");
                    for (int i = 0; i < 6; i++) {
                        args.add(p);
                    }
                }
                default -> throw ApiException.badRequest("unknown_filter", "Unsupported search field " + e.getKey()
                        + "; use actor_id, actor_type, action, resource_type, resource_id, outcome, legal_entity_id,"
                        + " request_id, service_name, session_id, from, to, text");
            }
        }
        UUID before = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        List<String> rows = store.search(where.toString(), args, before, limit);
        boolean more = rows.size() > limit;
        List<String> data = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursor.encode(UUID.fromString(json.readTree(data.getLast()).get("id").asString())) : null;
        return "{\"data\":[" + String.join(",", data) + "],\"has_more\":" + more
                + ",\"next_cursor\":" + (next == null ? "null" : "\"" + next + "\"") + "}";
    }

    private static void enumValue(String field, String v, java.util.Set<String> allowed) {
        if (!allowed.contains(v)) {
            throw ApiException.badRequest("invalid_value", field + " must be one of " + allowed);
        }
    }

    private static UUID uuid(String field, String v) {
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a uuid");
        }
    }

    private static OffsetDateTime time(String field, String v) {
        try {
            return OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_value", field + " must be an ISO-8601 date-time with offset");
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
