package io.meridian.analytics.application;

import io.meridian.analytics.domain.DatasetCatalog;
import io.meridian.analytics.domain.ShareTargets;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Data shares: destination connection details are validated per target type and stored but never returned (only a
 * non-secret summary); pause / resume; connectivity test; and the per-tenant delivery job, idempotent per business
 * date, which delivers every ACTIVE share due that day (cron in UTC, or every weekday without a cron). The delivery
 * transport is simulated: datasets are read and counted, destination checks are applied.
 */
@Service
public class DataShareService {
    private final Store store;
    private final DatasetService datasets;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public DataShareService(Store store, DatasetService datasets, OutboxWriter outbox, JsonMapper json, TransactionTemplate tx) {
        this.store = store;
        this.datasets = datasets;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
    }

    /** Validates column values (merged with the existing row) and derives the destination summary. */
    public void prepare(ObjectNode values, JsonNode existing, JsonNode targetConfig) {
        JsonNode merged = existing == null ? values : ((ObjectNode) existing.deepCopy()).setAll(values);
        String target = Fields.requireText(merged, "target");
        JsonNode datasetsNode = merged.get("datasets");
        if (datasetsNode == null || !datasetsNode.isArray() || datasetsNode.isEmpty()) {
            throw Fields.invalid("datasets", "required", "datasets must list at least one dataset");
        }
        for (JsonNode d : datasetsNode) {
            if (DatasetCatalog.find(d.asString()).isEmpty()) {
                throw Fields.invalid("datasets", "unknown_dataset", "Unknown dataset " + d.asString() + "; available: "
                        + DatasetCatalog.all().keySet());
            }
        }
        String cron = Fields.text(merged, "schedule_cron");
        if (cron != null && !cron.isBlank()) {
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException e) {
                throw Fields.invalid("schedule_cron", "invalid_cron", "schedule_cron is not a valid cron expression");
            }
        }
        if (targetConfig != null) {
            Map<String, Object> cfg = config(targetConfig);
            List<ShareTargets.Check> failed = ShareTargets.validate(target, cfg);
            if (!failed.isEmpty()) {
                throw new ApiException(422, "invalid_target_config", "target_config is not valid for " + target,
                        failed.stream().map(c -> new ApiException.FieldError("target_config", "invalid", c.message())).toList());
            }
            values.set("target_config", targetConfig);
            values.put("target_summary", ShareTargets.summary(target, cfg));
        } else if (values.has("target") && existing != null && !values.get("target").asString().equals(existing.get("target").asString())) {
            throw Fields.invalid("target", "target_config_required", "Changing the target needs new connection details (POST /target-config)");
        }
    }

    private Map<String, Object> config(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw Fields.invalid("target_config", "invalid_type", "target_config must be an object");
        }
        return json.convertValue(n, new TypeReference<Map<String, Object>>() {});
    }

    /** Replaces the destination connection details. */
    public String replaceConfig(UUID id, JsonNode targetConfig) {
        return tx.execute(s -> {
            JsonNode share = store.requireLocked("analytics_data_share", id);
            requireNotRevoked(share);
            ObjectNode values = json.createObjectNode();
            prepare(values, share, targetConfig);
            return store.updateReturning("""
                    update analytics_data_share t set target_config = ?::jsonb, target_summary = ?, last_error = null, updated_by = ?
                    where t.id = ? returning row_to_json(t)::text""",
                    values.get("target_config").toString(), values.get("target_summary").asString(), TenantContext.principal(), id)
                    .orElseThrow().toString();
        });
    }

    public String pause(UUID id, String reason) {
        return tx.execute(s -> {
            JsonNode share = store.requireLocked("analytics_data_share", id);
            String st = share.get("status").asString();
            if (!Set.of("ACTIVE", "FAILED").contains(st)) {
                throw ApiException.conflict("invalid_transition", "Only ACTIVE or FAILED shares can be paused (share is " + st + ")");
            }
            JsonNode row = store.updateReturning("""
                    update analytics_data_share t set status = 'PAUSED', status_reason = ?, updated_by = ?
                    where t.id = ? returning row_to_json(t)::text""", Fields.clip(reason, 255), TenantContext.principal(), id).orElseThrow();
            outbox.publish("reporting.data_share.paused", "analytics_data_share", id, publicView(row));
            return row.toString();
        });
    }

    public String resume(UUID id) {
        return tx.execute(s -> {
            JsonNode share = store.requireLocked("analytics_data_share", id);
            String st = share.get("status").asString();
            if (!Set.of("PAUSED", "FAILED").contains(st)) {
                throw ApiException.conflict("invalid_transition", "Only PAUSED or FAILED shares can be resumed (share is " + st + ")");
            }
            List<ShareTargets.Check> failed = ShareTargets.validate(share.get("target").asString(), config(share.get("target_config")));
            if (!failed.isEmpty()) {
                throw ApiException.unprocessable("invalid_target_config", "Fix the destination first: " + failed.getFirst().message());
            }
            JsonNode row = store.updateReturning("""
                    update analytics_data_share t set status = 'ACTIVE', status_reason = null, last_error = null, updated_by = ?
                    where t.id = ? returning row_to_json(t)::text""", TenantContext.principal(), id).orElseThrow();
            outbox.publish("reporting.data_share.resumed", "analytics_data_share", id, publicView(row));
            return row.toString();
        });
    }

    /** Connectivity / permission checks without delivering. */
    public ObjectNode test(UUID id) {
        JsonNode share = store.require("analytics_data_share", id);
        ObjectNode out = json.createObjectNode();
        ArrayNode checks = out.putArray("checks");
        boolean ok = true;
        List<ShareTargets.Check> failed = ShareTargets.validate(share.get("target").asString(), config(share.get("target_config")));
        checks.addObject().put("name", "destination").put("ok", failed.isEmpty())
                .put("message", failed.isEmpty() ? "Connection details complete for " + share.get("target").asString()
                        : String.join("; ", failed.stream().map(ShareTargets.Check::message).toList()));
        ok &= failed.isEmpty();
        for (JsonNode d : share.path("datasets")) {
            boolean exists = DatasetCatalog.find(d.asString()).isPresent();
            checks.addObject().put("name", "dataset:" + d.asString()).put("ok", exists)
                    .put("message", exists ? "Dataset available" : "Dataset no longer exists");
            ok &= exists;
        }
        boolean revoked = "REVOKED".equals(share.get("status").asString());
        checks.addObject().put("name", "status").put("ok", !revoked).put("message", "Share is " + share.get("status").asString());
        ok &= !revoked;
        out.put("ok", ok);
        out.put("data_share_id", id.toString());
        out.put("target", share.get("target").asString());
        out.put("target_summary", Fields.text(share, "target_summary"));
        return out;
    }

    /** True when the share delivers on {@code date}. */
    static boolean dueOn(String cron, LocalDate date) {
        if (cron == null || cron.isBlank()) {
            return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
        }
        ZonedDateTime next = CronExpression.parse(cron).next(date.atStartOfDay(ZoneOffset.UTC).minusNanos(1));
        return next != null && next.toLocalDate().equals(date);
    }

    /** Scheduled job body: deliver every ACTIVE share due on the business date. */
    public void deliver(LocalDate businessDate, Map<String, Integer> counts, List<String> errors) {
        List<JsonNode> shares = store.rows("select row_to_json(t)::text from analytics_data_share t where t.status = 'ACTIVE' order by t.id");
        int due = 0;
        int delivered = 0;
        int failed = 0;
        for (JsonNode share : shares) {
            UUID id = UUID.fromString(share.get("id").asString());
            if (!dueOn(Fields.text(share, "schedule_cron"), businessDate)) {
                continue;
            }
            due++;
            String error = null;
            long rows = 0;
            List<ShareTargets.Check> bad = ShareTargets.validate(share.get("target").asString(), config(share.get("target_config")));
            if (!bad.isEmpty()) {
                error = "destination: " + bad.getFirst().message();
            } else {
                try {
                    for (JsonNode d : share.get("datasets")) {
                        rows += datasets.rows(datasets.require(d.asString())).size();
                    }
                } catch (ApiException e) {
                    error = e.code() + ": " + e.getMessage();
                }
            }
            String err = error;
            long count = rows;
            if (err == null) {
                delivered++;
                tx.executeWithoutResult(s -> store.updateReturning("""
                        update analytics_data_share t set last_delivery_at = now(), last_delivery_rows = ?, last_error = null, updated_by = ?
                        where t.id = ? returning row_to_json(t)::text""", count, TenantContext.principal(), id)
                        .ifPresent(row -> {
                            ObjectNode payload = publicView(row);
                            payload.put("business_date", businessDate.toString());
                            outbox.publish("reporting.data_share.delivered", "analytics_data_share", id, payload);
                        }));
            } else {
                failed++;
                errors.add("share " + id + ": " + err);
                tx.executeWithoutResult(s -> store.updateReturning("""
                        update analytics_data_share t set status = 'FAILED', last_error = ?, status_reason = 'delivery failed', updated_by = ?
                        where t.id = ? returning row_to_json(t)::text""", Fields.clip(err, 4000), TenantContext.principal(), id)
                        .ifPresent(row -> {
                            ObjectNode payload = publicView(row);
                            payload.put("business_date", businessDate.toString());
                            outbox.publish("reporting.data_share.failed", "analytics_data_share", id, payload);
                        }));
            }
        }
        counts.put("active_shares", shares.size());
        counts.put("due", due);
        counts.put("delivered", delivered);
        counts.put("failed", failed);
    }

    private void requireNotRevoked(JsonNode share) {
        if ("REVOKED".equals(share.get("status").asString())) {
            throw ApiException.conflict("share_revoked", "The data share is revoked");
        }
    }

    /** Event payloads never carry the destination secrets. */
    public static ObjectNode publicView(JsonNode row) {
        ObjectNode n = ((ObjectNode) row).deepCopy();
        n.remove("target_config");
        return n;
    }
}
