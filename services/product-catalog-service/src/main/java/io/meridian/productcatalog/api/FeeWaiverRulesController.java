package io.meridian.productcatalog.api;

import io.meridian.productcatalog.api.generated.FeeWaiverRulesApi;
import io.meridian.productcatalog.api.generated.model.FeeWaiverRule;
import io.meridian.productcatalog.api.generated.model.FeeWaiverRuleCreate;
import io.meridian.productcatalog.api.generated.model.FeeWaiverRuleUpdate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Waiver rules of a fee. Writes are only allowed while the owning version is DRAFT (409 version_not_editable otherwise) and are
 * validated against product rules (422 invalid_configuration).
 */
@RestController
public class FeeWaiverRulesController implements FeeWaiverRulesApi {
    private final ContentEndpoints content;

    public FeeWaiverRulesController(ContentEndpoints content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<FeeWaiverRule> feeWaiverRulesCreate(String idempotencyKey, FeeWaiverRuleCreate body) {
        return content.create("fee_waiver_rule", FeeWaiverRule.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<FeeWaiverRule> feeWaiverRulesUpdate(UUID id, String ifMatch, FeeWaiverRuleUpdate body) {
        return content.update("fee_waiver_rule", FeeWaiverRule.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> feeWaiverRulesDelete(UUID id) {
        return content.delete("fee_waiver_rule", id);
    }
}
