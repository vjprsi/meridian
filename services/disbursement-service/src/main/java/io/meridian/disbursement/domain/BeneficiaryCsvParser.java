package io.meridian.disbursement.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a beneficiary file (RFC 4180 CSV: quoted fields with embedded delimiters, quotes and line breaks; comma or
 * semicolon delimiter). The first record is a header when it names the columns (msisdn and amount are required;
 * full_name, national_id, beneficiary_reference and narrative are optional, with common aliases); otherwise columns
 * are positional in that order. Row-level problems never abort the file: the row is returned with an error so it is
 * stored as an INVALID line and reported back.
 */
public final class BeneficiaryCsvParser {
    public static final String ROW_ERROR_PREFIX = "Row error: ";

    /** One data row. {@code error} is null when the row parsed cleanly. */
    public record Row(int lineNo, String msisdn, BigDecimal amount, String fullName, String nationalId,
                      String beneficiaryReference, String narrative, String error) {
        public boolean valid() {
            return error == null;
        }
    }

    /** The file cannot be read as a beneficiary file at all (no usable header / columns, too many rows). */
    public static final class FileFormatException extends RuntimeException {
        public FileFormatException(String message) {
            super(message);
        }
    }

    static final List<String> POSITIONAL = List.of("msisdn", "amount", "full_name", "national_id",
            "beneficiary_reference", "narrative");
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        for (String c : POSITIONAL) {
            ALIASES.put(c, c);
        }
        ALIASES.put("phone", "msisdn");
        ALIASES.put("mobile", "msisdn");
        ALIASES.put("phone_number", "msisdn");
        ALIASES.put("mobile_number", "msisdn");
        ALIASES.put("name", "full_name");
        ALIASES.put("beneficiary_name", "full_name");
        ALIASES.put("fullname", "full_name");
        ALIASES.put("id_number", "national_id");
        ALIASES.put("nationalid", "national_id");
        ALIASES.put("reference", "beneficiary_reference");
        ALIASES.put("beneficiary_id", "beneficiary_reference");
        ALIASES.put("description", "narrative");
    }

    private final String defaultCallingCode;
    private final int maxRows;

    public BeneficiaryCsvParser(String defaultCallingCode, int maxRows) {
        this.defaultCallingCode = defaultCallingCode;
        this.maxRows = maxRows;
    }

    public List<Row> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new FileFormatException("The file is empty");
        }
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        char delimiter = detectDelimiter(text);
        List<List<String>> records = records(text, delimiter);
        records.removeIf(r -> r.stream().allMatch(String::isBlank));
        if (records.isEmpty()) {
            throw new FileFormatException("The file has no rows");
        }
        Map<String, Integer> columns = header(records.getFirst());
        int start = 1;
        if (columns == null) {
            columns = new HashMap<>();
            for (int i = 0; i < POSITIONAL.size(); i++) {
                columns.put(POSITIONAL.get(i), i);
            }
            start = 0;
        }
        if (!columns.containsKey("msisdn") || !columns.containsKey("amount")) {
            throw new FileFormatException("The header must contain msisdn and amount columns");
        }
        if (records.size() - start == 0) {
            throw new FileFormatException("The file has a header but no beneficiary rows");
        }
        if (records.size() - start > maxRows) {
            throw new FileFormatException("The file has more than " + maxRows + " rows");
        }
        List<Row> rows = new ArrayList<>();
        int lineNo = 0;
        for (int i = start; i < records.size(); i++) {
            rows.add(row(++lineNo, records.get(i), columns));
        }
        return rows;
    }

    private Row row(int lineNo, List<String> rec, Map<String, Integer> cols) {
        String rawMsisdn = cell(rec, cols, "msisdn");
        String rawAmount = cell(rec, cols, "amount");
        String fullName = cell(rec, cols, "full_name");
        String nationalId = cell(rec, cols, "national_id");
        String reference = cell(rec, cols, "beneficiary_reference");
        String narrative = cell(rec, cols, "narrative");
        List<String> errors = new ArrayList<>();
        Optional<String> msisdn = Msisdn.normalize(rawMsisdn, defaultCallingCode);
        if (rawMsisdn == null) {
            errors.add("msisdn is missing");
        } else if (msisdn.isEmpty()) {
            errors.add("msisdn " + rawMsisdn + " is not a valid E.164 number");
        }
        BigDecimal amount = null;
        if (rawAmount == null) {
            errors.add("amount is missing");
        } else {
            try {
                amount = new BigDecimal(rawAmount.replace(",", "").replace(" ", ""));
                if (amount.signum() <= 0) {
                    errors.add("amount must be positive");
                } else if (amount.stripTrailingZeros().scale() > 2) {
                    errors.add("amount has more than 2 decimals");
                }
            } catch (NumberFormatException e) {
                errors.add("amount " + rawAmount + " is not a number");
                amount = null;
            }
        }
        String error = errors.isEmpty() ? null : truncate(ROW_ERROR_PREFIX + String.join("; ", errors), 255);
        return new Row(lineNo, msisdn.orElse(rawMsisdn == null ? null : truncate(rawMsisdn, 20)), amount,
                truncate(fullName, 200), truncate(nationalId, 64), truncate(reference, 64), truncate(narrative, 255), error);
    }

    private static String cell(List<String> rec, Map<String, Integer> cols, String name) {
        Integer i = cols.get(name);
        if (i == null || i >= rec.size()) {
            return null;
        }
        String v = rec.get(i).strip();
        return v.isEmpty() ? null : v;
    }

    private static Map<String, Integer> header(List<String> first) {
        Map<String, Integer> cols = new HashMap<>();
        boolean named = false;
        for (int i = 0; i < first.size(); i++) {
            String key = first.get(i).strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            String canonical = ALIASES.get(key);
            if (canonical != null) {
                named = true;
                cols.putIfAbsent(canonical, i);
            }
        }
        return named ? cols : null;
    }

    static char detectDelimiter(String text) {
        int nl = text.indexOf('\n');
        String firstLine = nl < 0 ? text : text.substring(0, nl);
        long commas = firstLine.chars().filter(c -> c == ',').count();
        long semis = firstLine.chars().filter(c -> c == ';').count();
        return semis > commas ? ';' : ',';
    }

    /** RFC 4180 record splitter. */
    static List<List<String>> records(String text, char delimiter) {
        List<List<String>> out = new ArrayList<>();
        List<String> rec = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                rec.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                rec.add(field.toString());
                field.setLength(0);
                out.add(rec);
                rec = new ArrayList<>();
            } else {
                field.append(c);
            }
            i++;
        }
        if (!field.isEmpty() || !rec.isEmpty()) {
            rec.add(field.toString());
            out.add(rec);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
