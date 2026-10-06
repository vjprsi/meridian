package io.meridian.inbox.application;

import static io.meridian.inbox.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.api.generated.model.Announcement;
import io.meridian.inbox.domain.AnnouncementRules;
import io.meridian.inbox.infrastructure.ExternalServices;
import io.meridian.inbox.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-app announcements: DRAFT content is edited freely; publishing schedules it (SCHEDULED until starts_at, then
 * LIVE, EXPIRED after ends_at, all by the database clock). Targeting by app and customer segment; customers can
 * dismiss dismissible announcements.
 */
@Service
public class AnnouncementService {
    static final String T = "announcement";
    private static final int MAX_ACTIVE = 100;

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final ExternalServices external;

    public AnnouncementService(Rows rows, CrudEngine crud, Events events, ExternalServices external) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.external = external;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v);
        v.put("status", "DRAFT");
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, Object body, int expectedVersion) {
        ObjectNode current = rows.get(T, id);
        requireDraft(current, "edited");
        ObjectNode v = crud.toColumns(T, body);
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        if (v.isEmpty()) {
            return current;
        }
        return rows.update(T, id, v, expectedVersion).orElseThrow(() -> Json.versionConflict(expectedVersion));
    }

    @Transactional
    public void delete(UUID id) {
        ObjectNode current = rows.lock(T, id);
        requireDraft(current, "deleted");
        rows.execute("delete from announcement_dismissal where announcement_id = ?", id);
        rows.execute("delete from announcement where id = ?", id);
    }

    @Transactional
    public ObjectNode publish(UUID id) {
        ObjectNode a = rows.lock(T, id);
        if (!AnnouncementRules.allowed("publish", text(a, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a DRAFT announcement can be published (status "
                    + text(a, "status") + ")");
        }
        OffsetDateTime now = rows.now();
        String status = AnnouncementRules.statusOnPublish(Json.ts(a, "starts_at"), Json.ts(a, "ends_at"), now);
        if (status == null) {
            throw ApiException.unprocessable("announcement_ended", "ends_at is in the past; adjust the schedule first");
        }
        ObjectNode v = rows.object();
        v.put("status", status);
        v.put("published_at", now.toString());
        v.put("published_by", Json.truncate(TenantContext.principal(), 64));
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.announcement.published", T, row, Announcement.class);
        if ("LIVE".equals(status)) {
            events.publish("engagement.announcement.live", T, row, Announcement.class);
        }
        return row;
    }

    @Transactional
    public ObjectNode withdraw(UUID id, String reason) {
        ObjectNode a = rows.lock(T, id);
        if (!AnnouncementRules.allowed("withdraw", text(a, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a SCHEDULED or LIVE announcement can be withdrawn (status "
                    + text(a, "status") + ")");
        }
        ObjectNode v = rows.object();
        v.put("status", "WITHDRAWN");
        v.put("withdrawn_at", rows.now().toString());
        if (reason != null) {
            v.put("status_reason", Json.truncate(reason, 255));
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.announcement.withdrawn", T, row, Announcement.class);
        return row;
    }

    @Transactional
    public void dismiss(UUID id, UUID customerId) {
        ObjectNode a = rows.get(T, id);
        if (!Json.bool(a, "dismissible")) {
            throw ApiException.unprocessable("not_dismissible", "This announcement cannot be dismissed");
        }
        String status = text(a, "status");
        if (!"LIVE".equals(status) && !"SCHEDULED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only a published announcement can be dismissed");
        }
        rows.execute("""
                insert into announcement_dismissal (id, tenant_id, announcement_id, customer_id, created_by)
                values (uuidv7(), ?::uuid, ?::uuid, ?::uuid, ?) on conflict do nothing""",
                TenantContext.require().toString(), id.toString(), customerId.toString(), TenantContext.principal());
    }

    /** Moves due announcements live and ended ones to EXPIRED (database clock). */
    @Transactional
    public ObjectNode refresh() {
        OffsetDateTime now = rows.now();
        int live = 0;
        int expired = 0;
        for (ObjectNode a : rows.query("""
                select row_to_json(t)::text from announcement t where t.status in ('SCHEDULED', 'LIVE')
                order by t.id for update""")) {
            String to = AnnouncementRules.scheduledStatus(text(a, "status"), Json.ts(a, "starts_at"), Json.ts(a, "ends_at"), now);
            if (to.equals(text(a, "status"))) {
                continue;
            }
            ObjectNode v = rows.object();
            v.put("status", to);
            ObjectNode row = rows.update(T, Json.uuid(a, "id"), v);
            if ("LIVE".equals(to)) {
                live++;
                events.publish("engagement.announcement.live", T, row, Announcement.class);
            } else {
                expired++;
                events.publish("engagement.announcement.expired", T, row, Announcement.class);
            }
        }
        ObjectNode out = rows.object();
        out.put("as_of", now.toString());
        out.put("went_live", live);
        out.put("expired", expired);
        return out;
    }

    /** Announcements shown now to the customer in the app: schedule, app and segment targeting, minus dismissals. */
    public JsonNode active(UUID customerId, String app, String placement, String language, Integer limit) {
        if (customerId == null || app == null) {
            throw ApiException.badRequest("missing_parameter", "customer_id and app are required");
        }
        OffsetDateTime now = rows.now();
        List<Object> args = new ArrayList<>(List.of(customerId));
        String sql = """
                select row_to_json(t)::text from announcement t where t.status in ('SCHEDULED', 'LIVE')
                  and not exists (select 1 from announcement_dismissal d where d.announcement_id = t.id and d.customer_id = ?)""";
        if (placement != null) {
            sql += " and t.placement = ?";
            args.add(placement);
        }
        sql += " order by t.priority, t.starts_at, t.id limit " + MAX_ACTIVE;
        List<ObjectNode> candidates = rows.query(sql, args.toArray());
        Set<String> segments = null;
        boolean segmentsLoaded = false;
        int max = limit == null || limit <= 0 ? 50 : Math.min(limit, MAX_ACTIVE);
        ArrayNode data = rows.object().arrayNode();
        for (ObjectNode a : candidates) {
            List<String> targeted = Json.strings(a.get("segment_ids"));
            if (!targeted.isEmpty() && !segmentsLoaded) {
                segments = external.customerSegments(customerId);
                segmentsLoaded = true;
            }
            if (!AnnouncementRules.visible(text(a, "status"), Json.ts(a, "starts_at"), Json.ts(a, "ends_at"), now,
                    Json.strings(a.get("apps")), app, targeted, segments)) {
                continue;
            }
            localise(a, language);
            data.add(a);
            if (data.size() >= max) {
                break;
            }
        }
        ObjectNode page = rows.object();
        page.set("data", data);
        page.put("has_more", false);
        page.putNull("next_cursor");
        return page;
    }

    private static void localise(ObjectNode a, String language) {
        if (language == null) {
            return;
        }
        JsonNode tr = a.path("languages").path(language);
        if (tr.isObject()) {
            if (tr.hasNonNull("title")) {
                a.put("title", tr.get("title").asString());
            }
            if (tr.hasNonNull("body")) {
                a.put("body", tr.get("body").asString());
            }
        }
    }

    private static void requireDraft(ObjectNode a, String verb) {
        if (!"DRAFT".equals(text(a, "status"))) {
            throw ApiException.conflict("announcement_not_draft", "Only a DRAFT announcement can be " + verb
                    + " (status " + text(a, "status") + "); withdraw it and create a new one");
        }
    }

    private static void validate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        OffsetDateTime start = Json.ts(v, "starts_at");
        OffsetDateTime end = Json.ts(v, "ends_at");
        if (start != null && end != null && !end.isAfter(start)) {
            errors.add(new FieldError("ends_at", "before_start", "ends_at must be after starts_at"));
        }
        if (v.has("priority") && v.get("priority").asInt() < 0) {
            errors.add(new FieldError("priority", "must_not_be_negative", "priority must be >= 0"));
        }
        JsonNode langs = v.get("languages");
        if (langs != null && !langs.isNull()) {
            langs.propertyNames().forEach(k -> {
                if (!k.matches("[a-z]{2}(-[A-Z]{2})?") || !langs.get(k).isObject()) {
                    errors.add(new FieldError("languages", "invalid_translation",
                            "languages must map a language code (e.g. fr, pt-BR) to {title, body}"));
                }
            });
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_announcement", "Announcement is invalid", errors);
        }
    }
}
