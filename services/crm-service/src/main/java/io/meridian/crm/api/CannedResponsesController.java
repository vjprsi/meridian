package io.meridian.crm.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.CannedResponsesApi;
import io.meridian.crm.api.generated.model.CannedResponse;
import io.meridian.crm.api.generated.model.CannedResponseCreate;
import io.meridian.crm.api.generated.model.CannedResponseUpdate;
import io.meridian.crm.api.generated.model.CannedResponsesRenderRequest;
import io.meridian.crm.application.CannedResponseService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Canned responses with validated placeholders and rendering for a case, complaint or customer. */
@RestController
public class CannedResponsesController implements CannedResponsesApi {
    private final CannedResponseService canned;
    private final Http http;

    public CannedResponsesController(CannedResponseService canned, Http http) {
        this.canned = canned;
        this.http = http;
    }

    @Override
    public ResponseEntity<CannedResponse> cannedResponsesCreate(String idempotencyKey, CannedResponseCreate body) {
        return http.created(idempotencyKey, "canned.create", body, CannedResponse.class, () -> canned.create(body));
    }

    @Override
    public ResponseEntity<CannedResponse> cannedResponsesUpdate(UUID id, String ifMatch, CannedResponseUpdate body) {
        TenantContext.require();
        return http.withEtag(canned.update(id, ifMatch, body), CannedResponse.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> cannedResponsesRender(UUID id, String idempotencyKey,
                                                                    CannedResponsesRenderRequest body) {
        JsonNode req = http.tree(body);
        return http.document(idempotencyKey, "canned.render", id, body, () -> canned.render(id, req));
    }
}
