package io.meridian.analytics.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** RFC 4180 CSV with spreadsheet formula-injection protection (cells starting with = + - @ are prefixed with '). */
public final class CsvWriter {
    private CsvWriter() {}

    public static String write(List<String> columns, JsonNode rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", columns.stream().map(CsvWriter::field).toList())).append("\r\n");
        for (JsonNode r : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                JsonNode v = r.get(columns.get(i));
                sb.append(v == null || v.isNull() ? "" : field(v.isString() ? v.asString() : v.toString()));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    public static String field(String s) {
        if (s == null) {
            return "";
        }
        String v = s;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
