package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import io.meridian.customerchannel.application.Ownership.Access;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The customer's loans (schedule, repayments, repay from an own account) and wallets. */
@Service
public class LendingWalletService {
    private static final String LOANS = "loan-service";
    private static final String WALLETS = "wallet-service";

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public LendingWalletService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    public JsonNode loans(Integer limit, String cursor) {
        return down.page(down.get(LOANS, "/v1/loans", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id"));
    }

    public JsonNode loan(UUID id) {
        return own.loan(id, "myLoansGet");
    }

    public JsonNode schedule(UUID id, Integer limit, String cursor) {
        own.loan(id, "myLoansSchedule");
        return down.get(LOANS, "/v1/loans/" + id + "/schedule", Query.none().paging(limit, cursor));
    }

    public JsonNode repayments(UUID id, Integer limit, String cursor) {
        own.loan(id, "myLoansRepayments");
        return down.get(LOANS, "/v1/loans/" + id + "/repayments", Query.none().paging(limit, cursor));
    }

    /** Manual repayment debited from an account the customer may transact on. */
    public JsonNode repay(UUID id, JsonNode req, String key) {
        String action = "myLoansRepay";
        own.loan(id, action);
        UUID source = uuid(req, "source_account_id", true);
        own.account(source, Access.TRANSACT, action);
        String amount = positive(req, "amount").toPlainString();
        stepUp.require(action, "LOAN", id);
        ObjectNode body = down.json().createObjectNode().put("amount", amount).put("source", "MANUAL")
                .put("source_account_id", source.toString());
        JsonNode r = down.post(LOANS, "/v1/loans/" + id + "/repay", body, key, "repay");
        audit.succeeded(action, "LOAN", id, r.path("id").asString(null));
        return r;
    }

    // ------------------------------------------------------------------ wallets

    public JsonNode wallets(Integer limit, String cursor) {
        return down.page(down.get(WALLETS, "/v1/wallets", Query.of("customer_id", me()).with("wallet_type", "CUSTOMER")
                        .paging(limit, cursor)),
                r -> mine(r, "customer_id") && !"CLOSED".equals(r.path("status").asString("")));
    }

    public JsonNode wallet(UUID id) {
        return own.wallet(id, "myWalletsGet");
    }

    public JsonNode walletBalance(UUID id) {
        own.wallet(id, "myWalletsBalance");
        return down.get(WALLETS, "/v1/wallets/" + id + "/balance");
    }

    public JsonNode walletTransactions(UUID id, OffsetDateTime from, OffsetDateTime to, Integer limit, String cursor) {
        own.wallet(id, "myWalletsTransactions");
        return down.get(WALLETS, "/v1/wallets/" + id + "/transactions",
                Query.of("from", from).with("to", to).paging(limit, cursor));
    }

    /** Receipt of one wallet transaction; the transaction must debit or credit a wallet the customer owns. */
    public ServiceClient.Download walletTransactionReceipt(UUID walletId, UUID txnId) {
        String action = "myWalletsTransactionsReceipt";
        own.wallet(walletId, action);
        JsonNode txn;
        try {
            txn = down.get(WALLETS, "/v1/wallet-transactions/" + txnId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound("WALLET_TRANSACTION", txnId);
            }
            throw e;
        }
        String id = walletId.toString();
        if (!id.equals(txn.path("payer_wallet_id").asString(null))
                && !id.equals(txn.path("payee_wallet_id").asString(null))) {
            throw own.deny(action, "WALLET_TRANSACTION", txnId);
        }
        return down.bytes(WALLETS, "/v1/wallet-transactions/" + txnId + "/receipt");
    }

    /** Resolve a payee wallet by phone number or wallet number (returns the masked payee name for confirmation). */
    public JsonNode lookup(JsonNode req) {
        return down.get(WALLETS, "/v1/wallets/lookup", payeeQuery(req));
    }

    /** Fee and total quote for a wallet-to-wallet send (no money moves; no step-up). */
    public JsonNode p2pQuote(UUID walletId, JsonNode req) {
        JsonNode wallet = own.wallet(walletId, "myWalletsP2pQuote");
        ObjectNode body = down.json().createObjectNode()
                .put("txn_type", "P2P")
                .put("payer_wallet_id", walletId.toString())
                .put("amount", positive(req, "amount").toPlainString())
                .put("currency", wallet.path("currency").asString());
        applyPayee(body, req);
        return down.post(WALLETS, "/v1/wallet-transactions/quote", body, null, "quote");
    }

    /** Send money from one of the customer's wallets to a phone/wallet number (step-up). */
    public JsonNode p2p(UUID walletId, JsonNode req, String key) {
        String action = "myWalletsP2p";
        JsonNode wallet = own.wallet(walletId, action);
        BigDecimal amount = positive(req, "amount");
        stepUp.require(action, "WALLET", walletId);
        ObjectNode body = down.json().createObjectNode()
                .put("payer_wallet_id", walletId.toString())
                .put("amount", amount.toPlainString())
                .put("currency", wallet.path("currency").asString())
                .put("channel", "APP")
                .put("customer_note", text(req, "note"));
        applyPayee(body, req);
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions/p2p", body, key, "p2p");
        audit.succeeded(action, "WALLET", walletId, r.path("id").asString(null));
        return r;
    }

    /** Transfer from one of the customer's wallets to a linked bank account (step-up). */
    public JsonNode toBank(UUID walletId, JsonNode req, String key) {
        String action = "myWalletsToBank";
        JsonNode wallet = own.wallet(walletId, action);
        UUID linkId = uuid(req, "beneficiary_account_id", true);
        bankLink(linkId, walletId, action);
        BigDecimal amount = positive(req, "amount");
        stepUp.require(action, "WALLET", walletId);
        ObjectNode body = down.json().createObjectNode()
                .put("payer_wallet_id", walletId.toString())
                .put("wallet_bank_link_id", linkId.toString())
                .put("amount", amount.toPlainString())
                .put("currency", wallet.path("currency").asString())
                .put("description", text(req, "reference"));
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions/wallet-to-bank", body, key, "wallet-to-bank");
        audit.succeeded(action, "WALLET", walletId, r.path("id").asString(null));
        return r;
    }

    /** Top up one of the customer's wallets from a linked bank account (step-up). */
    public JsonNode fromBank(UUID walletId, JsonNode req, String key) {
        String action = "myWalletsFromBank";
        JsonNode wallet = own.wallet(walletId, action);
        UUID linkId = uuid(req, "source_account_id", true);
        bankLink(linkId, walletId, action);
        BigDecimal amount = positive(req, "amount");
        stepUp.require(action, "WALLET", walletId);
        ObjectNode body = down.json().createObjectNode()
                .put("payee_wallet_id", walletId.toString())
                .put("wallet_bank_link_id", linkId.toString())
                .put("amount", amount.toPlainString())
                .put("currency", wallet.path("currency").asString());
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions/bank-to-wallet", body, key, "bank-to-wallet");
        audit.succeeded(action, "WALLET", walletId, r.path("id").asString(null));
        return r;
    }

    /** The lookup query for a {type (MSISDN|WALLET_NUMBER), value} payee reference. */
    private Query payeeQuery(JsonNode req) {
        String type = required(req, "type");
        String value = required(req, "value");
        return switch (type) {
            case "MSISDN" -> Query.of("msisdn", value);
            case "WALLET_NUMBER" -> Query.of("wallet_number", value);
            default -> throw ApiException.unprocessable("invalid_type", "type must be MSISDN or WALLET_NUMBER");
        };
    }

    /** Adds the payee side (phone goes as msisdn; a wallet number is resolved to a wallet id first). */
    private void applyPayee(ObjectNode body, JsonNode req) {
        String type = required(req, "type");
        String value = required(req, "value");
        switch (type) {
            case "MSISDN" -> body.put("payee_msisdn", value);
            case "WALLET_NUMBER" -> {
                JsonNode found = down.get(WALLETS, "/v1/wallets/lookup", Query.of("wallet_number", value));
                String id = found.path("id").asString(found.path("wallet_id").asString(null));
                if (id == null) {
                    throw ApiException.unprocessable("wallet_not_found", "No active wallet for that number");
                }
                body.put("payee_wallet_id", id);
            }
            default -> throw ApiException.unprocessable("invalid_type", "type must be MSISDN or WALLET_NUMBER");
        }
    }

    /** A bank link that belongs to the given wallet; 404 otherwise. */
    private JsonNode bankLink(UUID linkId, UUID walletId, String action) {
        JsonNode link;
        try {
            link = down.get(WALLETS, "/v1/wallet-bank-links/" + linkId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound("WALLET_BANK_LINK", linkId);
            }
            throw e;
        }
        if (!walletId.toString().equals(link.path("wallet_id").asString(null))) {
            throw own.deny(action, "WALLET_BANK_LINK", linkId);
        }
        return link;
    }
}
