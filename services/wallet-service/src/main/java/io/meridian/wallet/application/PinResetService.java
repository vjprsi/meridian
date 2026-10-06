package io.meridian.wallet.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.wallet.domain.PinPolicy;
import io.meridian.wallet.infrastructure.Downstream;
import io.meridian.wallet.infrastructure.Rows;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PIN reset: REQUESTED (OTP sent to the wallet's mobile number, or ID / agent check) -> VERIFIED -> COMPLETED with a
 * new PIN; REJECTED after too many wrong OTPs or by staff; EXPIRED after the validity window (database clock).
 */
@Service
public class PinResetService {
    static final String TABLE = "wallet_pin_reset";
    static final Set<String> METHODS = Set.of("OTP", "ID_CHECK", "AGENT", "SECURITY_QUESTIONS");

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final TransactionTemplate tx;
    private final WalletProperties props;
    private final WalletService wallets;
    private final Approvals approvals;

    /**
     * Approval resource type for staff-verified PIN resets (ID_CHECK / AGENT / SECURITY_QUESTIONS) in workflow-service
     * (G11); the action is {@code verify}. OTP resets are verified by the customer and never go to workflow-service.
     */
    public static final String APPROVAL_RESOURCE = "wallet_pin_reset";

    public PinResetService(Rows rows, Downstream downstream, Events events, TransactionTemplate tx,
                           WalletProperties props, WalletService wallets, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.tx = tx;
        this.props = props;
        this.wallets = wallets;
    }

    public ObjectNode create(JsonNode req, String key) {
        ObjectNode v = rows.columns(TABLE, req);
        String method = Rows.str(v, "verification_method");
        if (!METHODS.contains(method)) {
            throw ApiException.unprocessable("invalid_verification_method", "verification_method must be one of " + METHODS);
        }
        ObjectNode wallet = rows.find("wallet", Rows.uuid(v, "wallet_id")).orElseThrow(() ->
                ApiException.unprocessable("wallet_not_found", "The wallet does not exist"));
        if (Set.of("CLOSED", "BLOCKED").contains(Rows.str(wallet, "status"))) {
            throw ApiException.unprocessable("wallet_not_active", "The wallet is " + Rows.str(wallet, "status"));
        }
        if ("AGENT".equals(method) && !v.hasNonNull("agent_id")) {
            throw ApiException.unprocessable("agent_required", "An agent-verified reset needs agent_id");
        }
        String otp = "OTP".equals(method) ? PinPolicy.otp(6) : null;
        if (otp != null && Rows.str(wallet, "msisdn") == null) {
            throw ApiException.unprocessable("no_msisdn", "The wallet has no mobile number to send the code to");
        }
        ObjectNode row = tx.execute(s -> {
            rows.execute("""
                    update wallet_pin_reset set status = 'EXPIRED' where wallet_id = ? and status in ('REQUESTED', 'VERIFIED')""",
                    Rows.uuid(v, "wallet_id"));
            v.put("status", "REQUESTED");
            if (otp != null) {
                v.put("otp_hash", PinPolicy.hashOtp(otp));
            }
            v.put("expires_at", rows.scalar("select (now() + make_interval(mins => ?))::text", String.class,
                    props.pinResetMinutes()).orElseThrow());
            ObjectNode r = rows.insert(TABLE, v);
            events.publish("wallets.wallet_pin_reset.requested", TABLE, r);
            if (otp == null) {
                // Central maker-checker for staff verification; never carries a PIN, OTP or hash.
                UUID id = Rows.uuid(r, "id");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("wallet_id", Rows.str(r, "wallet_id"));
                payload.put("wallet_number", Rows.str(wallet, "wallet_number"));
                payload.put("wallet_name", Rows.str(wallet, "display_name"));
                payload.put("verification_method", method);
                payload.put("agent_id", Rows.str(r, "agent_id"));
                payload.put("document_id", Rows.str(r, "document_id"));
                payload.put("expires_at", Rows.str(r, "expires_at"));
                approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "verify",
                        "Verify PIN reset for wallet " + Rows.str(wallet, "wallet_number"), payload), "verify:" + id);
            }
            return r;
        });
        if (otp != null) {
            UUID notification = downstream.sendSms("wpr-" + Rows.str(row, "id") + "-otp", "CUSTOMER",
                    Rows.uuid(wallet, "customer_id"), Rows.str(wallet, "msisdn"), "SECURITY",
                    "Your wallet PIN reset code is " + otp + ". It expires in " + props.pinResetMinutes()
                            + " minutes. Never share it.");
            if (notification != null) {
                rows.execute("update wallet_pin_reset set notification_id = ? where id = ?", notification, Rows.uuid(row, "id"));
                row = rows.get(TABLE, Rows.uuid(row, "id"));
            }
        }
        return row;
    }

    public ObjectNode verify(UUID id, String otp, UUID documentId) {
        ObjectNode r = requireOpen(id, "REQUESTED");
        if ("OTP".equals(Rows.str(r, "verification_method"))) {
            if (otp == null) {
                throw ApiException.unprocessable("otp_required", "Give the code sent to the wallet's mobile number");
            }
            String outcome = tx.execute(s -> {
                ObjectNode cur = rows.lock(TABLE, id);
                if (PinPolicy.verifyOtp(otp, Rows.str(cur, "otp_hash"))) {
                    return "ok";
                }
                int attempts = Rows.integer(cur, "otp_attempts") + 1;
                ObjectNode v = rows.object();
                v.put("otp_attempts", attempts);
                if (attempts >= props.pin().otpMaxAttempts()) {
                    v.put("status", "REJECTED");
                    v.put("rejected_reason", "Too many wrong codes");
                    rows.update(TABLE, id, v);
                    return "rejected";
                }
                rows.update(TABLE, id, v);
                return "wrong";
            });
            if (!"ok".equals(outcome)) {
                throw ApiException.unprocessable("rejected".equals(outcome) ? "otp_attempts_exceeded" : "invalid_otp",
                        "rejected".equals(outcome) ? "Too many wrong codes; request a new reset" : "The code is wrong");
            }
        } else {
            if (TenantContext.principal().equals(Rows.str(r, "created_by"))) {
                throw ApiException.unprocessable("four_eyes_violation",
                        "The customer must be verified by a different user than the one who opened the request");
            }
            if ("ID_CHECK".equals(Rows.str(r, "verification_method")) && documentId == null && !r.hasNonNull("document_id")) {
                throw ApiException.unprocessable("document_required", "An ID check needs the captured document_id");
            }
        }
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode v = rows.object();
            v.put("status", "VERIFIED");
            v.put("verified_by", TenantContext.principal());
            if (documentId != null) {
                v.put("document_id", documentId.toString());
            }
            rows.update(TABLE, id, v);
            rows.execute("update wallet_pin_reset set verified_at = now(), otp_hash = null where id = ?", id);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "verified directly");
            return rows.get(TABLE, id);
        });
    }

    /** True while a staff-verified reset still awaits verification (REQUESTED, not an OTP reset). */
    public boolean awaitsVerification(UUID id) {
        return rows.find(TABLE, id)
                .filter(r -> "REQUESTED".equals(Rows.str(r, "status")))
                .filter(r -> !"OTP".equals(Rows.str(r, "verification_method")))
                .isPresent();
    }

    public ObjectNode complete(UUID id, String newPin) {
        ObjectNode r = requireOpen(id, "VERIFIED");
        ObjectNode wallet = rows.get("wallet", Rows.uuid(r, "wallet_id"));
        PinPolicy.validateNew(newPin, Rows.str(wallet, "msisdn"));
        wallets.storePin(Rows.uuid(r, "wallet_id"), newPin);
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            rows.execute("update wallet_pin_reset set status = 'COMPLETED', completed_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.wallet_pin_reset.completed", TABLE, after);
            return after;
        });
    }

    public ObjectNode reject(UUID id, String reason) {
        ObjectNode r = rows.get(TABLE, id);
        if (!Set.of("REQUESTED", "VERIFIED").contains(Rows.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "The reset request is " + Rows.str(r, "status"));
        }
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode v = rows.object();
            v.put("status", "REJECTED");
            v.put("rejected_reason", reason);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
            return rows.update(TABLE, id, v);
        });
    }

    /** The request in the expected status and not expired (an expired one is marked EXPIRED). */
    private ObjectNode requireOpen(UUID id, String expected) {
        ObjectNode r = rows.get(TABLE, id);
        if (!expected.equals(Rows.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "The reset request is " + Rows.str(r, "status"));
        }
        if (rows.scalar("select expires_at < now() from wallet_pin_reset where id = ?", Boolean.class, id).orElse(false)) {
            tx.executeWithoutResult(s -> rows.execute("update wallet_pin_reset set status = 'EXPIRED' where id = ?", id));
            throw ApiException.unprocessable("reset_expired", "The reset request expired; start a new one");
        }
        return r;
    }
}
