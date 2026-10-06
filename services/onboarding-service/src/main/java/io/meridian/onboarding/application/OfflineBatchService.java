package io.meridian.onboarding.application;

import io.meridian.common.error.ApiException;
import io.meridian.onboarding.api.generated.model.OnboardingOfflineBatch;
import io.meridian.onboarding.domain.JsonValues;
import io.meridian.onboarding.domain.Lifecycles;
import io.meridian.onboarding.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Syncs applications captured offline by agent devices. Each record maps to a deterministic application id
 * (batch id + client_ref), so processing and reprocessing are idempotent per record.
 */
@Service
public class OfflineBatchService {
    static final String T = "onboarding_offline_batch";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final PayloadStore payloads;
    private final ApplicationService applications;

    public OfflineBatchService(Rows rows, CrudEngine crud, Events events, PayloadStore payloads,
                               ApplicationService applications) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.payloads = payloads;
        this.applications = applications;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        v.remove(List.of("status", "result", "processed_at"));
        OffsetDateTime from = Events.ts(v, "captured_from");
        OffsetDateTime to = Events.ts(v, "captured_to");
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.unprocessable("invalid_capture_window", "captured_from must not be after captured_to");
        }
        if (v.path("application_count").asInt(0) < 0) {
            throw ApiException.unprocessable("invalid_application_count", "application_count must not be negative");
        }
        v.put("status", "RECEIVED");
        return process(rows.insert(T, v));
    }

    @Transactional
    public ObjectNode reprocess(UUID id) {
        ObjectNode batch = rows.lock(T, id);
        Lifecycles.batch(batch.get("status").asString(), "reprocess");
        return process(batch);
    }

    private ObjectNode process(ObjectNode batch) {
        UUID batchId = Events.uuid(batch, "id");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        String status;
        Optional<JsonNode> payload = Optional.empty();
        if (batch.path("payload_signature").asString("").isBlank()) {
            result.put("error", "invalid_signature");
        } else {
            payload = payloads.load(batch.get("payload_uri").asString());
            if (payload.isEmpty()) {
                result.put("error", "payload_unavailable");
            } else if (!payload.get().path("applications").isArray()
                    || payload.get().get("applications").size() != batch.get("application_count").asInt()) {
                result.put("error", "application_count_mismatch");
                payload = Optional.empty();
            }
        }
        int applied = 0;
        int conflicts = 0;
        int rejected = 0;
        ArrayNode records = result.putArray("records");
        if (payload.isPresent()) {
            OffsetDateTime from = Events.ts(batch, "captured_from");
            OffsetDateTime to = Events.ts(batch, "captured_to");
            for (JsonNode rec : payload.get().get("applications")) {
                ObjectNode outcome = apply(batch, batchId, rec, from, to);
                records.add(outcome);
                switch (outcome.get("outcome").asString()) {
                    case "APPLIED", "ALREADY_APPLIED" -> applied++;
                    case "CONFLICT" -> conflicts++;
                    default -> rejected++;
                }
            }
            status = conflicts > 0 ? "CONFLICT" : applied == 0 && rejected > 0 ? "REJECTED" : "APPLIED";
        } else {
            status = "REJECTED";
        }
        result.put("applied", applied);
        result.put("conflicts", conflicts);
        result.put("rejected", rejected);
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("status", status);
        v.set("result", result);
        v.put("processed_at", Events.now());
        ObjectNode updated = rows.update(T, batchId, v);
        events.publish("party.onboarding_offline_batch.processed", T, updated, OnboardingOfflineBatch.class);
        return updated;
    }

    private ObjectNode apply(ObjectNode batch, UUID batchId, JsonNode rec, OffsetDateTime from, OffsetDateTime to) {
        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        String ref = rec.path("client_ref").asString("");
        outcome.put("client_ref", ref);
        if (ref.isBlank() || ref.length() > 64) {
            return reject(outcome, "client_ref_required");
        }
        UUID appId = UUID.nameUUIDFromBytes((batchId + ":" + ref).getBytes(StandardCharsets.UTF_8));
        outcome.put("application_id", appId.toString());
        if (rows.find(ApplicationService.T_APP, appId).isPresent()) {
            outcome.put("outcome", "ALREADY_APPLIED");
            return outcome;
        }
        try {
            OffsetDateTime captured = OffsetDateTime.parse(rec.path("captured_at").asString(""));
            if ((from != null && captured.isBefore(from)) || (to != null && captured.isAfter(to))) {
                return reject(outcome, "captured_outside_window");
            }
        } catch (DateTimeParseException e) {
            return reject(outcome, "invalid_captured_at");
        }
        UUID journeyId;
        try {
            journeyId = UUID.fromString(rec.path("onboarding_journey_id").asString(""));
        } catch (IllegalArgumentException e) {
            return reject(outcome, "journey_not_found");
        }
        String phone = rec.path("phone").asString("");
        if (!phone.isBlank()) {
            Integer open = rows.jdbc().sql("""
                    select count(*) from onboarding_application
                    where phone = ? and status in ('STARTED', 'IN_PROGRESS') and id <> ?""")
                    .params(phone, appId).query(Integer.class).single();
            if (open > 0) {
                outcome.put("outcome", "CONFLICT");
                outcome.put("reason", "open_application_for_phone");
                return outcome;
            }
        }
        ObjectNode v = JsonNodeFactory.instance.objectNode();
        v.put("id", appId.toString());
        v.put("application_number", "OFL-" + appId.toString().replace("-", "").substring(0, 20).toUpperCase());
        v.put("onboarding_journey_id", journeyId.toString());
        v.put("channel", rec.path("channel").asString("AGENT"));
        v.put("agent_id", batch.get("agent_id").asString());
        v.put("device_fingerprint", batch.get("device_fingerprint").asString());
        if (!phone.isBlank()) {
            v.put("phone", phone);
        }
        if (!JsonValues.blank(rec.get("email"))) {
            v.put("email", rec.get("email").asString());
        }
        if (!JsonValues.blank(rec.get("legal_entity_id"))) {
            v.put("legal_entity_id", rec.get("legal_entity_id").asString());
        }
        if (rec.path("collected_data").isObject()) {
            v.set("collected_data", rec.get("collected_data"));
        }
        try {
            applications.createFrom(v);
        } catch (ApiException e) {
            return reject(outcome, e.code());
        }
        outcome.put("outcome", "APPLIED");
        return outcome;
    }

    private static ObjectNode reject(ObjectNode outcome, String reason) {
        outcome.put("outcome", "REJECTED");
        outcome.put("reason", reason);
        return outcome;
    }
}
