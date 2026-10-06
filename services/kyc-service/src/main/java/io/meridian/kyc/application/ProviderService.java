package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.text;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Provider registry: configuration validation, routing to adapters, connectivity tests. */
@Service
public class ProviderService {
    static final String TABLE = "kyc_provider";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private final Rows rows;
    private final CrudEngine crud;
    private final KycSupport kyc;
    private final List<ProviderAdapter> adapters;

    public ProviderService(Rows rows, CrudEngine crud, KycSupport kyc, List<ProviderAdapter> adapters) {
        this.rows = rows;
        this.crud = crud;
        this.kyc = kyc;
        this.adapters = adapters;
    }

    /** Normalises and validates client values; derives the credentials vault path (not part of the contract). */
    public ObjectNode createValues(Object body) {
        ObjectNode v = crud.toColumns(TABLE, body);
        String code = text(v, "code").trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw ApiException.unprocessable("invalid_code", "Provider code must be 2-40 chars of A-Z, 0-9 and _");
        }
        v.put("code", code);
        normalise(v);
        v.put("credentials_ref", "vault://kyc/providers/" + code.toLowerCase(Locale.ROOT));
        if (!v.has("webhook_secret_ref")) {
            v.put("webhook_secret_ref", "vault://kyc/providers/" + code.toLowerCase(Locale.ROOT) + "/webhook");
        }
        return v;
    }

    @Transactional
    public ObjectNode create(Object body) {
        return rows.insert(TABLE, createValues(body));
    }

    /** Validates a PATCH body; the update itself goes through the generic engine. */
    public void validateUpdate(Object body) {
        ObjectNode v = crud.toColumns(TABLE, body);
        if (v.has("code")) {
            throw ApiException.unprocessable("immutable_field", "Provider code cannot be changed");
        }
        normalise(v);
    }

    private void normalise(ObjectNode v) {
        if (v.has("capabilities")) {
            List<String> caps = KycSupport.values(v.get("capabilities")).stream()
                    .map(c -> c.toUpperCase(Locale.ROOT)).distinct().toList();
            if (caps.isEmpty()) {
                throw ApiException.unprocessable("invalid_capabilities", "At least one capability is required");
            }
            for (String c : caps) {
                if (!KycRules.CAPABILITIES.contains(c)) {
                    throw ApiException.unprocessable("invalid_capabilities", "Unknown capability " + c);
                }
            }
            v.set("capabilities", kyc.valuesObject(caps));
        }
        if (v.has("countries")) {
            List<String> countries = KycSupport.values(v.get("countries")).stream()
                    .map(c -> c.toUpperCase(Locale.ROOT)).distinct().toList();
            for (String c : countries) {
                if (!COUNTRY.matcher(c).matches()) {
                    throw ApiException.unprocessable("invalid_countries", "Countries must be ISO 3166 alpha-2 codes");
                }
            }
            v.set("countries", kyc.valuesObject(countries));
        }
        if (v.has("timeout_ms") && (v.get("timeout_ms").asInt() < 100 || v.get("timeout_ms").asInt() > 120_000)) {
            throw ApiException.unprocessable("invalid_timeout", "timeout_ms must be between 100 and 120000");
        }
        if (v.hasNonNull("base_url")) {
            String url = text(v, "base_url");
            if (!url.startsWith("https://") && !url.startsWith("http://") && !url.startsWith("sandbox:")) {
                throw ApiException.unprocessable("invalid_base_url", "base_url must be an http(s) or sandbox: URL");
            }
        }
    }

    public ObjectNode require(UUID id) {
        return rows.require(TABLE, id);
    }

    public ObjectNode byCode(String code) {
        return rows.query("select row_to_json(t)::text from kyc_provider t where t.code = ?",
                        code.toUpperCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("kyc provider", code));
    }

    public static void requireActive(JsonNode provider) {
        if (!KycSupport.bool(provider, "is_active")) {
            throw ApiException.conflict("provider_inactive", "Provider " + text(provider, "code") + " is not active");
        }
    }

    public static boolean capable(JsonNode provider, String capability) {
        return KycSupport.values(provider.get("capabilities")).contains(capability);
    }

    /** Active fallback provider with the capability, best priority first. */
    public ObjectNode fallback(String capability, UUID excluding) {
        return rows.query("""
                        select row_to_json(t)::text from kyc_provider t
                        where t.is_active and t.is_fallback and t.id <> ? order by t.priority, t.code""", excluding)
                .stream().filter(p -> capable(p, capability)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("no_fallback_provider",
                        "No active fallback provider supports " + capability));
    }

    public ProviderAdapter adapter(ProviderAdapter.Provider p) {
        return adapters.stream().filter(a -> a.supports(p)).findFirst().orElse(null);
    }

    public static ProviderAdapter.Provider descriptor(JsonNode row) {
        return new ProviderAdapter.Provider(KycSupport.uuid(row, "id"), text(row, "code"), text(row, "base_url"),
                row.hasNonNull("timeout_ms") ? row.get("timeout_ms").asInt() : 10_000);
    }

    public Map<String, Object> testConnection(UUID id) {
        ObjectNode row = require(id);
        requireActive(row);
        ProviderAdapter.Provider p = descriptor(row);
        ProviderAdapter adapter = adapter(p);
        ProviderAdapter.ConnectionResult r = adapter == null
                ? new ProviderAdapter.ConnectionResult(false, 0, "No adapter for this provider; configure base_url")
                : adapter.testConnection(p);
        return Map.of("provider_id", id.toString(), "provider_code", p.code(), "ok", r.ok(),
                "latency_ms", r.latencyMs(), "message", r.message(), "tested_at", KycSupport.now());
    }
}
