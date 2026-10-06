package io.meridian.openbanking.application;

import static io.meridian.openbanking.infrastructure.Rows.str;
import static io.meridian.openbanking.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.openbanking.infrastructure.Rows;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Append-only access log of TPP calls: entries from the API gateway and from consent-scoped operations of this
 * service (written in their own transaction, so failed calls are logged too). Each entry touches the consent's
 * last_accessed_at.
 */
@Service
public class AccessLogService {
    static final String TABLE = "ob_access_log";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** One call to record. */
    public record Entry(UUID tppId, UUID consentId, String endpoint, String method, int status, String interactionId,
                        String psuIp, long latencyMs, Integer records) {}

    private final Rows rows;

    public AccessLogService(Rows rows) {
        this.rows = rows;
    }

    /** Gateway-supplied entry (validated). */
    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        UUID tppId = uuid(v, "tpp_id");
        if (rows.find(TppService.TPP, tppId).isEmpty()) {
            throw ApiException.unprocessable("tpp_not_found", "TPP " + tppId + " does not exist");
        }
        UUID consentId = uuid(v, "ob_consent_id");
        if (consentId != null) {
            ObjectNode consent = rows.find(ConsentService.CONSENT, consentId).orElseThrow(() ->
                    ApiException.unprocessable("consent_not_found", "Consent " + consentId + " does not exist"));
            if (!tppId.equals(uuid(consent, "tpp_id"))) {
                throw ApiException.unprocessable("consent_tpp_mismatch", "The consent belongs to another TPP");
            }
            if (!v.hasNonNull("customer_id") && str(consent, "customer_id") != null) {
                v.put("customer_id", str(consent, "customer_id"));
            }
        }
        String method = str(v, "http_method").toUpperCase(java.util.Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw ApiException.unprocessable("invalid_http_method", "http_method must be one of " + METHODS);
        }
        v.put("http_method", method);
        int status = v.path("http_status").asInt();
        if (status < 100 || status > 599) {
            throw ApiException.unprocessable("invalid_http_status", "http_status must be 100-599");
        }
        if (v.path("psu_present").asBoolean(false) && !v.hasNonNull("psu_ip_address")) {
            throw ApiException.unprocessable("psu_ip_required", "psu_ip_address is required when psu_present is true");
        }
        ObjectNode row = rows.insert(TABLE, v);
        touch(consentId);
        return row;
    }

    /** Records a call made through this service; never fails the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Entry e) {
        if (e.tppId() == null || rows.find(TppService.TPP, e.tppId()).isEmpty()) {
            return;
        }
        ObjectNode v = rows.object();
        v.put("tpp_id", e.tppId().toString());
        if (e.consentId() != null) {
            rows.find(ConsentService.CONSENT, e.consentId()).ifPresent(c -> {
                v.put("ob_consent_id", e.consentId().toString());
                if (str(c, "customer_id") != null) {
                    v.put("customer_id", str(c, "customer_id"));
                }
            });
        }
        v.put("endpoint", e.endpoint().length() > 255 ? e.endpoint().substring(0, 255) : e.endpoint());
        v.put("http_method", e.method());
        v.put("http_status", e.status());
        v.put("psu_present", e.psuIp() != null);
        if (e.psuIp() != null) {
            v.put("psu_ip_address", e.psuIp());
        }
        if (e.interactionId() != null) {
            v.put("interaction_id", e.interactionId().length() > 64 ? e.interactionId().substring(0, 64) : e.interactionId());
        }
        v.put("latency_ms", (int) Math.min(Integer.MAX_VALUE, e.latencyMs()));
        if (e.records() != null) {
            v.put("records_returned", e.records());
        }
        rows.insert(TABLE, v);
        if (v.has("ob_consent_id")) {
            touch(e.consentId());
        }
    }

    private void touch(UUID consentId) {
        if (consentId != null) {
            rows.execute("update ob_consent set last_accessed_at = now() where id = ?", consentId);
        }
    }
}
