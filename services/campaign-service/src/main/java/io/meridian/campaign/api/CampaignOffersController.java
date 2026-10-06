package io.meridian.campaign.api;

import io.meridian.campaign.api.generated.CampaignOffersApi;
import io.meridian.campaign.api.generated.model.CampaignOffer;
import io.meridian.campaign.api.generated.model.CampaignOfferCreate;
import io.meridian.campaign.api.generated.model.CampaignOfferUpdate;
import io.meridian.campaign.api.generated.model.CampaignOffersAssignRequest;
import io.meridian.campaign.api.generated.model.CampaignOffersPauseRequest;
import io.meridian.campaign.api.generated.model.CampaignOffersWithdrawRequest;
import io.meridian.campaign.application.Json;
import io.meridian.campaign.application.OfferService;
import io.meridian.common.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Personalised offers: lifecycle and consent-checked assignment. */
@RestController
public class CampaignOffersController implements CampaignOffersApi {
    private final OfferService offers;
    private final Http http;

    public CampaignOffersController(OfferService offers, Http http) {
        this.offers = offers;
        this.http = http;
    }

    @Override
    public ResponseEntity<CampaignOffer> campaignOffersCreate(String idempotencyKey, CampaignOfferCreate body) {
        return http.created(idempotencyKey, "offer.create", body, CampaignOffer.class, () -> offers.create(body));
    }

    @Override
    public ResponseEntity<CampaignOffer> campaignOffersUpdate(UUID id, String ifMatch, CampaignOfferUpdate body) {
        TenantContext.require();
        return http.withEtag(offers.update(id, ifMatch, body), CampaignOffer.class);
    }

    @Override
    public ResponseEntity<CampaignOffer> campaignOffersActivate(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "offer.activate", id, null, CampaignOffer.class, () -> offers.activate(id));
    }

    @Override
    public ResponseEntity<CampaignOffer> campaignOffersPause(UUID id, String idempotencyKey, CampaignOffersPauseRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "offer.pause", id, body, CampaignOffer.class, () -> offers.pause(id, reason));
    }

    @Override
    public ResponseEntity<CampaignOffer> campaignOffersWithdraw(UUID id, String idempotencyKey,
                                                                CampaignOffersWithdrawRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "offer.withdraw", id, body, CampaignOffer.class, () -> offers.withdraw(id, reason));
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignOffersAssign(UUID id, String idempotencyKey,
                                                                    CampaignOffersAssignRequest body) {
        JsonNode req = http.tree(body);
        return http.document(idempotencyKey, "offer.assign", id, body, () -> offers.assign(id, req));
    }
}
