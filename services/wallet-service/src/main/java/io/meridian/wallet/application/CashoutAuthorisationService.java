package io.meridian.wallet.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.wallet.domain.CashoutAuthPolicy;
import io.meridian.wallet.domain.WalletLifecycle;
import io.meridian.wallet.domain.WalletNumbers;
import io.meridian.wallet.infrastructure.Downstream;
import io.meridian.wallet.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-TXN-2 (G-07): agent passcode cash-out, cardless ATM OTP cash-out with auto-rollback, and cardless / pre-authorised
 * authorisation codes.
 *
 * <p>All three reuse the WE-TXN-1 voucher seam. A registered holder authorises the movement; the <em>hold</em> leg is a
 * normal CASH_OUT (priced, limited and TCP-checked as the equivalent registered cash-out) whose credit side is the
 * e-money suspense account (2710 / IND02) rather than a payee wallet, recorded against a hashed one-time code
 * (PBKDF2, never stored or echoed in plaintext). Redeem releases the hold out of suspense on presenting the code:
 *
 * <ul>
 *   <li>AGENT_PASSCODE - suspense -&gt; the agent float wallet that disbursed the cash;</li>
 *   <li>ATM_OTP - suspense -&gt; the ATM / branch settlement float wallet on dispense;</li>
 *   <li>AUTH_CODE - suspense -&gt; the payee (captured once within the validity window).</li>
 * </ul>
 *
 * <p>On expiry the gated per-tenant scheduler (chiefly for unredeemed ATM OTPs) rolls the hold back to the customer
 * wallet. Every money move goes through {@link TransactionEngine} with deterministic keys, so retries and overlapping
 * scheduler passes never post twice; redeem and rollback are re-checked under the row lock so each happens once.
 */
@Service
public class CashoutAuthorisationService {
    private static final Logger log = LoggerFactory.getLogger(CashoutAuthorisationService.class);
    static final String TABLE = "wallet_cashout_authorisation";

    private final Rows rows;
    private final TransactionEngine engine;
    private final Downstream downstream;
    private final Events events;
    private final WalletProperties props;
    private final TransactionTemplate tx;

    public CashoutAuthorisationService(Rows rows, TransactionEngine engine, Downstream downstream, Events events,
                                       WalletProperties props, TransactionTemplate tx) {
        this.rows = rows;
        this.engine = engine;
        this.downstream = downstream;
        this.events = events;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ issuance

    /**
     * Issues one of the three authorisations: validates the registered customer wallet, moves the funds to the e-money
     * suspense account through the engine (priced / limited / TCP-checked as a registered cash-out), records the hold
     * against a hashed one-time code, and returns the authorisation together with the plaintext code (shown once only).
     */
    public ObjectNode issue(String authType, JsonNode req, String key) {
        CashoutAuthPolicy.requireKnownType(authType);
        Optional<ObjectNode> existing = rows.queryOne(
                "select row_to_json(t)::text from wallet_cashout_authorisation t where idempotency_key = ?", key);
        if (existing.isPresent()) {
            return withoutCode(existing.get()); // replay: the code is only ever returned on first issuance
        }
        UUID customerWalletId = Rows.uuid(req, "customer_wallet_id");
        if (customerWalletId == null) {
            throw ApiException.unprocessable("customer_required", "customer_wallet_id is required");
        }
        BigDecimal amount = Rows.dec(req, "amount");
        String currency = Rows.str(req, "currency");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        ObjectNode customer = customer(customerWalletId, currency);
        UUID suspense = suspenseAccount();

        var cfg = props.cashoutAuth();
        String code = CashoutAuthPolicy.generateCode(cfg.codeLength());
        String channel = channel(req, "APP");

        // the hold leg: debit the customer wallet, credit the e-money suspense account (not a payee wallet)
        ObjectNode extra = rows.object();
        copy(req, extra, "agent_outlet_id");
        copy(req, extra, "agent_id");
        extra.put("description", Rows.str(req, "description") != null ? Rows.str(req, "description")
                : defaultDescription(authType, amount, currency));
        ObjectNode txn = engine.execute(new TransactionEngine.Command(CashoutAuthPolicy.issueTxnType(authType), channel,
                CashoutAuthPolicy.initiatorType(authType), amount, currency, customerWalletId, null, null, null,
                suspense, Rows.str(req, "pin"), null, extra, key + "-hold"));

        int defaultMinutes = CashoutAuthPolicy.defaultExpiryMinutes(authType, cfg.agentPasscodeExpiryMinutes(),
                cfg.atmOtpExpiryMinutes(), cfg.authCodeExpiryMinutes());
        OffsetDateTime expiresAt = CashoutAuthPolicy.expiry(OffsetDateTime.now(), Rows.integer(req, "expiry_minutes"),
                defaultMinutes);

        ObjectNode a = rows.object();
        a.put("reference", WalletNumbers.reference());
        a.put("auth_type", authType);
        a.put("status", "ISSUED");
        a.put("customer_wallet_id", customerWalletId.toString());
        a.put("amount", amount.toPlainString());
        a.put("currency", currency);
        a.put("fee_amount", Rows.decOr0(txn, "fee_amount").toPlainString());
        a.put("tax_amount", Rows.decOr0(txn, "tax_amount").toPlainString());
        a.put("total_debit_amount", Rows.decOr0(txn, "total_debit_amount").toPlainString());
        a.put("suspense_ledger_account_id", suspense.toString());
        a.put("code_hash", CashoutAuthPolicy.codeHash(code, props.pin().iterations()));
        a.put("channel", channel);
        a.put("initiator_type", CashoutAuthPolicy.initiatorType(authType));
        a.put("initiated_by", TenantContext.principal());
        copy(req, a, "agent_id");
        copy(req, a, "agent_outlet_id");
        if ("AUTH_CODE".equals(authType)) {
            copy(req, a, "payee_wallet_id");
        }
        a.put("issue_transaction_id", Rows.str(txn, "id"));
        a.put("expires_at", expiresAt.toString());
        a.put("idempotency_key", key);
        a.put("description", Rows.str(req, "description"));
        copy(req, a, "metadata");
        ObjectNode row = tx.execute(s -> {
            ObjectNode inserted = rows.insert(TABLE, a);
            events.publish("wallets.wallet_cashout_authorisation.issued", TABLE, inserted);
            return inserted;
        });
        notifyIssued(row, customer, code);
        ObjectNode out = withoutCode(row);
        out.put("code", code); // once only, transient (never read back from the row)
        return out;
    }

    // ------------------------------------------------------------------ redeem

    /**
     * Redeems an authorisation once by presenting the one-time code: verifies the code and settles the held funds out
     * of suspense - to the agent float wallet (AGENT_PASSCODE), the ATM / branch settlement wallet (ATM_OTP) or the
     * payee (AUTH_CODE). Redeem-once is enforced under the row lock, so a retry returns the already-redeemed row.
     */
    public ObjectNode redeem(UUID id, String code, JsonNode req) {
        ObjectNode auth = verifyRedeemable(id, code);
        String authType = Rows.str(auth, "auth_type");
        UUID creditWalletId = redeemCreditWallet(authType, auth, req);
        ObjectNode credit = rows.find("wallet", creditWalletId).orElseThrow(() ->
                ApiException.unprocessable("settlement_wallet_not_found", "The settlement wallet does not exist"));
        if (!CashoutAuthPolicy.SETTLEMENT_WALLETS.contains(Rows.str(credit, "wallet_type"))
                && !"CUSTOMER".equals(Rows.str(credit, "wallet_type"))) {
            throw ApiException.unprocessable("invalid_settlement_wallet",
                    "Wallet " + Rows.str(credit, "wallet_number") + " may not receive a cash-out settlement");
        }
        if (!WalletLifecycle.canCredit(Rows.str(credit, "status"))) {
            throw ApiException.unprocessable("settlement_wallet_not_active", "The settlement wallet is "
                    + Rows.str(credit, "status"));
        }
        if (!Rows.str(auth, "currency").equals(Rows.str(credit, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The settlement wallet is in "
                    + Rows.str(credit, "currency"));
        }
        // suspense -> settlement wallet (zero-fee system movement; the hold leg already charged the customer)
        ObjectNode txn = engine.execute(new TransactionEngine.Command("CASHOUT_SETTLE", "SYSTEM", "SYSTEM",
                Rows.dec(auth, "amount"), Rows.str(auth, "currency"), null, creditWalletId, null, null,
                Rows.uuid(auth, "suspense_ledger_account_id"), null, null,
                description(rows.object(), "Cash-out " + Rows.str(auth, "reference") + " redeemed"),
                "coa-" + id + "-settle"));
        UUID agentId = Rows.uuid(req, "agent_id");
        UUID outletId = Rows.uuid(req, "agent_outlet_id");
        return tx.execute(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if ("REDEEMED".equals(Rows.str(cur, "status"))) {
                return cur;
            }
            ObjectNode u = rows.object();
            u.put("status", "REDEEMED");
            u.put("redeem_transaction_id", Rows.str(txn, "id"));
            u.put("redeemed_wallet_id", creditWalletId.toString());
            u.put("redeemed_by", TenantContext.principal());
            if (agentId != null) {
                u.put("agent_id", agentId.toString());
            }
            if (outletId != null) {
                u.put("agent_outlet_id", outletId.toString());
            }
            rows.update(TABLE, id, u);
            rows.execute("update wallet_cashout_authorisation set redeemed_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.wallet_cashout_authorisation.redeemed", TABLE, after);
            return after;
        });
    }

    /** The wallet credited on redeem, by mechanism: the agent float, the ATM/branch settlement float, or the payee. */
    private UUID redeemCreditWallet(String authType, ObjectNode auth, JsonNode req) {
        return switch (authType) {
            case "AGENT_PASSCODE" -> required(Rows.uuid(req, "agent_wallet_id"), "agent_wallet_required",
                    "agent_wallet_id is required to redeem an agent passcode cash-out");
            case "ATM_OTP" -> required(Rows.uuid(req, "settlement_wallet_id"), "settlement_wallet_required",
                    "settlement_wallet_id (the ATM / branch settlement wallet) is required to redeem an ATM OTP");
            case "AUTH_CODE" -> {
                UUID payee = Rows.uuid(req, "payee_wallet_id");
                yield required(payee != null ? payee : Rows.uuid(auth, "payee_wallet_id"), "payee_wallet_required",
                        "payee_wallet_id is required to capture an authorisation code");
            }
            default -> throw ApiException.unprocessable("invalid_auth_type",
                    "Unknown cash-out authorisation type " + authType);
        };
    }

    /** Locks the authorisation, enforces ISSUED + not expired, and checks the code (counting failed attempts). */
    private ObjectNode verifyRedeemable(UUID id, String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.unprocessable("code_required", "code is required");
        }
        return tx.execute(s -> {
            ObjectNode a = rows.lock(TABLE, id);
            String status = Rows.str(a, "status");
            if (!"ISSUED".equals(status)) {
                throw ApiException.conflict("not_redeemable", "The authorisation is " + status);
            }
            if (CashoutAuthPolicy.expired(Rows.ts(a, "expires_at"), OffsetDateTime.now())) {
                throw ApiException.unprocessable("authorisation_expired",
                        "The authorisation has expired and is awaiting rollback");
            }
            if (Rows.integer(a, "code_attempts") >= props.cashoutAuth().codeMaxAttempts()) {
                throw ApiException.unprocessable("code_locked", "Too many wrong codes; the authorisation is locked");
            }
            if (!CashoutAuthPolicy.verifyCode(code, Rows.str(a, "code_hash"))) {
                rows.execute("update wallet_cashout_authorisation set code_attempts = code_attempts + 1 where id = ?", id);
                throw ApiException.unprocessable("invalid_code", "Incorrect code");
            }
            return a;
        });
    }

    // ------------------------------------------------------------------ expiry / rollback

    /**
     * Rolls back every issued authorisation whose expiry has passed (database clock): posts a suspense -&gt; customer
     * return so the held funds are given back to the holder. Idempotent (deterministic per-row keys; status re-checked
     * under the row lock), so a retry or an overlapping scheduler pass never double-posts.
     */
    public ObjectNode expireAndRollback() {
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from wallet_cashout_authorisation t
                where t.status = 'ISSUED' and t.expires_at < now()
                order by t.expires_at limit 500""");
        int rolledBack = 0;
        List<String> failed = new ArrayList<>();
        for (ObjectNode a : due) {
            try {
                rollback(a);
                rolledBack++;
            } catch (RuntimeException e) {
                failed.add(Rows.str(a, "id"));
                log.warn("could not roll back cash-out authorisation {}: {}", Rows.str(a, "id"), e.getMessage());
            }
        }
        ObjectNode out = rows.object();
        out.put("rolled_back", rolledBack);
        out.set("failed_ids", rows.json().valueToTree(failed));
        return out;
    }

    /** Rolls one authorisation back: suspense -&gt; customer wallet (principal only; the hold-leg fee/tax stays earned). */
    private void rollback(ObjectNode auth) {
        UUID id = Rows.uuid(auth, "id");
        ObjectNode txn = engine.execute(new TransactionEngine.Command("CASHOUT_ROLLBACK", "SYSTEM", "SYSTEM",
                Rows.dec(auth, "amount"), Rows.str(auth, "currency"), null, Rows.uuid(auth, "customer_wallet_id"),
                null, null, Rows.uuid(auth, "suspense_ledger_account_id"), null, null,
                description(rows.object(), "Cash-out " + Rows.str(auth, "reference") + " expired - returned to customer"),
                "coa-" + id + "-rollback"));
        tx.executeWithoutResult(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if (!"ISSUED".equals(Rows.str(cur, "status"))) {
                return;
            }
            ObjectNode u = rows.object();
            u.put("status", "ROLLED_BACK");
            u.put("failure_reason", "Expired unredeemed - returned to customer");
            u.put("rollback_transaction_id", Rows.str(txn, "id"));
            rows.update(TABLE, id, u);
            rows.execute("update wallet_cashout_authorisation set rolled_back_at = now() where id = ?", id);
            events.publish("wallets.wallet_cashout_authorisation.rolled_back", TABLE, rows.get(TABLE, id));
        });
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode customer(UUID customerWalletId, String currency) {
        ObjectNode w = rows.find("wallet", customerWalletId).orElseThrow(() ->
                ApiException.unprocessable("customer_not_found", "The customer wallet does not exist"));
        if (!"CUSTOMER".equals(Rows.str(w, "wallet_type"))) {
            throw ApiException.unprocessable("not_customer_wallet", "A cash-out authorisation is initiated from a customer wallet");
        }
        if (!WalletLifecycle.canDebit(Rows.str(w, "status"))) {
            throw ApiException.unprocessable("customer_not_active", "The customer wallet is " + Rows.str(w, "status"));
        }
        if (!currency.equals(Rows.str(w, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The customer wallet is in " + Rows.str(w, "currency"));
        }
        return w;
    }

    private UUID suspenseAccount() {
        UUID suspense = props.gl().suspense();
        if (suspense == null) {
            throw new ApiException(422, "ledger_account_not_configured", "meridian.wallet.gl.suspense is not configured");
        }
        return suspense;
    }

    private void notifyIssued(ObjectNode auth, ObjectNode customer, String code) {
        try {
            downstream.sendSms("coa-" + Rows.str(auth, "id") + "-code", "CUSTOMER", Rows.uuid(customer, "customer_id"),
                    Rows.str(customer, "msisdn"), "SECURITY",
                    "Your one-time code to withdraw " + Rows.str(auth, "amount") + " " + Rows.str(auth, "currency")
                            + " is " + code + ". Ref " + Rows.str(auth, "reference") + ". Never share it.");
        } catch (RuntimeException e) {
            log.warn("one-time code SMS for cash-out authorisation {} could not be sent: {}",
                    Rows.str(auth, "id"), e.getMessage());
        }
    }

    private static UUID required(UUID value, String code, String message) {
        if (value == null) {
            throw ApiException.unprocessable(code, message);
        }
        return value;
    }

    private static String defaultDescription(String authType, BigDecimal amount, String currency) {
        String what = switch (authType) {
            case "AGENT_PASSCODE" -> "Agent passcode cash-out";
            case "ATM_OTP" -> "ATM cash-out";
            case "AUTH_CODE" -> "Authorisation code";
            default -> "Cash-out authorisation";
        };
        return what + " " + amount.toPlainString() + " " + currency;
    }

    private static ObjectNode withoutCode(ObjectNode row) {
        ObjectNode copy = row.deepCopy();
        copy.remove("code_hash");
        return copy;
    }

    private static ObjectNode description(ObjectNode extra, String description) {
        extra.put("description", description.length() > 255 ? description.substring(0, 255) : description);
        return extra;
    }

    private static void copy(JsonNode from, ObjectNode to, String field) {
        if (from.hasNonNull(field)) {
            to.set(field, from.get(field));
        }
    }

    private static String channel(JsonNode req, String dflt) {
        String c = Rows.str(req, "channel");
        return c == null ? dflt : c;
    }
}
