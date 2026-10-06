package io.meridian.campaign.application;

import static io.meridian.campaign.application.Json.text;

import io.meridian.campaign.infrastructure.ExternalServices;
import io.meridian.campaign.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** A/B variants: editable only while their campaign is DRAFT; test sends go through notification-service. */
@Service
public class VariantService {
    static final String T = "campaign_variant";

    private final Rows rows;
    private final CrudEngine crud;
    private final ExternalServices external;

    public VariantService(Rows rows, CrudEngine crud, ExternalServices external) {
        this.rows = rows;
        this.crud = crud;
        this.external = external;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        if (text(v, "code") != null) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        ObjectNode campaign = draftCampaign(Json.uuid(v, "campaign_id"));
        validate(v, campaign);
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        ObjectNode v = crud.toColumns(T, body);
        if (v.has("campaign_id") && !text(v, "campaign_id").equals(text(current, "campaign_id"))) {
            throw ApiException.unprocessable("immutable_field", "campaign_id cannot be changed");
        }
        if (v.has("code")) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        ObjectNode campaign = draftCampaign(Json.uuid(current, "campaign_id"));
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged, campaign);
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    @Transactional
    public void delete(UUID id) {
        ObjectNode current = rows.lock(T, id);
        draftCampaign(Json.uuid(current, "campaign_id"));
        rows.execute("delete from campaign_variant where id = ?", id);
    }

    public ObjectNode testSend(UUID id, String recipient) {
        ObjectNode v = rows.get(T, id);
        if (!ExternalServices.deliverable(text(v, "channel"))) {
            throw ApiException.unprocessable("channel_not_sendable", text(v, "channel") + " messages are not sent electronically");
        }
        if (recipient == null || recipient.isBlank()) {
            throw ApiException.badRequest("validation_failed", "recipient is required");
        }
        String body = text(v, "body") != null ? text(v, "body") : "[template " + text(v, "notification_template_code") + "]";
        UUID notification = external.sendTest(recipient.trim(), text(v, "channel"), text(v, "subject"),
                "[TEST] " + body, "campaign-test:" + id + ":" + UUID.randomUUID());
        ObjectNode out = rows.object();
        out.put("campaign_variant_id", id.toString());
        out.put("notification_id", notification.toString());
        out.put("channel", text(v, "channel"));
        out.put("recipient", recipient.trim());
        return out;
    }

    private ObjectNode draftCampaign(UUID campaignId) {
        ObjectNode c = rows.queryOne("select row_to_json(t)::text from campaign t where t.id = ? for update", campaignId)
                .orElseThrow(() -> ApiException.unprocessable("campaign_not_found", "Campaign " + campaignId + " does not exist"));
        if (!"DRAFT".equals(text(c, "status"))) {
            throw ApiException.conflict("campaign_not_editable", "Variants can only change while the campaign is DRAFT");
        }
        return c;
    }

    private void validate(ObjectNode v, ObjectNode campaign) {
        List<FieldError> errors = new ArrayList<>();
        BigDecimal share = Json.decimal(v, "traffic_share");
        if (share == null || share.signum() <= 0 || share.compareTo(BigDecimal.ONE) > 0) {
            errors.add(new FieldError("traffic_share", "out_of_range", "traffic_share must be in (0, 1]"));
        }
        if (!Json.strings(campaign.get("channels")).contains(text(v, "channel"))) {
            errors.add(new FieldError("channel", "not_a_campaign_channel", "The campaign does not use channel " + text(v, "channel")));
        }
        if (text(v, "notification_template_code") == null && text(v, "body") == null
                && ExternalServices.deliverable(text(v, "channel"))) {
            errors.add(new FieldError("body", "required", "A body or a notification_template_code is required"));
        }
        if (text(v, "offer_id") != null && rows.find("campaign_offer", Json.uuid(v, "offer_id")).isEmpty()) {
            errors.add(new FieldError("offer_id", "not_found", "Offer does not exist"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_variant", "Variant is invalid", errors);
        }
    }
}
