package io.meridian.archive.application;

import io.meridian.archive.domain.ArchiveRules;
import io.meridian.archive.domain.Certificate;
import io.meridian.archive.domain.HoldMatcher;
import io.meridian.archive.infrastructure.JsonValues;
import io.meridian.archive.infrastructure.Rows;
import io.meridian.archive.infrastructure.SourceClient;
import io.meridian.archive.infrastructure.WormStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Purge requests: DRAFT -> PENDING_APPROVAL -> APPROVED (four-eyes) -> EXECUTING -> COMPLETED, with legal holds
 * checked at submit, approval and execution (BLOCKED_BY_HOLD / 409). Execution asks the owning service to purge or
 * anonymise the selection (held ids excluded) and issues a hashed certificate of destruction kept in WORM storage.
 */
@Service
public class PurgeService {
    private static final int CERTIFICATE_RETENTION_YEARS = 100;

    private final Rows rows;
    private final LegalHoldService holds;
    private final SourceClient source;
    private final WormStore worm;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public PurgeService(Rows rows, LegalHoldService holds, SourceClient source, WormStore worm, OutboxWriter outbox,
                        TransactionTemplate tx, JsonMapper json) {
        this.rows = rows;
        this.holds = holds;
        this.source = source;
        this.worm = worm;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    public ObjectNode prepareCreate(ObjectNode v) {
        ArchiveRules.requirePurgeAction(JsonValues.text(v, "action"));
        JsonNode selector = v.get("record_selector");
        if (selector == null || !selector.isObject() || selector.isEmpty()) {
            throw ApiException.unprocessable("record_selector_required", "record_selector must describe the records to purge");
        }
        if (!v.hasNonNull("data_classification_id")) {
            throw ApiException.unprocessable("data_classification_required", "data_classification_id (the table) is required");
        }
        JsonNode cls = rows.text("data_classification", UUID.fromString(v.get("data_classification_id").asString()), false)
                .map(rows::tree).orElseThrow(() -> ApiException.unprocessable("unknown_classification", "data_classification_id does not exist"));
        if (v.hasNonNull("retention_policy_id")) {
            JsonNode policy = rows.text("retention_policy", UUID.fromString(v.get("retention_policy_id").asString()), false)
                    .map(rows::tree).orElseThrow(() -> ApiException.unprocessable("unknown_policy", "retention_policy_id does not exist"));
            if (!policy.get("data_class").asString().equals(cls.get("data_class").asString())) {
                throw ApiException.unprocessable("class_mismatch", "The policy covers " + policy.get("data_class").asString()
                        + " but the table is classified " + cls.get("data_class").asString());
            }
            if (selector.hasNonNull("created_before")) {
                LocalDate before = LocalDate.parse(selector.get("created_before").asString().substring(0, 10));
                LocalDate cutoff = ArchiveRules.purgeCutoff(LocalDate.now(), policy.get("retention_years").asInt());
                if (before.isAfter(cutoff)) {
                    throw ApiException.unprocessable("retention_not_expired", "Records created after " + cutoff
                            + " are still within the " + policy.get("retention_years").asInt() + "-year retention period");
                }
            }
        }
        LegalHoldService.selectorIds(selector); // id lists in the selector must be JSON arrays
        v.put("status", "DRAFT");
        return v;
    }

    public String submit(UUID id) {
        return tx.execute(s -> {
            JsonNode p = rows.require("purge_request", id, true);
            ArchiveRules.requireSubmit(p.get("status").asString());
            HoldMatcher.Decision d = holds.decide(holds.activeHolds(), p);
            if (d.blocked()) {
                return rows.updateReturning("""
                        update purge_request set status = 'BLOCKED_BY_HOLD', blocked_by_hold_id = ?::uuid
                        where id = ? returning row_to_json(purge_request)::text""", d.blockedBy().id(), id);
            }
            Long estimate = estimate(p, d);
            boolean approvalNeeded = !p.hasNonNull("retention_policy_id") || rows.require("retention_policy",
                    UUID.fromString(p.get("retention_policy_id").asString()), false).path("requires_approval").asBoolean(true);
            String row = rows.updateReturning("""
                    update purge_request set status = ?, estimated_records = ? where id = ?
                    returning row_to_json(purge_request)::text""", approvalNeeded ? "PENDING_APPROVAL" : "APPROVED", estimate, id);
            if (!approvalNeeded) {
                outbox.publish("operations.purge_request.approved", "purge_request", id, json.readTree(row));
            }
            return row;
        });
    }

    /** Four-eyes approval: the approver must differ from the requester. */
    public String approve(UUID id) {
        return tx.execute(s -> {
            JsonNode p = rows.require("purge_request", id, true);
            ArchiveRules.requireDecision(p.get("status").asString());
            if (Objects.equals(p.path("created_by").asString(null), TenantContext.principal())) {
                throw ApiException.unprocessable("four_eyes_violation", "A purge must be approved by a different user than the requester");
            }
            String row = rows.updateReturning("""
                    update purge_request set status = 'APPROVED', approved_by = ?, decided_at = now(), updated_by = ?
                    where id = ? returning row_to_json(purge_request)::text""", TenantContext.principal(),
                    TenantContext.principal(), id);
            outbox.publish("operations.purge_request.approved", "purge_request", id, json.readTree(row));
            return row;
        });
    }

    /** The checker rejects a pending purge; the reason is kept on the request. */
    public String reject(UUID id, String reason) {
        return tx.execute(s -> {
            JsonNode p = rows.require("purge_request", id, true);
            ArchiveRules.requireDecision(p.get("status").asString());
            String row = rows.updateReturning("""
                    update purge_request set status = 'REJECTED', decision_reason = ?, decided_at = now(), updated_by = ?
                    where id = ? returning row_to_json(purge_request)::text""", reason, TenantContext.principal(), id);
            outbox.publish("operations.purge_request.rejected", "purge_request", id, json.readTree(row));
            return row;
        });
    }

    /** Cancels a request that has not executed; the reason is kept on the request. */
    public String cancel(UUID id, String reason) {
        return tx.execute(s -> {
            JsonNode p = rows.require("purge_request", id, true);
            ArchiveRules.requireCancel(p.get("status").asString());
            String row = rows.updateReturning("""
                    update purge_request set status = 'CANCELLED', decision_reason = ?, decided_at = now(), updated_by = ?
                    where id = ? returning row_to_json(purge_request)::text""", reason, TenantContext.principal(), id);
            outbox.publish("operations.purge_request.cancelled", "purge_request", id, json.readTree(row));
            return row;
        });
    }

    public String execute(UUID id) {
        JsonNode p = rows.require("purge_request", id, false);
        ArchiveRules.requireExecute(p.get("status").asString());
        HoldMatcher.Decision d = holds.decide(holds.activeHolds(), p);
        if (d.blocked()) {
            tx.executeWithoutResult(s -> rows.update("""
                    update purge_request set status = 'BLOCKED_BY_HOLD', blocked_by_hold_id = ?::uuid where id = ?""",
                    d.blockedBy().id(), id));
            throw ApiException.conflict("blocked_by_legal_hold", "Legal hold " + d.blockedBy().id() + " (" + d.blockedBy().name()
                    + ") covers this data; the purge cannot run");
        }
        int moved = tx.execute(s -> rows.update("""
                update purge_request set status = 'EXECUTING' where id = ? and status in ('APPROVED', 'FAILED')""", id));
        if (moved == 0) {
            throw ApiException.conflict("invalid_transition", "Purge request is no longer executable");
        }
        JsonNode cls = rows.require("data_classification", UUID.fromString(p.get("data_classification_id").asString()), false);
        ObjectNode cmd = json.createObjectNode();
        cmd.put("purge_request_id", id.toString());
        cmd.put("database_name", cls.get("database_name").asString());
        cmd.put("table_name", cls.get("table_name").asString());
        cmd.put("action", p.get("action").asString());
        cmd.set("record_selector", p.get("record_selector"));
        cmd.set("exclude", exclusions(d.exclusions()));
        long purged;
        try {
            JsonNode res = source.purge(cls.get("service_name").asString(), cmd, "prg-" + id);
            purged = res == null ? 0 : res.path("records_purged").asLong(0);
        } catch (ApiException e) {
            return tx.execute(s -> rows.updateReturning("""
                    update purge_request set status = 'FAILED' where id = ? returning row_to_json(purge_request)::text""", id));
        }
        OffsetDateTime executedAt = io.meridian.common.time.Clocks.now();
        UUID certId = Ids.newId();
        ObjectNode cert = json.createObjectNode();
        cert.put("certificate_id", certId.toString());
        cert.put("type", "CERTIFICATE_OF_DESTRUCTION");
        cert.put("tenant_id", TenantContext.require().toString());
        cert.put("purge_request_id", id.toString());
        cert.put("action", p.get("action").asString());
        cert.put("method", "PURGE".equals(p.get("action").asString()) ? "Irreversible deletion in the owning service"
                : "Irreversible anonymisation of personal data in the owning service");
        cert.put("scope_description", p.get("scope_description").asString());
        cert.set("record_selector", p.get("record_selector"));
        ObjectNode data = cert.putObject("data");
        data.put("service_name", cls.get("service_name").asString());
        data.put("database_name", cls.get("database_name").asString());
        data.put("table_name", cls.get("table_name").asString());
        data.put("data_class", cls.get("data_class").asString());
        data.put("sensitivity", cls.get("sensitivity").asString());
        cert.put("records_purged", purged);
        cert.put("estimated_records", p.path("estimated_records").isNumber() ? p.get("estimated_records").asLong() : null);
        cert.set("legal_hold_exclusions", exclusions(d.exclusions()));
        cert.put("requested_by", p.path("created_by").asString(null));
        cert.put("approved_by", p.path("approved_by").asString(null));
        cert.put("executed_by", TenantContext.principal());
        cert.put("executed_at", executedAt.toString());
        if (p.hasNonNull("retention_policy_id")) {
            JsonNode policy = rows.require("retention_policy", UUID.fromString(p.get("retention_policy_id").asString()), false);
            ObjectNode pol = cert.putObject("retention_policy");
            pol.put("id", policy.get("id").asString());
            pol.put("data_class", policy.get("data_class").asString());
            pol.put("retention_years", policy.get("retention_years").asInt());
            pol.put("regulation_reference", policy.path("regulation_reference").asString(null));
        }
        String hash = Certificate.hash(cert);
        worm.put("certificates/" + TenantContext.require() + "/" + certId + ".json",
                Certificate.canonical(cert).getBytes(StandardCharsets.UTF_8), LocalDate.now().plusYears(CERTIFICATE_RETENTION_YEARS));
        return tx.execute(s -> {
            String row = rows.updateReturning("""
                    update purge_request set status = 'COMPLETED', executed_at = ?, records_purged = ?,
                           certificate_document_id = ?, certificate_hash = ?
                    where id = ? returning row_to_json(purge_request)::text""", executedAt, purged, certId, hash, id);
            outbox.publish("operations.purge_request.completed", "purge_request", id, json.readTree(row));
            return row;
        });
    }

    /** The certificate document data with an integrity check of its stored hash. */
    public JsonNode certificate(UUID id) {
        JsonNode p = rows.require("purge_request", id, false);
        if (!"COMPLETED".equals(p.get("status").asString()) || !p.hasNonNull("certificate_document_id")) {
            throw ApiException.conflict("certificate_not_available", "A certificate exists only for a completed purge");
        }
        String uri = WormStore.SCHEME + "certificates/" + TenantContext.require() + "/" + p.get("certificate_document_id").asString() + ".json";
        JsonNode cert = json.readTree(worm.get(uri));
        ObjectNode out = json.createObjectNode();
        out.put("purge_request_id", id.toString());
        out.put("certificate_document_id", p.get("certificate_document_id").asString());
        out.put("certificate_hash", p.get("certificate_hash").asString());
        out.put("hash_verified", Certificate.hash(cert).equals(p.get("certificate_hash").asString()));
        out.put("location", uri);
        out.set("certificate", cert);
        return out;
    }

    private Long estimate(JsonNode p, HoldMatcher.Decision d) {
        try {
            JsonNode cls = rows.require("data_classification", UUID.fromString(p.get("data_classification_id").asString()), false);
            ObjectNode body = json.createObjectNode();
            body.put("table_name", cls.get("table_name").asString());
            body.set("record_selector", p.get("record_selector"));
            body.set("exclude", exclusions(d.exclusions()));
            JsonNode res = source.count(cls.get("service_name").asString(), body, null);
            return res != null && res.path("count").isNumber() ? res.get("count").asLong() : null;
        } catch (ApiException e) {
            return null; // the estimate is informative; the owning service may not be reachable at submit time
        }
    }

    private ObjectNode exclusions(Map<String, Set<String>> ex) {
        ObjectNode o = json.createObjectNode();
        ex.forEach((scope, ids) -> {
            var a = o.putArray(HoldMatcher.SELECTOR_FIELDS.getOrDefault(scope, scope.toLowerCase(java.util.Locale.ROOT) + "_ids"));
            ids.forEach(a::add);
        });
        return o;
    }
}
