package io.meridian.gl.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.api.generated.AccountingPeriodsApi;
import io.meridian.gl.api.generated.model.AccountingPeriod;
import io.meridian.gl.api.generated.model.AccountingPeriodCreate;
import io.meridian.gl.api.generated.model.AccountingPeriodsApproveCloseRequest;
import io.meridian.gl.api.generated.model.AccountingPeriodsReopenRequest;
import io.meridian.gl.api.generated.model.PeriodCloseRun;
import io.meridian.gl.application.Fields;
import io.meridian.gl.application.PeriodService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Accounting periods: non-overlapping creation, maker/checker close (incl. year-end P&L transfer), reopen, trial balance. */
@RestController
public class AccountingPeriodsController implements AccountingPeriodsApi {
    private final PeriodService periods;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public AccountingPeriodsController(PeriodService periods, Idempotency idempotency, Responses responses,
                                       JsonMapper json) {
        this.periods = periods;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<AccountingPeriod> accountingPeriodsCreate(String idempotencyKey, AccountingPeriodCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, AccountingPeriod.class,
                () -> responses.created(periods.create(body), AccountingPeriod.class));
    }

    @Override
    public ResponseEntity<PeriodCloseRun> accountingPeriodsClose(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("close", id, null), PeriodCloseRun.class,
                () -> responses.ok(periods.close(id), PeriodCloseRun.class));
    }

    @Override
    public ResponseEntity<AccountingPeriod> accountingPeriodsApproveClose(UUID id, String idempotencyKey,
                                                                          AccountingPeriodsApproveCloseRequest body) {
        TenantContext.require();
        var req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("approve-close", id, req),
                AccountingPeriod.class,
                () -> responses.ok(periods.approveClose(id, Fields.text(req, "comment")), AccountingPeriod.class));
    }

    @Override
    public ResponseEntity<AccountingPeriod> accountingPeriodsReopen(UUID id, String idempotencyKey,
                                                                    AccountingPeriodsReopenRequest body) {
        TenantContext.require();
        var req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("reopen", id, req),
                AccountingPeriod.class,
                () -> responses.ok(periods.reopen(id, Fields.text(req, "reason")), AccountingPeriod.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> accountingPeriodsTrialBalance(UUID id) {
        TenantContext.require();
        return responses.object(periods.trialBalance(id));
    }
}
