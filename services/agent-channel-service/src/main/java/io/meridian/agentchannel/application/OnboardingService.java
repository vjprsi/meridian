package io.meridian.agentchannel.application;

import static io.meridian.agentchannel.application.Ownership.me;
import static io.meridian.agentchannel.application.Ownership.mine;

import io.meridian.agentchannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Assisted onboarding by the agent (onboarding-service, channel AGENT) and offline batches signed with the device key.
 */
@Service
public class OnboardingService {
    private static final String ONBOARDING = "onboarding-service";
    private static final String DOCS = "document-service";

    private final Downstream down;
    private final Ownership own;
    private final ActionAudit audit;
    private final DeviceKeys keys;

    public OnboardingService(Downstream down, Ownership own, ActionAudit audit, DeviceKeys keys) {
        this.down = down;
        this.own = own;
        this.audit = audit;
        this.keys = keys;
    }

    private static boolean agentJourney(JsonNode j) {
        if (!"PUBLISHED".equals(j.path("status").asString(null))) {
            return false;
        }
        for (JsonNode c : j.path("channels")) {
            if ("AGENT".equals(c.asString(null))) {
                return true;
            }
        }
        return false;
    }

    public JsonNode journeys(Integer limit, String cursor) {
        return down.page(down.get(ONBOARDING, "/v1/onboarding/journeys", Query.of("status", "PUBLISHED")
                .paging(limit, cursor)), OnboardingService::agentJourney);
    }

    public JsonNode applications(String status, Integer limit, String cursor) {
        return down.page(down.get(ONBOARDING, "/v1/onboarding/applications", Query.of("agent_id", me())
                        .with("channel", "AGENT").with("status", status).paging(limit, cursor)),
                r -> mine(r, "agent_id") && "AGENT".equals(r.path("channel").asString(null)));
    }

    public JsonNode application(UUID id) {
        return own.application(id, "agentMeApplicationsGet");
    }

    /** Starts an application on a published agent-channel journey; channel AGENT and the agent set server-side. */
    public JsonNode start(JsonNode req, String key) {
        String action = "agentMeApplicationsCreate";
        own.activeAgent();
        UUID journeyId = Req.uuid(req, "onboarding_journey_id", true);
        JsonNode journey;
        try {
            journey = down.get(ONBOARDING, "/v1/onboarding/journeys/" + journeyId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("journey_not_available", "The journey is not available to agents");
            }
            throw e;
        }
        if (!agentJourney(journey)) {
            throw ApiException.unprocessable("journey_not_available", "The journey is not available to agents");
        }
        ObjectNode body = down.object()
                .put("onboarding_journey_id", journeyId.toString())
                .put("channel", "AGENT")
                .put("agent_id", me().toString())
                .put("phone", Req.required(req, "phone"));
        String email = Req.text(req, "email");
        if (email != null && !email.isBlank()) {
            body.put("email", email);
        }
        Req.copy(body, req, "collected_data");
        Req.copy(body, req, "product_ids");
        UUID device = Req.uuid(req, "agent_device_id", false);
        if (device != null) {
            own.device(device, action);
            body.put("device_fingerprint", device.toString());
        }
        JsonNode r = down.post(ONBOARDING, "/v1/onboarding/applications", body, key, "application");
        audit.succeeded(action, "APPLICATION", null, r.path("id").asString(null));
        return r;
    }

    public JsonNode nextStep(UUID id) {
        own.application(id, "agentMeApplicationsNextStep");
        return down.get(ONBOARDING, "/v1/onboarding/applications/" + id + "/next-step");
    }

    public JsonNode steps(UUID id, Integer limit, String cursor) {
        own.application(id, "agentMeApplicationsSteps");
        return down.get(ONBOARDING, "/v1/onboarding/applications/" + id + "/steps", Query.none().paging(limit, cursor));
    }

    public JsonNode submitStep(UUID id, String stepCode, JsonNode req, String key) {
        String action = "agentMeApplicationsAddSteps";
        own.application(id, action);
        if (stepCode == null || !stepCode.matches("[A-Za-z0-9_\\-]{1,64}")) {
            throw ApiException.badRequest("invalid_step_code", "step_code is invalid");
        }
        JsonNode input = req.get("input");
        if (input == null || !input.isObject()) {
            throw ApiException.unprocessable("missing_field", "input (object) is required");
        }
        ObjectNode body = down.object();
        body.set("input", input);
        JsonNode r = down.post(ONBOARDING, "/v1/onboarding/applications/" + id + "/steps/" + stepCode, body, key,
                "step:" + id + ":" + stepCode);
        audit.succeeded(action, "APPLICATION", id, null);
        return r;
    }

    public JsonNode submit(UUID id, String key) {
        String action = "agentMeApplicationsSubmit";
        own.application(id, action);
        JsonNode r = down.post(ONBOARDING, "/v1/onboarding/applications/" + id + "/submit", null, key, "submit:" + id);
        audit.succeeded(action, "APPLICATION", id, null);
        return r;
    }

    // ------------------------------------------------------------------ documents of an application

    public JsonNode registerDocument(UUID applicationId, JsonNode req, String key) {
        String action = "agentMeApplicationsDocuments";
        own.application(applicationId, action);
        ObjectNode body = down.object()
                .put("owner_type", "ONBOARDING_APPLICATION")
                .put("owner_id", applicationId.toString())
                .put("document_type", Req.required(req, "document_type"))
                .put("file_name", Req.required(req, "file_name"))
                .put("content_type", Req.required(req, "content_type"))
                .put("classification", "CONFIDENTIAL")
                .put("uploaded_via", "AGENT_APP");
        String number = Req.text(req, "document_number");
        if (number != null && !number.isBlank()) {
            body.put("document_number", number);
        }
        JsonNode r = down.post(DOCS, "/v1/documents", body, key, "document:" + applicationId);
        audit.succeeded(action, "DOCUMENT", applicationId, r.path("id").asString(null));
        return r;
    }

    public JsonNode uploadUrl(UUID applicationId, UUID documentId, String key) {
        String action = "agentMeApplicationsDocumentsUploadUrl";
        own.application(applicationId, action);
        own.applicationDocument(applicationId, documentId, action);
        return down.post(DOCS, "/v1/documents/" + documentId + "/upload-url", null, key, "upload-url:" + documentId);
    }

    public JsonNode completeUpload(UUID applicationId, UUID documentId, JsonNode req, String key) {
        String action = "agentMeApplicationsDocumentsCompleteUpload";
        own.application(applicationId, action);
        own.applicationDocument(applicationId, documentId, action);
        long size = req.path("size_bytes").asLong(-1);
        String checksum = Req.required(req, "checksum");
        if (size < 0 || !checksum.matches("[0-9a-fA-F]{64}")) {
            throw ApiException.unprocessable("invalid_upload", "size_bytes and a SHA-256 checksum (hex) are required");
        }
        JsonNode r = down.post(DOCS, "/v1/documents/" + documentId + "/complete-upload", down.object()
                .put("size_bytes", size).put("checksum", checksum.toLowerCase()), key, "complete:" + documentId);
        audit.succeeded(action, "DOCUMENT", documentId, null);
        return r;
    }

    // ------------------------------------------------------------------ offline batches

    public JsonNode batches(String clientKey, Integer limit, String cursor) {
        return down.page(down.get(ONBOARDING, "/v1/onboarding/offline-batches", Query.of("agent_id", me())
                        .with("idempotency_key", clientKey == null || clientKey.isBlank() ? null
                                : Downstream.key(clientKey, "offline-batch"))
                        .paging(limit, cursor)),
                r -> mine(r, "agent_id"));
    }

    public JsonNode batch(UUID id) {
        return own.offlineBatch(id, "agentMeOfflineBatchesGet");
    }

    /**
     * Verifies the device signature over {@link DeviceKeys#batchPayload} with the device's ACTIVE key and forwards the
     * batch with the agent and device set server-side; payload_signature carries the verified signature as
     * {@code <algorithm>:<device id>:<signature>}. The device must be one of the agent's (404), ACTIVE (422
     * device_not_active) and have an ACTIVE key (422 device_not_registered / device_key_revoked); a signature that does
     * not verify is refused with 422 device_signature_invalid. Every refusal is audited and published.
     */
    public JsonNode submitBatch(JsonNode req, String key) {
        String action = "agentMeOfflineBatchesCreate";
        own.activeAgent();
        UUID deviceId = Req.uuid(req, "agent_device_id", true);
        JsonNode device = own.device(deviceId, action);
        Optional<? extends JsonNode> keyRow = keys.activeKey(deviceId);
        if (keyRow.isEmpty()) {
            String code = keys.anyKey(deviceId) ? "device_key_revoked" : "device_not_registered";
            audit.signatureInvalid(action, "OFFLINE_BATCH", null, code, deviceId);
            throw ApiException.unprocessable(code, "device_key_revoked".equals(code)
                    ? "The device's signing key was revoked" : "The device has no registered signing key");
        }
        if (!"ACTIVE".equals(device.path("status").asString(""))) {
            audit.signatureInvalid(action, "OFFLINE_BATCH", null, "device_not_active", deviceId);
            throw ApiException.unprocessable("device_not_active", "The device is " + device.path("status").asString());
        }
        OffsetDateTime from = OffsetDateTime.parse(Req.required(req, "captured_from"));
        OffsetDateTime to = OffsetDateTime.parse(Req.required(req, "captured_to"));
        int count = req.path("application_count").asInt(-1);
        String payloadUri = Req.required(req, "payload_uri");
        String signature = Req.required(req, "signature");
        String payload = DeviceKeys.batchPayload(TenantContext.require(), me(), deviceId, key, from.toInstant(),
                to.toInstant(), count, payloadUri);
        if (!DeviceKeys.verify(keyRow.get(), payload, signature)) {
            audit.signatureInvalid(action, "OFFLINE_BATCH", null, "device_signature_invalid", deviceId);
            throw ApiException.unprocessable("device_signature_invalid",
                    "The batch signature does not verify with the device's registered key");
        }
        keys.used(keyRow.get());
        ObjectNode body = down.object()
                .put("agent_id", me().toString())
                .put("device_fingerprint", deviceId.toString())
                .put("captured_from", from.toString())
                .put("captured_to", to.toString())
                .put("application_count", count)
                .put("payload_uri", payloadUri)
                .put("payload_signature", keyRow.get().path("key_algorithm").asString() + ":" + deviceId + ":"
                        + signature.trim());
        JsonNode r = down.post(ONBOARDING, "/v1/onboarding/offline-batches", body, key, "offline-batch");
        ActionAudit.noteAuth("DEVICE_PROOF", deviceId);
        audit.succeeded(action, "OFFLINE_BATCH", null, r.path("id").asString(null));
        return r;
    }
}
