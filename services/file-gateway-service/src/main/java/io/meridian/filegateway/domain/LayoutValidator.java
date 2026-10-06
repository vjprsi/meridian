package io.meridian.filegateway.domain;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates file layouts (format definitions) and parses file content against them.
 *
 * <p>Schema ({@code schema_definition}) per format kind:
 * <ul>
 *   <li>FIXED_WIDTH / NACHA: {@code {"fields":[{"name","start"(1-based),"length","type","required","format","pattern","allowed"}]}}
 *       or {@code {"type_start":1,"type_length":1,"record_types":[{"code":"1","fields":[...]}]}} for header/detail/trailer layouts</li>
 *   <li>CSV: {@code {"fields":[{"name","type","required",...}]}} in column order</li>
 *   <li>JSON: {@code {"records_path":"records","fields":[...]}}</li>
 *   <li>XML_ISO20022: {@code {"message_type":"camt.053.001.08","record_element":"Ntry","required_elements":["Amt"]}}</li>
 * </ul>
 * Field types: STRING / ALPHANUMERIC, ALPHA, NUMERIC (digits), DECIMAL, DATE (default {@code yyyyMMdd} for fixed
 * width, ISO otherwise; override with {@code format}).
 */
public final class LayoutValidator {
    public static final int MAX_ERRORS = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LayoutValidator() {}

    public record FieldError(int line, String field, String code, String message) {}

    public record Result(boolean valid, int recordCount, int errorCount, List<FieldError> errors) {}

    // ------------------------------------------------------------------ schema checks

    /** Problems with a layout definition itself (empty when the layout is usable). */
    public static List<String> schemaProblems(String kind, JsonNode schema, Integer recordLength, String delimiter) {
        List<String> p = new ArrayList<>();
        if (schema == null || !schema.isObject()) {
            p.add("schema_definition must be an object");
            return p;
        }
        switch (kind) {
            case "FIXED_WIDTH", "NACHA" -> {
                int len = recordLength != null ? recordLength : "NACHA".equals(kind) ? 94 : 0;
                if (len <= 0) {
                    p.add("record_length is required for fixed-width formats");
                }
                if (schema.has("record_types")) {
                    if (!schema.path("type_start").isNumber() || !schema.path("type_length").isNumber()) {
                        p.add("type_start and type_length are required with record_types");
                    }
                    for (JsonNode rt : schema.get("record_types")) {
                        if (!rt.hasNonNull("code")) {
                            p.add("every record type needs a code");
                        }
                        fixedFieldProblems(rt.path("fields"), len, p);
                    }
                } else if (schema.has("fields")) {
                    fixedFieldProblems(schema.get("fields"), len, p);
                } else if (!"NACHA".equals(kind)) {
                    p.add("fields or record_types is required");
                }
            }
            case "CSV" -> {
                if (delimiter != null && delimiter.isEmpty()) {
                    p.add("delimiter must not be empty");
                }
                namedFieldProblems(schema.path("fields"), p, true);
            }
            case "JSON" -> namedFieldProblems(schema.path("fields"), p, false);
            case "XML_ISO20022" -> {
                String mt = schema.path("message_type").asString("");
                if (!mt.matches("[a-z]{4}\\.\\d{3}\\.\\d{3}\\.\\d{2}")) {
                    p.add("message_type must be an ISO 20022 message id such as camt.053.001.08");
                }
                if (!schema.hasNonNull("record_element")) {
                    p.add("record_element (e.g. Ntry) is required");
                }
            }
            default -> { }
        }
        return p;
    }

    private static void fixedFieldProblems(JsonNode fields, int recordLength, List<String> p) {
        if (!fields.isArray() || fields.isEmpty()) {
            p.add("fields must be a non-empty array");
            return;
        }
        List<int[]> spans = new ArrayList<>();
        for (JsonNode f : fields) {
            String name = f.path("name").asString("");
            int start = f.path("start").asInt(0);
            int length = f.path("length").asInt(0);
            if (name.isBlank() || start < 1 || length < 1) {
                p.add("field " + (name.isBlank() ? "?" : name) + " needs name, start >= 1 and length >= 1");
                continue;
            }
            if (recordLength > 0 && start + length - 1 > recordLength) {
                p.add("field " + name + " ends at " + (start + length - 1) + " beyond record_length " + recordLength);
            }
            for (int[] s : spans) {
                if (start <= s[1] && start + length - 1 >= s[0]) {
                    p.add("field " + name + " overlaps another field");
                }
            }
            spans.add(new int[]{start, start + length - 1});
            typeProblems(f, p);
        }
    }

    private static void namedFieldProblems(JsonNode fields, List<String> p, boolean required) {
        if (!fields.isArray() || (required && fields.isEmpty())) {
            if (required || !fields.isMissingNode()) {
                p.add("fields must be a non-empty array");
            }
            return;
        }
        for (JsonNode f : fields) {
            if (f.path("name").asString("").isBlank()) {
                p.add("every field needs a name");
            }
            typeProblems(f, p);
        }
    }

    private static void typeProblems(JsonNode f, List<String> p) {
        String type = f.path("type").asString("STRING").toUpperCase(Locale.ROOT);
        if (!List.of("STRING", "ALPHANUMERIC", "ALPHA", "NUMERIC", "DECIMAL", "DATE").contains(type)) {
            p.add("field " + f.path("name").asString("?") + " has unknown type " + type);
        }
        if (f.hasNonNull("pattern")) {
            try {
                Pattern.compile(f.get("pattern").asString());
            } catch (PatternSyntaxException e) {
                p.add("field " + f.path("name").asString("?") + " has an invalid pattern");
            }
        }
    }

    // ------------------------------------------------------------------ content validation

    public static Result validate(String kind, JsonNode schema, Integer recordLength, String delimiter, boolean hasHeader,
                                  String content) {
        List<FieldError> errors = new ArrayList<>();
        int records;
        switch (kind) {
            case "FIXED_WIDTH", "NACHA" -> records = fixed(schema, recordLength != null ? recordLength
                    : "NACHA".equals(kind) ? 94 : 0, hasHeader, content, errors);
            case "CSV" -> records = csv(schema, delimiter == null || delimiter.isEmpty() ? "," : delimiter, hasHeader, content, errors);
            case "JSON" -> records = json(schema, content, errors);
            case "XML_ISO20022" -> records = xml(schema, content, errors);
            default -> records = (int) lines(content).stream().filter(l -> !l.isBlank()).count() - (hasHeader ? 1 : 0);
        }
        int count = errors.size();
        List<FieldError> capped = errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors;
        return new Result(count == 0, Math.max(0, records), count, List.copyOf(capped));
    }

    private static int fixed(JsonNode schema, int recordLength, boolean hasHeader, String content, List<FieldError> errors) {
        List<String> lines = lines(content);
        int records = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || (hasHeader && i == 0)) {
                continue;
            }
            records++;
            int n = i + 1;
            if (recordLength > 0 && line.length() != recordLength) {
                errors.add(new FieldError(n, null, "record_length", "Record has length " + line.length()
                        + ", expected " + recordLength));
                continue;
            }
            JsonNode fields = schema.path("fields");
            if (schema.has("record_types")) {
                int ts = schema.path("type_start").asInt(1);
                int tl = schema.path("type_length").asInt(1);
                String code = line.length() >= ts - 1 + tl ? line.substring(ts - 1, ts - 1 + tl) : "";
                fields = null;
                for (JsonNode rt : schema.get("record_types")) {
                    if (rt.path("code").asString().equals(code)) {
                        fields = rt.path("fields");
                    }
                }
                if (fields == null) {
                    errors.add(new FieldError(n, null, "unknown_record_type", "Unknown record type '" + code + "'"));
                    continue;
                }
            }
            for (JsonNode f : fields) {
                int start = f.path("start").asInt(1) - 1;
                int end = start + f.path("length").asInt(0);
                String name = f.path("name").asString();
                if (end > line.length()) {
                    errors.add(new FieldError(n, name, "field_out_of_range", "Field " + name + " exceeds the record"));
                    continue;
                }
                checkValue(n, f, line.substring(start, end).trim(), "yyyyMMdd", errors);
            }
        }
        return records;
    }

    private static int csv(JsonNode schema, String delimiter, boolean hasHeader, String content, List<FieldError> errors) {
        List<String> lines = lines(content);
        JsonNode fields = schema.path("fields");
        int records = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            List<String> cols = splitCsv(line, delimiter);
            int n = i + 1;
            if (hasHeader && i == 0) {
                for (int c = 0; c < Math.min(cols.size(), fields.size()); c++) {
                    String expected = fields.get(c).path("name").asString();
                    if (!cols.get(c).trim().equalsIgnoreCase(expected)) {
                        errors.add(new FieldError(n, expected, "header_mismatch",
                                "Header column " + (c + 1) + " is '" + cols.get(c) + "', expected '" + expected + "'"));
                    }
                }
                continue;
            }
            records++;
            if (fields.isArray() && !fields.isEmpty() && cols.size() != fields.size()) {
                errors.add(new FieldError(n, null, "column_count", "Record has " + cols.size() + " columns, expected "
                        + fields.size()));
                continue;
            }
            for (int c = 0; c < fields.size(); c++) {
                checkValue(n, fields.get(c), cols.get(c).trim(), "yyyy-MM-dd", errors);
            }
        }
        return records;
    }

    private static int json(JsonNode schema, String content, List<FieldError> errors) {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (RuntimeException e) {
            errors.add(new FieldError(1, null, "malformed_json", "Content is not valid JSON"));
            return 0;
        }
        JsonNode records = root.isArray() ? root : root.path(schema.path("records_path").asString("records"));
        if (!records.isArray()) {
            errors.add(new FieldError(1, null, "records_missing", "No records array found"));
            return 0;
        }
        int i = 0;
        for (JsonNode r : records) {
            i++;
            for (JsonNode f : schema.path("fields")) {
                JsonNode v = r.get(f.path("name").asString());
                checkValue(i, f, v == null || v.isNull() ? "" : v.asString(), "yyyy-MM-dd", errors);
            }
        }
        return records.size();
    }

    private static int xml(JsonNode schema, String content, List<FieldError> errors) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            errors.add(new FieldError(1, null, "malformed_xml", "Content is not well-formed XML: " + e.getMessage()));
            return 0;
        }
        Element root = doc.getDocumentElement();
        String mt = schema.path("message_type").asString("");
        String ns = root.getNamespaceURI();
        if (!mt.isEmpty() && (ns == null || !ns.endsWith(mt))) {
            errors.add(new FieldError(1, null, "message_type", "Namespace " + ns + " is not ISO 20022 " + mt));
        }
        String recordElement = schema.path("record_element").asString("");
        NodeList recs = recordElement.isEmpty() ? null : doc.getElementsByTagNameNS("*", recordElement);
        int count = recs == null ? 0 : recs.getLength();
        for (int i = 0; recs != null && i < recs.getLength(); i++) {
            Element e = (Element) recs.item(i);
            for (JsonNode req : schema.path("required_elements")) {
                if (e.getElementsByTagNameNS("*", req.asString()).getLength() == 0) {
                    errors.add(new FieldError(i + 1, req.asString(), "required",
                            recordElement + " #" + (i + 1) + " has no " + req.asString()));
                }
            }
        }
        return count;
    }

    private static void checkValue(int line, JsonNode f, String v, String defaultDateFormat, List<FieldError> errors) {
        String name = f.path("name").asString();
        if (v.isEmpty()) {
            if (f.path("required").asBoolean(false)) {
                errors.add(new FieldError(line, name, "required", name + " is required"));
            }
            return;
        }
        String type = f.path("type").asString("STRING").toUpperCase(Locale.ROOT);
        boolean ok = switch (type) {
            case "NUMERIC" -> v.matches("\\d+");
            case "DECIMAL" -> v.matches("[-+]?\\d+(\\.\\d+)?");
            case "ALPHA" -> v.matches("[\\p{L} ]+");
            case "DATE" -> isDate(v, f.path("format").asString(defaultDateFormat));
            default -> true;
        };
        if (!ok) {
            errors.add(new FieldError(line, name, "invalid_" + type.toLowerCase(Locale.ROOT),
                    name + " value '" + abbreviate(v) + "' is not a valid " + type));
            return;
        }
        if (f.hasNonNull("pattern") && !v.matches(f.get("pattern").asString())) {
            errors.add(new FieldError(line, name, "pattern_mismatch", name + " does not match " + f.get("pattern").asString()));
        }
        if (f.path("allowed").isArray() && !f.path("allowed").isEmpty()) {
            boolean found = false;
            for (JsonNode a : f.get("allowed")) {
                found |= a.asString().equals(v);
            }
            if (!found) {
                errors.add(new FieldError(line, name, "value_not_allowed", name + " value '" + abbreviate(v) + "' is not allowed"));
            }
        }
        if (f.path("max_length").isNumber() && v.length() > f.get("max_length").asInt()) {
            errors.add(new FieldError(line, name, "too_long", name + " exceeds " + f.get("max_length").asInt() + " characters"));
        }
    }

    private static boolean isDate(String v, String format) {
        try {
            LocalDate.parse(v, DateTimeFormatter.ofPattern(format));
            return true;
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return false;
        }
    }

    /** CSV split honouring double quotes ("" escapes a quote). */
    public static List<String> splitCsv(String line, String delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (line.startsWith(delimiter, i)) {
                out.add(cur.toString());
                cur.setLength(0);
                i += delimiter.length() - 1;
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    static List<String> lines(String content) {
        List<String> out = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));
        while (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        return out;
    }

    private static String abbreviate(String v) {
        return v.length() > 40 ? v.substring(0, 40) + "..." : v;
    }
}
