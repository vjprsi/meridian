package io.meridian.cardclearing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parses simple Visa BASE II / Mastercard IPM-like presentment files: CSV with a header row (RFC 4180 quoting) or a
 * JSON array already turned into field maps by the caller. Field names are the clearing_record column names.
 * Validation is strict: one bad record fails the whole file (a scheme file is posted all-or-nothing at parse level).
 * Sensitive card data columns (PAN, CVV, track, PIN) are refused outright.
 */
public final class ClearingFileParser {
    public static final Set<String> RECORD_TYPES = Set.of("PRESENTMENT", "REFUND", "REVERSAL", "CHARGEBACK",
            "REPRESENTMENT", "FEE_COLLECTION", "FUNDS_DISBURSEMENT");
    static final Set<String> FORBIDDEN = Set.of("pan", "card_number", "primary_account_number", "cvv", "cvv2", "cvc",
            "track1", "track2", "pin", "pin_block");
    private static final Pattern CCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern MCC = Pattern.compile("\\d{4}");

    private ClearingFileParser() {}

    public record Result(List<ParsedRecord> records, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }

        static Result failed(String error) {
            return new Result(List.of(), List.of(error));
        }
    }

    public static Result parseCsv(String content) {
        List<List<String>> rows;
        try {
            rows = csv(content);
        } catch (IllegalArgumentException e) {
            return Result.failed(e.getMessage());
        }
        if (rows.isEmpty()) {
            return Result.failed("file contains no header row");
        }
        List<String> header = rows.getFirst().stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        List<Map<String, String>> maps = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() == 1 && r.getFirst().isBlank()) {
                continue;
            }
            if (r.size() != header.size()) {
                return Result.failed("row " + i + ": expected " + header.size() + " fields, found " + r.size());
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                m.put(header.get(c), r.get(c));
            }
            maps.add(m);
        }
        return parseRows(maps);
    }

    /** Validates field maps (from CSV or JSON) into records; collects every error with its row number. */
    public static Result parseRows(List<Map<String, String>> rows) {
        if (rows.isEmpty()) {
            return Result.failed("file contains no records");
        }
        List<ParsedRecord> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = new Row(i + 1, rows.get(i), errors);
            ParsedRecord p = r.parse();
            if (p != null) {
                out.add(p);
            }
        }
        return errors.isEmpty() ? new Result(out, List.of()) : new Result(List.of(), errors);
    }

    private static final class Row {
        final int n;
        final Map<String, String> f = new LinkedHashMap<>();
        final List<String> errors;
        final int before;

        Row(int n, Map<String, String> fields, List<String> errors) {
            this.n = n;
            fields.forEach((k, v) -> f.put(k.trim().toLowerCase(Locale.ROOT), v == null ? null : v.trim()));
            this.errors = errors;
            this.before = errors.size();
        }

        ParsedRecord parse() {
            for (String k : f.keySet()) {
                if (FORBIDDEN.contains(k)) {
                    err("field '" + k + "' is not accepted (sensitive card data; identify cards by card_id)");
                }
            }
            String type = upper(req("record_type"));
            if (type != null && !RECORD_TYPES.contains(type)) {
                err("record_type " + type + " is not one of " + RECORD_TYPES);
            }
            UUID cardId = uuid("card_id");
            LocalDate date = date("transaction_date");
            BigDecimal txAmount = amount("transaction_amount", true, true);
            String txCcy = ccy("transaction_currency", true);
            String billCcy = ccy("billing_currency", false);
            BigDecimal billAmount = amount("billing_amount", false, true);
            if (billCcy == null) {
                billCcy = txCcy;
            }
            if (billAmount == null) {
                if (billCcy != null && txCcy != null && !billCcy.equals(txCcy)) {
                    err("billing_amount is required when billing_currency differs from transaction_currency");
                }
                billAmount = txAmount;
            }
            String setCcy = ccy("settlement_currency", false);
            BigDecimal setAmount = amount("settlement_amount", false, true);
            if (setCcy == null) {
                setCcy = billCcy;
            }
            if (setAmount == null) {
                if (setCcy != null && billCcy != null && !setCcy.equals(billCcy)) {
                    err("settlement_amount is required when settlement_currency differs from billing_currency");
                }
                setAmount = billAmount;
            }
            String country = opt("merchant_country");
            if (country != null) {
                country = country.toUpperCase(Locale.ROOT);
                if (!COUNTRY.matcher(country).matches()) {
                    err("merchant_country must be ISO 3166 alpha-2");
                }
            }
            String mcc = opt("mcc");
            if (mcc != null && !MCC.matcher(mcc).matches()) {
                err("mcc must be 4 digits");
            }
            String ntid = len("network_transaction_id", 64);
            String arn = len("arn", 23);
            String rrn = len("rrn", 12);
            String authCode = len("auth_code", 6);
            String merchantId = len("merchant_id", 32);
            String merchantName = len("merchant_name", 64);
            String rateCode = len("interchange_rate_code", 16);
            BigDecimal interchange = amount("interchange_amount", false, false);
            BigDecimal schemeFee = amount("scheme_fee_amount", false, false);
            if (errors.size() > before) {
                return null;
            }
            Map<String, String> raw = new LinkedHashMap<>();
            f.forEach((k, v) -> {
                if (KNOWN.contains(k) && v != null && !v.isBlank()) {
                    raw.put(k, v);
                }
            });
            return new ParsedRecord(type, cardId, ntid, arn, rrn, authCode, merchantId, merchantName, mcc, country,
                    date, txAmount, txCcy, billAmount, billCcy, setAmount, setCcy, interchange, rateCode, schemeFee, raw);
        }

        void err(String m) {
            errors.add("row " + n + ": " + m);
        }

        String opt(String k) {
            String v = f.get(k);
            return v == null || v.isBlank() ? null : v;
        }

        String req(String k) {
            String v = opt(k);
            if (v == null) {
                err(k + " is required");
            }
            return v;
        }

        String len(String k, int max) {
            String v = opt(k);
            if (v != null && v.length() > max) {
                err(k + " exceeds " + max + " characters");
            }
            return v;
        }

        static String upper(String v) {
            return v == null ? null : v.toUpperCase(Locale.ROOT);
        }

        UUID uuid(String k) {
            String v = opt(k);
            if (v == null) {
                return null;
            }
            try {
                return UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                err(k + " is not a UUID");
                return null;
            }
        }

        LocalDate date(String k) {
            String v = req(k);
            if (v == null) {
                return null;
            }
            try {
                return LocalDate.parse(v);
            } catch (DateTimeParseException e) {
                err(k + " must be an ISO date (YYYY-MM-DD)");
                return null;
            }
        }

        BigDecimal amount(String k, boolean required, boolean positive) {
            String v = required ? req(k) : opt(k);
            if (v == null) {
                return null;
            }
            try {
                BigDecimal d = new BigDecimal(v);
                if (positive ? d.signum() <= 0 : d.signum() < 0) {
                    err(k + " must be " + (positive ? "greater than 0" : "0 or more"));
                    return null;
                }
                return d;
            } catch (NumberFormatException e) {
                err(k + " is not a decimal amount");
                return null;
            }
        }

        String ccy(String k, boolean required) {
            String v = required ? req(k) : opt(k);
            if (v == null) {
                return null;
            }
            v = v.toUpperCase(Locale.ROOT);
            if (!CCY.matcher(v).matches()) {
                err(k + " must be an ISO 4217 code");
                return null;
            }
            return v;
        }
    }

    static final Set<String> KNOWN = Set.of("record_type", "card_id", "network_transaction_id", "arn", "rrn",
            "auth_code", "merchant_id", "merchant_name", "mcc", "merchant_country", "transaction_date",
            "transaction_amount", "transaction_currency", "billing_amount", "billing_currency", "settlement_amount",
            "settlement_currency", "interchange_amount", "interchange_rate_code", "scheme_fee_amount");

    /** RFC 4180 CSV: comma separated, double-quoted fields may contain commas, quotes ("") and line breaks. */
    static List<List<String>> csv(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null) {
            return rows;
        }
        String s = content.startsWith("﻿") ? content.substring(1) : content;
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    quoted = true;
                    any = true;
                }
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    any = true;
                }
                case '\r' -> { }
                case '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    if (any || !(row.size() == 1 && row.getFirst().isEmpty())) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                    any = false;
                }
                default -> {
                    cell.append(c);
                    any = true;
                }
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        if (any || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
