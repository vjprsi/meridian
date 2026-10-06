package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.text;
import static io.meridian.kyc.application.KycSupport.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.model.KycCheck;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.infrastructure.Rows;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Provider checks: routing to an adapter, synchronous and asynchronous (callback) results, retries with fallback. */
@Service
public class CheckService {
    static final String TABLE = "kyc_check";
    private static final Set<String> CALLBACK_STATUSES = Set.of("PASSED", "FAILED", "INCONCLUSIVE", "ERROR", "EXPIRED");
    /** Server-owned outcome fields that a client cannot set on create. */
    private static final List<String> OUTCOME_FIELDS = List.of("status", "score", "result_summary", "reason_codes",
            "provider_reference", "completed_at", "cost", "cost_currency", "requested_at");

    private final Rows rows;
    private final CrudEngine crud;
    private final KycSupport kyc;
    private final ProfileService profiles;
    private final ProviderService providers;
    private final ServiceClient services;
    private final Environment env;

    public CheckService(Rows rows, CrudEngine crud, KycSupport kyc, ProfileService profiles, ProviderService providers,
                        ServiceClient services, Environment env) {
        this.rows = rows;
        this.crud = crud;
        this.kyc = kyc;
        this.profiles = profiles;
        this.providers = providers;
        this.services = services;
        this.env = env;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(TABLE, body);
        OUTCOME_FIELDS.forEach(v::remove);
        UUID profileId = uuid(v, "kyc_profile_id");
        profiles.existing(profileId);
        String capability = KycRules.capabilityFor(text(v, "check_type"));
        JsonNode provider = rows.find(ProviderService.TABLE, uuid(v, "kyc_provider_id")).orElseThrow(() ->
                ApiException.unprocessable("provider_not_found", "KYC provider " + text(v, "kyc_provider_id") + " does not exist"));
        ProviderService.requireActive(provider);
        if (!ProviderService.capable(provider, capability)) {
            throw ApiException.unprocessable("provider_capability_missing",
                    "Provider " + text(provider, "code") + " does not support " + capability);
        }
        if (v.hasNonNull("kyc_document_id")) {
            JsonNode doc = rows.find(DocumentService.TABLE, uuid(v, "kyc_document_id")).orElseThrow(() ->
                    ApiException.unprocessable("document_not_found", "KYC document does not exist"));
            if (!profileId.equals(uuid(doc, "kyc_profile_id"))) {
                throw ApiException.unprocessable("document_profile_mismatch", "The document belongs to another profile");
            }
        }
        v.put("status", "PENDING");
        ObjectNode check = rows.insert(TABLE, v);
        return run(check, provider);
    }

    @Transactional
    public ObjectNode retry(UUID id, boolean useFallback) {
        ObjectNode check = rows.lock(TABLE, id);
        KycRules.requireRetryable(text(check, "status"));
        String capability = KycRules.capabilityFor(text(check, "check_type"));
        JsonNode provider;
        if (useFallback) {
            provider = providers.fallback(capability, uuid(check, "kyc_provider_id"));
        } else {
            provider = providers.require(uuid(check, "kyc_provider_id"));
            ProviderService.requireActive(provider);
        }
        ObjectNode v = rows.object();
        v.put("status", "PENDING");
        v.put("kyc_provider_id", text(provider, "id"));
        v.put("requested_at", KycSupport.now());
        v.putNull("completed_at");
        v.putNull("provider_reference");
        return run(rows.update(TABLE, id, v), provider);
    }

    /** Calls the provider adapter and records the outcome; terminal outcomes publish party.kyc_check.completed. */
    private ObjectNode run(ObjectNode check, JsonNode providerRow) {
        UUID id = uuid(check, "id");
        ProviderAdapter.Provider provider = ProviderService.descriptor(providerRow);
        ProviderAdapter adapter = providers.adapter(provider);
        JsonNode doc = check.hasNonNull("kyc_document_id")
                ? rows.find(DocumentService.TABLE, uuid(check, "kyc_document_id")).orElse(null) : null;
        ProviderAdapter.CheckResult result;
        String subject = subjectName(check, doc);
        if (adapter == null) {
            result = new ProviderAdapter.CheckResult("ERROR", null, List.of("NO_ADAPTER"), null,
                    "No adapter configured for the provider", null, null);
        } else if (subject == null) {
            result = new ProviderAdapter.CheckResult("ERROR", null, List.of("SUBJECT_UNAVAILABLE"), null,
                    "Subject data unavailable", null, null);
        } else {
            result = adapter.runCheck(provider, new ProviderAdapter.CheckRequest(id, text(check, "check_type"), subject,
                    text(doc, "document_type"), text(doc, "issuing_country")));
        }
        ObjectNode request = kyc.object();
        request.put("check_type", text(check, "check_type"));
        request.put("provider_code", provider.code());
        request.put("subject", "[redacted]");
        if (doc != null) {
            request.put("document_type", text(doc, "document_type"));
        }
        ObjectNode v = rows.object();
        v.set("request_payload", request);
        apply(v, result.status(), result.score() == null ? null : result.score().toPlainString(), result.reasonCodes(),
                result.summary());
        if (result.providerReference() != null) {
            v.put("provider_reference", result.providerReference());
        }
        if (result.cost() != null) {
            v.put("cost", result.cost().toPlainString());
            v.put("cost_currency", result.costCurrency());
        }
        v.set("response_payload", kyc.tree(result));
        ObjectNode updated = rows.update(TABLE, id, v);
        if (KycRules.TERMINAL_CHECK.contains(result.status())) {
            kyc.publish("party.kyc_check.completed", TABLE, updated, KycCheck.class);
        }
        return updated;
    }

    private void apply(ObjectNode v, String status, String score, List<String> reasons, String summary) {
        v.put("status", status);
        if (score != null) {
            v.put("score", score);
        }
        v.set("reason_codes", kyc.valuesObject(reasons == null ? List.of() : reasons));
        if (summary != null) {
            v.put("result_summary", summary.length() > 500 ? summary.substring(0, 500) : summary);
        }
        if (KycRules.TERMINAL_CHECK.contains(status)) {
            v.put("completed_at", KycSupport.now());
        }
    }

    /** Name of the subject: OCR/MRZ data of the linked document, else the customer record (customer-service). */
    private String subjectName(JsonNode check, JsonNode doc) {
        JsonNode extracted = doc == null ? null : doc.get("extracted_data");
        if (extracted != null && extracted.isObject()) {
            String full = text(extracted, "full_name");
            if (full != null && !full.isBlank()) {
                return full;
            }
            String first = text(extracted, "first_name");
            String last = text(extracted, "last_name");
            if (first != null || last != null) {
                return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            }
        }
        UUID profileId = uuid(check, "kyc_profile_id");
        UUID customerId = uuid(profiles.existing(profileId), "customer_id");
        try {
            JsonNode customer = services.get("customer-service", "/v1/customers/" + customerId);
            String name = customer == null ? null : text(customer, "display_name");
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Asynchronous provider result. Already completed checks are left untouched (duplicate deliveries). */
    @Transactional
    public void callback(String providerCode, JsonNode payload) {
        JsonNode provider = providers.byCode(providerCode);
        String reference = text(payload, "provider_reference");
        String status = text(payload, "status");
        if (reference == null || status == null) {
            throw ApiException.unprocessable("invalid_callback", "payload.provider_reference and payload.status are required");
        }
        if (!CALLBACK_STATUSES.contains(status)) {
            throw ApiException.unprocessable("invalid_callback", "Unsupported result status " + status);
        }
        verifySignature(text(provider, "code"), reference, status, text(payload, "signature"));
        ObjectNode check = rows.query("""
                        select row_to_json(t)::text from kyc_check t
                        where t.kyc_provider_id = ? and t.provider_reference = ? for update""",
                        uuid(provider, "id"), reference).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("kyc check with provider reference", reference));
        if (KycRules.TERMINAL_CHECK.contains(text(check, "status"))) {
            return;
        }
        List<String> reasons = KycSupport.values(payload.get("reason_codes"));
        ObjectNode v = rows.object();
        apply(v, status, text(payload, "score"), reasons, text(payload, "result_summary"));
        v.set("response_payload", payload);
        ObjectNode updated = rows.update(TABLE, uuid(check, "id"), v);
        kyc.publish("party.kyc_check.completed", TABLE, updated, KycCheck.class);
    }

    /** HMAC-SHA256 over "provider_reference:status" when a webhook secret is configured for the provider. */
    private void verifySignature(String code, String reference, String status, String signature) {
        String secret = env.getProperty("meridian.kyc.webhook-secrets." + code);
        if (secret == null || secret.isBlank()) {
            return;
        }
        if (signature == null) {
            throw new ApiException(401, "invalid_signature", "Callback signature is missing");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((reference + ":" + status).getBytes(StandardCharsets.UTF_8));
            byte[] given;
            try {
                given = HexFormat.of().parseHex(signature.toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiException(401, "invalid_signature", "Callback signature is invalid");
            }
            if (!MessageDigest.isEqual(expected, given)) {
                throw new ApiException(401, "invalid_signature", "Callback signature is invalid");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
