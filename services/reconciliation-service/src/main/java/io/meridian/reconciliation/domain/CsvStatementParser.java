package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses delimited statement files. The column mapping comes from the source's parser_config:
 * {@code columns} maps a record field (value_date, amount, currency, direction, external_ref, end_to_end_id,
 * booking_date, counterparty, narrative) to a header name (or a 0-based index when the file has no header).
 * Without a direction column the amount is signed (negative = DEBIT).
 */
public final class CsvStatementParser {

    /** Parsing options. Unmapped fields default to a header of the same name. */
    public record Config(char delimiter, boolean hasHeader, Map<String, String> columns, DateTimeFormatter dateFormat,
                         String defaultCurrency, Set<String> creditValues, Set<String> debitValues) {

        public static Config defaults(String defaultCurrency) {
            return new Config(',', true, Map.of(), DateTimeFormatter.ISO_LOCAL_DATE, defaultCurrency,
                    Set.of("C", "CR", "CRDT", "CREDIT"), Set.of("D", "DR", "DBIT", "DEBIT"));
        }
    }

    private static final List<String> FIELDS = List.of("value_date", "amount", "currency", "direction", "external_ref",
            "end_to_end_id", "booking_date", "counterparty", "narrative");

    private CsvStatementParser() {}

    public static ParsedStatement parse(String content, Config cfg) {
        String[] rows = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int lineNo = 0;
        List<String> header = null;
        List<StatementLine> lines = new ArrayList<>();
        Map<String, Integer> index = null;
        for (String row : rows) {
            lineNo++;
            if (row.isBlank()) {
                continue;
            }
            List<String> cells = split(row, cfg.delimiter());
            if (cfg.hasHeader() && header == null) {
                header = cells.stream().map(String::trim).toList();
                index = resolveColumns(header, cfg);
                continue;
            }
            if (index == null) {
                index = resolveColumns(null, cfg);
            }
            lines.add(line(cells, index, cfg, lineNo));
        }
        LocalDate statementDate = lines.stream().map(StatementLine::valueDate).max(LocalDate::compareTo).orElse(null);
        return new ParsedStatement(statementDate, null, null, lines);
    }

    private static Map<String, Integer> resolveColumns(List<String> header, Config cfg) {
        Map<String, Integer> index = new HashMap<>();
        for (String field : FIELDS) {
            String source = cfg.columns().getOrDefault(field, header == null ? null : field);
            if (source == null) {
                continue;
            }
            if (header == null) {
                try {
                    index.put(field, Integer.parseInt(source));
                } catch (NumberFormatException e) {
                    throw new ParsedStatement.ParseException("Column for " + field + " must be an index when the file has no header");
                }
            } else {
                int i = header.indexOf(source);
                if (i >= 0) {
                    index.put(field, i);
                } else if (cfg.columns().containsKey(field)) {
                    throw new ParsedStatement.ParseException("Configured column '" + source + "' for " + field
                            + " is not in the header " + header);
                }
            }
        }
        for (String required : List.of("value_date", "amount")) {
            if (!index.containsKey(required)) {
                throw new ParsedStatement.ParseException("No column for required field " + required);
            }
        }
        return index;
    }

    private static StatementLine line(List<String> cells, Map<String, Integer> index, Config cfg, int lineNo) {
        Map<String, String> raw = new LinkedHashMap<>();
        index.forEach((f, i) -> raw.put(f, i < cells.size() ? cells.get(i).trim() : ""));
        try {
            BigDecimal amount = new BigDecimal(required(raw, "amount", lineNo).replace(" ", ""));
            StatementLine.Direction direction;
            String dc = blankToNull(raw.get("direction"));
            if (dc != null) {
                String v = dc.toUpperCase(Locale.ROOT);
                if (cfg.creditValues().contains(v)) {
                    direction = StatementLine.Direction.CREDIT;
                } else if (cfg.debitValues().contains(v)) {
                    direction = StatementLine.Direction.DEBIT;
                } else {
                    throw new ParsedStatement.ParseException("Line " + lineNo + ": unknown direction '" + dc + "'");
                }
                amount = amount.abs();
            } else {
                direction = amount.signum() < 0 ? StatementLine.Direction.DEBIT : StatementLine.Direction.CREDIT;
                amount = amount.abs();
            }
            if (amount.signum() == 0) {
                throw new ParsedStatement.ParseException("Line " + lineNo + ": amount must not be zero");
            }
            String currency = blankToNull(raw.get("currency"));
            if (currency == null) {
                currency = cfg.defaultCurrency();
            }
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new ParsedStatement.ParseException("Line " + lineNo + ": missing or invalid currency");
            }
            String booking = blankToNull(raw.get("booking_date"));
            return new StatementLine(blankToNull(raw.get("external_ref")), blankToNull(raw.get("end_to_end_id")),
                    LocalDate.parse(required(raw, "value_date", lineNo), cfg.dateFormat()),
                    booking == null ? null : LocalDate.parse(booking, cfg.dateFormat()), direction, amount, currency,
                    blankToNull(raw.get("counterparty")), blankToNull(raw.get("narrative")), raw);
        } catch (NumberFormatException e) {
            throw new ParsedStatement.ParseException("Line " + lineNo + ": invalid amount '" + raw.get("amount") + "'");
        } catch (DateTimeParseException e) {
            throw new ParsedStatement.ParseException("Line " + lineNo + ": invalid date (" + e.getParsedString() + ")");
        }
    }

    private static String required(Map<String, String> raw, String field, int lineNo) {
        String v = blankToNull(raw.get(field));
        if (v == null) {
            throw new ParsedStatement.ParseException("Line " + lineNo + ": " + field + " is empty");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** RFC 4180 style split of one line: quoted cells may contain the delimiter and doubled quotes. */
    static List<String> split(String row, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
