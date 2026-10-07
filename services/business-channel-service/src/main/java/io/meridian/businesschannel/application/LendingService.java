package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.application.Access.Need;
import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Business loans and credit lines. Every user of the business may see them; repaying needs a MAKER or ADMIN entitled
 * to initiate on the debited account, drawing on a credit line (new debt) needs an ADMIN, into an account of the
 * business the admin may initiate on. Money movement needs a recent authentication. Repayments and draws go through the
 * business's approval matrix when a rule of their type (LOAN_REPAYMENT, CREDIT_LINE_REPAYMENT, CREDIT_LINE_DRAW)
 * covers the amount ({@link OperationApprovals}); otherwise they act immediately.
 */
@Service
public class LendingService {
    static final String LOANS = "loan-service";

    private final Downstream down;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OperationApprovals approvals;

    public LendingService(Downstream down, Access access, StepUp stepUp, ActionAudit audit, OperationApprovals approvals) {
        this.down = down;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.approvals = approvals;
    }

    public JsonNode loans(Integer limit, String cursor) {
        return down.page(down.get(LOANS, "/v1/loans", Query.of("customer_id", business()).paging(limit, cursor)),
                r -> Access.ofBusiness(r, "customer_id"));
    }

    public JsonNode loan(UUID id) {
        return access.loan(id, "businessLoansGet");
    }

    public JsonNode schedule(UUID id, Integer limit, String cursor) {
        access.loan(id, "businessLoansSchedule");
        return down.get(LOANS, "/v1/loans/" + id + "/schedule", Query.none().paging(limit, cursor));
    }

    public OperationApprovals.Outcome repayLoan(UUID id, JsonNode req, String key) {
        String action = "businessLoansRepay";
        access.requireRole(action, "LOAN", id, "ADMIN", "MAKER");
        JsonNode loan = access.loan(id, action);
        UUID source = Req.uuid(req, "source_account_id", true);
        JsonNode account = access.account(source, Need.INITIATE, action);
        BigDecimal amount = Req.amount(req, "amount");
        stepUp.require(action, "LOAN", id);
        OperationApprovals.Outcome r = approvals.run("LOAN_REPAYMENT", account, amount,
                "Loan repayment " + loan.path("loan_number").asString(id.toString()), LOANS, "/v1/loans/" + id + "/repay",
                down.json().createObjectNode().put("amount", amount.toPlainString()).put("source", "MANUAL")
                        .put("source_account_id", source.toString()), key, "repay");
        audit.succeeded(action, "LOAN", id, r.body().path("id").asString(null));
        return r;
    }

    public JsonNode creditLines(Integer limit, String cursor) {
        return down.page(down.get(LOANS, "/v1/credit-lines", Query.of("customer_id", business()).paging(limit, cursor)),
                r -> Access.ofBusiness(r, "customer_id"));
    }

    public JsonNode creditLine(UUID id) {
        return access.creditLine(id, "businessCreditLinesGet");
    }

    public JsonNode drawdowns(UUID id, Integer limit, String cursor) {
        access.creditLine(id, "businessCreditLinesDrawdowns");
        return down.page(down.get(LOANS, "/v1/credit-lines/" + id + "/drawdowns", Query.none().paging(limit, cursor)),
                r -> !r.has("credit_line_id") || id.toString().equals(r.path("credit_line_id").asString(null)));
    }

    public OperationApprovals.Outcome draw(UUID id, JsonNode req, String key) {
        String action = "businessCreditLinesDraw";
        access.requireAdmin(action, "CREDIT_LINE", id);
        JsonNode line = access.creditLine(id, action);
        UUID destination = Req.uuid(req, "destination_account_id", true);
        JsonNode account = access.account(destination, Need.INITIATE, action);
        if (!account.path("currency").asString("").equals(line.path("currency").asString(""))) {
            throw ApiException.unprocessable("currency_mismatch", "The account must be in the credit line currency");
        }
        BigDecimal amount = Req.amount(req, "amount");
        stepUp.require(action, "CREDIT_LINE", id);
        OperationApprovals.Outcome r = approvals.run("CREDIT_LINE_DRAW", account, amount, "Credit line draw",
                LOANS, "/v1/credit-lines/" + id + "/draw", down.json().createObjectNode()
                        .put("draw_type", "TRANSFER_TO_ACCOUNT").put("amount", amount.toPlainString())
                        .put("destination_account_id", destination.toString()), key, "draw");
        audit.succeeded(action, "CREDIT_LINE", id, r.body().path("id").asString(null));
        return r;
    }

    public OperationApprovals.Outcome repayLine(UUID id, JsonNode req, String key) {
        String action = "businessCreditLinesRepay";
        access.requireRole(action, "CREDIT_LINE", id, "ADMIN", "MAKER");
        access.creditLine(id, action);
        UUID source = Req.uuid(req, "source_account_id", true);
        JsonNode account = access.account(source, Need.INITIATE, action);
        BigDecimal amount = Req.amount(req, "amount");
        stepUp.require(action, "CREDIT_LINE", id);
        OperationApprovals.Outcome r = approvals.run("CREDIT_LINE_REPAYMENT", account, amount, "Credit line repayment",
                LOANS, "/v1/credit-lines/" + id + "/repay", down.json().createObjectNode()
                        .put("amount", amount.toPlainString()).put("source", "MANUAL")
                        .put("source_account_id", source.toString()), key, "repay");
        audit.succeeded(action, "CREDIT_LINE", id, r.body().path("id").asString(null));
        return r;
    }
}
