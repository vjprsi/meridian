package io.meridian.gl.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.api.generated.GlAccountsApi;
import io.meridian.gl.api.generated.model.GlAccount;
import io.meridian.gl.api.generated.model.GlAccountCreate;
import io.meridian.gl.api.generated.model.GlAccountUpdate;
import io.meridian.gl.application.ChartService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Chart of accounts: hierarchy rules on create and update. List/get are generic CRUD. */
@RestController
public class GlAccountsController implements GlAccountsApi {
    private final ChartService chart;
    private final Idempotency idempotency;
    private final Responses responses;

    public GlAccountsController(ChartService chart, Idempotency idempotency, Responses responses) {
        this.chart = chart;
        this.idempotency = idempotency;
        this.responses = responses;
    }

    @Override
    public ResponseEntity<GlAccount> glAccountsCreate(String idempotencyKey, GlAccountCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, GlAccount.class,
                () -> responses.created(chart.create(body), GlAccount.class));
    }

    @Override
    public ResponseEntity<GlAccount> glAccountsUpdate(UUID id, String ifMatch, GlAccountUpdate body) {
        TenantContext.require();
        return responses.ok(chart.update(id, ifMatch, body), GlAccount.class);
    }
}
