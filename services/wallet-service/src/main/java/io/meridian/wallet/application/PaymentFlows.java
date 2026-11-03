package io.meridian.wallet.application;

import io.meridian.common.error.ApiException;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.wallet.domain.FeeCalculator;
import io.meridian.wallet.domain.LimitEvaluator;
import io.meridian.wallet.infrastructure.Downstream;
import io.meridian.wallet.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Maps the named transaction actions (P2P, cash-in/out, bank transfers, generic create, quote) onto the engine. */
@Service
public class PaymentFlows {
    static final Set<String> AGENT_WALLETS = Set.of("AGENT", "SUPER_AGENT", "FLOAT");

    private final TransactionEngine engine;
    private final Rows rows;
    private final Tariffs tariffs;
    private final Downstream downstream;
    private final WalletProperties props;

    public PaymentFlows(TransactionEngine engine, Rows rows, Tariffs tariffs, Downstream downstream, WalletProperties props) {
        this.engine = engine;
        this.rows = rows;
        this.tariffs = tariffs;
        this.downstream = downstream;
        this.props = props;
    }

    /** Generic create for internal callers (bill payments, merchant payments, payouts, interop, settlements). */
    public ObjectNode create(JsonNode req, String key) {
        String type = Rows.str(req, "txn_type");
        if ("REFUND".equals(type)) {
            validateRefund(req);
        }
        boolean twoPhase = Rows.bool(req, "two_phase");
        return engine.execute(new TransactionEngine.Command(type, Rows.str(req, "channel"), Rows.str(req, "initiator_type"),
                Rows.dec(req, "amount"), Rows.str(req, "currency"), Rows.uuid(req, "payer_wallet_id"),
                Rows.uuid(req, "payee_wallet_id"), Rows.str(req, "payee_msisdn"), null,
                Rows.uuid(req, "counterparty_ledger_account_id"), Rows.str(req, "pin"), twoPhase ? "SYSTEM" : null,
                extra(req), key));
    }

    private void validateRefund(JsonNode req) {
        UUID original = Rows.uuid(req, "original_transaction_id");
        if (original == null) {
            throw ApiException.unprocessable("original_transaction_required", "A refund needs original_transaction_id");
        }
        ObjectNode o = rows.find("wallet_transaction", original).orElseThrow(() ->
                ApiException.unprocessable("original_transaction_not_found", "The refunded transaction does not exist"));
        if (!"COMPLETED".equals(Rows.str(o, "status"))) {
            throw ApiException.unprocessable("original_not_completed", "Only a completed transaction can be refunded");
        }
        if (!Rows.str(o, "payee_wallet_id").equals(Rows.str(req, "payer_wallet_id"))
                || !Rows.str(o, "payer_wallet_id").equals(Rows.str(req, "payee_wallet_id"))) {
            throw ApiException.unprocessable("refund_parties_mismatch", "A refund goes from the original payee back to the original payer");
        }
        BigDecimal refunded = rows.scalar("""
                select coalesce(sum(amount), 0) from wallet_transaction
                where original_transaction_id = ? and txn_type = 'REFUND' and status in ('COMPLETED', 'PENDING_CONFIRMATION', 'INITIATED')""",
                BigDecimal.class, original).orElse(BigDecimal.ZERO);
        if (refunded.add(Rows.dec(req, "amount")).compareTo(Rows.dec(o, "amount")) > 0) {
            throw ApiException.unprocessable("refund_exceeds_amount", "Refunds would exceed the original amount "
                    + Rows.str(o, "amount"));
        }
    }

    public ObjectNode p2p(JsonNode req, String key) {
        ObjectNode extra = extra(req);
        return engine.execute(new TransactionEngine.Command("P2P", channel(req, "APP"), "CUSTOMER", Rows.dec(req, "amount"),
                Rows.str(req, "currency"), Rows.uuid(req, "payer_wallet_id"), Rows.uuid(req, "payee_wallet_id"),
                Rows.str(req, "payee_msisdn"), Rows.str(req, "payee_alias"), null, Rows.str(req, "pin"), null, extra, key));
    }

    /** Cash-in: the agent's float wallet sends e-value to the customer who handed over cash. */
    public ObjectNode cashIn(JsonNode req, String key) {
        ObjectNode agent = agentWallet(Rows.uuid(req, "agent_wallet_id"));
        ObjectNode extra = extra(req);
        extra.put("agent_id", Rows.str(agent, "agent_id"));
        return engine.execute(new TransactionEngine.Command("CASH_IN", channel(req, "AGENT_APP"), "AGENT",
                Rows.dec(req, "amount"), Rows.str(req, "currency"), Rows.uuid(agent, "id"), null,
                Rows.str(req, "payee_msisdn"), null, null, Rows.str(req, "pin"), null, extra, key));
    }

    /**
     * Cash-out: the customer's funds are held and the agent hands over cash once the customer confirms with the
     * wallet PIN (or, without a PIN, with an OTP sent to the wallet's mobile number).
     */
    public ObjectNode cashOut(JsonNode req, String key) {
        ObjectNode agent = agentWallet(Rows.uuid(req, "agent_wallet_id"));
        ObjectNode customer = rows.find("wallet", Rows.uuid(req, "payer_wallet_id")).orElseThrow(() ->
                ApiException.unprocessable("payer_not_found", "The customer wallet does not exist"));
        String method;
        if (Rows.bool(customer, "has_pin")) {
            method = "PIN";
        } else if (Rows.str(customer, "msisdn") != null) {
            method = "OTP";
        } else {
            throw ApiException.unprocessable("pin_not_set", "The customer wallet has no PIN and no mobile number to confirm with");
        }
        ObjectNode extra = extra(req);
        extra.put("agent_id", Rows.str(agent, "agent_id"));
        return engine.execute(new TransactionEngine.Command("CASH_OUT", channel(req, "AGENT_APP"), "AGENT",
                Rows.dec(req, "amount"), Rows.str(req, "currency"), Rows.uuid(customer, "id"), Rows.uuid(agent, "id"),
                null, null, null, null, method, extra, key));
    }

    public ObjectNode walletToBank(JsonNode req, String key) {
        UUID walletId = Rows.uuid(req, "payer_wallet_id");
        ObjectNode link = activeLink(Rows.uuid(req, "wallet_bank_link_id"), walletId, Rows.str(req, "currency"));
        UUID counterparty = bankAccount(link);
        ObjectNode extra = extra(req);
        extra.put("wallet_bank_link_id", Rows.str(link, "id"));
        extra.put("payee_external_ref", mask(Rows.str(link, "account_number")));
        return engine.execute(new TransactionEngine.Command("WALLET_TO_BANK", "APP", "CUSTOMER", Rows.dec(req, "amount"),
                Rows.str(req, "currency"), walletId, null, null, null, counterparty, Rows.str(req, "pin"), null, extra, key));
    }

    public ObjectNode bankToWallet(JsonNode req, String key) {
        UUID walletId = Rows.uuid(req, "payee_wallet_id");
        ObjectNode link = activeLink(Rows.uuid(req, "wallet_bank_link_id"), walletId, Rows.str(req, "currency"));
        if (link.get("deposit_account_id") == null || link.get("deposit_account_id").isNull()) {
            if (Rows.str(link, "mandate_reference") == null) {
                throw ApiException.unprocessable("mandate_required",
                        "Pulling from another bank needs a direct-debit mandate on the link");
            }
        }
        UUID counterparty = bankAccount(link);
        ObjectNode extra = extra(req);
        extra.put("wallet_bank_link_id", Rows.str(link, "id"));
        extra.put("payee_external_ref", mask(Rows.str(link, "account_number")));
        return engine.execute(new TransactionEngine.Command("BANK_TO_WALLET", "APP", "CUSTOMER", Rows.dec(req, "amount"),
                Rows.str(req, "currency"), null, walletId, null, null, counterparty, null, null, extra, key));
    }

    /** Fee, tax and limit pre-check without creating anything. */
    public ObjectNode quote(JsonNode req) {
        String type = Rows.str(req, "txn_type");
        BigDecimal amount = Rows.dec(req, "amount");
        String currency = Rows.str(req, "currency");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        ObjectNode payer = Rows.uuid(req, "payer_wallet_id") == null ? null
                : rows.find("wallet", Rows.uuid(req, "payer_wallet_id")).orElseThrow(() ->
                        ApiException.unprocessable("payer_not_found", "The payer wallet does not exist"));
        String channel = channel(req, "APP");
        String remote = quoteRemoteType(req, type);
        FeeCalculator.Fee fee = tariffs.fee(type, channel, payer, amount, currency, remote);
        ObjectNode out = rows.object();
        out.put("txn_type", type);
        out.put("channel", channel);
        out.put("amount", amount.toPlainString());
        out.put("currency", currency);
        out.put("fee_amount", fee.fee().toPlainString());
        out.put("tax_amount", fee.tax().toPlainString());
        out.put("total_debit_amount", amount.add(fee.total()).toPlainString());
        out.put("fee_rule_id", fee.ruleId() == null ? null : fee.ruleId().toString());
        out.put("remote_txn_type", remote);
        ObjectNode payee = null;
        if (Rows.uuid(req, "payee_wallet_id") != null) {
            payee = rows.find("wallet", Rows.uuid(req, "payee_wallet_id")).orElse(null);
        } else if (Rows.str(req, "payee_msisdn") != null) {
            payee = rows.queryOne("""
                    select row_to_json(w)::text from wallet w where w.msisdn = ? and w.currency = ?
                      and w.status in ('ACTIVE', 'DORMANT') order by (w.wallet_type = 'CUSTOMER') desc, w.is_default desc limit 1""",
                    Rows.str(req, "payee_msisdn"), currency).orElse(null);
        }
        out.put("payee_wallet_id", payee == null ? null : Rows.str(payee, "id"));
        out.put("payee_name", payee == null ? null : Rows.str(payee, "display_name"));
        String violation = null;
        if (payer != null) {
            for (LimitEvaluator.Limit l : tariffs.limits(payer, type)) {
                var v = LimitEvaluator.checkDebit(l, tariffs.usage(Rows.uuid(payer, "id"), l.txnType()), amount);
                if (v.isPresent()) {
                    violation = v.get().message();
                    break;
                }
            }
        }
        out.put("limits_ok", violation == null);
        out.put("limit_violation", violation);
        return out;
    }

    /** WE-PRICE-1 (PE-16): classifies a quote's cash-in/cash-out against the given outlet's cell/lac (null otherwise). */
    private String quoteRemoteType(JsonNode req, String type) {
        if (!TransactionEngine.REMOTE_CLASSIFIED.contains(type) || Rows.uuid(req, "agent_outlet_id") == null) {
            return null;
        }
        ObjectNode outlet = rows.find("agent_outlet", Rows.uuid(req, "agent_outlet_id")).orElse(null);
        if (outlet == null) {
            return null;
        }
        return io.meridian.wallet.domain.RemoteClassifier.classify(Rows.str(req, "cell_id"), Rows.str(req, "lac_id"),
                Rows.str(outlet, "cell_id"), Rows.str(outlet, "lac_id"));
    }

    private ObjectNode agentWallet(UUID id) {
        if (id == null) {
            throw ApiException.unprocessable("agent_wallet_required", "agent_wallet_id is required");
        }
        ObjectNode w = rows.find("wallet", id).orElseThrow(() ->
                ApiException.unprocessable("agent_wallet_not_found", "The agent wallet does not exist"));
        if (!AGENT_WALLETS.contains(Rows.str(w, "wallet_type"))) {
            throw ApiException.unprocessable("not_agent_wallet", "Wallet " + Rows.str(w, "wallet_number") + " is not an agent float wallet");
        }
        return w;
    }

    private ObjectNode activeLink(UUID linkId, UUID walletId, String currency) {
        if (linkId == null || walletId == null) {
            throw ApiException.unprocessable("invalid_request", "wallet and wallet_bank_link_id are required");
        }
        ObjectNode link = rows.find("wallet_bank_link", linkId).orElseThrow(() ->
                ApiException.unprocessable("bank_link_not_found", "The bank link does not exist"));
        if (!walletId.equals(Rows.uuid(link, "wallet_id"))) {
            throw ApiException.unprocessable("bank_link_mismatch", "The bank link belongs to another wallet");
        }
        if (!"ACTIVE".equals(Rows.str(link, "status"))) {
            throw ApiException.unprocessable("bank_link_not_verified", "The bank link is " + Rows.str(link, "status"));
        }
        if (!Rows.str(link, "currency").equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch", "The bank account is in " + Rows.str(link, "currency"));
        }
        return link;
    }

    /** On-us accounts post against the deposit account's ledger account; other banks go through the clearing account. */
    private UUID bankAccount(ObjectNode link) {
        UUID deposit = Rows.uuid(link, "deposit_account_id");
        if (deposit != null) {
            JsonNode acc = downstream.depositAccount(deposit);
            String status = acc.path("status").asString("");
            if (!Set.of("ACTIVE", "OPEN").contains(status)) {
                throw ApiException.unprocessable("bank_account_not_active", "The linked deposit account is " + status);
            }
            String ledgerAccount = acc.path("ledger_account_id").asString(null);
            if (ledgerAccount == null) {
                throw ApiException.unprocessable("bank_account_not_active", "The linked deposit account has no ledger account");
            }
            return UUID.fromString(ledgerAccount);
        }
        if (props.gl().bankClearing() == null) {
            throw new ApiException(422, "ledger_account_not_configured", "meridian.wallet.gl.bank-clearing is not configured");
        }
        return props.gl().bankClearing();
    }

    static String mask(String account) {
        return account == null || account.length() <= 4 ? account : "****" + account.substring(account.length() - 4);
    }

    private static String channel(JsonNode req, String dflt) {
        String c = Rows.str(req, "channel");
        return c == null ? dflt : c;
    }

    private ObjectNode extra(JsonNode req) {
        ObjectNode e = rows.object();
        for (String f : TransactionEngine.EXTRA) {
            if (req.hasNonNull(f)) {
                e.set(f, req.get(f));
            }
        }
        return e;
    }
}
