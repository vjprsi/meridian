package io.meridian.filegateway.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Renders outbound records into file content using a format layout (CSV, fixed width or JSON lines) and
 * computes control totals (record count and the sum of the {@code amount} field, or of the layout's
 * {@code control_sum_field}) written by the caller into the trailer metadata.
 */
public final class FileRenderer {
    private FileRenderer() {}

    public record Rendered(String content, int recordCount, BigDecimal controlSum) {}

    public static Rendered render(String kind, JsonNode schema, Integer recordLength, String delimiter, boolean hasHeader,
                                  JsonNode records) {
        if (records == null || !records.isArray()) {
            throw ApiException.unprocessable("records_required", "records must be an array of objects");
        }
        String sumField = schema == null ? "amount" : schema.path("control_sum_field").asString("amount");
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode r : records) {
            if (r.hasNonNull(sumField)) {
                try {
                    sum = sum.add(new BigDecimal(r.get(sumField).asString()));
                } catch (NumberFormatException e) {
                    throw ApiException.unprocessable("invalid_amount", sumField + " must be a decimal");
                }
            }
        }
        List<String> lines = new ArrayList<>();
        JsonNode fields = schema == null ? null : schema.path("fields");
        switch (kind == null ? "JSON" : kind) {
            case "CSV" -> {
                String d = delimiter == null || delimiter.isEmpty() ? "," : delimiter;
                requireFields(fields);
                if (hasHeader) {
                    List<String> h = new ArrayList<>();
                    fields.forEach(f -> h.add(f.path("name").asString()));
                    lines.add(String.join(d, h));
                }
                for (JsonNode r : records) {
                    List<String> cols = new ArrayList<>();
                    for (JsonNode f : fields) {
                        cols.add(csvCell(text(r, f.path("name").asString()), d));
                    }
                    lines.add(String.join(d, cols));
                }
            }
            case "FIXED_WIDTH" -> {
                requireFields(fields);
                int len = recordLength == null ? 0 : recordLength;
                for (JsonNode r : records) {
                    char[] line = " ".repeat(Math.max(len, width(fields))).toCharArray();
                    for (JsonNode f : fields) {
                        String v = text(r, f.path("name").asString());
                        int start = f.path("start").asInt(1) - 1;
                        int length = f.path("length").asInt(0);
                        String type = f.path("type").asString("STRING").toUpperCase(Locale.ROOT);
                        if (v.length() > length) {
                            throw ApiException.unprocessable("value_too_long", "Value of " + f.path("name").asString()
                                    + " does not fit " + length + " characters");
                        }
                        String padded = type.equals("NUMERIC") || type.equals("DECIMAL")
                                ? "0".repeat(length - v.length()) + v
                                : v + " ".repeat(length - v.length());
                        padded.getChars(0, length, line, start);
                    }
                    lines.add(new String(line));
                }
            }
            default -> records.forEach(r -> lines.add(r.toString()));
        }
        return new Rendered(String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"), records.size(), sum);
    }

    private static void requireFields(JsonNode fields) {
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            throw ApiException.unprocessable("format_has_no_fields", "The format layout defines no fields to render");
        }
    }

    private static int width(JsonNode fields) {
        int w = 0;
        for (JsonNode f : fields) {
            w = Math.max(w, f.path("start").asInt(1) - 1 + f.path("length").asInt(0));
        }
        return w;
    }

    private static String text(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null || v.isNull() ? "" : v.asString();
    }

    private static String csvCell(String v, String delimiter) {
        if (v.contains(delimiter) || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
