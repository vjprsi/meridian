package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.decOr0;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Overdraft facilities. The approved limit of the ACTIVE facility is mirrored on the deposit account and on the
 * ledger account ({@code overdraft_limit}), where it extends the available balance for postings.
 */
@Service
public class OverdraftService {
    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final FeeService fees;

    private final Approvals approvals;

    /** Approval resource type for overdraft facility credit approvals in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "overdraft_facility";

    public OverdraftService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events, FeeService fees,
                            Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.fees = fees;
    }

    @Transactional
    public ObjectNode request(Object body) {
        ObjectNode v = rows.columns("overdraft_facility", body);
        UUID accountId = uuid(v, "deposit_account_id");
        accounts.reference(accountId, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        accounts.requireNotClosed(acc);
        accounts.requireCurrency(acc, str(v, "currency"));
        BigDecimal limit = dec(v, "limit_amount");
        validateLimit(acc, limit);
        LocalDate starts = date(v, "starts_on");
        LocalDate expires = date(v, "expires_on");
        if (expires != null && !expires.isAfter(starts)) {
            throw ApiException.unprocessable("invalid_expiry", "expires_on must be after starts_on");
        }
        BigDecimal rate = dec(v, "interest_rate");
        if (rate != null && (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0)) {
            throw ApiException.unprocessable("invalid_rate", "interest_rate is an annual fraction between 0 and 1");
        }
        if (rows.scalar("""
                select count(*) from overdraft_facility where deposit_account_id = ?
                  and status in ('PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED')""", Long.class, accountId).orElse(0L) > 0) {
            throw ApiException.conflict("facility_exists", "The account already has an open overdraft facility");
        }
        v.put("status", "PENDING_APPROVAL");
        ObjectNode row = rows.insert("overdraft_facility", v);
        requestApproval(row, acc);
        return row;
    }

    /** Central maker-checker: the credit officer decides in the workflow inbox; routed by the limit amount. */
    private void requestApproval(ObjectNode f, JsonNode acc) {
        UUID id = uuid(f, "id");
        BigDecimal limit = dec(f, "limit_amount");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deposit_account_id", str(f, "deposit_account_id"));
        payload.put("account_number", str(acc, "account_number"));
        payload.put("overdraft_type", str(f, "overdraft_type"));
        payload.put("limit_amount", limit.toPlainString());
        payload.put("currency", str(f, "currency"));
        payload.put("interest_rate", str(f, "interest_rate"));
        payload.put("starts_on", str(f, "starts_on"));
        payload.put("expires_on", str(f, "expires_on"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Overdraft " + limit.toPlainString() + " " + str(f, "currency") + " on account " + str(acc, "account_number"),
                payload).withAmount(limit, str(f, "currency")), "request:" + id + ":" + limit.toPlainString());
    }

    /** True while the facility still awaits approval with the requested limit unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, BigDecimal limit) {
        return rows.find("overdraft_facility", id)
                .filter(f -> "PENDING_APPROVAL".equals(str(f, "status")))
                .filter(f -> limit == null || limit.compareTo(decOr0(f, "limit_amount")) == 0)
                .isPresent();
    }

    private void validateLimit(JsonNode acc, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) {
            throw ApiException.unprocessable("invalid_limit", "limit_amount must be positive");
        }
        if (!Currencies.fitsMinorUnit(limit, str(acc, "currency"))) {
            throw ApiException.unprocessable("invalid_limit", "limit_amount has too many decimals");
        }
        ProductSnapshot.of(acc).overdraftMaxLimit().ifPresent(max -> {
            if (limit.compareTo(max) > 0) {
                throw ApiException.unprocessable("limit_above_product_maximum",
                        "limit_amount exceeds the product maximum " + max.toPlainString());
            }
        });
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment, String clientKey) {
        ObjectNode f = rows.lock("overdraft_facility", id);
        Lifecycle.OVERDRAFT_FACILITY.require(str(f, "status"), "ACTIVE");
        AccountSupport.requireFourEyes(f, "overdraft facility");
        ObjectNode acc = accounts.lock(uuid(f, "deposit_account_id"));
        UUID ledgerAccount = accounts.requireLedger(acc);
        accounts.requireNotClosed(acc);
        BigDecimal limit = dec(f, "limit_amount");
        ledger.setOverdraftLimit(ledgerAccount, limit, "Overdraft facility approved", limitKey(f, "approve"));
        ObjectNode a = rows.object();
        a.put("overdraft_limit", limit.toPlainString());
        rows.update(AccountSupport.TABLE, uuid(acc, "id"), a);
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", TenantContext.principal());
        ObjectNode approved = rows.update("overdraft_facility", id, v);
        BigDecimal fee = dec(f, "arrangement_fee");
        if (fee != null && fee.signum() > 0) {
            fees.chargeLocked(acc, "OVERDRAFT_ARRANGEMENT", fee, null, "OVERDRAFT_FACILITY", id,
                    "Overdraft arrangement fee", "arrangement:" + id);
        }
        events.publish("accounts.overdraft_facility.approved", "overdraft_facility", approved);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return approved;
    }

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        ObjectNode f = rows.lock("overdraft_facility", id);
        Lifecycle.OVERDRAFT_FACILITY.require(str(f, "status"), "SUSPENDED");
        return withdrawLimit(f, "SUSPENDED", reason);
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode f = rows.lock("overdraft_facility", id);
        Lifecycle.OVERDRAFT_FACILITY.require(str(f, "status"), "CANCELLED");
        if ("PENDING_APPROVAL".equals(str(f, "status"))) {
            ObjectNode v = rows.object();
            v.put("status", "CANCELLED");
            ObjectNode cancelled = rows.update("overdraft_facility", id, v);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, reason == null ? "cancelled" : reason);
            return cancelled;
        }
        return withdrawLimit(f, "CANCELLED", reason);
    }

    /** Removes the limit from account and ledger; flags an excess when the account is already overdrawn. */
    private ObjectNode withdrawLimit(ObjectNode f, String status, String reason) {
        UUID id = uuid(f, "id");
        ObjectNode acc = accounts.lock(uuid(f, "deposit_account_id"));
        ObjectNode v = rows.object();
        v.put("status", status);
        if ("ACTIVE".equals(str(f, "status"))) {
            UUID ledgerAccount = accounts.requireLedger(acc);
            ledger.setOverdraftLimit(ledgerAccount, BigDecimal.ZERO, "Overdraft facility " + status.toLowerCase(java.util.Locale.ROOT)
                    + (reason == null ? "" : ": " + reason), limitKey(f, status.toLowerCase(java.util.Locale.ROOT)));
            ObjectNode a = rows.object();
            a.put("overdraft_limit", "0");
            rows.update(AccountSupport.TABLE, uuid(acc, "id"), a);
            BigDecimal utilised = utilisation(ledgerAccount);
            v.put("utilised_amount", utilised.toPlainString());
            ObjectNode updated = rows.update("overdraft_facility", id, v);
            if (utilised.signum() > 0) {
                events.publish("accounts.overdraft_facility.limit_exceeded", "overdraft_facility", updated);
            }
            return updated;
        }
        return rows.update("overdraft_facility", id, v);
    }

    /** Ledger key per facility state change: stable when the same change is retried, new for every later change. */
    private static String limitKey(JsonNode facility, String action) {
        return LedgerPort.key("overdraft-limit", uuid(facility, "id"), action + ":" + facility.path("row_version").asInt(1));
    }

    @Transactional
    public ObjectNode changeLimit(UUID id, BigDecimal newLimit, String reason) {
        ObjectNode f = rows.lock("overdraft_facility", id);
        String status = str(f, "status");
        Lifecycle.OVERDRAFT_FACILITY.requireIn(status, "change the limit of", "PENDING_APPROVAL", "ACTIVE");
        ObjectNode acc = accounts.lock(uuid(f, "deposit_account_id"));
        validateLimit(acc, newLimit);
        if (newLimit.compareTo(decOr0(f, "limit_amount")) == 0) {
            throw ApiException.unprocessable("limit_unchanged", "The facility already has this limit");
        }
        ObjectNode v = rows.object();
        v.put("limit_amount", newLimit.toPlainString());
        if ("ACTIVE".equals(status)) {
            UUID ledgerAccount = accounts.requireLedger(acc);
            BigDecimal utilised = utilisation(ledgerAccount);
            if (newLimit.compareTo(utilised) < 0) {
                throw ApiException.unprocessable("limit_below_utilisation", "The account is overdrawn by "
                        + utilised.toPlainString() + "; the limit cannot go below the current utilisation");
            }
            ledger.setOverdraftLimit(ledgerAccount, newLimit, reason, limitKey(f, "change-limit"));
            ObjectNode a = rows.object();
            a.put("overdraft_limit", newLimit.toPlainString());
            rows.update(AccountSupport.TABLE, uuid(acc, "id"), a);
            v.put("utilised_amount", utilised.toPlainString());
        }
        ObjectNode row = rows.update("overdraft_facility", id, v);
        if ("PENDING_APPROVAL".equals(status)) {
            // the open request is for the old limit (its decision is ignored); approval is asked for the new one
            requestApproval(row, acc);
        }
        return row;
    }

    private BigDecimal utilisation(UUID ledgerAccount) {
        BigDecimal posted = LedgerPort.posted(ledger.balance(ledgerAccount));
        return posted.signum() < 0 ? posted.negate() : BigDecimal.ZERO;
    }
}
