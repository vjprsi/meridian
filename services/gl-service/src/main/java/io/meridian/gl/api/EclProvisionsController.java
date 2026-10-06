package io.meridian.gl.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.api.generated.EclProvisionsApi;
import io.meridian.gl.api.generated.model.EclProvisionsCalculateRequest;
import io.meridian.gl.application.EclService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IFRS 9 ECL. /calculate takes the exposures ([{exposure_type, exposure_id, stage, ead, pd_12m, pd_lifetime or pd,
 * lgd, currency}]) and the optional provision GL accounts used to post the provision journal; it returns the
 * calculation result (totals, journal, persisted provisions).
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
    public ResponseEntity<Map<String, Object>> eclProvisionsCalculate(String idempotencyKey,
                                                                      EclProvisionsCalculateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return responses.object(idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.ok(ecl.calculate(req))));
    }
}
