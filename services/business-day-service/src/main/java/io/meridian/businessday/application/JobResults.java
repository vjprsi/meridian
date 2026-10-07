package io.meridian.businessday.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Derives what an EOD job did from its target's response, so a run that did nothing is visible (a zero accrual no
 * longer looks the same as a full one). Targets may return {@code records_processed} / {@code records_failed}
 * explicitly; otherwise top-level numeric counters are interpreted by name: {@code *_failed}, {@code failed},
 * {@code errors} count as failures; other counters ({@code *_count}, {@code *processed}, {@code accrued},
 * {@code posted}, ...) as processed records. Amount-like fields are ignored.
 */
final class JobResults {
    static final int MAX_SUMMARY_CHARS = 16_000;
    private static final Set<String> COUNTERS = Set.of("processed", "count", "total", "accrued", "posted", "expired",
            "created", "matched", "sent", "updated", "closed", "charged", "capitalised", "matured", "promoted", "indexed",
            "delivered", "generated", "evaluated", "scanned", "renewed", "marked", "rolled", "settled", "items", "jobs");
    private static final Set<String> NOT_COUNTS = Set.of("amount", "total_amount", "balance", "rate", "value", "ms",
            "duration", "seconds", "version", "row_version", "partition", "attempt");

    private JobResults() {}

    static Long processed(JsonNode res) {
        if (res == null || !res.isObject()) {
            return null;
        }
        if (res.hasNonNull("records_processed") && res.get("records_processed").isIntegralNumber()) {
            return res.get("records_processed").asLong();
        }
        long sum = 0;
        boolean any = false;
        for (Map.Entry<String, JsonNode> e : res.properties()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (e.getValue().isIntegralNumber() && isCounter(k) && !isFailure(k)) {
                sum += e.getValue().asLong();
                any = true;
            }
        }
        return any ? sum : null;
    }

    static Long failed(JsonNode res) {
        if (res == null || !res.isObject()) {
            return null;
        }
        if (res.hasNonNull("records_failed") && res.get("records_failed").isIntegralNumber()) {
            return res.get("records_failed").asLong();
        }
        long sum = 0;
        boolean any = false;
        for (Map.Entry<String, JsonNode> e : res.properties()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (e.getValue().isIntegralNumber() && isFailure(k)) {
                sum += e.getValue().asLong();
                any = true;
            }
        }
        return any ? sum : null;
    }

    /** The response as stored in {@code eod_job_run.result}: objects only, truncated to a bounded size. */
    static String summary(JsonNode res, JsonMapper json) {
        if (res == null || res.isNull() || res.isMissingNode()) {
            return null;
        }
        String text = json.writeValueAsString(res.isObject() ? res : json.createObjectNode().set("value", res));
        if (text.length() <= MAX_SUMMARY_CHARS) {
            return text;
        }
        ObjectNode cut = json.createObjectNode();
        cut.put("truncated", true);
        cut.put("original_size", text.length());
        if (res.isObject()) {
            for (Map.Entry<String, JsonNode> e : res.properties()) {
                if (e.getValue().isValueNode() && e.getValue().asString("").length() <= 200) {
                    cut.set(e.getKey(), e.getValue());
                }
            }
        }
        return json.writeValueAsString(cut);
    }

    private static boolean isFailure(String k) {
        return k.equals("failed") || k.equals("errors") || k.endsWith("_failed") || k.endsWith("_errors");
    }

    private static boolean isCounter(String k) {
        if (NOT_COUNTS.contains(k) || k.endsWith("_amount") || k.endsWith("_ms") || k.endsWith("_rate")) {
            return false;
        }
        if (k.endsWith("_count") || k.endsWith("processed") || COUNTERS.contains(k)) {
            return true;
        }
        int u = k.lastIndexOf('_');
        return u > 0 && COUNTERS.contains(k.substring(u + 1));
    }
}
