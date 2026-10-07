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
 * Parses delimited statement and scheme settlement files. The column mapping comes from the source's parser_config:
 * {@code columns} maps a record field ({@link #FIELDS}) to a header name (or a 0-based index when the file has no
 * header). Without a direction column the amount is signed (negative = DEBIT). Options: delimiter, has_header,
 * skip_lines (preamble), date_format, decimal_separator ("," for 1.234,56), currency, credit_values / debit_values,
 * reversal_values (values of the reversal column meaning "reversal"), and a {@code preset}
 * ({@link #SCHEME_SETTLEMENT}) supplying default columns for card scheme / acquirer settlement files.
 */
public final class CsvStatementParser {

    /** Default columns of a scheme / acquirer net settlement report. */
    public static final Map<String, String> SCHEME_SETTLEMENT = Map.of(
            "value_date", "settlement_date", "amount", "net_amount", "currency", "currency",
            "external_ref", "settlement_id", "batch_ref", "settlement_id", "charges_amount", "fee_amount",
            "original_amount", "gross_amount", "narrative", "description", "batch_count", "transaction_count");

    /** Parsing options. Unmapped fields default to a header of the same name. */
    public record Config(char delimiter, boolean hasHeader, Map<String, String> columns, DateTimeFormatter dateFormat,
                         String defaultCurrency, Set<String> creditValues, Set<String> debitValues,
                         char decimalSeparator, int skipLines, Set<String> reversalValues,
                         Map<String, String> presetColumns) {
        public static final Set<String> REVERSAL_VALUES = Set.of("Y", "YES", "TRUE", "1", "R", "REV", "REVERSAL");

        public Config(char delimiter, boolean hasHeader, Map<String, String> columns, DateTimeFormatter dateFormat,
                      String defaultCurrency, Set<String> creditValues, Set<String> debitValues) {
            this(delimiter, hasHeader, columns, dateFormat, defaultCurrency, creditValues, debitValues, '.', 0,
                    REVERSAL_VALUES, Map.of());
        }

        public static Config defaults(String defaultCurrency) {
            return new Config(',', true, Map.of(), DateTimeFormatter.ISO_LOCAL_DATE, defaultCurrency,
                    Set.of("C", "CR", "CRDT", "CREDIT"), Set.of("D", "DR", "DBIT", "DEBIT"));
        }
    }

    public static final List<String> FIELDS = List.of("value_date", "amount", "currency", "direction", "external_ref",
            "end_to_end_id", "booking_date", "counterparty", "narrative", "instruction_id", "uetr",
            "counterparty_account", "transaction_code", "reversal", "charges_amount", "original_amount",
            "original_currency", "batch_ref", "batch_count");

    private CsvStatementParser() {}

    public static ParsedStatement parse(String content, Config cfg) {
        String[] rows = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int lineNo = 0;
        List<String> header = null;
        List<StatementLine> lines = new ArrayList<>();
        Map<String, Integer> index = null;
        for (String row : rows) {
            lineNo++;
            if (lineNo <= cfg.skipLines() || row.isBlank()) {
                continue;
            }
            List<String> cells = split(row, cfg.delimiter());
            if (cfg.hasHeader() && header == null) {
                header = cells.stream().map(s -> s.trim().replace("﻿", "")).toList();
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
            boolean configured = cfg.columns().containsKey(field);
            String source = configured ? cfg.columns().get(field)
                    : cfg.presetColumns().getOrDefault(field, header == null ? null : field);
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
                } else if (configured) {
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
            BigDecimal amount = number(required(raw, "amount", lineNo), cfg);
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
            String rev = blankToNull(raw.get("reversal"));
            String charges = blankToNull(raw.get("charges_amount"));
            String original = blankToNull(raw.get("original_amount"));
            String count = blankToNull(raw.get("batch_count"));
            BigDecimal chargesAmt = charges == null ? null : number(charges, cfg).abs();
            BigDecimal originalAmt = original == null ? null : number(original, cfg).abs();
            String originalCcy = blankToNull(raw.get("original_currency"));
            if (originalAmt != null && originalCcy == null) {
                originalCcy = currency;
            }
            if (originalAmt != null && originalAmt.compareTo(amount) == 0 && currency.equals(originalCcy)) {
                originalAmt = null;
                originalCcy = null;
            }
            return new StatementLine(blankToNull(raw.get("external_ref")), blankToNull(raw.get("end_to_end_id")),
                    LocalDate.parse(required(raw, "value_date", lineNo), cfg.dateFormat()),
                    booking == null ? null : LocalDate.parse(booking, cfg.dateFormat()), direction, amount, currency,
                    blankToNull(raw.get("counterparty")), blankToNull(raw.get("narrative")), raw,
                    new StatementLine.Details(blankToNull(raw.get("instruction_id")), blankToNull(raw.get("uetr")),
                            blankToNull(raw.get("counterparty_account")), blankToNull(raw.get("transaction_code")),
                            rev != null && cfg.reversalValues().contains(rev.toUpperCase(Locale.ROOT)),
                            chargesAmt == null || chargesAmt.signum() == 0 ? null : chargesAmt, originalAmt, originalCcy,
                            blankToNull(raw.get("batch_ref")), count == null ? null : Integer.valueOf(count)));
        } catch (NumberFormatException e) {
            throw new ParsedStatement.ParseException("Line " + lineNo + ": invalid number in '" + raw + "'");
        } catch (DateTimeParseException e) {
            throw new ParsedStatement.ParseException("Line " + lineNo + ": invalid date (" + e.getParsedString() + ")");
        }
    }

    private static BigDecimal number(String s, Config cfg) {
        String v = s.replace(" ", "").replace(" ", "");
        if (cfg.decimalSeparator() == ',') {
            v = v.replace(".", "").replace(',', '.');
        } else {
            v = v.replace(",", "");
        }
        if (v.startsWith("(") && v.endsWith(")")) {
            v = "-" + v.substring(1, v.length() - 1);
        }
        return new BigDecimal(v);
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
