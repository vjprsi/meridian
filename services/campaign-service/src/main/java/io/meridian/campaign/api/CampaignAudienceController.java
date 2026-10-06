package io.meridian.campaign.api;

import io.meridian.campaign.api.generated.CampaignAudienceApi;
import io.meridian.campaign.api.generated.model.CampaignAudienceConvertedRequest;
import io.meridian.campaign.api.generated.model.CampaignAudienceMember;
import io.meridian.campaign.application.AudienceService;
import io.meridian.campaign.application.Json;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Engagement tracking of campaign audience members. */
@RestController
public class CampaignAudienceController implements CampaignAudienceApi {
    private final AudienceService audience;
    private final Http http;

    public CampaignAudienceController(AudienceService audience, Http http) {
        this.audience = audience;
        this.http = http;
    }

    @Override
    public ResponseEntity<CampaignAudienceMember> campaignAudienceOpened(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "audience.opened", id, null, CampaignAudienceMember.class, () -> audience.opened(id));
    }

    @Override
    public ResponseEntity<CampaignAudienceMember> campaignAudienceClicked(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "audience.clicked", id, null, CampaignAudienceMember.class, () -> audience.clicked(id));
    }

    @Override
    public ResponseEntity<CampaignAudienceMember> campaignAudienceConverted(UUID id, String idempotencyKey,
                                                                            CampaignAudienceConvertedRequest body) {
        BigDecimal revenue = Json.decimal(http.tree(body), "revenue");
        return http.ok(idempotencyKey, "audience.converted", id, body, CampaignAudienceMember.class,
                () -> audience.converted(id, revenue));
    }

    @Override
    public ResponseEntity<CampaignAudienceMember> campaignAudienceUnsubscribe(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "audience.unsubscribe", id, null, CampaignAudienceMember.class,
                () -> audience.unsubscribe(id));
    }
}
