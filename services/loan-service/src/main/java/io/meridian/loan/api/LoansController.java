package io.meridian.loan.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loan.api.generated.LoansApi;
import io.meridian.loan.api.generated.model.Loan;
import io.meridian.loan.api.generated.model.LoanAccrualPage;
import io.meridian.loan.api.generated.model.LoanCreate;
import io.meridian.loan.api.generated.model.LoanDisbursement;
import io.meridian.loan.api.generated.model.LoanPrepaymentQuote;
import io.meridian.loan.api.generated.model.LoanRateChange;
import io.meridian.loan.api.generated.model.LoanRepayment;
import io.meridian.loan.api.generated.model.LoanRepaymentPage;
import io.meridian.loan.api.generated.model.LoanRestructure;
import io.meridian.loan.api.generated.model.LoanStatusHistoryPage;
import io.meridian.loan.api.generated.model.LoanUpdate;
import io.meridian.loan.api.generated.model.LoanWriteOff;
import io.meridian.loan.api.generated.model.LoansCancelRequest;
import io.meridian.loan.api.generated.model.LoansChangeRateRequest;
import io.meridian.loan.api.generated.model.LoansDisburseRequest;
import io.meridian.loan.api.generated.model.LoansPrepayRequest;
import io.meridian.loan.api.generated.model.LoansPrepaymentQuoteRequest;
import io.meridian.loan.api.generated.model.LoansRepayRequest;
import io.meridian.loan.api.generated.model.LoansRestructureRequest;
import io.meridian.loan.api.generated.model.LoansSetAutoDebitRequest;
import io.meridian.loan.api.generated.model.LoansSimulateRequest;
import io.meridian.loan.api.generated.model.LoansWriteOffRequest;
import io.meridian.loan.api.generated.model.RepaymentSchedulePage;
import io.meridian.loan.api.generated.model.ScheduleInstallmentPage;
import io.meridian.loan.application.DisbursementService;
import io.meridian.loan.application.EndOfDayJobs;
import io.meridian.loan.application.LoanService;
import io.meridian.loan.application.LoanSupport;
import io.meridian.loan.application.PrepaymentService;
import io.meridian.loan.application.RepaymentService;
import io.meridian.loan.application.RestructureService;
import io.meridian.loan.application.WriteOffService;
import io.meridian.loan.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Loans: booking, disbursement, repayments, prepayment, rate changes, restructures, write-offs, views and EOD jobs. */
@RestController
public class LoansController implements LoansApi {
    private final LoanService loans;
    private final LoanSupport support;
    private final DisbursementService disbursements;
    private final RepaymentService repayments;
    private final PrepaymentService prepayments;
    private final RestructureService restructures;
    private final WriteOffService writeOffs;
    private final EndOfDayJobs eod;
    private final CrudEngine crud;
    private final Actions actions;

    public LoansController(LoanService loans, LoanSupport support, DisbursementService disbursements,
                           RepaymentService repayments, PrepaymentService prepayments, RestructureService restructures,
                           WriteOffService writeOffs, EndOfDayJobs eod, CrudEngine crud, Actions actions) {
        this.loans = loans;
        this.support = support;
        this.disbursements = disbursements;
        this.repayments = repayments;
        this.prepayments = prepayments;
        this.restructures = restructures;
        this.writeOffs = writeOffs;
        this.eod = eod;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Loan> loansCreate(String idempotencyKey, LoanCreate body) {
        return actions.create(idempotencyKey, body, Loan.class, () -> loans.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Loan> loansUpdate(UUID id, String ifMatch, LoanUpdate body) {
        TenantContext.require();
        loans.guardPatch(actions.tree(body), support.get(id));
        return crud.update(LoanSupport.LOAN, Loan.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<LoanDisbursement> loansDisburse(UUID id, String idempotencyKey, LoansDisburseRequest body) {
        return actions.action(idempotencyKey, id, "disburse", body, LoanDisbursement.class,
                () -> disbursements.disburse(id, actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<LoanRepayment> loansRepay(UUID id, String idempotencyKey, LoansRepayRequest body) {
        return actions.action(idempotencyKey, id, "repay", body, LoanRepayment.class,
                () -> repayments.repay(id, actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<LoanPrepaymentQuote> loansPrepaymentQuote(UUID id, String idempotencyKey, LoansPrepaymentQuoteRequest body) {
        return actions.action(idempotencyKey, id, "prepayment-quote", body, LoanPrepaymentQuote.class,
                () -> prepayments.quote(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<LoanRepayment> loansPrepay(UUID id, String idempotencyKey, LoansPrepayRequest body) {
        return actions.action(idempotencyKey, id, "prepay", body, LoanRepayment.class,
                () -> prepayments.prepay(id, actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<LoanRateChange> loansChangeRate(UUID id, String idempotencyKey, LoansChangeRateRequest body) {
        return actions.action(idempotencyKey, id, "change-rate", body, LoanRateChange.class,
                () -> restructures.changeRate(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<LoanRestructure> loansRestructure(UUID id, String idempotencyKey, LoansRestructureRequest body) {
        return actions.action(idempotencyKey, id, "restructure", body, LoanRestructure.class,
                () -> restructures.propose(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<LoanWriteOff> loansWriteOff(UUID id, String idempotencyKey, LoansWriteOffRequest body) {
        return actions.action(idempotencyKey, id, "write-off", body, LoanWriteOff.class,
                () -> writeOffs.request(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<Loan> loansCancel(UUID id, String idempotencyKey, LoansCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, Loan.class,
                () -> loans.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Loan> loansClose(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "close", null, Loan.class, () -> loans.close(id));
    }

    @Override
    public ResponseEntity<Loan> loansSetAutoDebit(UUID id, String idempotencyKey, LoansSetAutoDebitRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "set-auto-debit", body, Loan.class,
                () -> loans.setAutoDebit(id, req.path("auto_debit_enabled").asBoolean(), Http.uuid(req, "repayment_account_id")));
    }

    @Override
    public ResponseEntity<ScheduleInstallmentPage> loansSchedule(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        support.get(id);
        List<tools.jackson.databind.node.ObjectNode> list = support.rows().query("""
                select row_to_json(i)::text from schedule_installment i
                where i.loan_id = ? and (i.repayment_schedule_id = (select id from repayment_schedule s where s.loan_id = ?
                        order by (s.status = 'ACTIVE') desc, s.version desc limit 1)
                     or i.status in ('DUE','PARTIALLY_PAID','OVERDUE'))
                order by i.due_date, i.installment_no limit ?""", id, id, limit == null ? 1000 : Math.min(Math.max(limit, 1), 1000));
        return ResponseEntity.ok(page(list, ScheduleInstallmentPage.class));
    }

    @Override
    public ResponseEntity<RepaymentSchedulePage> loansSchedules(UUID id, Integer limit, String cursor) {
        return child("repayment_schedule", RepaymentSchedulePage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<LoanRepaymentPage> loansRepayments(UUID id, Integer limit, String cursor) {
        return child("loan_repayment", LoanRepaymentPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<LoanAccrualPage> loansAccruals(UUID id, Integer limit, String cursor) {
        return child("loan_accrual", LoanAccrualPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<LoanStatusHistoryPage> loansStatusHistory(UUID id, Integer limit, String cursor) {
        return child("loan_status_history", LoanStatusHistoryPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<Map<String, Object>> loansSimulate(String idempotencyKey, LoansSimulateRequest body) {
        return actions.objectAction(idempotencyKey, "simulate", body, () -> loans.simulate(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> loansEodAccrue(LocalDate businessDate, String idempotencyKey) {
        return eod(EndOfDayJobs.Job.DAILY_ACCRUAL, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> loansEodInstallmentsDue(LocalDate businessDate, String idempotencyKey) {
        return eod(EndOfDayJobs.Job.INSTALLMENT_DUE, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> loansEodAutoDebit(LocalDate businessDate, String idempotencyKey) {
        return eod(EndOfDayJobs.Job.AUTO_DEBIT, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> loansEodDelinquency(LocalDate businessDate, String idempotencyKey) {
        return eod(EndOfDayJobs.Job.DELINQUENCY_UPDATE, businessDate, idempotencyKey);
    }

    private ResponseEntity<Map<String, Object>> eod(EndOfDayJobs.Job job, LocalDate businessDate, String key) {
        return actions.objectAction(key, "eod-" + job.name(), Map.of("business_date", String.valueOf(businessDate)),
                () -> eod.run(job, businessDate));
    }

    private <T> ResponseEntity<T> child(String table, Class<T> page, UUID id, Integer limit, String cursor) {
        TenantContext.require();
        support.get(id);
        return crud.list(table, page, Crud.params().with("loan_id", id).with("limit", limit).with("cursor", cursor));
    }

    private <T> T page(List<ObjectNode> rows, Class<T> type) {
        ObjectNode p = support.rows().object();
        ArrayNode data = p.putArray("data");
        rows.forEach(data::add);
        p.put("has_more", false);
        p.putNull("next_cursor");
        return support.rows().as(p, type);
    }

    static String text(JsonNode n, String f) {
        return Rows.str(n, f);
    }
}
