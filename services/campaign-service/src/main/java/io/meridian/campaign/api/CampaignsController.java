package io.meridian.campaign.api;

import io.meridian.campaign.api.generated.CampaignsApi;
import io.meridian.campaign.api.generated.model.Campaign;
import io.meridian.campaign.api.generated.model.CampaignAudienceMemberPage;
import io.meridian.campaign.api.generated.model.CampaignCreate;
import io.meridian.campaign.api.generated.model.CampaignMetricPage;
import io.meridian.campaign.api.generated.model.CampaignUpdate;
import io.meridian.campaign.api.generated.model.CampaignVariantPage;
import io.meridian.campaign.api.generated.model.CampaignsApproveRequest;
import io.meridian.campaign.api.generated.model.CampaignsBuildAudienceRequest;
import io.meridian.campaign.api.generated.model.CampaignsCancelRequest;
import io.meridian.campaign.api.generated.model.CampaignsPauseRequest;
import io.meridian.campaign.api.generated.model.CampaignsPreviewAudienceRequest;
import io.meridian.campaign.api.generated.model.CampaignsRejectRequest;
import io.meridian.campaign.api.generated.model.CampaignsScheduleRequest;
import io.meridian.campaign.application.AudienceService;
import io.meridian.campaign.application.CampaignService;
import io.meridian.campaign.application.Json;
import io.meridian.campaign.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Campaigns: definition, maker-checker, audience selection, sending and performance. */
@RestController
public class CampaignsController implements CampaignsApi {
    private final CampaignService campaigns;
    private final AudienceService audience;
    private final Rows rows;
    private final Http http;

    public CampaignsController(CampaignService campaigns, AudienceService audience, Rows rows, Http http) {
        this.campaigns = campaigns;
        this.audience = audience;
        this.rows = rows;
        this.http = http;
    }

    @Override
    public ResponseEntity<Campaign> campaignsCreate(String idempotencyKey, CampaignCreate body) {
        return http.created(idempotencyKey, "campaign.create", body, Campaign.class, () -> campaigns.create(body));
    }

    @Override
    public ResponseEntity<Campaign> campaignsUpdate(UUID id, String ifMatch, CampaignUpdate body) {
        TenantContext.require();
        return http.withEtag(campaigns.update(id, ifMatch, body), Campaign.class);
    }

    @Override
    public ResponseEntity<Campaign> campaignsSubmit(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "campaign.submit", id, null, Campaign.class, () -> campaigns.submit(id));
    }

    @Override
    public ResponseEntity<Campaign> campaignsApprove(UUID id, String idempotencyKey, CampaignsApproveRequest body) {
        String comment = Json.text(http.tree(body), "comment");
        return http.ok(idempotencyKey, "campaign.approve", id, body, Campaign.class, () -> campaigns.approve(id, comment));
    }

    @Override
    public ResponseEntity<Campaign> campaignsReject(UUID id, String idempotencyKey, CampaignsRejectRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "campaign.reject", id, body, Campaign.class, () -> campaigns.reject(id, reason));
    }

    @Override
    public ResponseEntity<Campaign> campaignsSchedule(UUID id, String idempotencyKey, CampaignsScheduleRequest body) {
        JsonNode req = http.tree(body);
        OffsetDateTime start = OffsetDateTime.parse(Json.required(req, "start_at"));
        OffsetDateTime end = Json.ts(req, "end_at");
        return http.ok(idempotencyKey, "campaign.schedule", id, body, Campaign.class, () -> campaigns.schedule(id, start, end));
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignsBuildAudience(UUID id, String idempotencyKey,
                                                                      CampaignsBuildAudienceRequest body) {
        List<UUID> customers = Json.uuids(http.tree(body), "customer_ids");
        return http.document(idempotencyKey, "campaign.build-audience", id, body, () -> audience.build(id, customers));
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignsPreviewAudience(UUID id, String idempotencyKey,
                                                                        CampaignsPreviewAudienceRequest body) {
        List<UUID> customers = Json.uuids(http.tree(body), "customer_ids");
        return http.document(idempotencyKey, "campaign.preview-audience", id, body, () -> audience.preview(id, customers));
    }

    @Override
    public ResponseEntity<Campaign> campaignsLaunch(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "campaign.launch", id, null, Campaign.class, () -> campaigns.launch(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignsDispatch(UUID id, String idempotencyKey) {
        return http.document(idempotencyKey, "campaign.dispatch", id, null, () -> audience.dispatch(id));
    }

    @Override
    public ResponseEntity<Campaign> campaignsPause(UUID id, String idempotencyKey, CampaignsPauseRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "campaign.pause", id, body, Campaign.class, () -> campaigns.pause(id, reason));
    }

    @Override
    public ResponseEntity<Campaign> campaignsResume(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "campaign.resume", id, null, Campaign.class, () -> campaigns.resume(id));
    }

    @Override
    public ResponseEntity<Campaign> campaignsComplete(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "campaign.complete", id, null, Campaign.class, () -> campaigns.complete(id));
    }

    @Override
    public ResponseEntity<Campaign> campaignsCancel(UUID id, String idempotencyKey, CampaignsCancelRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "campaign.cancel", id, body, Campaign.class, () -> campaigns.cancel(id, reason));
    }

    @Override
    public ResponseEntity<CampaignVariantPage> campaignsVariants(UUID id, Integer limit, String cursor) {
        return ResponseEntity.ok(http.read(child("campaign_variant", id, limit, cursor), CampaignVariantPage.class));
    }

    @Override
    public ResponseEntity<CampaignMetricPage> campaignsMetrics(UUID id, Integer limit, String cursor) {
        return ResponseEntity.ok(http.read(child("campaign_metric", id, limit, cursor), CampaignMetricPage.class));
    }

    @Override
    public ResponseEntity<CampaignAudienceMemberPage> campaignsAudience(UUID id, Integer limit, String cursor) {
        return ResponseEntity.ok(http.read(child("campaign_audience_member", id, limit, cursor), CampaignAudienceMemberPage.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignsPerformance(UUID id) {
        TenantContext.require();
        return http.query(audience.performance(id));
    }

    private JsonNode child(String table, UUID campaignId, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("campaign", campaignId);
        return rows.page("select row_to_json(t)::text from " + table + " t where t.campaign_id = ?", List.of(campaignId),
                limit, cursor);
    }
}
