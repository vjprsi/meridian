package io.meridian.reconciliation.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.FourEyes;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.ExceptionLifecycle;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adjustments and write-offs of reconciliation breaks, under four-eyes.
 * <pre>
 *   request (maker) --> PENDING_APPROVAL --approve (checker != maker)--> APPROVED: journal entry posted to
 *                                                                         ledger-service, break RESOLVED / WRITTEN_OFF
 *                                        --reject (checker != maker)---> REJECTED: break stays open
 * </pre>
 * An ADJUSTMENT books the missing side (ledger_id + balanced entries required); a WRITE_OFF closes the break as
 * WRITTEN_OFF, posting entries (e.g. to a P&amp;L write-off account) when given. One pending request per break.
 * Requests are also routed to workflow-service (central approvals, amount-based policies); the decision comes back
 * through {@link AdjustmentApprovalHandler}. The posting is idempotent per adjustment.
 */
@Service
public class AdjustmentService {
    public static final String APPROVAL_RESOURCE = "recon_adjustment";
    private static final String T = "recon_adjustment";
    private static final Set<String> KINDS = Set.of("ADJUSTMENT", "WRITE_OFF");

    private final ReconStore store;
    private final CrudEngine crud;
    private final ServiceClient services;
    private final OutboxWriter outbox;
    private final Approvals approvals;
    private final JsonMapper json;

    public AdjustmentService(ReconStore store, CrudEngine crud, ServiceClient services, OutboxWriter outbox,
                             Approvals approvals, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.services = services;
        this.outbox = outbox;
        this.approvals = approvals;
        this.json = json;
    }

    @Transactional
    public String request(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        UUID exceptionId = Fields.requireUuid(v, "recon_exception_id");
        String kind = Fields.requireText(v, "kind");
        if (!KINDS.contains(kind)) {
            throw Fields.invalid("kind", "invalid_enum", "kind must be one of " + KINDS);
        }
        BigDecimal amount = Fields.requireDecimal(v, "amount");
        if (amount.signum() <= 0) {
            throw Fields.invalid("amount", "min", "amount must be positive");
        }
        String ccy = Fields.requireText(v, "currency").trim();
        if (Fields.text(v, "reason") == null || Fields.text(v, "reason").isBlank()) {
            throw Fields.invalid("reason", "required", "reason is required");
        }
        JsonNode exception = store.requireLocked("recon_exception", exceptionId);
        if (!ExceptionLifecycle.isOpen(ExceptionLifecycle.Status.valueOf(exception.get("status").asString()))) {
            throw ApiException.conflict("exception_closed", "Exception " + exceptionId + " is "
                    + exception.get("status").asString());
        }
        validateEntries(v, kind, ccy);
        if (store.count("select count(*) from recon_adjustment where recon_exception_id = ? and status = 'PENDING_APPROVAL'",
                exceptionId) > 0) {
            throw ApiException.conflict("adjustment_pending", "Exception " + exceptionId
                    + " already has an adjustment waiting for approval");
        }
        v.put("status", "PENDING_APPROVAL");
        v.put("currency", ccy);
        v.put("requested_by", TenantContext.principal());
        JsonNode row = json.readTree(crud.insert(T, v));
        UUID id = UUID.fromString(row.get("id").asString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recon_exception_id", exceptionId.toString());
        payload.put("kind", kind);
        payload.put("amount", amount.toPlainString());
        payload.put("currency", ccy);
        payload.put("reason", v.get("reason").asString());
        payload.put("break_reason", exception.get("reason").asString());
        payload.put("posts_to_ledger", v.path("entries").isArray() && !v.path("entries").isEmpty());
        JsonNode request = approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, kind.toLowerCase(java.util.Locale.ROOT),
                ("WRITE_OFF".equals(kind) ? "Write off" : "Adjust") + " reconciliation break " + exceptionId, payload)
                .withAmount(amount, ccy), "request:" + id);
        UUID requestId = Approvals.idOf(request);
        if (requestId != null) {
            store.update("update recon_adjustment set approval_request_id = ? where id = ?", requestId, id);
        }
        JsonNode stored = store.require(T, id);
        outbox.publish("ledger.recon_adjustment.requested", T, id, stored);
        return stored.toString();
    }

    /** Checker approval: posts the journal entry (when it has entries) and closes the break. */
    @Transactional
    public String approve(UUID id) {
        JsonNode a = store.requireLocked(T, id);
        requirePending(a);
        FourEyes.require(Fields.text(a, "requested_by"), TenantContext.principal(),
                "The requester of an adjustment or write-off cannot approve it");
        UUID exceptionId = UUID.fromString(a.get("recon_exception_id").asString());
        JsonNode e = store.requireLocked("recon_exception", exceptionId);
        String kind = a.get("kind").asString();
        ExceptionLifecycle.Status to = ExceptionLifecycle.next(ExceptionLifecycle.Status.valueOf(e.get("status").asString()),
                "WRITE_OFF".equals(kind) ? ExceptionLifecycle.Action.WRITE_OFF : ExceptionLifecycle.Action.RESOLVE);
        String journalId = null;
        if (a.path("entries").isArray() && !a.path("entries").isEmpty()) {
            ObjectNode body = json.createObjectNode();
            body.put("ledger_id", a.get("ledger_id").asString());
            body.put("entry_type", "ADJUSTMENT");
            body.put("source_type", "RECON_EXCEPTION");
            body.put("source_id", exceptionId.toString());
            body.put("description", truncate(("WRITE_OFF".equals(kind) ? "Reconciliation write-off: " : "Reconciliation adjustment: ")
                    + a.get("reason").asString(), 500));
            body.put("reference", "RECON-" + exceptionId);
            body.set("entries", a.get("entries"));
            JsonNode posted = services.post("ledger-service", "/v1/journal-entries", body, "recon-adj:" + id);
            journalId = posted == null ? null : posted.path("id").asString(null);
        }
        String now = Clocks.now().toString();
        store.update("""
                update recon_adjustment set status = 'APPROVED', approved_by = ?, decided_at = ?::timestamptz,
                       journal_entry_id = ?::uuid, updated_by = ?
                where id = ?""", TenantContext.principal(), now, journalId, TenantContext.principal(), id);
        String note = ("WRITE_OFF".equals(kind) ? "Written off" : "Adjusted") + " (" + a.get("amount").asString() + " "
                + a.get("currency").asString().trim() + ") requested by " + a.get("requested_by").asString()
                + ", approved by " + TenantContext.principal() + ": " + a.get("reason").asString()
                + (journalId == null ? "" : " [ledger adjustment " + journalId + "]");
        store.update("""
                update recon_exception set status = ?, resolution_note = ?, resolved_at = now(), updated_by = ?
                where id = ?""", to.name(), note, TenantContext.principal(), exceptionId);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "decided in reconciliation-service");
        JsonNode row = store.require(T, id);
        outbox.publish("ledger.recon_adjustment.approved", T, id, row);
        outbox.publish("ledger.recon_exception.resolved", "recon_exception", exceptionId,
                store.require("recon_exception", exceptionId));
        return row.toString();
    }

    @Transactional
    public String reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw Fields.invalid("reason", "required", "A reason is required to reject an adjustment");
        }
        JsonNode a = store.requireLocked(T, id);
        requirePending(a);
        FourEyes.require(Fields.text(a, "requested_by"), TenantContext.principal(),
                "The requester of an adjustment or write-off cannot reject it");
        store.update("""
                update recon_adjustment set status = 'REJECTED', rejection_reason = ?, approved_by = ?,
                       decided_at = ?::timestamptz, updated_by = ?
                where id = ?""", truncate(reason, 500), TenantContext.principal(), Clocks.now().toString(),
                TenantContext.principal(), id);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "decided in reconciliation-service");
        JsonNode row = store.require(T, id);
        outbox.publish("ledger.recon_adjustment.rejected", T, id, row);
        return row.toString();
    }

    public boolean awaitsApproval(UUID id) {
        return store.find(T, id).map(r -> "PENDING_APPROVAL".equals(r.get("status").asString())).orElse(false);
    }

    private static void requirePending(JsonNode a) {
        if (!"PENDING_APPROVAL".equals(a.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Adjustment is " + a.get("status").asString()
                    + ", not PENDING_APPROVAL");
        }
    }

    /** ADJUSTMENT needs ledger_id and >= 2 entries; entries must be valid lines that balance per currency. */
    private static void validateEntries(JsonNode v, String kind, String ccy) {
        JsonNode entries = v.path("entries");
        boolean has = entries.isArray() && !entries.isEmpty();
        if ("ADJUSTMENT".equals(kind) && !has) {
            throw ApiException.unprocessable("invalid_adjustment", "An ADJUSTMENT needs ledger_id and balanced entries");
        }
        if (!has) {
            return;
        }
        if (!v.hasNonNull("ledger_id")) {
            throw ApiException.unprocessable("invalid_adjustment", "ledger_id is required with entries");
        }
        if (entries.size() < 2) {
            throw ApiException.unprocessable("invalid_adjustment", "entries need at least a debit and a credit line");
        }
        Map<String, BigDecimal> net = new HashMap<>();
        for (JsonNode e : entries) {
            if (!e.isObject() || Fields.uuid(e, "ledger_account_id") == null) {
                throw ApiException.unprocessable("invalid_adjustment", "Each entry needs ledger_account_id");
            }
            String dir = Fields.text(e, "direction");
            BigDecimal amt = Fields.decimal(e, "amount");
            String c = Fields.text(e, "currency") == null ? ccy : e.get("currency").asString().trim();
            if (!"DEBIT".equals(dir) && !"CREDIT".equals(dir) || amt == null || amt.signum() <= 0) {
                throw ApiException.unprocessable("invalid_adjustment",
                        "Each entry needs direction DEBIT or CREDIT and a positive amount");
            }
            net.merge(c, "DEBIT".equals(dir) ? amt : amt.negate(), BigDecimal::add);
        }
        net.forEach((c, n) -> {
            if (n.signum() != 0) {
                throw ApiException.unprocessable("unbalanced_adjustment", "Entries do not balance in " + c + " (net "
                        + n.toPlainString() + ")");
            }
        });
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
