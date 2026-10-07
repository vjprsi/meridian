package io.meridian.businesschannel.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bulk payment and payroll CSV files: parsing (header row, RFC 4180 quoting) and line validation, and the canonical
 * CSV handed to payment-service on release. Columns: {@code creditor_name}, {@code creditor_account_number} and
 * {@code amount} (required); {@code creditor_account_id_type}, {@code creditor_agent_bic},
 * {@code creditor_agent_clearing_id}, {@code currency} and {@code remittance} (or {@code remittance_unstructured})
 * optional. A file-level problem (no header, missing column, too many lines) throws {@link FileProblem}; line problems
 * are collected per line and field so the user can fix the file.
 */
public final class BatchCsv {
    public static final List<String> COLUMNS = List.of("creditor_name", "creditor_account_id_type",
            "creditor_account_number", "creditor_agent_bic", "creditor_agent_clearing_id", "amount", "remittance");
    static final Set<String> ID_TYPES = Set.of("IBAN", "BBAN", "ACCOUNT_NUMBER", "WALLET", "PROXY");
    static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    static final Pattern ACCOUNT = Pattern.compile("[A-Z0-9+._-]{1,64}");
    static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}");

    /** A file that cannot be processed at all. */
    public static final class FileProblem extends RuntimeException {
        private final String code;

        FileProblem(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** One validation error: line number (1 = first payment line), field, stable code and message. */
    public record LineError(int line, String field, String code, String message) {}

    /** The validated batch: normalised lines (column -> value), errors, number of invalid lines and the total. */
    public record Batch(List<Map<String, String>> lines, List<LineError> errors, int invalidLines, BigDecimal total) {}

    private BatchCsv() {}

    public static Batch parse(String content, String currency, int maxLines) {
        List<List<String>> rows = rows(content.replace("﻿", ""));
        if (rows.isEmpty()) {
            throw new FileProblem("empty_file", "The CSV file has no header row");
        }
        Map<String, Integer> idx = new HashMap<>();
        List<String> header = rows.getFirst();
        for (int i = 0; i < header.size(); i++) {
            idx.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        for (String required : List.of("creditor_name", "creditor_account_number", "amount")) {
            if (!idx.containsKey(required)) {
                throw new FileProblem("missing_csv_column", "The CSV header must contain " + required);
            }
        }
        List<List<String>> body = rows.subList(1, rows.size()).stream()
                .filter(r -> !r.stream().allMatch(String::isBlank)).toList();
        if (body.isEmpty()) {
            throw new FileProblem("empty_file", "The CSV file has no payment lines");
        }
        if (body.size() > maxLines) {
            throw new FileProblem("too_many_lines", "A batch has at most " + maxLines + " lines");
        }
        List<Map<String, String>> lines = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int invalid = 0;
        int n = 0;
        for (List<String> r : body) {
            n++;
            int before = errors.size();
            Map<String, String> l = new LinkedHashMap<>();
            String name = col(r, idx, "creditor_name");
            if (name == null) {
                errors.add(new LineError(n, "creditor_name", "missing_field", "Beneficiary name is required"));
            } else if (name.length() > 140) {
                errors.add(new LineError(n, "creditor_name", "too_long", "Beneficiary name has at most 140 characters"));
            }
            l.put("creditor_name", name);
            String number = col(r, idx, "creditor_account_number");
            number = number == null ? null : number.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            String type = col(r, idx, "creditor_account_id_type");
            type = type == null ? null : type.toUpperCase(Locale.ROOT);
            if (number == null) {
                errors.add(new LineError(n, "creditor_account_number", "missing_field", "Beneficiary account is required"));
            } else if (!ACCOUNT.matcher(number).matches()) {
                errors.add(new LineError(n, "creditor_account_number", "invalid_account",
                        "Beneficiary account has invalid characters or is longer than 64"));
            }
            if (type == null && number != null) {
                type = IBAN.matcher(number).matches() ? "IBAN" : "ACCOUNT_NUMBER";
            }
            if (type != null && !ID_TYPES.contains(type)) {
                errors.add(new LineError(n, "creditor_account_id_type", "invalid_value",
                        "Account identifier type must be one of " + ID_TYPES));
            } else if ("IBAN".equals(type) && number != null && !validIban(number)) {
                errors.add(new LineError(n, "creditor_account_number", "invalid_iban", "The IBAN is not valid"));
            }
            l.put("creditor_account_id_type", type);
            l.put("creditor_account_number", number);
            String bic = col(r, idx, "creditor_agent_bic");
            bic = bic == null ? null : bic.toUpperCase(Locale.ROOT);
            if (bic != null && !BIC.matcher(bic).matches()) {
                errors.add(new LineError(n, "creditor_agent_bic", "invalid_bic", "The BIC is not valid"));
            }
            l.put("creditor_agent_bic", bic);
            String clearing = col(r, idx, "creditor_agent_clearing_id");
            if (clearing != null && clearing.length() > 35) {
                errors.add(new LineError(n, "creditor_agent_clearing_id", "too_long", "Clearing id has at most 35 characters"));
            }
            l.put("creditor_agent_clearing_id", clearing);
            String amountText = col(r, idx, "amount");
            BigDecimal amount = null;
            try {
                amount = amountText == null ? null : new BigDecimal(amountText.replace(",", ""));
            } catch (NumberFormatException e) {
                amount = null;
            }
            if (amount == null || amount.signum() <= 0) {
                errors.add(new LineError(n, "amount", "invalid_amount", "Amount is missing, not a number or not positive"));
            } else if (amount.stripTrailingZeros().scale() > 2) {
                errors.add(new LineError(n, "amount", "invalid_amount", "Amount has at most 2 decimals"));
            }
            l.put("amount", amount == null ? amountText : amount.toPlainString());
            String ccy = col(r, idx, "currency");
            if (ccy != null && !ccy.equalsIgnoreCase(currency)) {
                errors.add(new LineError(n, "currency", "currency_mismatch",
                        "Every line must be in the debit account currency " + currency));
            }
            String remittance = idx.containsKey("remittance_unstructured") ? col(r, idx, "remittance_unstructured")
                    : col(r, idx, "remittance");
            if (remittance != null && remittance.length() > 140) {
                errors.add(new LineError(n, "remittance", "too_long", "Remittance has at most 140 characters"));
            }
            l.put("remittance", remittance);
            if (errors.size() > before) {
                invalid++;
            } else {
                total = total.add(amount);
            }
            lines.add(l);
        }
        return new Batch(lines, errors, invalid, total);
    }

    /** The canonical CSV of valid lines (payment-service upload format). */
    public static String render(List<Map<String, String>> lines, String currency) {
        StringBuilder sb = new StringBuilder("creditor_name,creditor_account_id_type,creditor_account_number,"
                + "creditor_agent_bic,creditor_agent_clearing_id,amount,currency,remittance_unstructured\r\n");
        for (Map<String, String> l : lines) {
            sb.append(String.join(",", quote(l.get("creditor_name")), quote(l.get("creditor_account_id_type")),
                    quote(l.get("creditor_account_number")), quote(l.get("creditor_agent_bic")),
                    quote(l.get("creditor_agent_clearing_id")), quote(l.get("amount")), quote(currency),
                    quote(l.get("remittance")))).append("\r\n");
        }
        return sb.toString();
    }

    static String quote(String v) {
        if (v == null) {
            return "";
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    static boolean validIban(String iban) {
        if (!IBAN.matcher(iban).matches()) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static String col(List<String> row, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        if (i == null || i >= row.size()) {
            return null;
        }
        String v = row.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    /** RFC 4180 rows: commas, double-quoted fields with "" escapes and embedded newlines. */
    static List<List<String>> rows(String s) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    f.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    quoted = true;
                    any = true;
                }
                case ',' -> {
                    row.add(f.toString());
                    f.setLength(0);
                    any = true;
                }
                case '\r' -> { }
                case '\n' -> {
                    row.add(f.toString());
                    f.setLength(0);
                    out.add(row);
                    row = new ArrayList<>();
                    any = false;
                }
                default -> {
                    f.append(c);
                    any = true;
                }
            }
        }
        if (quoted) {
            throw new FileProblem("invalid_csv", "Unterminated quoted field");
        }
        if (any || f.length() > 0) {
            row.add(f.toString());
            out.add(row);
        }
        return out;
    }
}
