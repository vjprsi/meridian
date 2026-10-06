package io.meridian.merchant.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchant.domain.EmvQr;
import io.meridian.merchant.domain.MerchantRules;
import io.meridian.merchant.infrastructure.Rows;
import io.meridian.merchant.infrastructure.WalletClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Merchant onboarding and lifecycle, stores and terminals. */
@Service
public class MerchantService {
    static final String TABLE = "merchant";

    private final Rows rows;
    private final WalletClient wallets;
    private final Events events;
    private final Approvals approvals;

    /**
     * Approval resource type for merchant onboarding (first activation) in workflow-service (G11); the action is
     * {@code activate}. Reactivation after a suspension stays a direct action (no submit step).
     */
    public static final String APPROVAL_RESOURCE = "merchant";

    public MerchantService(Rows rows, WalletClient wallets, Events events, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.wallets = wallets;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        if (!Rows.str(v, "mcc").matches("\\d{4}")) {
            throw ApiException.unprocessable("invalid_mcc", "mcc must be a 4-digit ISO 18245 merchant category code");
        }
        try {
            EmvQr.numericCurrency(Rows.str(v, "currency"));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("unsupported_currency", e.getMessage());
        }
        if (v.hasNonNull("merchant_fee_plan_id")) {
            requireFeePlan(Rows.uuid(v, "merchant_fee_plan_id"), Rows.str(v, "currency"));
        }
        if (v.hasNonNull("daily_limit") && Rows.dec(v, "daily_limit").signum() <= 0) {
            throw ApiException.unprocessable("invalid_daily_limit", "daily_limit must be positive");
        }
        String code;
        do {
            code = MerchantRules.merchantCode();
        } while (rows.scalar("select count(*) from merchant where merchant_code = ?", Long.class, code).orElse(0L) > 0);
        v.put("merchant_code", code);
        v.put("status", "ONBOARDING");
        ObjectNode row = rows.insert(TABLE, v);
        // Central maker-checker: onboarding is approved in the workflow inbox; MerchantApprovalHandler activates.
        UUID id = Rows.uuid(row, "id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchant_code", code);
        payload.put("legal_name", Rows.str(row, "legal_name"));
        payload.put("trading_name", Rows.str(row, "trading_name"));
        payload.put("mcc", Rows.str(row, "mcc"));
        payload.put("currency", Rows.str(row, "currency"));
        payload.put("merchant_fee_plan_id", Rows.str(row, "merchant_fee_plan_id"));
        payload.put("daily_limit", Rows.str(row, "daily_limit"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "activate",
                "Onboard merchant " + Rows.str(row, "trading_name") + " (" + code + ")", payload), "activate:" + id);
        return row;
    }

    /** True while the merchant is still ONBOARDING under the submitted merchant code (never activated). */
    @Transactional(readOnly = true)
    public boolean awaitsOnboarding(UUID id, String merchantCode) {
        return rows.find(TABLE, id)
                .filter(m -> "ONBOARDING".equals(Rows.str(m, "status")))
                .filter(m -> merchantCode == null || merchantCode.equals(Rows.str(m, "merchant_code")))
                .isPresent();
    }

    void requireFeePlan(UUID planId, String currency) {
        ObjectNode plan = rows.find("merchant_fee_plan", planId).orElseThrow(() ->
                ApiException.unprocessable("fee_plan_not_found", "The merchant fee plan does not exist"));
        if (!Rows.bool(plan, "is_active") || !currency.equals(Rows.str(plan, "currency"))) {
            throw ApiException.unprocessable("invalid_fee_plan", "The fee plan must be active and in " + currency);
        }
    }

    /** Activation (checker differs from the creator); the merchant wallet is opened on first activation. */
    @Transactional
    public ObjectNode activate(UUID id, String comment) {
        ObjectNode m = rows.lock(TABLE, id);
        String from = Rows.str(m, "status");
        MerchantRules.requireTransition(from, "ACTIVE");
        if (TenantContext.principal().equals(Rows.str(m, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "A merchant must be activated by a different user than its creator");
        }
        if (!m.hasNonNull("merchant_fee_plan_id")) {
            throw ApiException.unprocessable("fee_plan_required", "Assign a merchant fee plan before activation");
        }
        requireFeePlan(Rows.uuid(m, "merchant_fee_plan_id"), Rows.str(m, "currency"));
        ObjectNode v = rows.object();
        if (!m.hasNonNull("settlement_wallet_id")) {
            v.put("settlement_wallet_id", wallets.openMerchantWallet(id, Rows.str(m, "trading_name"),
                    Rows.str(m, "currency")).toString());
        }
        v.put("status", "ACTIVE");
        v.put("status_reason", comment);
        rows.update(TABLE, id, v);
        boolean first = !m.hasNonNull("onboarded_at");
        rows.execute("update merchant set onboarded_at = coalesce(onboarded_at, now()) where id = ?", id);
        ObjectNode row = rows.get(TABLE, id);
        events.publish(first ? "wallets.merchant.onboarded" : "wallets.merchant.status_changed", TABLE, row);
        if ("ONBOARDING".equals(from)) {
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "activated directly");
        }
        return row;
    }

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        return change(id, "SUSPENDED", reason);
    }

    /** Termination also deactivates every QR code, payment link and terminal of the merchant. */
    @Transactional
    public ObjectNode terminate(UUID id, String reason) {
        ObjectNode row = change(id, "TERMINATED", reason);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "terminated");
        rows.execute("update merchant_qr set is_active = false where merchant_id = ? and is_active", id);
        rows.execute("update merchant_payment_link set is_active = false where merchant_id = ? and is_active", id);
        rows.execute("""
                update merchant_terminal set status = 'INACTIVE', status_reason = 'Merchant terminated'
                where merchant_id = ? and status = 'ACTIVE'""", id);
        return row;
    }

    private ObjectNode change(UUID id, String to, String reason) {
        ObjectNode m = rows.lock(TABLE, id);
        MerchantRules.requireTransition(Rows.str(m, "status"), to);
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("status_reason", reason);
        ObjectNode row = rows.update(TABLE, id, v);
        events.publish("wallets.merchant.status_changed", TABLE, row);
        return row;
    }

    // ------------------------------------------------------------------ stores and terminals

    @Transactional
    public ObjectNode createStore(JsonNode req) {
        ObjectNode v = rows.columns("merchant_store", req);
        ObjectNode m = merchant(Rows.uuid(v, "merchant_id"));
        if ("TERMINATED".equals(Rows.str(m, "status"))) {
            throw ApiException.unprocessable("merchant_terminated", "The merchant is terminated");
        }
        if (v.hasNonNull("mcc") && !Rows.str(v, "mcc").matches("\\d{4}")) {
            throw ApiException.unprocessable("invalid_mcc", "mcc must be 4 digits");
        }
        return rows.insert("merchant_store", v);
    }

    @Transactional
    public ObjectNode createTerminal(JsonNode req) {
        ObjectNode v = rows.columns("merchant_terminal", req);
        ObjectNode m = merchant(Rows.uuid(v, "merchant_id"));
        if ("TERMINATED".equals(Rows.str(m, "status"))) {
            throw ApiException.unprocessable("merchant_terminated", "The merchant is terminated");
        }
        if (!java.util.Set.of("POS", "SOFT_POS", "ECOMMERCE", "VENDING").contains(Rows.str(v, "terminal_type"))) {
            throw ApiException.unprocessable("invalid_terminal_type", "terminal_type must be POS, SOFT_POS, ECOMMERCE or VENDING");
        }
        requireStoreOf(Rows.uuid(v, "merchant_store_id"), Rows.uuid(v, "merchant_id"));
        v.put("status", "ACTIVE");
        return rows.insert("merchant_terminal", v);
    }

    @Transactional
    public ObjectNode deactivateTerminal(UUID id, String reason) {
        ObjectNode t = rows.lock("merchant_terminal", id);
        if (!"ACTIVE".equals(Rows.str(t, "status"))) {
            throw ApiException.conflict("invalid_transition", "The terminal is " + Rows.str(t, "status"));
        }
        ObjectNode v = rows.object();
        v.put("status", "INACTIVE");
        v.put("status_reason", reason == null ? "Deactivated" : reason);
        return rows.update("merchant_terminal", id, v);
    }

    void requireStoreOf(UUID storeId, UUID merchantId) {
        if (storeId == null) {
            return;
        }
        ObjectNode s = rows.find("merchant_store", storeId).orElseThrow(() ->
                ApiException.unprocessable("store_not_found", "The store does not exist"));
        if (!merchantId.equals(Rows.uuid(s, "merchant_id"))) {
            throw ApiException.unprocessable("store_mismatch", "The store belongs to another merchant");
        }
    }

    ObjectNode merchant(UUID id) {
        if (id == null) {
            throw ApiException.unprocessable("merchant_required", "merchant_id is required");
        }
        return rows.find(TABLE, id).orElseThrow(() ->
                ApiException.unprocessable("merchant_not_found", "The merchant does not exist"));
    }
}
