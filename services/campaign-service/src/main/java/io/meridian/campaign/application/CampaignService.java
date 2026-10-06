package io.meridian.campaign.application;

import static io.meridian.campaign.application.Json.text;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.campaign.api.generated.model.Campaign;
import io.meridian.campaign.domain.Lifecycles;
import io.meridian.campaign.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Campaign definition and lifecycle: DRAFT -> submit -> PENDING_APPROVAL -> approve (four eyes) -> SCHEDULED ->
 * launch -> RUNNING <-> PAUSED -> COMPLETED, with reject back to DRAFT and cancel from any open status. Definitions
 * can only change while DRAFT, so what the checker approved is what runs.
 */
@Service
public class CampaignService {
    static final String T = "campaign";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,31}");

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final AudienceService audience;
    private final Approvals approvals;

    /** Approval resource type for submitted campaigns in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "campaign";

    public CampaignService(Rows rows, CrudEngine crud, Events events, AudienceService audience, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.audience = audience;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        if (text(v, "code") != null) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        validate(v);
        v.put("status", "DRAFT");
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if (!"DRAFT".equals(text(current, "status"))) {
            throw ApiException.conflict("campaign_not_editable",
                    "A " + text(current, "status") + " campaign cannot be changed; only DRAFT campaigns are editable");
        }
        ObjectNode v = crud.toColumns(T, body);
        if (v.has("code")) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    private void validate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        String code = text(v, "code");
        if (code != null && !CODE.matcher(code).matches()) {
            errors.add(new FieldError("code", "invalid_format", "code must be 2-32 of A-Z, 0-9, '_' or '-'"));
        }
        if (Json.strings(v.get("segment_ids")).isEmpty()) {
            errors.add(new FieldError("segment_ids", "required", "At least one target segment is required"));
        }
        if (Json.strings(v.get("channels")).isEmpty()) {
            errors.add(new FieldError("channels", "required", "At least one channel is required"));
        }
        List<String> targets = Json.strings(v.get("segment_ids"));
        if (Json.strings(v.get("exclusion_segment_ids")).stream().anyMatch(targets::contains)) {
            errors.add(new FieldError("exclusion_segment_ids", "overlap", "A segment cannot be both targeted and excluded"));
        }
        BigDecimal control = Json.decimal(v, "control_group_pct");
        if (control != null && (control.signum() < 0 || control.compareTo(new BigDecimal("0.5")) > 0)) {
            errors.add(new FieldError("control_group_pct", "out_of_range", "control_group_pct must be between 0 and 0.5"));
        }
        OffsetDateTime start = Json.ts(v, "start_at");
        OffsetDateTime end = Json.ts(v, "end_at");
        if (start != null && end != null && !end.isAfter(start)) {
            errors.add(new FieldError("end_at", "before_start", "end_at must be after start_at"));
        }
        BigDecimal budget = Json.decimal(v, "budget_amount");
        if (budget != null) {
            if (budget.signum() <= 0) {
                errors.add(new FieldError("budget_amount", "must_be_positive", "budget_amount must be > 0"));
            }
            if (text(v, "currency") == null) {
                errors.add(new FieldError("currency", "required", "currency is required with budget_amount"));
            }
        }
        BigDecimal cpm = Json.decimal(v, "cost_per_message");
        if (cpm != null && cpm.signum() < 0) {
            errors.add(new FieldError("cost_per_message", "must_not_be_negative", "cost_per_message must be >= 0"));
        }
        if (v.hasNonNull("frequency_cap") && v.get("frequency_cap").asInt() < 1) {
            errors.add(new FieldError("frequency_cap", "out_of_range", "frequency_cap must be >= 1"));
        }
        JsonNode window = v.get("send_window");
        if (window != null && window.isObject() && !window.isEmpty()) {
            for (String f : List.of("start_hour", "end_hour")) {
                JsonNode h = window.get(f);
                if (h == null || !h.isIntegralNumber() || h.asInt() < 0 || h.asInt() > 23) {
                    errors.add(new FieldError("send_window." + f, "out_of_range", f + " must be an hour 0..23"));
                }
            }
            if (window.hasNonNull("time_zone")) {
                try {
                    ZoneId.of(window.get("time_zone").asString());
                } catch (DateTimeException e) {
                    errors.add(new FieldError("send_window.time_zone", "invalid", "Unknown time zone"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_campaign", "Campaign is invalid", errors);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode submit(UUID id) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CAMPAIGN.apply("submit", text(c, "status"));
        if (rows.count("select count(*) from campaign_variant where campaign_id = ?", id) == 0) {
            throw ApiException.unprocessable("no_variants", "Add at least one variant before submitting the campaign");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("submitted_by", Json.truncate(TenantContext.principal(), 64));
        v.put("submitted_at", rows.now().toString());
        v.putNull("status_reason");
        ObjectNode row = publish("engagement.campaign.submitted", rows.update(T, id, v));
        // Central maker-checker: the checker decides in the workflow inbox; CampaignApprovalHandler applies it.
        // submitted_at identifies this submission (the definition is frozen while PENDING_APPROVAL).
        String submittedAt = text(row, "submitted_at");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("campaign_id", id.toString());
        payload.put("code", text(row, "code"));
        payload.put("name", text(row, "name"));
        payload.put("segment_ids", Json.strings(row.get("segment_ids")));
        payload.put("exclusion_segment_ids", Json.strings(row.get("exclusion_segment_ids")));
        payload.put("channels", Json.strings(row.get("channels")));
        payload.put("start_at", text(row, "start_at"));
        payload.put("end_at", text(row, "end_at"));
        payload.put("budget_amount", text(row, "budget_amount"));
        payload.put("currency", text(row, "currency"));
        payload.put("submitted_by", text(row, "submitted_by"));
        payload.put("submitted_at", submittedAt);
        payload.put("row_version", row.path("row_version").asInt());
        ApprovalSpec spec = ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Campaign " + text(row, "code") + " - " + text(row, "name"), payload);
        BigDecimal budget = Json.decimal(row, "budget_amount");
        if (budget != null) {
            spec = spec.withAmount(budget, text(row, "currency"));
        }
        approvals.request(spec, "submit:" + id + ":" + submittedAt);
        return row;
    }

    /** True while the campaign is PENDING_APPROVAL for the submission identified by {@code submittedAt}. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String submittedAt) {
        return rows.find(T, id)
                .filter(c -> "PENDING_APPROVAL".equals(text(c, "status")))
                .filter(c -> submittedAt == null || submittedAt.equals(text(c, "submitted_at")))
                .isPresent();
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CAMPAIGN.apply("approve", text(c, "status"));
        String checker = TenantContext.principal();
        if (checker.equals(text(c, "submitted_by")) || checker.equals(text(c, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "A campaign must be approved by a different user than its maker");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("approved_by", Json.truncate(checker, 64));
        v.put("approved_at", rows.now().toString());
        if (comment != null) {
            v.put("approval_comment", Json.truncate(comment, 500));
        }
        ObjectNode row = publish("engagement.campaign.approved", rows.update(T, id, v));
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return row;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CAMPAIGN.apply("reject", text(c, "status"));
        if (TenantContext.principal().equals(text(c, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker cannot reject their own submission");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("status_reason", Json.truncate(reason, 255));
        v.putNull("submitted_by");
        v.putNull("submitted_at");
        ObjectNode row = publish("engagement.campaign.rejected", rows.update(T, id, v));
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return row;
    }

    @Transactional
    public ObjectNode schedule(UUID id, OffsetDateTime startAt, OffsetDateTime endAt) {
        ObjectNode c = rows.lock(T, id);
        Lifecycles.CAMPAIGN.apply("schedule", text(c, "status"));
        OffsetDateTime end = endAt != null ? endAt : Json.ts(c, "end_at");
        if (end != null && !end.isAfter(startAt)) {
            throw new ApiException(422, "invalid_campaign", "Campaign is invalid",
                    List.of(new FieldError("end_at", "before_start", "end_at must be after start_at")));
        }
        ObjectNode v = rows.object();
        v.put("start_at", startAt.toString());
        if (endAt != null) {
            v.put("end_at", endAt.toString());
        }
        return publish("engagement.campaign.scheduled", rows.update(T, id, v));
    }

    /** SCHEDULED -> RUNNING and a first dispatch (which may pause the campaign when the budget runs out). */
    @Transactional
    public ObjectNode launch(UUID id) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CAMPAIGN.apply("launch", text(c, "status"));
        if (text(c, "audience_built_at") == null
                || rows.count("select count(*) from campaign_audience_member where campaign_id = ?", id) == 0) {
            throw ApiException.unprocessable("audience_not_built", "Build the campaign audience before launching");
        }
        if (rows.count("select count(*) from campaign_variant where campaign_id = ?", id) == 0) {
            throw ApiException.unprocessable("no_variants", "The campaign has no variants");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("launched_at", rows.now().toString());
        v.putNull("status_reason");
        publish("engagement.campaign.started", rows.update(T, id, v));
        audience.dispatchLocked(id);
        return rows.get(T, id);
    }

    @Transactional
    public ObjectNode pause(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CAMPAIGN.apply("pause", text(c, "status")));
        v.put("status_reason", Json.truncate(reason, 255));
        return publish("engagement.campaign.paused", rows.update(T, id, v));
    }

    @Transactional
    public ObjectNode resume(UUID id) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CAMPAIGN.apply("resume", text(c, "status"));
        if ("budget_exhausted".equals(text(c, "status_reason")) && !audience.budgetAllowsOneMore(c)) {
            throw ApiException.unprocessable("budget_exhausted", "The campaign budget is exhausted; raise it before resuming");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.putNull("status_reason");
        publish("engagement.campaign.resumed", rows.update(T, id, v));
        audience.dispatchLocked(id);
        return rows.get(T, id);
    }

    @Transactional
    public ObjectNode complete(UUID id) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CAMPAIGN.apply("complete", text(c, "status")));
        v.put("completed_at", rows.now().toString());
        return publish("engagement.campaign.completed", rows.update(T, id, v));
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        boolean pending = "PENDING_APPROVAL".equals(text(c, "status"));
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CAMPAIGN.apply("cancel", text(c, "status")));
        v.put("status_reason", Json.truncate(reason, 255));
        v.put("completed_at", rows.now().toString());
        ObjectNode row = publish("engagement.campaign.cancelled", rows.update(T, id, v));
        if (pending) {
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "campaign cancelled");
        }
        return row;
    }

    private ObjectNode publish(String type, ObjectNode row) {
        events.publish(type, T, row, Campaign.class);
        return row;
    }
}
