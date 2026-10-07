package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;

import io.meridian.customerchannel.application.Downstream.Query;
import io.meridian.customerchannel.application.Ownership.Access;
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
}
