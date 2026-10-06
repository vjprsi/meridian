package io.meridian.webhook.domain;

import io.meridian.common.error.ApiException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** State machines and validation rules for endpoints, deliveries and replay jobs (no framework dependencies). */
public final class WebhookRules {
    private WebhookRules() {}

    private static final Set<String> RESERVED_HEADERS = Set.of("content-type", "content-length", "host",
            "user-agent", "authorization", "transfer-encoding", "connection");

    // ------------------------------------------------------------------ endpoint

    public static void validateUrl(String url, boolean allowInsecureLocalhost) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_url", "Endpoint URL is not a valid URI");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw ApiException.unprocessable("invalid_url", "Endpoint URL must have a host");
        }
        if (u.getUserInfo() != null) {
            throw ApiException.unprocessable("invalid_url", "Endpoint URL must not contain credentials");
        }
        if ("https".equals(scheme)) {
            return;
        }
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if ("http".equals(scheme) && local && allowInsecureLocalhost) {
            return;
        }
        throw ApiException.unprocessable("https_required", "Webhook endpoints must use https");
    }

    public static void validateCustomHeaders(Map<String, Object> headers) {
        if (headers == null) {
            return;
        }
        if (headers.size() > 20) {
            throw ApiException.unprocessable("invalid_custom_headers", "At most 20 custom headers are allowed");
        }
        headers.forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            if (!k.matches("[A-Za-z0-9-]{1,64}") || RESERVED_HEADERS.contains(name) || name.startsWith("meridian-")) {
                throw ApiException.unprocessable("invalid_custom_headers", "Header " + k + " is not allowed");
            }
            if (!(v instanceof String s) || s.length() > 500 || s.contains("\n") || s.contains("\r")) {
                throw ApiException.unprocessable("invalid_custom_headers", "Header " + k + " must be a single-line string");
            }
        });
    }

    public static String enable(String status) {
        if ("ACTIVE".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Endpoint is already ACTIVE");
        }
        return "ACTIVE";
    }

    public static String disable(String status) {
        if ("DISABLED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Endpoint is already DISABLED");
        }
        return "DISABLED";
    }

    public static void requireDeliverable(String endpointStatus) {
        if ("DISABLED".equals(endpointStatus)) {
            throw ApiException.conflict("endpoint_disabled", "Endpoint is disabled; enable it first");
        }
    }

    /** Status after a failed attempt given the new consecutive failure count. */
    public static String afterFailure(String status, int consecutiveFailures, int disableThreshold) {
        if ("DISABLED".equals(status)) {
            return status;
        }
        return consecutiveFailures >= disableThreshold ? "DISABLED" : "FAILING";
    }

    // ------------------------------------------------------------------ delivery

    public static void requireRetryable(String deliveryStatus) {
        if (!Set.of("FAILED", "ABANDONED", "RETRYING").contains(deliveryStatus)) {
            throw ApiException.conflict("invalid_transition",
                    "Only FAILED, ABANDONED or RETRYING deliveries can be retried (status " + deliveryStatus + ")");
        }
    }

    public static boolean isSuccess(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    // ------------------------------------------------------------------ replay

    public static void requireCancellable(String jobStatus) {
        if (!"QUEUED".equals(jobStatus) && !"RUNNING".equals(jobStatus)) {
            throw ApiException.conflict("invalid_transition", "Replay job is " + jobStatus + " and cannot be cancelled");
        }
    }
}
