package io.meridian.gl.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.api.generated.EclProvisionsApi;
import io.meridian.gl.api.generated.model.EclProvisionsCalculateRequest;
import io.meridian.gl.api.generated.model.PeriodCloseRun;
import io.meridian.gl.application.EclService;
import io.meridian.web.idempotency.Idempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IFRS 9 ECL. /calculate accepts, beyond the generated request model, {@code exposures}
 * ([{exposure_type, exposure_id, stage, ead, pd_12m, pd_lifetime | pd, lgd, currency}]) and the optional
 * {@code provision_expense_gl_account_id} / {@code allowance_gl_account_id} used to post the provision journal.
 */
@RestController
public class EclProvisionsController implements EclProvisionsApi {
    private final EclService ecl;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public EclProvisionsController(EclService ecl, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.ecl = ecl;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<PeriodCloseRun> eclProvisionsCalculate(String idempotencyKey, EclProvisionsCalculateRequest body) {
        TenantContext.require();
        JsonNode req = RawBody.json(json);
        return idempotency.execute(idempotencyKey, req, PeriodCloseRun.class,
                () -> responses.ok(ecl.calculate(req), PeriodCloseRun.class));
    }
}
