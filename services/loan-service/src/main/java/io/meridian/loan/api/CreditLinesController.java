package io.meridian.loan.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loan.api.generated.CreditLinesApi;
import io.meridian.loan.api.generated.model.CreditLine;
import io.meridian.loan.api.generated.model.CreditLineCreate;
import io.meridian.loan.api.generated.model.CreditLineDrawdown;
import io.meridian.loan.api.generated.model.CreditLineDrawdownPage;
import io.meridian.loan.api.generated.model.CreditLineLimitChange;
import io.meridian.loan.api.generated.model.CreditLineLimitChangePage;
import io.meridian.loan.api.generated.model.CreditLineStatementPage;
import io.meridian.loan.api.generated.model.CreditLineUpdate;
import io.meridian.loan.api.generated.model.CreditLinesChangeLimitRequest;
import io.meridian.loan.api.generated.model.CreditLinesCloseRequest;
import io.meridian.loan.api.generated.model.CreditLinesConvertToInstalmentsRequest;
import io.meridian.loan.api.generated.model.CreditLinesDrawRequest;
import io.meridian.loan.api.generated.model.CreditLinesFreezeRequest;
import io.meridian.loan.api.generated.model.CreditLinesRepayRequest;
import io.meridian.loan.api.generated.model.CreditLinesUnfreezeRequest;
import io.meridian.loan.api.generated.model.Loan;
import io.meridian.loan.api.generated.model.LoanRepayment;
import io.meridian.loan.application.CreditLineService;
import io.meridian.loan.application.EndOfDayJobs;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Revolving credit lines: lifecycle, draws, payments, limits, conversions, views and the statement cycle job. */
@RestController
public class CreditLinesController implements CreditLinesApi {
    /** Fields a PATCH may change (money, rates, status and links are owned by actions). */
    static final Set<String> PATCHABLE = Set.of("review_date", "metadata", "autopay_mode", "repayment_account_id",
            "card_id", "custom_field_1", "custom_field_2", "custom_field_3", "custom_field_4", "custom_field_5");

    private final CreditLineService lines;
    private final EndOfDayJobs eod;
    private final CrudEngine crud;
    private final Actions actions;

    public CreditLinesController(CreditLineService lines, EndOfDayJobs eod, CrudEngine crud, Actions actions) {
        this.lines = lines;
        this.eod = eod;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CreditLine> creditLinesCreate(String idempotencyKey, CreditLineCreate body) {
        return actions.create(idempotencyKey, body, CreditLine.class, () -> lines.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CreditLine> creditLinesUpdate(UUID id, String ifMatch, CreditLineUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        req.propertyNames().forEach(f -> {
            if (!req.get(f).isNull() && !PATCHABLE.contains(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed with PATCH; use the credit-line actions");
            }
        });
        String autopay = Http.text(req, "autopay_mode");
        if (autopay != null && !Set.of("NONE", "MINIMUM", "STATEMENT", "FULL").contains(autopay)) {
            throw ApiException.unprocessable("invalid_autopay_mode", "autopay_mode must be NONE, MINIMUM, STATEMENT or FULL");
        }
        return crud.update("credit_line", CreditLine.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<CreditLine> creditLinesActivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "activate", null, CreditLine.class, () -> lines.activate(id));
    }

    @Override
    public ResponseEntity<CreditLineDrawdown> creditLinesDraw(UUID id, String idempotencyKey, CreditLinesDrawRequest body) {
        return actions.action(idempotencyKey, id, "draw", body, CreditLineDrawdown.class,
                () -> lines.draw(id, actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<LoanRepayment> creditLinesRepay(UUID id, String idempotencyKey, CreditLinesRepayRequest body) {
        return actions.action(idempotencyKey, id, "repay", body, LoanRepayment.class,
                () -> lines.repay(id, actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<CreditLineLimitChange> creditLinesChangeLimit(UUID id, String idempotencyKey,
                                                                        CreditLinesChangeLimitRequest body) {
        return actions.action(idempotencyKey, id, "change-limit", body, CreditLineLimitChange.class,
                () -> lines.changeLimit(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<Loan> creditLinesConvertToInstalments(UUID id, String idempotencyKey,
                                                                CreditLinesConvertToInstalmentsRequest body) {
        return actions.action(idempotencyKey, id, "convert-to-instalments", body, Loan.class,
                () -> lines.convert(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<CreditLine> creditLinesFreeze(UUID id, String idempotencyKey, CreditLinesFreezeRequest body) {
        return actions.action(idempotencyKey, id, "freeze", body, CreditLine.class,
                () -> lines.freeze(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CreditLine> creditLinesUnfreeze(UUID id, String idempotencyKey, CreditLinesUnfreezeRequest body) {
        return actions.action(idempotencyKey, id, "unfreeze", body, CreditLine.class,
                () -> lines.unfreeze(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CreditLine> creditLinesClose(UUID id, String idempotencyKey, CreditLinesCloseRequest body) {
        return actions.action(idempotencyKey, id, "close", body, CreditLine.class,
                () -> lines.close(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> creditLinesAvailable(UUID id) {
        TenantContext.require();
        return actions.object(lines.available(id));
    }

    @Override
    public ResponseEntity<CreditLineDrawdownPage> creditLinesDrawdowns(UUID id, Integer limit, String cursor) {
        return child("credit_line_drawdown", CreditLineDrawdownPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<CreditLineStatementPage> creditLinesStatements(UUID id, Integer limit, String cursor) {
        return child("credit_line_statement", CreditLineStatementPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<CreditLineLimitChangePage> creditLinesLimitChanges(UUID id, Integer limit, String cursor) {
        return child("credit_line_limit_change", CreditLineLimitChangePage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<Map<String, Object>> creditLinesEodStatements(LocalDate businessDate, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "eod-" + EndOfDayJobs.Job.CREDIT_LINE_STATEMENTS.name(),
                Map.of("business_date", String.valueOf(businessDate)),
                () -> eod.run(EndOfDayJobs.Job.CREDIT_LINE_STATEMENTS, businessDate));
    }

    private <T> ResponseEntity<T> child(String table, Class<T> page, UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("credit_line", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("credit line", id));
        return crud.list(table, page, Crud.params().with("credit_line_id", id).with("limit", limit).with("cursor", cursor));
    }
}
