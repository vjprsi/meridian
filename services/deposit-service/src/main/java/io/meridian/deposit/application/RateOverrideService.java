package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Account rate overrides under maker-checker (SEC-20). A new override is PENDING_APPROVAL and does not apply to
 * interest until a checker other than the maker approves it (directly or in the workflow inbox); a rejection is
 * recorded (REJECTED, rejected_by, rejection_reason). Absolute rates stay within [-0.1, 1] and adjustments within
 * +/- {@code meridian.deposit.max-rate-adjustment} (default 0.05). PATCH runs the same validation: a pending
 * override may be edited (which re-opens the approval); an ACTIVE one only gets an earlier {@code valid_to};
 * anything else is immutable. Only non-active overrides may be deleted.
 */
@Service
public class RateOverrideService {
    public static final String APPROVAL_RESOURCE = "account_rate_override";
    static final String TABLE = "account_rate_override";
    private static final Set<String> KINDS = Set.of("CREDIT_INTEREST", "DEBIT_INTEREST", "OVERDRAFT_INTEREST",
            "UNARRANGED_OVERDRAFT_INTEREST", "PENALTY_INTEREST", "BONUS_INTEREST", "CREDIT", "DEBIT", "OVERDRAFT",
            "UNARRANGED_OVERDRAFT", "PENALTY", "BONUS");
    private static final BigDecimal MIN_RATE = new BigDecimal("-0.1");

    private final Rows rows;
    private final AccountSupport accounts;
    private final Approvals approvals;
    private final BigDecimal maxAdjustment;

    public RateOverrideService(Rows rows, AccountSupport accounts, Approvals approvals,
                               @Value("${meridian.deposit.max-rate-adjustment:0.05}") BigDecimal maxAdjustment) {
        this.rows = rows;
        this.accounts = accounts;
        this.approvals = approvals;
        this.maxAdjustment = maxAdjustment;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        accounts.reference(uuid(v, "deposit_account_id"), "deposit_account_id");
        validate(v);
        v.put("status", "PENDING_APPROVAL");
        for (String serverOnly : new String[] {"approved_by", "approved_at", "rejected_by", "rejection_reason",
                "approval_request_id"}) {
            v.remove(serverOnly);
        }
        ObjectNode row = rows.insert(TABLE, v);
        return requestApproval(row);
    }

    @Transactional
    public ObjectNode update(UUID id, Integer expectedVersion, Object body) {
        ObjectNode cur = rows.lock(TABLE, id);
        if (expectedVersion != null && expectedVersion != cur.path("row_version").asInt()) {
            throw ApiException.conflict("version_conflict", "The override was changed; reload and retry");
        }
        ObjectNode changes = rows.columns(TABLE, body);
        String status = str(cur, "status");
        if ("ACTIVE".equals(status)) {
            // only end an approved override earlier; never change its rate, account, kind or start
            changes.properties().stream().map(Map.Entry::getKey).filter(k -> !k.equals("valid_to") && !k.startsWith("custom_field"))
                    .findFirst().ifPresent(k -> {
                        throw ApiException.conflict("override_immutable",
                                "An approved override can only be ended earlier (valid_to); create a new one instead");
                    });
            LocalDate to = changes.hasNonNull("valid_to") ? LocalDate.parse(changes.get("valid_to").asString()) : null;
            LocalDate old = cur.hasNonNull("valid_to") ? LocalDate.parse(cur.get("valid_to").asString()) : null;
            if (to == null || (old != null && to.isAfter(old)) || to.isBefore(LocalDate.parse(str(cur, "valid_from")))) {
                throw ApiException.unprocessable("invalid_validity",
                        "valid_to may only move an approved override's end earlier (not before valid_from)");
            }
            return rows.update(TABLE, id, changes);
        }
        if (!"PENDING_APPROVAL".equals(status)) {
            throw ApiException.conflict("override_immutable", "A " + status + " override cannot be changed");
        }
        ObjectNode merged = cur.deepCopy();
        merged.setAll(changes);
        if (changes.has("override_rate") && !changes.hasNonNull("rate_adjustment")) {
            merged.remove("rate_adjustment");
        }
        if (changes.has("rate_adjustment") && !changes.hasNonNull("override_rate")) {
            merged.remove("override_rate");
        }
        validate(merged);
        if (merged.hasNonNull("deposit_account_id") && !str(merged, "deposit_account_id").equals(str(cur, "deposit_account_id"))) {
            throw ApiException.unprocessable("field_not_updatable", "deposit_account_id cannot be changed");
        }
        ObjectNode updated = rows.update(TABLE, id, changes);
        return requestApproval(updated);
    }

    @Transactional
    public void delete(UUID id) {
        ObjectNode cur = rows.lock(TABLE, id);
        if ("ACTIVE".equals(str(cur, "status"))) {
            throw ApiException.conflict("override_active",
                    "An approved override cannot be deleted; end it with valid_to");
        }
        rows.execute("delete from account_rate_override where id = ?", id);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "override deleted");
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode cur = rows.lock(TABLE, id);
        requirePending(cur);
        AccountSupport.requireFourEyes(cur, "rate override");
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", TenantContext.principal());
        v.put("approved_at", Clocks.now().toString());
        ObjectNode approved = rows.update(TABLE, id, v);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, comment == null ? "approved directly" : "approved: " + comment);
        return approved;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode cur = rows.lock(TABLE, id);
        requirePending(cur);
        AccountSupport.requireFourEyes(cur, "rate override");
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A rejection reason is required");
        }
        ObjectNode v = rows.object();
        v.put("status", "REJECTED");
        v.put("rejected_by", TenantContext.principal());
        v.put("rejection_reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
        ObjectNode rejected = rows.update(TABLE, id, v);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return rejected;
    }

    /** True while the override still awaits the decision of exactly this approval request (edits open a new one). */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, UUID approvalRequestId) {
        return rows.find(TABLE, id)
                .filter(o -> "PENDING_APPROVAL".equals(str(o, "status")))
                .filter(o -> approvalRequestId != null && approvalRequestId.toString().equals(str(o, "approval_request_id")))
                .isPresent();
    }

    private static void requirePending(JsonNode cur) {
        if (!"PENDING_APPROVAL".equals(str(cur, "status"))) {
            throw ApiException.conflict("invalid_transition", "The override is " + str(cur, "status"));
        }
    }

    private void validate(JsonNode v) {
        if (!KINDS.contains(str(v, "rate_kind"))) {
            throw ApiException.unprocessable("invalid_rate_kind", "rate_kind must be an InterestRateKind value");
        }
        BigDecimal rate = v.hasNonNull("override_rate") ? new BigDecimal(v.get("override_rate").asString()) : null;
        BigDecimal adjustment = v.hasNonNull("rate_adjustment") ? new BigDecimal(v.get("rate_adjustment").asString()) : null;
        if ((rate == null) == (adjustment == null)) {
            throw ApiException.unprocessable("rate_required", "Give exactly one of override_rate or rate_adjustment");
        }
        if (rate != null && (rate.compareTo(MIN_RATE) < 0 || rate.compareTo(BigDecimal.ONE) > 0)) {
            throw ApiException.unprocessable("invalid_rate", "override_rate is an annual fraction between -0.1 and 1");
        }
        if (adjustment != null && adjustment.abs().compareTo(maxAdjustment) > 0) {
            throw ApiException.unprocessable("invalid_rate_adjustment",
                    "rate_adjustment must be within +/-" + maxAdjustment.toPlainString());
        }
        String from = str(v, "valid_from");
        String to = str(v, "valid_to");
        if (from == null) {
            throw ApiException.unprocessable("invalid_validity", "valid_from is required");
        }
        if (to != null && LocalDate.parse(to).isBefore(LocalDate.parse(from))) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
        if (str(v, "reason") == null || str(v, "reason").isBlank()) {
            throw ApiException.unprocessable("reason_required", "reason is required");
        }
    }

    /** Central maker-checker (workflow inbox); the request id is stored, so an edit supersedes the open request. */
    private ObjectNode requestApproval(ObjectNode row) {
        UUID id = uuid(row, "id");
        String version = row.path("row_version").asString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deposit_account_id", str(row, "deposit_account_id"));
        payload.put("rate_kind", str(row, "rate_kind"));
        payload.put("override_rate", str(row, "override_rate"));
        payload.put("rate_adjustment", str(row, "rate_adjustment"));
        payload.put("valid_from", str(row, "valid_from"));
        payload.put("valid_to", str(row, "valid_to"));
        payload.put("reason", str(row, "reason"));
        String rate = str(row, "override_rate") != null ? "rate " + str(row, "override_rate")
                : "adjustment " + str(row, "rate_adjustment");
        JsonNode request = approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Rate override (" + str(row, "rate_kind") + ", " + rate + ")", payload), "request:" + id + ":" + version);
        UUID requestId = Approvals.idOf(request);
        if (requestId == null) {
            return row;
        }
        approvals.cancelOpenExcept(APPROVAL_RESOURCE, id, null, requestId, "superseded");
        ObjectNode v = rows.object();
        v.put("approval_request_id", requestId.toString());
        return rows.update(TABLE, id, v);
    }
}
