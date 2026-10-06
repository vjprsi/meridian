package io.meridian.sanctions.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses list files into entry records. CSV (RFC 4180, header row, multi-values separated by ';') with columns
 * external_id, entry_type, primary_name, aliases, date_of_birth, place_of_birth, nationality, countries, identifiers,
 * programs, pep_level, remarks, listed_on. JSON is parsed by the caller into the same field names.
 */
public final class ListFileParser {
    private ListFileParser() {}

    public static final Set<String> ENTRY_TYPES = Set.of("INDIVIDUAL", "ENTITY", "VESSEL", "AIRCRAFT", "COUNTRY");
    static final Set<String> MULTI = Set.of("aliases", "countries", "identifiers", "programs");

    /** One parsed row: field values (multi-valued fields as lists) or an error. */
    public record Row(int line, Map<String, Object> fields, String error) {}

    public static List<Row> csv(String content) {
        List<List<String>> records = records(content);
        List<Row> out = new ArrayList<>();
        if (records.isEmpty()) {
            return out;
        }
        List<String> header = records.getFirst().stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records.get(i);
            if (r.size() == 1 && r.getFirst().isBlank()) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < r.size(); c++) {
                String v = r.get(c).trim();
                if (v.isEmpty()) {
                    continue;
                }
                if (MULTI.contains(header.get(c))) {
                    List<String> parts = new ArrayList<>();
                    for (String p : v.split(";")) {
                        if (!p.isBlank()) {
                            parts.add(p.trim());
                        }
                    }
                    f.put(header.get(c), parts);
                } else {
                    f.put(header.get(c), v);
                }
            }
            out.add(validate(i + 1, f));
        }
        return out;
    }

    /** Validates and normalises one record (from CSV or JSON). */
    public static Row validate(int line, Map<String, Object> f) {
        Object id = f.get("external_id");
        Object name = f.get("primary_name");
        if (id == null || id.toString().isBlank()) {
            return new Row(line, f, "external_id is required");
        }
        if (name == null || name.toString().isBlank()) {
            return new Row(line, f, "primary_name is required");
        }
        String type = f.getOrDefault("entry_type", "INDIVIDUAL").toString().toUpperCase(Locale.ROOT);
        if (!ENTRY_TYPES.contains(type)) {
            return new Row(line, f, "unknown entry_type " + type);
        }
        f.put("entry_type", type);
        if (f.get("nationality") instanceof String n && n.length() != 2) {
            return new Row(line, f, "nationality must be an ISO 3166 alpha-2 code");
        }
        if (f.get("nationality") instanceof String n) {
            f.put("nationality", n.toUpperCase(Locale.ROOT));
        }
        if (f.get("countries") instanceof List<?> cs) {
            List<String> up = new ArrayList<>();
            for (Object c : cs) {
                up.add(c.toString().trim().toUpperCase(Locale.ROOT));
            }
            f.put("countries", up);
        }
        return new Row(line, f, null);
    }

    /** RFC 4180 records: quoted fields may contain separators, quotes ("") and line breaks. */
    static List<List<String>> records(String s) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            out.add(row);
        }
        return out;
    }
}
