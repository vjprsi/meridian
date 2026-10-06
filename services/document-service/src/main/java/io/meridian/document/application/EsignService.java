package io.meridian.document.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.domain.Lifecycles;
import io.meridian.document.infrastructure.DocumentStore;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * E-signature envelopes: DRAFT -> SENT -> (VIEWED) -> PARTIALLY_SIGNED -> SIGNED, or DECLINED / EXPIRED /
 * CANCELLED. Provider status arrives through an HMAC-signed callback.
 */
@Service
public class EsignService {
    public static final Set<String> PROVIDERS = Set.of("DOCUSIGN", "ADOBE_SIGN", "NATIVE_OTP");
    /** Header carrying {@code sha256=<hex HMAC-SHA256 of the raw request body>} on provider callbacks. */
    public static final String SIGNATURE_HEADER = "X-Esign-Signature";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
    private static final Set<String> OPEN = Set.of("SENT", "VIEWED", "PARTIALLY_SIGNED");

    private final CrudEngine crud;
    private final DocumentStore store;
    private final DocumentService documents;
    private final EsignProvider provider;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String callbackSecret;
    private final Duration defaultValidity;

    public EsignService(CrudEngine crud, DocumentStore store, DocumentService documents, EsignProvider provider,
                        OutboxWriter outbox, JdbcClient jdbc, JsonMapper json,
                        @Value("${meridian.esign.callback-secret:change-me-esign-callback-secret}") String callbackSecret,
                        @Value("${meridian.esign.default-validity:P30D}") Duration defaultValidity) {
        this.crud = crud;
        this.store = store;
        this.documents = documents;
        this.provider = provider;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
        this.callbackSecret = callbackSecret;
        this.defaultValidity = defaultValidity;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("esign_request", body);
        String prov = values.get("provider").asString().toUpperCase(Locale.ROOT);
        if (!PROVIDERS.contains(prov)) {
            throw ApiException.unprocessable("unsupported_provider", "provider must be one of " + PROVIDERS);
        }
        values.put("provider", prov);
        UUID documentId = UUID.fromString(values.get("document_id").asString());
        String status = jdbc.sql("select status from document where id = ? for share").param(documentId)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.unprocessable("document_not_found", "Document " + documentId + " does not exist"));
        if (!"AVAILABLE".equals(status)) {
            throw ApiException.unprocessable("document_not_available", "Document " + documentId + " is " + status + "; it must be AVAILABLE");
        }
        if (values.hasNonNull("expires_at")
                && !OffsetDateTime.parse(values.get("expires_at").asString()).toInstant().isAfter(Instant.now())) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
        }
        values.set("signers", normaliseSigners(values.get("signers")));
        for (String ro : List.of("provider_envelope_id", "signed_document_id", "sent_at", "completed_at")) {
            values.remove(ro);
        }
        values.put("id", Ids.newId().toString());
        values.put("status", "DRAFT");
        return crud.insert("esign_request", values);
    }

    @Transactional
    public String send(UUID id) {
        JsonNode e = store.lock("esign_request", id);
        String from = e.get("status").asString();
        require(from, "SENT");
        UUID documentId = UUID.fromString(e.get("document_id").asString());
        String docStatus = jdbc.sql("select status from document where id = ?").param(documentId).query(String.class).single();
        if (!"AVAILABLE".equals(docStatus)) {
            throw ApiException.unprocessable("document_not_available", "Document " + documentId + " is " + docStatus);
        }
        String envelope = provider.send(e.get("provider").asString(), id,
                documents.internalDownloadUrl(documentId, Duration.ofDays(7)), signers(e));
        Instant expires = e.hasNonNull("expires_at") ? null : Instant.now().plus(defaultValidity);
        jdbc.sql("""
                update esign_request set status = 'SENT', provider_envelope_id = ?, sent_at = now(),
                       expires_at = coalesce(expires_at, ?::timestamptz), updated_by = ?
                where id = ?""")
                .params(envelope, expires == null ? null : expires.toString(), TenantContext.principal(), id).update();
        return store.row("esign_request", id);
    }

    @Transactional
    public String remind(UUID id) {
        JsonNode e = store.lock("esign_request", id);
        String status = e.get("status").asString();
        if (!OPEN.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Only sent envelopes awaiting signatures can be reminded (status " + status + ")");
        }
        List<Map<String, Object>> pending = signers(e).stream().filter(s -> !"SIGNED".equals(s.get("status"))).toList();
        provider.remind(e.get("provider").asString(), e.get("provider_envelope_id").asString(), pending);
        ObjectNode wrapper = (ObjectNode) e.get("signers").deepCopy();
        wrapper.put("reminder_count", wrapper.path("reminder_count").asInt(0) + 1);
        wrapper.put("last_reminded_at", Instant.now().toString());
        jdbc.sql("update esign_request set signers = ?::jsonb, updated_by = ? where id = ?")
                .params(json.writeValueAsString(wrapper), TenantContext.principal(), id).update();
        return store.row("esign_request", id);
    }

    @Transactional
    public String cancel(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A cancellation reason is required");
        }
        JsonNode e = store.lock("esign_request", id);
        String from = e.get("status").asString();
        require(from, "CANCELLED");
        if (e.hasNonNull("provider_envelope_id")) {
            provider.cancel(e.get("provider").asString(), e.get("provider_envelope_id").asString(), reason);
        }
        ObjectNode wrapper = (ObjectNode) e.get("signers").deepCopy();
        wrapper.put("cancel_reason", reason);
        wrapper.put("cancelled_by", TenantContext.principal());
        jdbc.sql("""
                update esign_request set status = 'CANCELLED', completed_at = now(), signers = ?::jsonb, updated_by = ?
                where id = ?""")
                .params(json.writeValueAsString(wrapper), TenantContext.principal(), id).update();
        return store.row("esign_request", id);
    }

    /**
     * Provider callback. The raw body must carry a valid {@value #SIGNATURE_HEADER}. Payload fields:
     * {@code envelope_id}, {@code event} (VIEWED, SIGNED, PARTIALLY_SIGNED, DECLINED, EXPIRED), optional
     * {@code signer_email} (per-signer signature) and {@code signed_document_id}.
     */
    @Transactional
    public Map<String, Object> callback(String providerName, byte[] rawBody, String signature, JsonNode payload) {
        verifySignature(rawBody, signature);
        String prov = providerName.toUpperCase(Locale.ROOT);
        if (payload == null || !payload.hasNonNull("envelope_id") || !payload.hasNonNull("event")) {
            throw ApiException.unprocessable("invalid_callback", "payload.envelope_id and payload.event are required");
        }
        String envelope = payload.get("envelope_id").asString();
        UUID id = jdbc.sql("select id from esign_request where provider_envelope_id = ?").param(envelope)
                .query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("esign envelope", envelope));
        JsonNode e = store.lock("esign_request", id);
        if (!e.get("provider").asString().equals(prov)) {
            throw ApiException.unprocessable("provider_mismatch", "Envelope " + envelope + " belongs to " + e.get("provider").asString());
        }
        String from = e.get("status").asString();
        String event = payload.get("event").asString().toUpperCase(Locale.ROOT);
        String outcome;
        switch (event) {
            case "VIEWED" -> {
                if ("SENT".equals(from)) {
                    setStatus(id, "VIEWED", null, false);
                    outcome = "VIEWED";
                } else if (Lifecycles.isTerminalEsign(from)) {
                    throw ApiException.conflict("invalid_transition", "Envelope is already " + from);
                } else {
                    outcome = "IGNORED";
                }
            }
            case "SIGNED", "PARTIALLY_SIGNED" -> {
                ObjectNode wrapper = (ObjectNode) e.get("signers").deepCopy();
                ArrayNode list = (ArrayNode) wrapper.get("signers");
                String signer = payload.hasNonNull("signer_email") ? payload.get("signer_email").asString() : null;
                boolean matched = false;
                for (JsonNode s : list) {
                    if (signer == null || signer.equalsIgnoreCase(s.path("email").asString(""))) {
                        ((ObjectNode) s).put("status", "SIGNED");
                        ((ObjectNode) s).put("signed_at", Instant.now().toString());
                        matched = true;
                    }
                }
                if (!matched) {
                    throw ApiException.unprocessable("unknown_signer", "Signer " + signer + " is not on envelope " + envelope);
                }
                boolean all = true;
                for (JsonNode s : list) {
                    all &= "SIGNED".equals(s.path("status").asString());
                }
                String to = all ? "SIGNED" : "PARTIALLY_SIGNED";
                require(from, to);
                UUID signedDoc = null;
                if (all && payload.hasNonNull("signed_document_id")) {
                    signedDoc = UUID.fromString(payload.get("signed_document_id").asString());
                    UUID check = signedDoc;
                    jdbc.sql("select id from document where id = ?").param(check).query(UUID.class).optional()
                            .orElseThrow(() -> ApiException.unprocessable("document_not_found", "Signed document " + check + " does not exist"));
                }
                jdbc.sql("""
                        update esign_request set status = ?, signers = ?::jsonb, signed_document_id = coalesce(?, signed_document_id),
                               completed_at = case when ? then now() else completed_at end, updated_by = ?
                        where id = ?""")
                        .params(to, json.writeValueAsString(wrapper), signedDoc, all, TenantContext.principal(), id).update();
                if (all) {
                    outbox.publish("platform.esign_request.completed", "esign_request", id,
                            json.readTree(store.row("esign_request", id)));
                }
                outcome = to;
            }
            case "DECLINED" -> {
                require(from, "DECLINED");
                setStatus(id, "DECLINED", payload.hasNonNull("reason") ? payload.get("reason").asString() : null, true);
                outbox.publish("platform.esign_request.declined", "esign_request", id,
                        json.readTree(store.row("esign_request", id)));
                outcome = "DECLINED";
            }
            case "EXPIRED" -> {
                require(from, "EXPIRED");
                setStatus(id, "EXPIRED", null, true);
                outcome = "EXPIRED";
            }
            default -> throw ApiException.unprocessable("unsupported_event", "Unsupported e-sign event " + event);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("esign_request_id", id.toString());
        r.put("event", event);
        r.put("outcome", outcome);
        r.put("status", jdbc.sql("select status from esign_request where id = ?").param(id).query(String.class).single());
        return r;
    }

    /** Expires open envelopes past their expiry time. */
    @Transactional
    public int expireOverdue() {
        return jdbc.sql("""
                update esign_request set status = 'EXPIRED', completed_at = now(), updated_by = 'system'
                where status in ('SENT', 'VIEWED', 'PARTIALLY_SIGNED') and expires_at < now()""").update();
    }

    /** Computes the callback signature header value for a raw body (used by providers' sandbox and tests). */
    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void verifySignature(byte[] rawBody, String header) {
        if (header == null || rawBody == null
                || !MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.UTF_8),
                        header.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "invalid_signature", "Missing or invalid " + SIGNATURE_HEADER + " header");
        }
    }

    private void setStatus(UUID id, String status, String reason, boolean complete) {
        jdbc.sql("""
                update esign_request set status = ?, completed_at = case when ? then now() else completed_at end,
                       signers = case when ?::text is null then signers else signers || jsonb_build_object('decline_reason', ?::text) end,
                       updated_by = ?
                where id = ?""")
                .params(status, complete, reason, reason, TenantContext.principal(), id).update();
    }

    private static void require(String from, String to) {
        try {
            Lifecycles.require(Lifecycles.ESIGN, "E-sign request", from, to);
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict(e.code(), e.getMessage());
        }
    }

    private List<Map<String, Object>> signers(JsonNode e) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : e.path("signers").path("signers")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.convertValue(s, Map.class);
            out.add(m);
        }
        return out;
    }

    /** Validates {"signers":[{name, email|phone, order?, auth_method?}]} and adds per-signer status. */
    private JsonNode normaliseSigners(JsonNode wrapper) {
        JsonNode list = wrapper == null ? null : wrapper.get("signers");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw ApiException.unprocessable("signers_required",
                    "signers must be an object {\"signers\":[{\"name\":..,\"email\":..}]} with at least one signer");
        }
        if (list.size() > 20) {
            throw ApiException.unprocessable("too_many_signers", "At most 20 signers per envelope");
        }
        ArrayNode out = json.createArrayNode();
        Set<String> seen = new java.util.HashSet<>();
        int i = 0;
        for (JsonNode s : list) {
            i++;
            if (!s.isObject() || !s.hasNonNull("name") || s.get("name").asString().isBlank()) {
                throw ApiException.unprocessable("invalid_signer", "Signer " + i + " needs a name");
            }
            String email = s.hasNonNull("email") ? s.get("email").asString().trim() : null;
            String phone = s.hasNonNull("phone") ? s.get("phone").asString().trim() : null;
            if (email == null && phone == null) {
                throw ApiException.unprocessable("invalid_signer", "Signer " + i + " needs an email or phone");
            }
            if (email != null && !EMAIL.matcher(email).matches()) {
                throw ApiException.unprocessable("invalid_signer", "Signer " + i + " has an invalid email");
            }
            if (phone != null && !PHONE.matcher(phone).matches()) {
                throw ApiException.unprocessable("invalid_signer", "Signer " + i + " phone must be E.164");
            }
            String identity = (email != null ? email : phone).toLowerCase(Locale.ROOT);
            if (!seen.add(identity)) {
                throw ApiException.unprocessable("duplicate_signer", "Signer " + identity + " appears twice");
            }
            ObjectNode n = (ObjectNode) s.deepCopy();
            if (email != null) {
                n.put("email", email);
            }
            if (!n.hasNonNull("order")) {
                n.put("order", i);
            }
            if (!n.hasNonNull("auth_method")) {
                n.put("auth_method", "EMAIL");
            }
            n.put("status", "PENDING");
            out.add(n);
        }
        ObjectNode w = json.createObjectNode();
        w.set("signers", out);
        return w;
    }
}
