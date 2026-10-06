package io.meridian.openbanking.api;

import io.meridian.common.error.ApiException;
import io.meridian.openbanking.application.AccessLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Wraps consent-scoped TPP operations: measures latency and writes an access-log entry (also for failed calls,
 * with the problem status) carrying the FAPI interaction id and the PSU IP address.
 */
@Component
class TppCalls {
    private static final Logger LOG = LoggerFactory.getLogger(TppCalls.class);

    private final AccessLogService accessLog;

    TppCalls(AccessLogService accessLog) {
        this.accessLog = accessLog;
    }

    <T> ResponseEntity<T> logged(UUID tppId, UUID consentId, Supplier<ResponseEntity<T>> call) {
        HttpServletRequest req = Http.request();
        long start = System.nanoTime();
        int status = 500;
        try {
            ResponseEntity<T> r = call.get();
            status = r == null ? 200 : r.getStatusCode().value();
            return r;
        } catch (ApiException e) {
            status = e.status();
            throw e;
        } finally {
            try {
                String psuIp = header(req, "PSU-IP-Address");
                if (psuIp == null) {
                    psuIp = header(req, "x-fapi-customer-ip-address");
                }
                accessLog.record(new AccessLogService.Entry(tppId, consentId, req.getRequestURI(), req.getMethod(), status,
                        header(req, "x-fapi-interaction-id"), psuIp, (System.nanoTime() - start) / 1_000_000, null));
            } catch (RuntimeException ex) {
                LOG.warn("could not write open banking access log: {}", ex.getMessage());
            }
        }
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
