package io.meridian.campaign.api;

import io.meridian.campaign.api.generated.CampaignVariantsApi;
import io.meridian.campaign.api.generated.model.CampaignVariant;
import io.meridian.campaign.api.generated.model.CampaignVariantCreate;
import io.meridian.campaign.api.generated.model.CampaignVariantUpdate;
import io.meridian.campaign.api.generated.model.CampaignVariantsTestSendRequest;
import io.meridian.campaign.application.Json;
import io.meridian.campaign.application.VariantService;
import io.meridian.common.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Campaign A/B variants (editable while the campaign is DRAFT) and test sends. */
@RestController
public class CampaignVariantsController implements CampaignVariantsApi {
    private final VariantService variants;
    private final Http http;

    public CampaignVariantsController(VariantService variants, Http http) {
        this.variants = variants;
        this.http = http;
    }

    @Override
    public ResponseEntity<CampaignVariant> campaignVariantsCreate(String idempotencyKey, CampaignVariantCreate body) {
        return http.created(idempotencyKey, "variant.create", body, CampaignVariant.class, () -> variants.create(body));
    }

    @Override
    public ResponseEntity<CampaignVariant> campaignVariantsUpdate(UUID id, String ifMatch, CampaignVariantUpdate body) {
        TenantContext.require();
        return http.withEtag(variants.update(id, ifMatch, body), CampaignVariant.class);
    }

    @Override
    public ResponseEntity<Void> campaignVariantsDelete(UUID id) {
        TenantContext.require();
        variants.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignVariantsTestSend(UUID id, String idempotencyKey,
                                                                        CampaignVariantsTestSendRequest body) {
        String recipient = Json.required(http.tree(body), "recipient");
        return http.document(idempotencyKey, "variant.test-send", id, body, () -> variants.testSend(id, recipient));
    }
}
