package io.meridian.wallet.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.wallet.domain.VoucherPolicy;
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
 * WE-TXN-1 (G-03, MQ-FIN-4/14/17): unregistered / voucher flows.
 *
 * <p>A sender (customer or agent) sends value to a non-subscriber identified by MSISDN. The send is a normal wallet
 * transaction (priced, limited and TCP-checked as the equivalent registered cash-in / P2P) whose credit side is the
 * e-money suspense account (2710 / IND02) instead of a payee wallet; a {@code wallet_voucher} row records the hold
 * against a hashed one-time passcode. The beneficiary later CLAIMS by registering a wallet for the MSISDN
 * (suspense -&gt; their wallet) or, on expiry, a rollback returns the funds to the sender (suspense -&gt; sender) or
 * leaves them in suspense to age to unclaimed via the WE-FLOAT-1 settlement run. Agent passcode cash-out is WE-TXN-2
 * and reuses the same voucher + passcode seam (claim entry point / ISSUED status).
 *
 * <p>All money moves through {@link TransactionEngine} with deterministic keys, so retries never post twice; the
 * passcode is returned once at issuance and never stored or echoed in plaintext.
 */
@Service
public class VoucherService {
    private static final Logger log = LoggerFactory.getLogger(VoucherService.class);
    static final String TABLE = "wallet_voucher";

    private final Rows rows;
    private final TransactionEngine engine;
    private final Downstream downstream;
    private final Events events;
    private final WalletProperties props;
    private final TransactionTemplate tx;

    public VoucherService(Rows rows, TransactionEngine engine, Downstream downstream, Events events,
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
     * Issues a voucher for one of the three send flows: validates the sender and the unregistered recipient, moves the
     * funds to suspense through the engine (pricing / limits / TCP applied), records the hold against a hashed one-time
     * passcode, and returns the voucher together with the plaintext passcode (shown this once only).
     */
    public ObjectNode issue(String voucherType, JsonNode req, String key) {
        Optional<ObjectNode> existing = rows.queryOne(
                "select row_to_json(t)::text from wallet_voucher t where idempotency_key = ?", key);
        if (existing.isPresent()) {
            return withoutPasscode(existing.get()); // replay: the passcode is only ever returned on first issuance
        }
        UUID senderWalletId = VoucherPolicy.fundedByAgent(voucherType)
                ? Rows.uuid(req, "agent_wallet_id") : Rows.uuid(req, "sender_wallet_id");
        if (senderWalletId == null) {
            throw ApiException.unprocessable("sender_required",
                    VoucherPolicy.fundedByAgent(voucherType) ? "agent_wallet_id is required" : "sender_wallet_id is required");
        }
        String recipient = Rows.str(req, "recipient_msisdn");
        if (recipient == null) {
            throw ApiException.unprocessable("recipient_required", "recipient_msisdn is required");
        }
        BigDecimal amount = Rows.dec(req, "amount");
        String currency = Rows.str(req, "currency");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        ObjectNode sender = sender(senderWalletId, voucherType, currency);
        requireUnregistered(recipient, currency);
        UUID suspense = suspenseAccount();

        String passcode = VoucherPolicy.generatePasscode(props.voucher().passcodeLength());
        String channel = channel(req, VoucherPolicy.fundedByAgent(voucherType) ? "AGENT_APP" : "APP");

        // the send leg: debit the sender wallet, credit the e-money suspense account (not a payee wallet)
        ObjectNode extra = rows.object();
        if (sender.hasNonNull("agent_id")) {
            extra.set("agent_id", sender.get("agent_id"));
        }
        copy(req, extra, "agent_outlet_id");
        copy(req, extra, "customer_note");
        extra.put("description", Rows.str(req, "description") != null ? Rows.str(req, "description")
                : "Send to unregistered " + recipient);
        ObjectNode txn = engine.execute(new TransactionEngine.Command(VoucherPolicy.issueTxnType(voucherType), channel,
                VoucherPolicy.initiatorType(voucherType), amount, currency, senderWalletId, null, null, null, suspense,
                Rows.str(req, "pin"), null, extra, key + "-issue"));

        OffsetDateTime expiresAt = VoucherPolicy.expiry(OffsetDateTime.now(), Rows.integer(req, "expiry_hours"),
                props.voucher().expiryHours());
        ObjectNode v = rows.object();
        v.put("reference", WalletNumbers.reference());
        v.put("voucher_type", voucherType);
        v.put("status", "ISSUED");
        v.put("recipient_msisdn", recipient);
        v.put("sender_wallet_id", senderWalletId.toString());
        v.put("amount", amount.toPlainString());
        v.put("currency", currency);
        v.put("fee_amount", Rows.decOr0(txn, "fee_amount").toPlainString());
        v.put("tax_amount", Rows.decOr0(txn, "tax_amount").toPlainString());
        v.put("total_debit_amount", Rows.decOr0(txn, "total_debit_amount").toPlainString());
        v.put("suspense_ledger_account_id", suspense.toString());
        v.put("passcode_hash", VoucherPolicy.hashPasscode(passcode, props.pin().iterations()));
        v.put("channel", channel);
        v.put("initiator_type", VoucherPolicy.initiatorType(voucherType));
        v.put("initiated_by", TenantContext.principal());
        if (sender.hasNonNull("agent_id")) {
            v.set("agent_id", sender.get("agent_id"));
        }
        copy(req, v, "agent_outlet_id");
        v.put("rollback_target", props.voucher().rollbackTarget());
        v.put("issue_transaction_id", Rows.str(txn, "id"));
        UUID grade = downstream.unregisteredGrade();
        if (grade != null) {
            v.put("grade_id", grade.toString());
        }
        v.put("expires_at", expiresAt.toString());
        v.put("idempotency_key", key);
        v.put("description", Rows.str(req, "description"));
        copy(req, v, "metadata");
        ObjectNode row = tx.execute(s -> {
            ObjectNode inserted = rows.insert(TABLE, v);
            events.publish("wallets.wallet_voucher.issued", TABLE, inserted);
            return inserted;
        });
        notifyIssued(row, passcode);
        ObjectNode out = withoutPasscode(row);
        out.put("passcode", passcode); // once only, transient (never read back from the row)
        return out;
    }

    // ------------------------------------------------------------------ claim by registration

    /**
     * Claims a voucher from a wallet newly registered for the recipient MSISDN: verifies the one-time passcode and
     * moves the funds out of suspense into that wallet. The claim entry point (and the ISSUED status) is the seam
     * WE-TXN-2 reuses for agent passcode cash-out.
     */
    public ObjectNode claim(UUID id, UUID claimingWalletId, String passcode) {
        if (claimingWalletId == null) {
            throw ApiException.unprocessable("claiming_wallet_required", "claiming_wallet_id is required");
        }
        ObjectNode voucher = verifyClaimable(id, passcode);
        ObjectNode wallet = rows.find("wallet", claimingWalletId).orElseThrow(() ->
                ApiException.unprocessable("claiming_wallet_not_found", "The claiming wallet does not exist"));
        if (!Rows.str(voucher, "recipient_msisdn").equals(Rows.str(wallet, "msisdn"))) {
            throw ApiException.unprocessable("recipient_mismatch",
                    "The claiming wallet is not registered to the voucher's recipient mobile number");
        }
        if (!WalletLifecycle.canCredit(Rows.str(wallet, "status"))) {
            throw ApiException.unprocessable("claiming_wallet_not_active", "The claiming wallet is " + Rows.str(wallet, "status"));
        }
        if (!Rows.str(voucher, "currency").equals(Rows.str(wallet, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The claiming wallet is in " + Rows.str(wallet, "currency"));
        }
        // suspense -> claiming wallet (zero-fee system movement; the send already charged the sender)
        ObjectNode txn = engine.execute(new TransactionEngine.Command("VOUCHER_CLAIM", "SYSTEM", "SYSTEM",
                Rows.dec(voucher, "amount"), Rows.str(voucher, "currency"), null, claimingWalletId, null, null,
                Rows.uuid(voucher, "suspense_ledger_account_id"), null, null,
                description(rows.object(), "Voucher " + Rows.str(voucher, "reference") + " claimed"),
                "vou-" + id + "-claim"));
        return tx.execute(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if ("CLAIMED".equals(Rows.str(cur, "status"))) {
                return cur;
            }
            ObjectNode u = rows.object();
            u.put("status", "CLAIMED");
            u.put("claim_transaction_id", Rows.str(txn, "id"));
            u.put("claimed_wallet_id", claimingWalletId.toString());
            u.put("claimed_by", TenantContext.principal());
            rows.update(TABLE, id, u);
            rows.execute("update wallet_voucher set claimed_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.wallet_voucher.claimed", TABLE, after);
            return after;
        });
    }

    /** Locks the voucher, enforces ISSUED + not expired, and checks the passcode (counting failed attempts). */
    private ObjectNode verifyClaimable(UUID id, String passcode) {
        if (passcode == null || passcode.isBlank()) {
            throw ApiException.unprocessable("passcode_required", "passcode is required");
        }
        return tx.execute(s -> {
            ObjectNode v = rows.lock(TABLE, id);
            String status = Rows.str(v, "status");
            if (!"ISSUED".equals(status)) {
                throw ApiException.conflict("voucher_not_claimable", "The voucher is " + status);
            }
            if (VoucherPolicy.expired(Rows.ts(v, "expires_at"), OffsetDateTime.now())) {
                throw ApiException.unprocessable("voucher_expired", "The voucher has expired and is awaiting rollback");
            }
            if (Rows.integer(v, "passcode_attempts") >= props.voucher().passcodeMaxAttempts()) {
                throw ApiException.unprocessable("passcode_locked", "Too many wrong passcodes; the voucher is locked");
            }
            if (!VoucherPolicy.verifyPasscode(passcode, Rows.str(v, "passcode_hash"))) {
                rows.execute("update wallet_voucher set passcode_attempts = passcode_attempts + 1 where id = ?", id);
                throw ApiException.unprocessable("invalid_passcode", "Incorrect passcode");
            }
            return v;
        });
    }

    // ------------------------------------------------------------------ expiry / rollback

    /**
     * Rolls back every issued voucher whose expiry has passed (database clock): SENDER vouchers post a suspense -&gt;
     * sender return, UNCLAIMED vouchers are marked EXPIRED and left in suspense to age via the WE-FLOAT-1 run.
     * Idempotent (deterministic per-voucher keys; status re-checked under the row lock), so a retry or an overlapping
     * scheduler pass never double-posts.
     */
    public ObjectNode expireAndRollback() {
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from wallet_voucher t
                where t.status = 'ISSUED' and t.expires_at < now()
                order by t.expires_at limit 500""");
        int rolledBack = 0;
        int agedToUnclaimed = 0;
        List<String> failed = new ArrayList<>();
        for (ObjectNode v : due) {
            try {
                if (rollback(v)) {
                    rolledBack++;
                } else {
                    agedToUnclaimed++;
                }
            } catch (RuntimeException e) {
                failed.add(Rows.str(v, "id"));
                log.warn("could not roll back voucher {}: {}", Rows.str(v, "id"), e.getMessage());
            }
        }
        ObjectNode out = rows.object();
        out.put("rolled_back", rolledBack);
        out.put("aged_to_unclaimed", agedToUnclaimed);
        out.set("failed_ids", rows.json().valueToTree(failed));
        return out;
    }

    /**
     * Rolls one voucher back; returns true when funds were returned to the sender, false when left to age. A
     * SENDER-target voucher returns the principal suspense -&gt; sender; WE-FIN-1 / D-ED-08: when
     * {@code refund-fee-on-expiry} is on (default), its issuance fee and tax are also refunded to the sender by
     * symmetrically reversing the send-leg income legs (fee from the fee-income GL, tax from the tax-payable GL) rather
     * than over-drawing the suspense (which only held the principal). A voucher left to age to unclaimed returns
     * nothing, so no fee is refunded. Each refund is a keyed zero-fee SYSTEM movement, so a retry never double-refunds.
     */
    private boolean rollback(ObjectNode voucher) {
        UUID id = Rows.uuid(voucher, "id");
        boolean toSender = VoucherPolicy.rollbackToSender(Rows.str(voucher, "rollback_target"));
        UUID rollbackTxn = null;
        boolean refunded = false;
        if (toSender) {
            String currency = Rows.str(voucher, "currency");
            UUID sender = Rows.uuid(voucher, "sender_wallet_id");
            String ref = Rows.str(voucher, "reference");
            // principal: suspense -> sender
            ObjectNode txn = engine.execute(new TransactionEngine.Command("VOUCHER_ROLLBACK", "SYSTEM", "SYSTEM",
                    Rows.dec(voucher, "amount"), currency, null, sender,
                    null, null, Rows.uuid(voucher, "suspense_ledger_account_id"), null, null,
                    description(rows.object(), "Voucher " + ref + " expired - returned to sender"),
                    "vou-" + id + "-rollback"));
            rollbackTxn = Rows.uuid(txn, "id");
            boolean refundFee = VoucherPolicy.refundFeeOnExpiry(props.voucher().refundFeeOnExpiry(),
                    Rows.str(voucher, "status"));
            BigDecimal fee = Rows.decOr0(voucher, "fee_amount");
            BigDecimal tax = Rows.decOr0(voucher, "tax_amount");
            if (refundFee && fee.signum() > 0) {
                engine.execute(new TransactionEngine.Command("VOUCHER_FEE_REFUND", "SYSTEM", "SYSTEM", fee, currency, null,
                        sender, null, null, feeIncomeAccount(), null, null,
                        description(rows.object(), "Voucher " + ref + " fee refunded - service not rendered"),
                        "vou-" + id + "-fee-refund"));
            }
            if (refundFee && tax.signum() > 0) {
                engine.execute(new TransactionEngine.Command("VOUCHER_TAX_REFUND", "SYSTEM", "SYSTEM", tax, currency, null,
                        sender, null, null, taxPayableAccount(), null, null,
                        description(rows.object(), "Voucher " + ref + " tax refunded - service not rendered"),
                        "vou-" + id + "-tax-refund"));
            }
            refunded = refundFee && (fee.signum() > 0 || tax.signum() > 0);
        }
        UUID rb = rollbackTxn;
        boolean didRefund = refunded;
        tx.executeWithoutResult(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if (!"ISSUED".equals(Rows.str(cur, "status"))) {
                return;
            }
            ObjectNode u = rows.object();
            u.put("status", toSender ? "ROLLED_BACK" : "EXPIRED");
            u.put("failure_reason", toSender
                    ? (didRefund ? "Expired - principal + fee/tax returned to sender" : "Expired - returned to sender")
                    : "Expired - left in suspense to age to unclaimed");
            if (rb != null) {
                u.put("rollback_transaction_id", rb.toString());
            }
            rows.update(TABLE, id, u);
            rows.execute("update wallet_voucher set rolled_back_at = now() where id = ?", id);
            events.publish("wallets.wallet_voucher.rolled_back", TABLE, rows.get(TABLE, id));
        });
        return toSender;
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode sender(UUID senderWalletId, String voucherType, String currency) {
        ObjectNode w = rows.find("wallet", senderWalletId).orElseThrow(() ->
                ApiException.unprocessable("sender_not_found", "The sender wallet does not exist"));
        if (VoucherPolicy.fundedByAgent(voucherType) && !VoucherPolicy.AGENT_WALLETS.contains(Rows.str(w, "wallet_type"))) {
            throw ApiException.unprocessable("not_agent_wallet",
                    "Wallet " + Rows.str(w, "wallet_number") + " is not an agent float wallet");
        }
        if ("P2P_UNREGISTERED".equals(voucherType) && !"CUSTOMER".equals(Rows.str(w, "wallet_type"))) {
            throw ApiException.unprocessable("not_customer_wallet", "A P2P on-the-fly is sent from a customer wallet");
        }
        if (!WalletLifecycle.canDebit(Rows.str(w, "status"))) {
            throw ApiException.unprocessable("sender_not_active", "The sender wallet is " + Rows.str(w, "status"));
        }
        if (!currency.equals(Rows.str(w, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The sender wallet is in " + Rows.str(w, "currency"));
        }
        return w;
    }

    /** Rejects a send to an MSISDN that already has an active or dormant customer wallet (use a normal P2P / cash-in). */
    private void requireUnregistered(String msisdn, String currency) {
        boolean registered = rows.scalar("""
                select count(*) from wallet where msisdn = ? and currency = ? and wallet_type = 'CUSTOMER'
                  and status in ('ACTIVE', 'DORMANT')""", Long.class, msisdn, currency).orElse(0L) > 0;
        if (registered) {
            throw ApiException.unprocessable("recipient_registered",
                    "The recipient already has a wallet; send with a normal P2P or cash-in");
        }
    }

    private UUID suspenseAccount() {
        UUID suspense = props.gl().suspense();
        if (suspense == null) {
            throw new ApiException(422, "ledger_account_not_configured", "meridian.wallet.gl.suspense is not configured");
        }
        return suspense;
    }

    /** WE-FIN-1 / D-ED-08: the fee-income GL the send leg credited, debited to refund the fee on an expiry rollback. */
    private UUID feeIncomeAccount() {
        UUID account = props.gl().feeIncome();
        if (account == null) {
            throw new ApiException(422, "ledger_account_not_configured", "meridian.wallet.gl.fee-income is not configured");
        }
        return account;
    }

    /** WE-FIN-1 / D-ED-08: the tax-payable GL the send leg credited, debited to refund the tax on an expiry rollback. */
    private UUID taxPayableAccount() {
        UUID account = props.gl().taxPayable();
        if (account == null) {
            throw new ApiException(422, "ledger_account_not_configured", "meridian.wallet.gl.tax-payable is not configured");
        }
        return account;
    }

    private void notifyIssued(ObjectNode voucher, String passcode) {
        try {
            downstream.sendSms("vou-" + Rows.str(voucher, "id") + "-passcode", "CUSTOMER", null,
                    Rows.str(voucher, "recipient_msisdn"), "SECURITY",
                    "You have received " + Rows.str(voucher, "amount") + " " + Rows.str(voucher, "currency")
                            + ". Register and claim with passcode " + passcode + ". Ref "
                            + Rows.str(voucher, "reference") + ". Never share it.");
        } catch (RuntimeException e) {
            log.warn("passcode SMS for voucher {} could not be sent: {}", Rows.str(voucher, "id"), e.getMessage());
        }
    }

    private static ObjectNode withoutPasscode(ObjectNode row) {
        ObjectNode copy = row.deepCopy();
        copy.remove("passcode_hash");
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
