package io.meridian.wallet.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.wallet.domain.PinPolicy;
import io.meridian.wallet.domain.WalletLifecycle;
import io.meridian.wallet.domain.WalletNumbers;
import io.meridian.wallet.infrastructure.LedgerClient;
import io.meridian.wallet.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Wallet lifecycle: opening, activation (ledger account), suspension, blocking, closure, tiers and PINs. */
@Service
public class WalletService {
    static final String TABLE = "wallet";
    static final Set<String> AGENT_TYPES = Set.of("AGENT", "SUPER_AGENT", "FLOAT", "COMMISSION");

    private final Rows rows;
    private final LedgerClient ledger;
    private final Events events;
    private final PinGuard pins;
    private final WalletProperties props;
    private final TransactionTemplate tx;

    public WalletService(Rows rows, LedgerClient ledger, Events events, PinGuard pins, WalletProperties props,
                         TransactionTemplate tx) {
        this.tx = tx;
        this.rows = rows;
        this.ledger = ledger;
        this.events = events;
        this.pins = pins;
        this.props = props;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        String type = Rows.str(v, "wallet_type");
        switch (type) {
            case "CUSTOMER" -> require(v.hasNonNull("msisdn") || v.hasNonNull("customer_id"), "owner_required",
                    "A customer wallet needs a msisdn or customer_id");
            case "MERCHANT" -> require(v.hasNonNull("merchant_id"), "owner_required", "A merchant wallet needs merchant_id");
            default -> {
                if (AGENT_TYPES.contains(type)) {
                    require(v.hasNonNull("agent_id"), "owner_required", "An agent wallet needs agent_id");
                }
            }
        }
        int tier = v.hasNonNull("kyc_tier") ? v.get("kyc_tier").asInt() : 0;
        require(tier >= 0 && tier <= 9, "invalid_kyc_tier", "kyc_tier must be 0-9");
        String msisdn = Rows.str(v, "msisdn");
        if (msisdn != null && rows.scalar("""
                select count(*) from wallet where msisdn = ? and wallet_type = ? and currency = ? and status <> 'CLOSED'""",
                Long.class, msisdn, type, Rows.str(v, "currency")).orElse(0L) > 0) {
            throw ApiException.conflict("msisdn_in_use", "An open " + type + " wallet in " + Rows.str(v, "currency")
                    + " already exists for this mobile number");
        }
        String alias = Rows.str(v, "alias");
        if (alias != null && rows.scalar("select count(*) from wallet where lower(alias) = lower(?) and status <> 'CLOSED'",
                Long.class, alias).orElse(0L) > 0) {
            throw ApiException.conflict("alias_in_use", "The alias is already used by another wallet");
        }
        String pin = Rows.str(req, "pin");
        if (pin != null) {
            PinPolicy.validateNew(pin, msisdn);
            v.put("pin_hash", PinPolicy.hash(pin, props.pin().iterations()));
            v.put("has_pin", true);
        }
        String number;
        do {
            number = WalletNumbers.generate(props.walletNumberPrefix());
        } while (rows.scalar("select count(*) from wallet where wallet_number = ?", Long.class, number).orElse(0L) > 0);
        v.put("wallet_number", number);
        v.put("status", "PENDING");
        v.put("kyc_tier", tier);
        ObjectNode row = rows.insert(TABLE, v);
        if (pin != null) {
            rows.execute("update wallet set pin_set_at = now() where id = ?", Rows.uuid(row, "id"));
            row = rows.get(TABLE, Rows.uuid(row, "id"));
        }
        history(Rows.uuid(row, "id"), null, "PENDING", "Wallet opened");
        events.publish("wallets.wallet.created", TABLE, row);
        return row;
    }

    /** Activates a pending or dormant wallet; the ledger account is opened (idempotently) on first activation. */
    @Transactional
    public ObjectNode activate(UUID id, String reason) {
        ObjectNode w = rows.lock(TABLE, id);
        String from = Rows.str(w, "status");
        WalletLifecycle.requireWalletTransition(from, "ACTIVE");
        if ("SUSPENDED".equals(from) || "BLOCKED".equals(from)) {
            throw ApiException.conflict("invalid_transition", "Use /reactivate to lift a suspension or block");
        }
        ObjectNode v = rows.object();
        if (w.get("ledger_account_id") == null || w.get("ledger_account_id").isNull()) {
            UUID account = ledger.openWalletAccount("wal-" + id + "-ledger-account", Rows.str(w, "wallet_number"),
                    Rows.str(w, "display_name"), Rows.str(w, "currency"), id);
            v.put("ledger_account_id", account.toString());
        }
        v.put("status", "ACTIVE");
        v.put("status_reason", reason);
        v.putNull("dormant_since");
        rows.update(TABLE, id, v);
        rows.execute("update wallet set activated_at = coalesce(activated_at, now()) where id = ?", id);
        return changed(id, from, "ACTIVE", reason);
    }

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        return restrict(id, "SUSPENDED", reason);
    }

    @Transactional
    public ObjectNode block(UUID id, String reason) {
        return restrict(id, "BLOCKED", reason);
    }

    private ObjectNode restrict(UUID id, String to, String reason) {
        ObjectNode w = rows.lock(TABLE, id);
        String from = Rows.str(w, "status");
        WalletLifecycle.requireWalletTransition(from, to);
        ledger.restrict("wal-" + id + "-" + to.toLowerCase() + "-" + Ids.newId(), Rows.uuid(w, "ledger_account_id"),
                WalletLifecycle.ledgerStatus(to), reason);
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("status_reason", reason);
        rows.update(TABLE, id, v);
        return changed(id, from, to, reason);
    }

    /** Lifts a suspension or block; the checker must not be the principal who imposed it. */
    @Transactional
    public ObjectNode reactivate(UUID id, String reason) {
        ObjectNode w = rows.lock(TABLE, id);
        String from = Rows.str(w, "status");
        if (!"SUSPENDED".equals(from) && !"BLOCKED".equals(from)) {
            throw ApiException.conflict("invalid_transition", "Only a suspended or blocked wallet can be reactivated");
        }
        String maker = rows.scalar("""
                select changed_by from wallet_status_history where wallet_id = ? and to_status = ?
                order by created_at desc, id desc limit 1""", String.class, id, from).orElse(null);
        io.meridian.common.tenancy.FourEyes.require(maker,
                "The wallet must be reactivated by a different user than the one who " + from.toLowerCase() + " it");
        ledger.restrict("wal-" + id + "-reactivate-" + Ids.newId(), Rows.uuid(w, "ledger_account_id"), "ACTIVE", reason);
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("status_reason", reason);
        rows.update(TABLE, id, v);
        return changed(id, from, "ACTIVE", reason);
    }

    /** Closes a wallet: no pending holds; any residual balance is swept to another active wallet of the same currency. */
    @Transactional
    public ObjectNode close(UUID id, String reason, UUID sweepTo) {
        ObjectNode w = rows.lock(TABLE, id);
        String from = Rows.str(w, "status");
        WalletLifecycle.requireWalletTransition(from, "CLOSED");
        UUID account = Rows.uuid(w, "ledger_account_id");
        if (account != null) {
            JsonNode bal = ledger.balance(account);
            BigDecimal posted = LedgerClient.dec(bal, "posted_balance");
            if (LedgerClient.dec(bal, "pending_debits").signum() > 0) {
                throw ApiException.unprocessable("pending_holds", "The wallet has pending transactions; settle them first");
            }
            if (posted.signum() > 0) {
                if (sweepTo == null) {
                    throw ApiException.unprocessable("balance_not_zero",
                            "The wallet holds " + posted.toPlainString() + "; give sweep_to_wallet_id to move it");
                }
                sweep(w, posted, sweepTo, reason);
            }
            ledger.restrict("wal-" + id + "-close", account, "CLOSED", reason);
        }
        ObjectNode v = rows.object();
        v.put("status", "CLOSED");
        v.put("status_reason", reason);
        v.put("is_default", false);
        rows.update(TABLE, id, v);
        rows.execute("update wallet set closed_at = now() where id = ?", id);
        return changed(id, from, "CLOSED", reason);
    }

    private void sweep(ObjectNode w, BigDecimal amount, UUID sweepTo, String reason) {
        UUID id = Rows.uuid(w, "id");
        if (sweepTo.equals(id)) {
            throw ApiException.unprocessable("same_wallet", "Cannot sweep a wallet into itself");
        }
        ObjectNode target = rows.find(TABLE, sweepTo)
                .orElseThrow(() -> ApiException.unprocessable("sweep_wallet_not_found", "sweep_to_wallet_id does not exist"));
        if (!"ACTIVE".equals(Rows.str(target, "status")) || !Rows.str(target, "currency").equals(Rows.str(w, "currency"))) {
            throw ApiException.unprocessable("invalid_sweep_wallet", "The sweep wallet must be active and in " + Rows.str(w, "currency"));
        }
        UUID txnId = Ids.newId();
        String key = "wal-" + id + "-close-sweep";
        UUID je = ledger.post(key, "TRANSFER", "Closing balance sweep of wallet " + Rows.str(w, "wallet_number"),
                Rows.str(w, "currency"), null, "WALLET_TRANSACTION", txnId, List.of(
                        new LedgerClient.Line(Rows.uuid(w, "ledger_account_id"), "DEBIT", amount, "Closing sweep"),
                        new LedgerClient.Line(Rows.uuid(target, "ledger_account_id"), "CREDIT", amount, "Closing sweep")));
        ObjectNode t = rows.object();
        t.put("id", txnId.toString());
        t.put("reference", WalletNumbers.reference());
        t.put("txn_type", "ADJUSTMENT");
        t.put("status", "COMPLETED");
        t.put("channel", "SYSTEM");
        t.put("payer_wallet_id", id.toString());
        t.put("payee_wallet_id", sweepTo.toString());
        t.put("payee_name", Rows.str(target, "display_name"));
        t.put("amount", amount.toPlainString());
        t.put("currency", Rows.str(w, "currency"));
        t.put("total_debit_amount", amount.toPlainString());
        t.put("initiator_type", "STAFF");
        t.put("initiated_by", TenantContext.principal());
        t.put("journal_entry_id", je.toString());
        t.put("idempotency_key", key);
        t.put("description", reason == null ? "Closing balance sweep" : "Closing balance sweep: " + reason);
        ObjectNode txn = rows.insert("wallet_transaction", t);
        rows.execute("update wallet_transaction set completed_at = now() where id = ?", txnId);
        insertLeg(txnId, 1, "PAYER", id, Rows.uuid(w, "ledger_account_id"), "DEBIT", amount, Rows.str(w, "currency"), je);
        insertLeg(txnId, 2, "PAYEE", sweepTo, Rows.uuid(target, "ledger_account_id"), "CREDIT", amount, Rows.str(w, "currency"), je);
        events.publish("wallets.wallet_transaction.completed", "wallet_transaction", rows.get("wallet_transaction", txnId));
    }

    private void insertLeg(UUID txn, int no, String role, UUID wallet, UUID account, String dir, BigDecimal amount,
                           String currency, UUID je) {
        ObjectNode l = rows.object();
        l.put("wallet_transaction_id", txn.toString());
        l.put("leg_no", no);
        l.put("role", role);
        if (wallet != null) {
            l.put("wallet_id", wallet.toString());
        }
        l.put("ledger_account_id", account.toString());
        l.put("direction", dir);
        l.put("amount", amount.toPlainString());
        l.put("currency", currency);
        if (je != null) {
            l.put("journal_entry_id", je.toString());
        }
        rows.insert("wallet_transaction_leg", l);
    }

    /** Tiers only go up; the KYC profile that verified the new tier is recorded. */
    @Transactional
    public ObjectNode upgradeTier(UUID id, int tier, UUID kycProfileId) {
        ObjectNode w = rows.lock(TABLE, id);
        if ("CLOSED".equals(Rows.str(w, "status"))) {
            throw ApiException.conflict("invalid_transition", "A closed wallet cannot change tier");
        }
        int current = Rows.integer(w, "kyc_tier");
        if (tier <= current) {
            throw ApiException.unprocessable("tier_not_higher", "New tier " + tier + " must be above the current tier " + current);
        }
        require(tier <= 9, "invalid_kyc_tier", "kyc_tier must be 0-9");
        ObjectNode v = rows.object();
        v.put("kyc_tier", tier);
        v.put("kyc_profile_id", kycProfileId.toString());
        ObjectNode row = rows.update(TABLE, id, v);
        events.publish("wallets.wallet.tier_upgraded", TABLE, row);
        return row;
    }

    /** Sets or changes the PIN; changing requires the current PIN (failed attempts count towards the lockout). */
    public ObjectNode setPin(UUID id, String newPin, String currentPin) {
        ObjectNode w = rows.get(TABLE, id);
        if ("CLOSED".equals(Rows.str(w, "status")) || "BLOCKED".equals(Rows.str(w, "status"))) {
            throw ApiException.conflict("invalid_transition", "The PIN of a " + Rows.str(w, "status").toLowerCase()
                    + " wallet cannot be changed");
        }
        PinPolicy.validateNew(newPin, Rows.str(w, "msisdn"));
        if (Rows.bool(w, "has_pin")) {
            if (currentPin == null) {
                throw ApiException.unprocessable("current_pin_required", "current_pin is required to change the PIN");
            }
            pins.require(id, currentPin);
            if (currentPin.equals(newPin)) {
                throw ApiException.unprocessable("pin_reused", "The new PIN must differ from the current PIN");
            }
        }
        return storePin(id, newPin);
    }

    /** Stores a new PIN hash and clears failures and any lock (own transaction; callers validate the PIN first). */
    public ObjectNode storePin(UUID id, String pin) {
        String hash = PinPolicy.hash(pin, props.pin().iterations());
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            rows.execute("""
                    update wallet set pin_hash = ?, has_pin = true, pin_set_at = now(), pin_failed_attempts = 0,
                           pin_locked_until = null where id = ?""", hash, id);
            ObjectNode row = rows.get(TABLE, id);
            events.publish("wallets.wallet.pin_changed", TABLE, row);
            return row;
        });
    }

    /** End of day: active customer wallets without customer activity for the dormancy period become dormant. */
    @Transactional
    public ObjectNode markDormant(LocalDate businessDate) {
        List<ObjectNode> due = rows.query("""
                select row_to_json(w)::text from wallet w
                where w.status = 'ACTIVE' and w.wallet_type = 'CUSTOMER'
                  and coalesce(w.last_activity_at, w.activated_at, w.created_at) < (?::date - make_interval(days => ?))
                for update""", businessDate, props.dormancyDays());
        for (ObjectNode w : due) {
            UUID id = Rows.uuid(w, "id");
            ObjectNode v = rows.object();
            v.put("status", "DORMANT");
            v.put("status_reason", "No customer activity for " + props.dormancyDays() + " days");
            rows.update(TABLE, id, v);
            rows.execute("update wallet set dormant_since = now() where id = ?", id);
            changed(id, "ACTIVE", "DORMANT", "Dormancy as of " + businessDate);
        }
        ObjectNode out = rows.object();
        out.put("business_date", businessDate.toString());
        out.put("dormancy_days", props.dormancyDays());
        out.put("marked_dormant", due.size());
        return out;
    }

    private ObjectNode changed(UUID id, String from, String to, String reason) {
        history(id, from, to, reason);
        ObjectNode row = rows.get(TABLE, id);
        events.publish("wallets.wallet.status_changed", TABLE, row);
        return row;
    }

    private void history(UUID id, String from, String to, String reason) {
        ObjectNode h = rows.object();
        h.put("wallet_id", id.toString());
        if (from != null) {
            h.put("from_status", from);
        }
        h.put("to_status", to);
        if (reason != null) {
            h.put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
        h.put("changed_by", TenantContext.principal());
        rows.insert("wallet_status_history", h);
    }

    private static void require(boolean ok, String code, String message) {
        if (!ok) {
            throw ApiException.unprocessable(code, message);
        }
    }
}
