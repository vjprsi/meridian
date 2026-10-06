package io.meridian.filegateway.domain;

import io.meridian.common.error.ApiException;
import java.net.InetAddress;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** State machines and value rules for channels, inbound and outbound files. */
public final class FileRules {
    private FileRules() {}

    /** Vault / KMS reference such as {@code vault://kv/files/bank-x/sftp} or {@code aws-sm://prod/sftp-key}. */
    private static final Pattern SECRET_REF =
            Pattern.compile("^(vault|secret|aws-sm|azure-kv|gcp-sm|kms)://[A-Za-z0-9._/@:+-]{3,240}$");

    public static void requireSecretRef(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw ApiException.unprocessable(field + "_required", field + " (a vault reference) is required");
            }
            return;
        }
        if (!SECRET_REF.matcher(value).matches()) {
            throw ApiException.unprocessable("invalid_secret_ref", field
                    + " must be a secret-store reference (e.g. vault://kv/files/partner/sftp), never a literal credential");
        }
    }

    public static Pattern regex(String field, String value) {
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw ApiException.unprocessable("invalid_pattern", field + " is not a valid regular expression");
        }
    }

    /** True if the file name matches the pattern in full. */
    public static boolean matches(String pattern, String fileName) {
        try {
            return Pattern.compile(pattern).matcher(fileName).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    public static void requireIpOrCidr(String v) {
        String[] parts = v.split("/");
        try {
            if (parts.length > 2 || !parts[0].matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException();
            }
            InetAddress addr = InetAddress.ofLiteral(parts[0]);
            if (parts.length == 2) {
                int bits = Integer.parseInt(parts[1]);
                int max = addr.getAddress().length * 8;
                if (bits < 0 || bits > max) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_ip_allowlist", "'" + v + "' is not an IP address or CIDR block");
        }
    }

    public static boolean receives(String direction) {
        return "INBOUND".equals(direction) || "BOTH".equals(direction);
    }

    public static boolean sends(String direction) {
        return "OUTBOUND".equals(direction) || "BOTH".equals(direction);
    }

    // ------------------------------------------------------------------ inbound

    private static final Set<String> REPROCESSABLE = Set.of("RECEIVED", "REJECTED", "FAILED", "ROUTED");

    public static void requireReprocess(String status) {
        if ("QUARANTINED".equals(status)) {
            throw ApiException.conflict("file_quarantined", "The file is quarantined; release it after review first");
        }
        if (!REPROCESSABLE.contains(status)) {
            throw transition("Inbound file", status, "be reprocessed");
        }
    }

    public static void requireQuarantined(String status) {
        if (!"QUARANTINED".equals(status)) {
            throw transition("Inbound file", status, "be released from quarantine");
        }
    }

    // ------------------------------------------------------------------ outbound

    public static void requireRedeliver(String status) {
        if (!Set.of("GENERATED", "QUEUED", "FAILED", "DELIVERED", "ACKNOWLEDGED").contains(status)) {
            throw transition("Outbound file", status, "be (re)delivered");
        }
    }

    public static void requireCancel(String status) {
        if (!Set.of("REQUESTED", "GENERATING", "GENERATED", "ENCRYPTING", "QUEUED", "FAILED").contains(status)) {
            throw transition("Outbound file", status, "be cancelled");
        }
    }

    public static void requireAcknowledge(String status) {
        if (!"DELIVERED".equals(status)) {
            throw transition("Outbound file", status, "be acknowledged");
        }
    }

    public static ApiException transition(String what, String from, String action) {
        return ApiException.conflict("invalid_transition", what + " in status " + from + " cannot " + action);
    }
}
