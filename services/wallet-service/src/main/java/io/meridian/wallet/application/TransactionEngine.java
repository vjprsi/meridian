package io.meridian.wallet.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.wallet.domain.FeeCalculator;
import io.meridian.wallet.domain.LimitEvaluator;
import io.meridian.wallet.domain.PinPolicy;
import io.meridian.wallet.domain.WalletLifecycle;
import io.meridian.wallet.domain.WalletNumbers;
import io.meridian.wallet.infrastructure.Downstream;
import io.meridian.wallet.infrastructure.LedgerClient;
import io.meridian.wallet.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The wallet money engine. A transaction is validated (wallets, currency, PIN, fees, tier limits, risk), recorded as
 * INITIATED, then posted to the ledger either at once (one balanced journal entry: payer total, payee principal, fee
 * and tax GL) or in two phases (a hold for the total, captured on confirm or released on cancel / expiry). Ledger
 * calls carry deterministic keys derived from the transaction id, so a retried request never posts twice; ledger
 * rejections after the row exists are recorded as FAILED before the error is returned.
 */
@Service
public class TransactionEngine {
    private static final Logger log = LoggerFactory.getLogger(TransactionEngine.class);
    static final String TABLE = "wallet_transaction";
    static final Set<String> INITIATORS = Set.of("CUSTOMER", "AGENT", "MERCHANT", "SYSTEM", "BULK", "STAFF");
    static final Set<String> INTERACTIVE = Set.of("USSD", "SMS", "APP", "WEB", "QR", "AGENT_APP", "POS");
    /** Columns a caller may set on a transaction besides the core fields. */
    static final List<String> EXTRA = List.of("agent_id", "agent_outlet_id", "merchant_id", "biller_id", "bill_payment_id",
            "disbursement_beneficiary_id", "interop_transfer_id", "wallet_bank_link_id", "loan_id",
            "original_transaction_id", "payee_external_ref", "description", "customer_note", "device_id", "ip_address",
            "geo_lat", "geo_lon", "metadata", "custom_field_1", "custom_field_2", "custom_field_3", "custom_field_4",
            "custom_field_5");

    private final Rows rows;
    private final LedgerClient ledger;
    private final Downstream downstream;
    private final Tariffs tariffs;
    private final PinGuard pins;
    private final Events events;
    private final TransactionTemplate tx;
    private final WalletProperties props;
    private final Approvals approvals;

    /** Approval resource type for requested reversals in workflow-service (G11); the action is {@code reverse}. */
    public static final String REVERSAL_RESOURCE = "wallet_transaction";

    public TransactionEngine(Rows rows, LedgerClient ledger, Downstream downstream, Tariffs tariffs, PinGuard pins,
                             Events events, TransactionTemplate tx, WalletProperties props, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.ledger = ledger;
        this.downstream = downstream;
        this.tariffs = tariffs;
        this.pins = pins;
        this.events = events;
        this.tx = tx;
        this.props = props;
    }

    /**
     * A money movement request.
     *
     * @param counterparty        internal ledger account on the side that is not a wallet
     * @param confirmationMethod  null = single phase; PIN / OTP / SYSTEM = two phase (hold until confirmed)
     * @param extra               additional transaction columns (references, narrative, device)
     */
    public record Command(String txnType, String channel, String initiatorType, BigDecimal amount, String currency,
                          UUID payerWalletId, UUID payeeWalletId, String payeeMsisdn, String payeeAlias,
                          UUID counterparty, String pin, String confirmationMethod, ObjectNode extra, String key) {}

    // ------------------------------------------------------------------ initiation

    public ObjectNode execute(Command c) {
        Optional<ObjectNode> existing = rows.queryOne(
                "select row_to_json(t)::text from wallet_transaction t where idempotency_key = ?", c.key());
        if (existing.isPresent()) {
            return resume(existing.get());
        }
        Prepared p = prepare(c);
        UUID id = Ids.newId();
        Downstream.LimitCheck limit = p.payer == null ? new Downstream.LimitCheck(true, null, null)
                : downstream.reserveLimits("wtx-" + id + "-limits", Rows.uuid(p.payer, "id"),
                        Rows.uuid(p.payer, "customer_id"), c.txnType(), c.channel(), p.total, c.currency(), c.key());
        if (!limit.allowed()) {
            throw ApiException.unprocessable("limit_exceeded", limit.reason());
        }
        ObjectNode row;
        try {
            Downstream.FraudResult fraud = p.payer != null && Set.of("CUSTOMER", "AGENT").contains(c.initiatorType())
                    ? downstream.score("wtx-" + id + "-fraud", id, Rows.uuid(p.payer, "customer_id"),
                            Rows.uuid(p.payer, "ledger_account_id"), c.amount(), c.currency(), c.channel(),
                            Rows.str(c.extra(), "ip_address"), fraudContext(c, p))
                    : Downstream.FraudResult.SKIPPED;
            if ("BLOCK".equals(fraud.decision())) {
                throw ApiException.unprocessable("fraud_blocked", "The transaction was declined by fraud screening");
            }
            row = tx.execute(s -> insert(id, c, p, limit, fraud));
        } catch (RuntimeException e) {
            downstream.releaseLimits(limit.id(), "rejected: " + e.getMessage(), "wtx-" + id + "-limits-release");
            throw e;
        }
        return process(row);
    }

    private ObjectNode fraudContext(Command c, Prepared p) {
        ObjectNode ctx = rows.object();
        ctx.put("txn_type", c.txnType());
        ctx.put("initiator_type", c.initiatorType());
        if (p.payee != null) {
            ctx.put("payee_wallet_id", Rows.str(p.payee, "id"));
            ctx.put("payee_kyc_tier", Rows.integer(p.payee, "kyc_tier"));
        }
        ctx.put("payer_kyc_tier", Rows.integer(p.payer, "kyc_tier"));
        return ctx;
    }

    /** Resolved parties and amounts of a command. */
    static final class Prepared {
        ObjectNode payer;
        ObjectNode payee;
        UUID debitAccount;
        UUID creditAccount;
        FeeCalculator.Fee fee;
        BigDecimal total;
    }

    Prepared prepare(Command c) {
        if (c.txnType() == null || c.channel() == null || c.initiatorType() == null || c.amount() == null
                || c.currency() == null) {
            throw ApiException.unprocessable("invalid_request", "txn_type, channel, initiator_type, amount and currency are required");
        }
        if (!INITIATORS.contains(c.initiatorType())) {
            throw ApiException.unprocessable("invalid_initiator_type", "initiator_type must be one of " + INITIATORS);
        }
        if ("REVERSAL".equals(c.txnType())) {
            throw ApiException.unprocessable("use_reverse", "Reversals are created with POST /v1/wallet-transactions/{id}/reverse");
        }
        if (c.amount().signum() <= 0 || c.amount().stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
        }
        Prepared p = new Prepared();
        p.payer = c.payerWalletId() == null ? null : wallet(c.payerWalletId(), "payer");
        p.payee = resolvePayee(c);
        if (p.payer == null && p.payee == null) {
            throw ApiException.unprocessable("payee_required", "A payer and a payee wallet (or one wallet and counterparty_ledger_account_id) are required");
        }
        if (p.payer == null && c.counterparty() == null) {
            throw ApiException.unprocessable("payer_required", "payer_wallet_id or counterparty_ledger_account_id is required");
        }
        if (p.payee == null && c.counterparty() == null) {
            throw ApiException.unprocessable("payee_required", "A payee wallet, msisdn, alias or counterparty_ledger_account_id is required");
        }
        if (p.payer != null && p.payee != null && c.counterparty() != null) {
            throw ApiException.unprocessable("invalid_counterparty",
                    "counterparty_ledger_account_id may replace only one side of a wallet transaction");
        }
        if (p.payer != null && p.payee != null && Rows.str(p.payer, "id").equals(Rows.str(p.payee, "id"))) {
            throw ApiException.unprocessable("same_wallet", "Payer and payee must be different wallets");
        }
        if (p.payer != null) {
            requireCurrency(p.payer, c.currency());
            if (!WalletLifecycle.canDebit(Rows.str(p.payer, "status"))) {
                throw ApiException.unprocessable("wallet_not_active", "The payer wallet is " + Rows.str(p.payer, "status"));
            }
            p.debitAccount = account(p.payer);
        } else {
            p.debitAccount = c.counterparty();
        }
        if (p.payee != null) {
            requireCurrency(p.payee, c.currency());
            if (!WalletLifecycle.canCredit(Rows.str(p.payee, "status"))) {
                throw ApiException.unprocessable("payee_not_active", "The payee wallet is " + Rows.str(p.payee, "status"));
            }
            p.creditAccount = account(p.payee);
        } else {
            p.creditAccount = c.counterparty();
        }
        if (c.pin() != null) {
            if (p.payer == null) {
                throw ApiException.unprocessable("invalid_request", "pin is only accepted when a payer wallet is debited");
            }
            pins.require(Rows.uuid(p.payer, "id"), c.pin());
        } else if (p.payer != null && c.confirmationMethod() == null
                && Set.of("CUSTOMER", "AGENT").contains(c.initiatorType()) && INTERACTIVE.contains(c.channel())
                && Rows.bool(p.payer, "has_pin")) {
            throw ApiException.unprocessable("pin_required", "The payer must confirm with the wallet PIN");
        }
        p.fee = tariffs.fee(c.txnType(), c.channel(), p.payer, c.amount(), c.currency());
        p.total = c.amount().add(p.fee.total());
        return p;
    }

    private ObjectNode resolvePayee(Command c) {
        if (c.payeeWalletId() != null) {
            return wallet(c.payeeWalletId(), "payee");
        }
        if (c.payeeMsisdn() != null || c.payeeAlias() != null) {
            return rows.queryOne("""
                    select row_to_json(w)::text from wallet w
                    where (?::text is null or w.msisdn = ?) and (?::text is null or lower(w.alias) = lower(?))
                      and w.currency = ? and w.status in ('ACTIVE', 'DORMANT')
                      and w.wallet_type in ('CUSTOMER', 'MERCHANT', 'AGENT', 'SUPER_AGENT')
                    order by (w.wallet_type = 'CUSTOMER') desc, w.is_default desc, w.created_at limit 1""",
                    c.payeeMsisdn(), c.payeeMsisdn(), c.payeeAlias(), c.payeeAlias(), c.currency())
                    .orElseThrow(() -> ApiException.unprocessable("payee_not_found",
                            "No active " + c.currency() + " wallet is registered for the payee"));
        }
        return null;
    }

    private ObjectNode wallet(UUID id, String role) {
        return rows.find("wallet", id).orElseThrow(() -> ApiException.unprocessable(role + "_not_found",
                "The " + role + " wallet " + id + " does not exist"));
    }

    private static UUID account(ObjectNode w) {
        UUID a = Rows.uuid(w, "ledger_account_id");
        if (a == null) {
            throw ApiException.unprocessable("wallet_not_activated", "Wallet " + Rows.str(w, "wallet_number")
                    + " has never been activated");
        }
        return a;
    }

    private static void requireCurrency(ObjectNode w, String currency) {
        if (!currency.equals(Rows.str(w, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "Wallet " + Rows.str(w, "wallet_number") + " is in "
                    + Rows.str(w, "currency") + ", not " + currency);
        }
    }

    /** Inside a transaction: locks the payer, enforces tier limits and the payee maximum balance, records INITIATED. */
    private ObjectNode insert(UUID id, Command c, Prepared p, Downstream.LimitCheck limit, Downstream.FraudResult fraud) {
        if (p.payer != null) {
            ObjectNode locked = rows.lock("wallet", Rows.uuid(p.payer, "id"));
            if (!WalletLifecycle.canDebit(Rows.str(locked, "status"))) {
                throw ApiException.unprocessable("wallet_not_active", "The payer wallet is " + Rows.str(locked, "status"));
            }
            for (LimitEvaluator.Limit l : tariffs.limits(locked, c.txnType())) {
                LimitEvaluator.checkDebit(l, tariffs.usage(Rows.uuid(locked, "id"), l.txnType()), c.amount())
                        .ifPresent(v -> {
                            throw ApiException.unprocessable(v.code(), v.message());
                        });
            }
        }
        if (p.payee != null) {
            for (LimitEvaluator.Limit l : tariffs.limits(p.payee, null)) {
                if (l.txnType() == null && l.maxBalance() != null) {
                    BigDecimal balance = LedgerClient.dec(ledger.balance(p.creditAccount), "posted_balance");
                    LimitEvaluator.checkBalance(l, balance, c.amount()).ifPresent(v -> {
                        throw ApiException.unprocessable(v.code(), v.message());
                    });
                }
            }
        }
        ObjectNode v = rows.object();
        v.put("id", id.toString());
        v.put("reference", WalletNumbers.reference());
        v.put("txn_type", c.txnType());
        v.put("status", "INITIATED");
        v.put("channel", c.channel());
        v.put("initiator_type", c.initiatorType());
        v.put("initiated_by", TenantContext.principal());
        if (p.payer != null) {
            v.put("payer_wallet_id", Rows.str(p.payer, "id"));
            v.put("kyc_tier", Rows.integer(p.payer, "kyc_tier"));
        }
        if (p.payee != null) {
            v.put("payee_wallet_id", Rows.str(p.payee, "id"));
            v.put("payee_name", Rows.str(p.payee, "display_name"));
            if (c.payeeMsisdn() != null) {
                v.put("payee_msisdn", c.payeeMsisdn());
            }
        }
        if (c.counterparty() != null) {
            v.put("counterparty_ledger_account_id", c.counterparty().toString());
        }
        v.put("amount", c.amount().toPlainString());
        v.put("currency", c.currency());
        v.put("fee_amount", p.fee.fee().toPlainString());
        v.put("tax_amount", p.fee.tax().toPlainString());
        v.put("total_debit_amount", p.total.toPlainString());
        if (limit.id() != null) {
            v.put("limit_check_id", limit.id().toString());
        }
        if (fraud.id() != null) {
            v.put("fraud_score_id", fraud.id().toString());
        }
        if (fraud.decision() != null) {
            v.put("fraud_decision", fraud.decision());
        }
        if (fraud.score() != null) {
            v.put("risk_score", fraud.score().toPlainString());
        }
        if (c.confirmationMethod() != null) {
            v.put("confirmation_method", c.confirmationMethod());
        }
        v.put("idempotency_key", c.key());
        if (c.extra() != null) {
            for (String f : EXTRA) {
                if (c.extra().hasNonNull(f)) {
                    v.set(f, c.extra().get(f));
                }
            }
        }
        if (!v.hasNonNull("description")) {
            v.put("description", defaultDescription(c.txnType()));
        }
        ObjectNode row = rows.insert(TABLE, v);
        events.publish("wallets.wallet_transaction.initiated", TABLE, row);
        return row;
    }

    static String defaultDescription(String txnType) {
        String s = txnType.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ posting

    /** Continues a transaction that was recorded but not yet posted (fresh, or a crash / 5xx retry). */
    private ObjectNode resume(ObjectNode row) {
        return switch (Rows.str(row, "status")) {
            case "INITIATED" -> process(row);
            case "FAILED" -> throw new ApiException(422, Rows.str(row, "failure_code"), Rows.str(row, "failure_reason"));
            default -> row;
        };
    }

    private ObjectNode process(ObjectNode row) {
        UUID id = Rows.uuid(row, "id");
        if (Rows.str(row, "confirmation_method") != null) {
            UUID hold;
            try {
                hold = ledger.placeHold("wtx-" + id + "-hold", debitAccount(row), dec(row, "total_debit_amount"),
                        Rows.str(row, "currency"), Rows.str(row, "description") + " " + Rows.str(row, "reference"), id);
            } catch (ApiException e) {
                throw fail(row, e);
            }
            String otp = "OTP".equals(Rows.str(row, "confirmation_method")) ? PinPolicy.otp(6) : null;
            ObjectNode pending = tx.execute(s -> {
                ObjectNode v = rows.object();
                v.put("status", "PENDING_CONFIRMATION");
                v.put("hold_id", hold.toString());
                if (otp != null) {
                    v.put("confirmation_otp_hash", PinPolicy.hashOtp(otp));
                }
                rows.update(TABLE, id, v);
                rows.execute("update wallet_transaction set confirmation_expires_at = now() + make_interval(mins => ?) where id = ?",
                        props.confirmationMinutes(), id);
                return rows.get(TABLE, id);
            });
            if (otp != null) {
                sendOtp(pending, otp);
            }
            return pending;
        }
        List<LedgerClient.Line> lines = lines(row, dec(row, "amount"), true);
        UUID je;
        try {
            je = ledger.post("wtx-" + id + "-post", entryType(Rows.str(row, "txn_type")),
                    Rows.str(row, "description"), Rows.str(row, "currency"), Rows.str(row, "reference"),
                    "WALLET_TRANSACTION", id, lines);
        } catch (ApiException e) {
            throw fail(row, e);
        }
        UUID paymentId = null;
        if ("WALLET_TO_BANK".equals(Rows.str(row, "txn_type")) && row.hasNonNull("wallet_bank_link_id")
                && props.gl().bankClearing() != null
                && props.gl().bankClearing().equals(Rows.uuid(row, "counterparty_ledger_account_id"))) {
            try {
                ObjectNode link = rows.get("wallet_bank_link", Rows.uuid(row, "wallet_bank_link_id"));
                ObjectNode payer = rows.get("wallet", Rows.uuid(row, "payer_wallet_id"));
                // the wallet's ALLOW evaluation travels with the payout so payment-service does not score it again
                UUID allowScore = "ALLOW".equals(Rows.str(row, "fraud_decision")) ? Rows.uuid(row, "fraud_score_id") : null;
                paymentId = downstream.outboundPayment("wtx-" + id + "-payment", link, dec(row, "amount"),
                        Rows.str(row, "currency"), Rows.str(row, "reference"), Rows.uuid(payer, "id"),
                        Rows.str(payer, "display_name"), allowScore);
            } catch (ApiException e) {
                // the bank transfer was refused: compensate the posting and fail the transaction
                ledger.post("wtx-" + id + "-compensate", "REVERSAL", "Bank transfer refused: " + e.getMessage(),
                        Rows.str(row, "currency"), Rows.str(row, "reference"), "WALLET_TRANSACTION", id, invert(lines));
                throw fail(row, e);
            }
        }
        return complete(row, je, lines, paymentId);
    }

    /** Debit then credit lines: payer total, payee principal, fee and tax to their GL accounts. */
    private List<LedgerClient.Line> lines(ObjectNode row, BigDecimal principal, boolean withFees) {
        BigDecimal fee = withFees ? dec(row, "fee_amount") : BigDecimal.ZERO;
        BigDecimal tax = withFees ? dec(row, "tax_amount") : BigDecimal.ZERO;
        String ref = Rows.str(row, "reference");
        List<LedgerClient.Line> l = new ArrayList<>();
        l.add(new LedgerClient.Line(debitAccount(row), "DEBIT", principal.add(fee).add(tax), "Payer " + ref));
        l.add(new LedgerClient.Line(creditAccount(row), "CREDIT", principal, "Payee " + ref));
        if (fee.signum() > 0) {
            l.add(new LedgerClient.Line(gl(props.gl().feeIncome(), "fee-income"), "CREDIT", fee, "Fee " + ref));
        }
        if (tax.signum() > 0) {
            l.add(new LedgerClient.Line(gl(props.gl().taxPayable(), "tax-payable"), "CREDIT", tax, "Tax " + ref));
        }
        return l;
    }

    private static List<LedgerClient.Line> invert(List<LedgerClient.Line> lines) {
        return lines.stream().map(l -> new LedgerClient.Line(l.account(),
                "DEBIT".equals(l.direction()) ? "CREDIT" : "DEBIT", l.amount(), l.description())).toList();
    }

    private static UUID gl(UUID account, String name) {
        if (account == null) {
            throw new ApiException(422, "ledger_account_not_configured", "meridian.wallet.gl." + name + " is not configured");
        }
        return account;
    }

    private UUID debitAccount(ObjectNode row) {
        UUID payer = Rows.uuid(row, "payer_wallet_id");
        return payer == null ? Rows.uuid(row, "counterparty_ledger_account_id")
                : Rows.uuid(rows.get("wallet", payer), "ledger_account_id");
    }

    private UUID creditAccount(ObjectNode row) {
        UUID payee = Rows.uuid(row, "payee_wallet_id");
        return payee == null ? Rows.uuid(row, "counterparty_ledger_account_id")
                : Rows.uuid(rows.get("wallet", payee), "ledger_account_id");
    }

    static String entryType(String txnType) {
        return switch (txnType) {
            case "FEE" -> "FEE";
            case "WALLET_TO_BANK", "BANK_TO_WALLET", "MERCHANT_PAYMENT", "BILL_PAYMENT", "AIRTIME" -> "PAYMENT";
            case "ADJUSTMENT" -> "ADJUSTMENT";
            case "REFUND", "REVERSAL" -> "REVERSAL";
            case "LOAN_DISBURSEMENT", "LOAN_REPAYMENT" -> "LOAN";
            default -> "TRANSFER";
        };
    }

    /** Records the ledger rejection as FAILED (own transaction), releases the limit reservation, returns the error. */
    private ApiException fail(ObjectNode row, ApiException e) {
        if (e.status() >= 500) {
            return e; // outcome unknown: keep INITIATED so a retry with the same key resumes idempotently
        }
        UUID id = Rows.uuid(row, "id");
        tx.executeWithoutResult(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if (!"INITIATED".equals(Rows.str(cur, "status")) && !"PENDING_CONFIRMATION".equals(Rows.str(cur, "status"))) {
                return;
            }
            ObjectNode v = rows.object();
            v.put("status", "FAILED");
            v.put("failure_code", e.code());
            String msg = e.getMessage() == null ? e.code() : e.getMessage();
            v.put("failure_reason", msg.length() > 255 ? msg.substring(0, 255) : msg);
            events.publish("wallets.wallet_transaction.failed", TABLE, rows.update(TABLE, id, v));
        });
        downstream.releaseLimits(Rows.uuid(row, "limit_check_id"), e.code(), "wtx-" + id + "-limits-release");
        return new ApiException(422, e.code(), e.getMessage());
    }

    private ObjectNode complete(ObjectNode row, UUID journalEntryId, List<LedgerClient.Line> lines, UUID paymentId) {
        UUID id = Rows.uuid(row, "id");
        ObjectNode done = tx.execute(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            String status = Rows.str(cur, "status");
            if ("COMPLETED".equals(status)) {
                return cur;
            }
            WalletLifecycle.requireTxnTransition(status, "COMPLETED");
            ObjectNode v = rows.object();
            v.put("status", "COMPLETED");
            if (journalEntryId != null) {
                v.put("journal_entry_id", journalEntryId.toString());
            }
            if (paymentId != null) {
                v.put("payment_id", paymentId.toString());
            }
            rows.update(TABLE, id, v);
            rows.execute("update wallet_transaction set completed_at = now() where id = ?", id);
            writeLegs(cur, lines, journalEntryId);
            if ("CUSTOMER".equals(Rows.str(cur, "initiator_type")) && cur.hasNonNull("payer_wallet_id")) {
                rows.execute("update wallet set last_activity_at = now() where id = ?", Rows.uuid(cur, "payer_wallet_id"));
            }
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.wallet_transaction.completed", TABLE, after);
            return after;
        });
        downstream.commitLimits(Rows.uuid(done, "limit_check_id"), dec(done, "total_debit_amount"), "wtx-" + id + "-limits-commit");
        return done;
    }

    private void writeLegs(ObjectNode txn, List<LedgerClient.Line> lines, UUID je) {
        UUID id = Rows.uuid(txn, "id");
        int no = 0;
        for (LedgerClient.Line l : lines) {
            no++;
            String role = no == 1 ? "PAYER" : no == 2 ? "PAYEE" : l.description().startsWith("Fee") ? "FEE" : "TAX";
            UUID wallet = no == 1 ? Rows.uuid(txn, "payer_wallet_id") : no == 2 ? Rows.uuid(txn, "payee_wallet_id") : null;
            ObjectNode leg = rows.object();
            leg.put("wallet_transaction_id", id.toString());
            leg.put("leg_no", no);
            leg.put("role", role);
            if (wallet != null) {
                leg.put("wallet_id", wallet.toString());
            }
            leg.put("ledger_account_id", l.account().toString());
            leg.put("direction", l.direction());
            leg.put("amount", l.amount().toPlainString());
            leg.put("currency", Rows.str(txn, "currency"));
            if (je != null) {
                leg.put("journal_entry_id", je.toString());
            }
            if (role.equals("FEE") || role.equals("TAX")) {
                leg.put("beneficiary_type", role.equals("FEE") ? "PLATFORM" : "TAX_AUTHORITY");
            }
            leg.put("description", l.description());
            rows.insert("wallet_transaction_leg", leg);
        }
    }

    private void sendOtp(ObjectNode txn, String otp) {
        ObjectNode payer = rows.get("wallet", Rows.uuid(txn, "payer_wallet_id"));
        if (Rows.str(payer, "msisdn") == null) {
            return;
        }
        try {
            downstream.sendSms("wtx-" + Rows.str(txn, "id") + "-otp", "CUSTOMER", Rows.uuid(payer, "customer_id"),
                    Rows.str(payer, "msisdn"), "SECURITY", "Your code to confirm " + Rows.str(txn, "txn_type").replace('_', ' ')
                            + " " + Rows.str(txn, "amount") + " " + Rows.str(txn, "currency") + " is " + otp
                            + ". Ref " + Rows.str(txn, "reference") + ". Never share it.");
        } catch (RuntimeException e) {
            log.warn("OTP for {} could not be sent: {}", Rows.str(txn, "id"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ confirmation

    public ObjectNode confirm(UUID id, String pin, String otp) {
        ObjectNode row = rows.get(TABLE, id);
        if ("COMPLETED".equals(Rows.str(row, "status"))) {
            return row;
        }
        WalletLifecycle.requireTxnTransition(Rows.str(row, "status"), "COMPLETED");
        if (!"PENDING_CONFIRMATION".equals(Rows.str(row, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a pending transaction can be confirmed");
        }
        if (rows.scalar("select confirmation_expires_at < now() from wallet_transaction where id = ?", Boolean.class, id)
                .orElse(false)) {
            expire(row);
            throw ApiException.unprocessable("confirmation_expired", "The confirmation window has passed; the funds were released");
        }
        switch (Rows.str(row, "confirmation_method")) {
            case "PIN" -> {
                if (pin == null) {
                    throw ApiException.unprocessable("pin_required", "The payer must confirm with the wallet PIN");
                }
                pins.require(Rows.uuid(row, "payer_wallet_id"), pin);
            }
            case "OTP" -> verifyOtp(row, otp);
            default -> { }
        }
        UUID hold = Rows.uuid(row, "hold_id");
        BigDecimal amount = dec(row, "amount");
        BigDecimal fee = dec(row, "fee_amount");
        BigDecimal tax = dec(row, "tax_amount");
        String ref = Rows.str(row, "reference");
        ledger.capture("wtx-" + id + "-cap-principal", hold, amount, creditAccount(row), "Payee " + ref,
                fee.signum() == 0 && tax.signum() == 0);
        if (fee.signum() > 0) {
            ledger.capture("wtx-" + id + "-cap-fee", hold, fee, gl(props.gl().feeIncome(), "fee-income"), "Fee " + ref,
                    tax.signum() == 0);
        }
        if (tax.signum() > 0) {
            ledger.capture("wtx-" + id + "-cap-tax", hold, tax, gl(props.gl().taxPayable(), "tax-payable"), "Tax " + ref, true);
        }
        return complete(row, null, lines(row, amount, true), null);
    }

    private void verifyOtp(ObjectNode row, String otp) {
        UUID id = Rows.uuid(row, "id");
        if (otp == null) {
            throw ApiException.unprocessable("otp_required", "Confirm with the one-time code sent to the payer");
        }
        Boolean ok = tx.execute(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if (PinPolicy.verifyOtp(otp, Rows.str(cur, "confirmation_otp_hash"))) {
                return true;
            }
            rows.execute("update wallet_transaction set confirmation_attempts = confirmation_attempts + 1 where id = ?", id);
            return false;
        });
        if (!Boolean.TRUE.equals(ok)) {
            int attempts = Rows.integer(rows.get(TABLE, id), "confirmation_attempts");
            if (attempts >= props.pin().otpMaxAttempts()) {
                cancel(id, "Too many wrong confirmation codes");
                throw ApiException.unprocessable("otp_attempts_exceeded", "Too many wrong codes; the transaction was cancelled");
            }
            throw ApiException.unprocessable("invalid_otp", "Incorrect confirmation code");
        }
    }

    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode row = rows.get(TABLE, id);
        if ("CANCELLED".equals(Rows.str(row, "status"))) {
            return row;
        }
        WalletLifecycle.requireTxnTransition(Rows.str(row, "status"), "CANCELLED");
        return endPending(row, "CANCELLED", reason == null ? "Cancelled" : reason);
    }

    private ObjectNode expire(ObjectNode row) {
        return endPending(row, "EXPIRED", "Confirmation window passed");
    }

    private ObjectNode endPending(ObjectNode row, String status, String reason) {
        UUID id = Rows.uuid(row, "id");
        ledger.release("wtx-" + id + "-release", Rows.uuid(row, "hold_id"), reason);
        ObjectNode done = tx.execute(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if (!"PENDING_CONFIRMATION".equals(Rows.str(cur, "status"))) {
                return cur;
            }
            ObjectNode v = rows.object();
            v.put("status", status);
            v.put("failure_code", status.toLowerCase());
            v.put("failure_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
            ObjectNode after = rows.update(TABLE, id, v);
            events.publish("wallets.wallet_transaction.cancelled", TABLE, after);
            return after;
        });
        downstream.releaseLimits(Rows.uuid(done, "limit_check_id"), reason, "wtx-" + id + "-limits-release");
        return done;
    }

    /** Expires every pending transaction whose confirmation deadline passed (database clock). */
    public ObjectNode expirePending() {
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from wallet_transaction t
                where t.status = 'PENDING_CONFIRMATION' and t.confirmation_expires_at < now()
                order by t.confirmation_expires_at limit 500""");
        int expired = 0;
        List<String> failed = new ArrayList<>();
        for (ObjectNode t : due) {
            try {
                expire(t);
                expired++;
            } catch (RuntimeException e) {
                failed.add(Rows.str(t, "id"));
                log.warn("could not expire {}: {}", Rows.str(t, "id"), e.getMessage());
            }
        }
        ObjectNode out = rows.object();
        out.put("expired", expired);
        out.set("failed_ids", rows.json().valueToTree(failed));
        return out;
    }

    // ------------------------------------------------------------------ reversal

    public ObjectNode requestReversal(UUID id, String reason, BigDecimal amount) {
        return tx.execute(s -> {
            ObjectNode row = rows.lock(TABLE, id);
            if (!"COMPLETED".equals(Rows.str(row, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only a completed transaction can be reversed");
            }
            if (row.hasNonNull("reversal_requested_by")) {
                throw ApiException.conflict("reversal_already_requested", "A reversal is already awaiting approval");
            }
            BigDecimal amt = reversalAmount(row, amount);
            ObjectNode v = rows.object();
            v.put("reversal_requested_by", TenantContext.principal());
            v.put("reversal_requested_amount", amt.toPlainString());
            v.put("reversal_reason", reason);
            rows.update(TABLE, id, v);
            rows.execute("update wallet_transaction set reversal_requested_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.wallet_transaction.reversal_requested", TABLE, after);
            // Central maker-checker: the checker decides in the workflow inbox; ReversalApprovalHandler applies it.
            String requestedAt = Rows.str(after, "reversal_requested_at");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reference", Rows.str(after, "reference"));
            payload.put("txn_type", Rows.str(after, "txn_type"));
            payload.put("original_amount", Rows.str(after, "amount"));
            payload.put("already_reversed", dec(after, "reversed_amount").toPlainString());
            payload.put("reversal_amount", amt.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString());
            payload.put("currency", Rows.str(after, "currency"));
            payload.put("payer_wallet_id", Rows.str(after, "payer_wallet_id"));
            payload.put("payee_wallet_id", Rows.str(after, "payee_wallet_id"));
            payload.put("initiated_by", Rows.str(after, "initiated_by"));
            payload.put("reason", reason);
            payload.put("requested_at", requestedAt);
            approvals.request(ApprovalSpec.of(REVERSAL_RESOURCE, id, "reverse",
                            "Reverse wallet transaction " + Rows.str(after, "reference"), payload)
                    .withAmount(amt.setScale(2, java.math.RoundingMode.UNNECESSARY), Rows.str(after, "currency")),
                    "reversal:" + id + ":" + requestedAt);
            return after;
        });
    }

    /**
     * True while the transaction still awaits the reversal requested at {@code requestedAt} (the submission's
     * fingerprint) for {@code amount}; a later, decided or withdrawn request does not match.
     */
    public boolean awaitsReversal(UUID id, String requestedAt, BigDecimal amount) {
        return rows.find(TABLE, id)
                .filter(t -> "COMPLETED".equals(Rows.str(t, "status")))
                .filter(t -> t.hasNonNull("reversal_requested_by"))
                .filter(t -> requestedAt == null || requestedAt.equals(Rows.str(t, "reversal_requested_at")))
                .filter(t -> amount == null || amount.compareTo(dec(t, "reversal_requested_amount")) == 0)
                .isPresent();
    }

    /** Declines a pending reversal request (checker rejected it): the request is cleared, the transaction unchanged. */
    public ObjectNode declineReversal(UUID id, String reason) {
        return tx.execute(s -> {
            ObjectNode row = rows.lock(TABLE, id);
            if (!row.hasNonNull("reversal_requested_by")) {
                throw ApiException.conflict("invalid_transition", "No reversal is awaiting approval");
            }
            if (TenantContext.principal().equals(Rows.str(row, "reversal_requested_by"))) {
                throw ApiException.unprocessable("four_eyes_violation",
                        "The reversal must be decided by a different user than the one who requested it");
            }
            log.info("Reversal request on wallet transaction {} declined: {}", id, reason);
            ObjectNode v = rows.object();
            v.putNull("reversal_requested_by");
            v.putNull("reversal_requested_amount");
            v.putNull("reversal_requested_at");
            return rows.update(TABLE, id, v);
        });
    }

    private static BigDecimal reversalAmount(ObjectNode row, BigDecimal requested) {
        BigDecimal remaining = dec(row, "amount").subtract(dec(row, "reversed_amount"));
        BigDecimal amt = requested == null ? remaining : requested;
        if (amt.signum() <= 0 || amt.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "Reversal amount must be positive with at most 2 decimals");
        }
        if (amt.compareTo(remaining) > 0) {
            throw ApiException.unprocessable("reversal_exceeds_amount",
                    "At most " + remaining.toPlainString() + " can still be reversed");
        }
        return amt;
    }

    /**
     * Reverses a completed transaction with a compensating journal entry (principal back to the payer; a full reversal
     * also refunds fee and tax). Needs a prior reversal request; the checker must differ from the reversal maker and
     * from the original initiator (SEC-21).
     */
    public ObjectNode reverse(UUID id, String reason, BigDecimal requested) {
        String me = TenantContext.principal();
        // SEC-07: phase 1 reserves the amount under the original's row lock (remaining = amount - reversed_amount,
        // re-checked under the lock) and records the reversal as PROCESSING before anything is posted, so concurrent
        // reversals can never together exceed the original. CHECK (reversed_amount <= amount) is the backstop.
        Reservation r = tx.execute(s -> reserveReversal(id, reason, requested, me));
        UUID je;
        try {
            // deterministic key derived from the reserved reversal row: a retry of this reversal never posts twice
            je = ledger.post("wtx-" + r.revId() + "-post", "REVERSAL", "Reversal of " + Rows.str(r.original(), "reference")
                            + (reason == null ? "" : ": " + reason), Rows.str(r.original(), "currency"), r.ref(),
                    "WALLET_TRANSACTION", r.revId(), r.lines());
        } catch (ApiException e) {
            if (e.status() < 500) {
                // the ledger refused the posting: nothing moved, give the reserved amount back
                tx.executeWithoutResult(s -> releaseReversal(id, r, e));
            }
            // 5xx / unknown outcome: the reservation stays (PROCESSING) so no second reversal can exceed the original
            throw e;
        }
        ObjectNode result = tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode v = rows.object();
            v.put("status", "COMPLETED");
            v.put("journal_entry_id", je.toString());
            rows.update(TABLE, r.revId(), v);
            rows.execute("update wallet_transaction set completed_at = now() where id = ?", r.revId());
            ObjectNode revRow = rows.get(TABLE, r.revId());
            writeLegs(revRow, r.lines(), je);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.wallet_transaction.reversed", TABLE, after);
            events.publish("wallets.wallet_transaction.completed", TABLE, revRow);
            return revRow;
        });
        approvals.cancelOpen(REVERSAL_RESOURCE, id, null, "reversed directly");
        return result;
    }

    /** A reversal reserved on the original transaction, not yet posted. */
    private record Reservation(UUID revId, String ref, BigDecimal amount, boolean full, ObjectNode original,
                               List<LedgerClient.Line> lines) {}

    private Reservation reserveReversal(UUID id, String reason, BigDecimal requested, String me) {
        ObjectNode row = rows.lock(TABLE, id);
        if (!"COMPLETED".equals(Rows.str(row, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a completed transaction can be reversed");
        }
        if ("REVERSAL".equals(Rows.str(row, "txn_type"))) {
            throw ApiException.unprocessable("cannot_reverse_reversal", "A reversal cannot itself be reversed");
        }
        // SEC-21: a reversal always has a maker (request-reversal) and a different checker; customer, agent and
        // merchant transactions have no staff initiator, so "not the initiator" alone let one person reverse them
        String maker = Rows.str(row, "reversal_requested_by");
        if (maker == null) {
            throw ApiException.unprocessable("reversal_request_required",
                    "Request the reversal first (request-reversal); a different user then reverses it");
        }
        if (maker.equals(me) || me.equals(Rows.str(row, "initiated_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The reversal must be approved by a different user than the one who requested or initiated it");
        }
        BigDecimal amt = reversalAmount(row, requested != null ? requested : Rows.dec(row, "reversal_requested_amount"));
        boolean full = dec(row, "reversed_amount").add(amt).compareTo(dec(row, "amount")) == 0;
        boolean refundFees = full && dec(row, "reversed_amount").signum() == 0;
        UUID revId = Ids.newId();
        String ref = WalletNumbers.reference();
        List<LedgerClient.Line> original = lines(row, amt, refundFees);
        List<LedgerClient.Line> lines = new ArrayList<>();
        // compensating entry: payee (or counterparty) back to payer, then fee and tax back to the payer
        lines.add(new LedgerClient.Line(original.get(1).account(), "DEBIT", amt, "Payee " + ref));
        lines.add(new LedgerClient.Line(original.getFirst().account(), "CREDIT", original.getFirst().amount(), "Payer " + ref));
        for (int i = 2; i < original.size(); i++) {
            LedgerClient.Line l = original.get(i);
            lines.add(new LedgerClient.Line(l.account(), "DEBIT", l.amount(), l.description()));
        }
        ObjectNode rv = rows.object();
        rv.put("id", revId.toString());
        rv.put("reference", ref);
        rv.put("txn_type", "REVERSAL");
        rv.put("status", "PROCESSING");
        rv.put("channel", "SYSTEM");
        rv.put("initiator_type", "STAFF");
        rv.put("initiated_by", me);
        copy(row, rv, "payee_wallet_id", "payer_wallet_id");
        copy(row, rv, "payer_wallet_id", "payee_wallet_id");
        copy(row, rv, "counterparty_ledger_account_id", "counterparty_ledger_account_id");
        copy(row, rv, "agent_id", "agent_id");
        copy(row, rv, "merchant_id", "merchant_id");
        copy(row, rv, "biller_id", "biller_id");
        rv.put("amount", amt.toPlainString());
        rv.put("currency", Rows.str(row, "currency"));
        rv.put("fee_amount", refundFees ? Rows.str(row, "fee_amount") : "0");
        rv.put("tax_amount", refundFees ? Rows.str(row, "tax_amount") : "0");
        rv.put("total_debit_amount", amt.toPlainString());
        rv.put("original_transaction_id", id.toString());
        rv.put("idempotency_key", "reversal:" + revId);
        rv.put("description", ("Reversal of " + Rows.str(row, "reference") + (reason == null ? "" : ": " + reason)));
        if (rv.get("description").asString().length() > 255) {
            rv.put("description", rv.get("description").asString().substring(0, 255));
        }
        rows.insert(TABLE, rv);
        ObjectNode v = rows.object();
        v.put("reversed_amount", dec(row, "reversed_amount").add(amt).toPlainString());
        if (full) {
            v.put("status", "REVERSED");
        }
        v.putNull("reversal_requested_by");
        v.putNull("reversal_requested_amount");
        v.putNull("reversal_requested_at");
        if (reason != null) {
            v.put("reversal_reason", reason);
        }
        rows.update(TABLE, id, v);
        return new Reservation(revId, ref, amt, full, row, lines);
    }

    /** Gives back a reservation whose posting the ledger refused; a pending reversal request is restored. */
    private void releaseReversal(UUID id, Reservation r, ApiException cause) {
        ObjectNode cur = rows.lock(TABLE, id);
        ObjectNode v = rows.object();
        v.put("reversed_amount", dec(cur, "reversed_amount").subtract(r.amount()).toPlainString());
        if (r.full()) {
            v.put("status", "COMPLETED");
        }
        for (String f : List.of("reversal_requested_by", "reversal_requested_amount", "reversal_requested_at", "reversal_reason")) {
            if (r.original().hasNonNull(f)) {
                v.set(f, r.original().get(f));
            } else {
                v.putNull(f);
            }
        }
        rows.update(TABLE, id, v);
        ObjectNode failed = rows.object();
        failed.put("status", "FAILED");
        failed.put("failure_code", cause.code());
        String msg = cause.getMessage() == null ? cause.code() : cause.getMessage();
        failed.put("failure_reason", msg.length() > 255 ? msg.substring(0, 255) : msg);
        rows.update(TABLE, r.revId(), failed);
    }

    private static void copy(ObjectNode from, ObjectNode to, String src, String dst) {
        if (from.hasNonNull(src)) {
            to.set(dst, from.get(src));
        }
    }

    static BigDecimal dec(JsonNode n, String f) {
        BigDecimal d = Rows.dec(n, f);
        return d == null ? BigDecimal.ZERO : d;
    }
}
