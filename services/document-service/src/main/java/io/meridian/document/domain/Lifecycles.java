package io.meridian.document.domain;

import java.util.Map;
import java.util.Set;

/** Allowed status transitions for documents, templates and e-sign envelopes. Pure domain rules. */
public final class Lifecycles {
    private Lifecycles() {}

    /** Thrown when a transition is not allowed; mapped to 409 by the application layer. */
    public static final class InvalidTransition extends RuntimeException {
        private final String code;

        public InvalidTransition(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final Map<String, Set<String>> DOCUMENT = Map.of(
            "UPLOADING", Set.of("SCANNING", "AVAILABLE", "QUARANTINED", "DELETED"),
            "SCANNING", Set.of("AVAILABLE", "QUARANTINED", "DELETED"),
            "AVAILABLE", Set.of("ARCHIVED", "DELETED"),
            "QUARANTINED", Set.of("DELETED"),
            "ARCHIVED", Set.of("DELETED"),
            "DELETED", Set.of());

    public static final Map<String, Set<String>> TEMPLATE = Map.of(
            "DRAFT", Set.of("PENDING_APPROVAL", "RETIRED"),
            "PENDING_APPROVAL", Set.of("ACTIVE", "DRAFT"),
            "ACTIVE", Set.of("RETIRED"),
            "RETIRED", Set.of());

    public static final Map<String, Set<String>> ESIGN = Map.of(
            "DRAFT", Set.of("SENT", "CANCELLED"),
            "SENT", Set.of("VIEWED", "PARTIALLY_SIGNED", "SIGNED", "DECLINED", "EXPIRED", "CANCELLED"),
            "VIEWED", Set.of("PARTIALLY_SIGNED", "SIGNED", "DECLINED", "EXPIRED", "CANCELLED"),
            "PARTIALLY_SIGNED", Set.of("PARTIALLY_SIGNED", "SIGNED", "DECLINED", "EXPIRED", "CANCELLED"),
            "SIGNED", Set.of(),
            "DECLINED", Set.of(),
            "EXPIRED", Set.of(),
            "CANCELLED", Set.of());

    public static boolean allowed(Map<String, Set<String>> machine, String from, String to) {
        return machine.getOrDefault(from, Set.of()).contains(to);
    }

    public static void require(Map<String, Set<String>> machine, String entity, String from, String to) {
        if (!allowed(machine, from, to)) {
            throw new InvalidTransition("invalid_transition", entity + " cannot move from " + from + " to " + to);
        }
    }

    public static boolean isTerminalEsign(String status) {
        return ESIGN.getOrDefault(status, Set.of()).isEmpty();
    }

    /** Identity documents whose expiry triggers {@code platform.document.expiring}. */
    public static final Set<String> IDENTITY_TYPES = Set.of("ID_CARD", "PASSPORT", "DRIVING_LICENSE", "RESIDENCE_PERMIT");

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/heic", "image/tiff", "image/webp", "image/gif",
            "video/mp4", "video/webm", "video/quicktime", "text/plain", "text/csv", "text/html", "application/json",
            "application/xml", "text/xml", "application/zip", "application/msword", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    /** Normalises a client file name into a safe object-key segment. */
    public static String safeFileName(String fileName) {
        String base = fileName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "_");
        if (cleaned.isEmpty()) {
            cleaned = "file";
        }
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    /** Maps a template category to the stored document type of generated output. */
    public static String documentTypeFor(String category) {
        return switch (category) {
            case "STATEMENT" -> "ACCOUNT_STATEMENT";
            case "CONTRACT" -> "CONTRACT";
            case "CERTIFICATE" -> "CERTIFICATE";
            case "RECEIPT" -> "RECEIPT";
            case "REPORT" -> "REPORT";
            default -> "OTHER";
        };
    }
}
