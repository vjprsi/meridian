package io.meridian.analytics.domain;

import java.net.IDN;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Destination rules of data shares: required connection keys per target type, destination hygiene (no private or
 * loopback hosts, https endpoints) and a non-secret summary of the destination.
 */
public final class ShareTargets {
    private ShareTargets() {}

    private static final Map<String, List<String>> REQUIRED = Map.of(
            "S3", List.of("bucket", "region", "role_arn"),
            "GCS", List.of("bucket", "credentials_ref"),
            "AZURE_BLOB", List.of("account", "container", "credentials_ref"),
            "SNOWFLAKE", List.of("account", "database", "schema", "user", "credentials_ref"),
            "BIGQUERY", List.of("project", "dataset", "credentials_ref"),
            "DATABRICKS", List.of("host", "http_path", "credentials_ref"),
            "SFTP", List.of("host", "port", "path", "username", "credentials_ref"),
            "ICEBERG_REST", List.of("uri", "warehouse", "credentials_ref"));

    private static final List<String> SUMMARY_KEYS = List.of("bucket", "region", "account", "container", "database",
            "schema", "project", "dataset", "host", "path", "uri", "warehouse", "prefix");
    private static final Set<String> SECRET_HINTS = Set.of("secret", "password", "private_key", "token", "key");

    /** One check of a test. */
    public record Check(String name, boolean ok, String message) {}

    /** Validates a destination; returns failed checks (empty = valid). */
    public static List<Check> validate(String target, Map<String, Object> config) {
        List<Check> out = new ArrayList<>();
        List<String> required = REQUIRED.get(target);
        if (required == null) {
            out.add(new Check("target", false, "unknown target " + target));
            return out;
        }
        if (config == null) {
            out.add(new Check("config", false, "target_config is required"));
            return out;
        }
        for (String k : required) {
            Object v = config.get(k);
            if (v == null || String.valueOf(v).isBlank()) {
                out.add(new Check("config." + k, false, k + " is required for " + target));
            }
        }
        if (config.get("host") != null && !publicHost(String.valueOf(config.get("host")))) {
            out.add(new Check("config.host", false, "host must be a public DNS name or address"));
        }
        if (config.get("uri") != null) {
            String uri = String.valueOf(config.get("uri"));
            if (!uri.startsWith("https://")) {
                out.add(new Check("config.uri", false, "uri must use https"));
            } else {
                String host = uri.substring(8).split("[/:?#]")[0];
                if (!publicHost(host)) {
                    out.add(new Check("config.uri", false, "uri must point to a public host"));
                }
            }
        }
        if (config.get("port") != null) {
            java.math.BigDecimal p = Expr.toDecimal(config.get("port"));
            if (p == null || p.intValue() < 1 || p.intValue() > 65535) {
                out.add(new Check("config.port", false, "port must be 1-65535"));
            }
        }
        return out;
    }

    /** Non-secret description of the destination, e.g. {@code S3 s3://bucket/prefix (eu-west-1)}. */
    public static String summary(String target, Map<String, Object> config) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String k : SUMMARY_KEYS) {
            if (config != null && config.get(k) != null && SECRET_HINTS.stream().noneMatch(k::contains)) {
                parts.put(k, String.valueOf(config.get(k)));
            }
        }
        String s = target + " " + parts;
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    /** Delegates the address rules to the shared outbound policy (SEC-08); raw IPv6 literals stay refused here. */
    static boolean publicHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.contains(":") || !io.meridian.web.outbound.OutboundUrlPolicy.isPublicHostLiteral(h)) {
            return false;
        }
        try {
            h = IDN.toASCII(h);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || h.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");
    }
}
