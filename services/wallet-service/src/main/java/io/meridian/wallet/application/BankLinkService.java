package io.meridian.wallet.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.domain.PinPolicy;
import io.meridian.wallet.infrastructure.Downstream;
import io.meridian.wallet.infrastructure.Rows;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bank accounts linked to wallets. A link starts PENDING_VERIFICATION and becomes ACTIVE once ownership is proven:
 * NAME_ENQUIRY (on-us deposit account whose holder name matches the wallet name), MICRO_DEPOSIT (code shown on the
 * bank statement, limited attempts) or MANUAL / OPEN_BANKING (verified by a checker other than the creator).
 */
@Service
public class BankLinkService {
    static final String TABLE = "wallet_bank_link";
    static final Set<String> METHODS = Set.of("NAME_ENQUIRY", "MICRO_DEPOSIT", "OPEN_BANKING", "MANUAL");
    static final int MAX_ATTEMPTS = 3;
    /** Methods verified by a checker (four-eyes); these are routed through workflow-service in workflow mode. */
    static final Set<String> CHECKER_METHODS = Set.of("OPEN_BANKING", "MANUAL");
    /** Approval resource type for checker-verified bank links in workflow-service (G11); the action is {@code verify}. */
    public static final String APPROVAL_RESOURCE = "wallet_bank_link";

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final TransactionTemplate tx;
    private final Approvals approvals;

    public BankLinkService(Rows rows, Downstream downstream, Events events, TransactionTemplate tx, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.tx = tx;
    }

    public ObjectNode create(JsonNode req) {
        return tx.execute(s -> {
            ObjectNode v = rows.columns(TABLE, req);
            ObjectNode wallet = rows.find("wallet", Rows.uuid(v, "wallet_id")).orElseThrow(() ->
                    ApiException.unprocessable("wallet_not_found", "The wallet does not exist"));
            if ("CLOSED".equals(Rows.str(wallet, "status"))) {
                throw ApiException.unprocessable("wallet_not_active", "The wallet is closed");
            }
            String method = v.hasNonNull("verification_method") ? Rows.str(v, "verification_method") : "NAME_ENQUIRY";
            if (!METHODS.contains(method)) {
                throw ApiException.unprocessable("invalid_verification_method", "verification_method must be one of " + METHODS);
            }
            if ("NAME_ENQUIRY".equals(method) && !v.hasNonNull("deposit_account_id")) {
                throw ApiException.unprocessable("deposit_account_required",
                        "Name enquiry is only available for accounts held on Meridian (deposit_account_id)");
            }
            if (rows.scalar("""
                    select count(*) from wallet_bank_link where wallet_id = ? and bank_code = ? and account_number = ?
                      and status <> 'REMOVED'""", Long.class, Rows.uuid(v, "wallet_id"), Rows.str(v, "bank_code"),
                    Rows.str(v, "account_number")).orElse(0L) > 0) {
                throw ApiException.conflict("bank_link_exists", "This bank account is already linked to the wallet");
            }
            v.put("status", "PENDING_VERIFICATION");
            v.put("verification_method", method);
            if (Rows.bool(v, "is_default")) {
                rows.execute("update wallet_bank_link set is_default = false where wallet_id = ?", Rows.uuid(v, "wallet_id"));
            }
            if ("MICRO_DEPOSIT".equals(method)) {
                // the code travels in the narrative of the micro-deposit sent by the bank rail; only its hash is kept
                v.put("verification_code_hash", PinPolicy.hashOtp(PinPolicy.otp(6)));
            }
            ObjectNode row = rows.insert(TABLE, v);
            if (CHECKER_METHODS.contains(method)) {
                // Central maker-checker: the checker verifies in the workflow inbox; BankLinkApprovalHandler applies it.
                UUID id = Rows.uuid(row, "id");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("wallet_id", Rows.str(row, "wallet_id"));
                payload.put("wallet_number", Rows.str(wallet, "wallet_number"));
                payload.put("wallet_name", Rows.str(wallet, "display_name"));
                payload.put("verification_method", method);
                payload.put("bank_code", Rows.str(row, "bank_code"));
                payload.put("account_number", Rows.str(row, "account_number"));
                payload.put("account_name", Rows.str(row, "account_name"));
                payload.put("currency", Rows.str(row, "currency"));
                approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "verify",
                        "Verify bank link " + Rows.str(row, "bank_code") + " " + Rows.str(row, "account_number")
                                + " for wallet " + Rows.str(wallet, "wallet_number"), payload), "verify:" + id);
            }
            return row;
        });
    }

    /**
     * True while a checker-verified link is PENDING_VERIFICATION for the submitted bank account (the fingerprint:
     * bank code and account number).
     */
    public boolean awaitsVerification(UUID id, String bankCode, String accountNumber) {
        return rows.find(TABLE, id)
                .filter(l -> "PENDING_VERIFICATION".equals(Rows.str(l, "status")))
                .filter(l -> CHECKER_METHODS.contains(Rows.str(l, "verification_method")))
                .filter(l -> bankCode == null || bankCode.equals(Rows.str(l, "bank_code")))
                .filter(l -> accountNumber == null || accountNumber.equals(Rows.str(l, "account_number")))
                .isPresent();
    }

    public ObjectNode verify(UUID id, String code) {
        ObjectNode link = rows.get(TABLE, id);
        if ("ACTIVE".equals(Rows.str(link, "status"))) {
            return link;
        }
        if (!"PENDING_VERIFICATION".equals(Rows.str(link, "status"))) {
            throw ApiException.conflict("invalid_transition", "The link is " + Rows.str(link, "status"));
        }
        String method = Rows.str(link, "verification_method");
        String accountName = null;
        switch (method) {
            case "NAME_ENQUIRY" -> {
                JsonNode acc = downstream.depositAccount(Rows.uuid(link, "deposit_account_id"));
                ObjectNode wallet = rows.get("wallet", Rows.uuid(link, "wallet_id"));
                if (!Rows.str(link, "currency").equals(acc.path("currency").asString())) {
                    throw ApiException.unprocessable("currency_mismatch", "The deposit account is in " + acc.path("currency").asString());
                }
                accountName = holderName(acc);
                if (accountName == null || !namesMatch(accountName, Rows.str(wallet, "display_name"))) {
                    throw ApiException.unprocessable("name_mismatch",
                            "The account holder name does not match the wallet name");
                }
                if (Rows.uuid(wallet, "customer_id") != null && acc.hasNonNull("customer_id")
                        && !Rows.str(wallet, "customer_id").equals(acc.get("customer_id").asString())) {
                    throw ApiException.unprocessable("owner_mismatch", "The deposit account belongs to another customer");
                }
            }
            case "MICRO_DEPOSIT" -> {
                if (code == null) {
                    throw ApiException.unprocessable("verification_code_required", "Give the code shown on the bank statement");
                }
                Boolean ok = tx.execute(s -> {
                    ObjectNode cur = rows.lock(TABLE, id);
                    if (PinPolicy.verifyOtp(code, Rows.str(cur, "verification_code_hash"))) {
                        return true;
                    }
                    int attempts = Rows.integer(cur, "verification_attempts") + 1;
                    ObjectNode v = rows.object();
                    v.put("verification_attempts", attempts);
                    if (attempts >= MAX_ATTEMPTS) {
                        v.put("status", "SUSPENDED");
                        v.put("status_reason", "Too many wrong verification codes");
                    }
                    rows.update(TABLE, id, v);
                    return false;
                });
                if (!Boolean.TRUE.equals(ok)) {
                    throw ApiException.unprocessable("invalid_verification_code", "The verification code is wrong");
                }
            }
            default -> {
                if (TenantContext.principal().equals(Rows.str(link, "created_by"))) {
                    throw ApiException.unprocessable("four_eyes_violation",
                            "A manually verified link must be verified by a different user than its creator");
                }
            }
        }
        String name = accountName;
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode v = rows.object();
            v.put("status", "ACTIVE");
            v.put("verified_by", TenantContext.principal());
            if (name != null) {
                v.put("account_name", name.length() > 160 ? name.substring(0, 160) : name);
            }
            rows.update(TABLE, id, v);
            rows.execute("update wallet_bank_link set verified_at = now(), verification_code_hash = null where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.wallet_bank_link.verified", TABLE, after);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "verified directly");
            return after;
        });
    }

    public ObjectNode remove(UUID id, String reason) {
        return tx.execute(s -> {
            ObjectNode link = rows.lock(TABLE, id);
            if ("REMOVED".equals(Rows.str(link, "status"))) {
                return link;
            }
            if (rows.scalar("""
                    select count(*) from wallet_transaction where wallet_bank_link_id = ?
                      and status in ('INITIATED', 'PENDING_CONFIRMATION', 'PROCESSING')""", Long.class, id).orElse(0L) > 0) {
                throw ApiException.unprocessable("transfers_in_flight", "The link has transfers in progress");
            }
            ObjectNode v = rows.object();
            v.put("status", "REMOVED");
            v.put("status_reason", reason == null ? "Removed" : reason);
            v.put("is_default", false);
            ObjectNode after = rows.update(TABLE, id, v);
            events.publish("wallets.wallet_bank_link.removed", TABLE, after);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "removed directly");
            return after;
        });
    }

    static String holderName(JsonNode acc) {
        for (String f : new String[] {"account_name", "nickname", "holder_name"}) {
            if (acc.hasNonNull(f)) {
                return acc.get(f).asString();
            }
        }
        JsonNode md = acc.get("metadata");
        return md != null && md.hasNonNull("holder_name") ? md.get("holder_name").asString() : null;
    }

    /** Names match when, ignoring case, accents and punctuation, one name's tokens contain the other's. */
    public static boolean namesMatch(String a, String b) {
        Set<String> x = tokens(a);
        Set<String> y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        return x.containsAll(y) || y.containsAll(x);
    }

    static Set<String> tokens(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim();
        return n.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(n.split("\\s+")));
    }
}
