package io.meridian.productcatalog.api;

import io.meridian.productcatalog.api.generated.EligibilityRulesApi;
import io.meridian.productcatalog.api.generated.model.EligibilityRule;
import io.meridian.productcatalog.api.generated.model.EligibilityRuleCreate;
import io.meridian.productcatalog.api.generated.model.EligibilityRuleUpdate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Eligibility rules of a product version. Writes are only allowed while the owning version is DRAFT (409 version_not_editable otherwise) and are
 * validated against product rules (422 invalid_configuration).
 */
@RestController
public class EligibilityRulesController implements EligibilityRulesApi {
    private final ContentEndpoints content;

    public EligibilityRulesController(ContentEndpoints content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<EligibilityRule> eligibilityRulesCreate(String idempotencyKey, EligibilityRuleCreate body) {
        return content.create("eligibility_rule", EligibilityRule.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<EligibilityRule> eligibilityRulesUpdate(UUID id, String ifMatch, EligibilityRuleUpdate body) {
        return content.update("eligibility_rule", EligibilityRule.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> eligibilityRulesDelete(UUID id) {
        return content.delete("eligibility_rule", id);
    }
}
