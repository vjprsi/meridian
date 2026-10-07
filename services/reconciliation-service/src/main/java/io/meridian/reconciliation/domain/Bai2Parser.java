package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BAI2 (Cash Management Balance Reporting Specification v2, the US bank statement format) parser.
 *
 * <p>Records: {@code 01} file header, {@code 02} group header (as-of date, currency), {@code 03} account identifier
 * with summary amounts (type 010 opening ledger, 015 closing ledger), {@code 16} transaction detail, {@code 88}
 * continuation of the previous record, {@code 49} account trailer (control total = sum of the amounts of the account's
 * 03 and 16 records, record count), {@code 98} group and {@code 99} file trailers. Fields are comma-separated, a
 * record ends with {@code /} (the text field of a 16 record runs to the end of the record and may contain commas).
 * Amounts carry no decimal point: the implied decimals follow the currency (2; 0 for JPY / KRW / ...).
 *
 * <p>Transaction detail: type code 100-399 = credit, 400-699 = debit (890-999 non-monetary, skipped); funds type
 * {@code 0/1/2/Z} (no extra fields), {@code V} (value date + time: the line's value date), {@code S} (three
 * distributed amounts) or {@code D} (n availability pairs); then the bank reference (statement {@code externalRef}),
 * the customer reference (the originator's reference: end-to-end id) and free text (narrative; a UETR in it is
 * extracted). Opening / closing balances are returned when the file reports a single account, so the import checks
 * opening + movements = closing; account trailer control totals are verified.
 */
public final class Bai2Parser {
    private Bai2Parser() {}

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final Set<String> ZERO_DECIMALS = Set.of("JPY", "KRW", "CLP", "ISK", "VND", "XOF", "XAF", "UGX");
    private static final Pattern UETR = Pattern.compile(
            "(?i)\\b([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\\b");

    public static ParsedStatement parse(String content) {
        List<List<String>> records = records(content);
        if (records.isEmpty() || !"01".equals(records.getFirst().getFirst())) {
            throw new ParsedStatement.ParseException("Not a BAI2 file: the first record must be a 01 file header");
        }
        List<StatementLine> lines = new ArrayList<>();
        String groupCcy = null;
        LocalDate asOf = null;
        // 01: sender, receiver, creation date, creation time, file identification number, ...
        String fileId = trimToNull(field(records.getFirst(), 5));
        String account = null;
        String reported = null;
        String accountCcy = null;
        BigDecimal opening = null;
        BigDecimal closing = null;
        BigDecimal control = BigDecimal.ZERO;
        int accounts = 0;
        int n = 0;
        for (List<String> r : records) {
            n++;
            String code = r.getFirst();
            switch (code) {
                case "01", "98", "99" -> { }
                case "02" -> {
                    asOf = date(field(r, 4), n);
                    groupCcy = blank(field(r, 6)) ? groupCcy : field(r, 6).toUpperCase();
                }
                case "03" -> {
                    if (asOf == null) {
                        throw new ParsedStatement.ParseException("Record " + n + ": 03 account before a 02 group header");
                    }
                    accounts++;
                    account = field(r, 1);
                    reported = account;
                    accountCcy = blank(field(r, 2)) ? groupCcy : field(r, 2).toUpperCase();
                    if (accountCcy == null) {
                        throw new ParsedStatement.ParseException("Record " + n + ": no currency for account " + account);
                    }
                    control = BigDecimal.ZERO;
                    // summaries: type code, amount, item count, funds type (+ its extra fields)
                    int i = 3;
                    while (i < r.size() && !blank(field(r, i))) {
                        String type = field(r, i);
                        String amt = field(r, i + 1);
                        if (!blank(amt)) {
                            BigDecimal value = signedAmount(amt, accountCcy, n);
                            control = control.add(value);
                            if ("010".equals(type) && accounts == 1) {
                                opening = value;
                            } else if ("015".equals(type) && accounts == 1) {
                                closing = value;
                            }
                        }
                        i = skipFunds(r, i + 3, n);
                    }
                }
                case "16" -> {
                    if (account == null) {
                        throw new ParsedStatement.ParseException("Record " + n + ": 16 transaction before a 03 account");
                    }
                    StatementLine line = transaction(r, n, accountCcy, asOf);
                    String amt = field(r, 2);
                    if (!blank(amt)) {
                        control = control.add(amount(amt, accountCcy, n));
                    }
                    if (line != null) {
                        lines.add(line);
                    }
                }
                case "49" -> {
                    String declared = field(r, 1);
                    if (!blank(declared)) {
                        BigDecimal d = signedAmount(declared, accountCcy, n);
                        if (d.compareTo(control) != 0) {
                            throw new ParsedStatement.ParseException("Record " + n + ": account " + account
                                    + " control total " + d.toPlainString() + " != sum of its amounts "
                                    + control.toPlainString());
                        }
                    }
                    account = null;
                }
                default -> throw new ParsedStatement.ParseException("Record " + n + ": unknown BAI2 record code " + code);
            }
        }
        if (accounts != 1) {
            opening = null;
            closing = null;
        }
        return new ParsedStatement(asOf, opening, closing, lines, fileId, null, accounts == 1 ? reported : null,
                null, null);
    }

    private static StatementLine transaction(List<String> r, int n, String ccy, LocalDate asOf) {
        String type = field(r, 1);
        int typeCode;
        try {
            typeCode = Integer.parseInt(type);
        } catch (NumberFormatException e) {
            throw new ParsedStatement.ParseException("Record " + n + ": invalid type code " + type);
        }
        StatementLine.Direction dir;
        if (typeCode >= 100 && typeCode <= 399) {
            dir = StatementLine.Direction.CREDIT;
        } else if (typeCode >= 400 && typeCode <= 699) {
            dir = StatementLine.Direction.DEBIT;
        } else {
            return null; // 890-999: non-monetary information
        }
        if (blank(field(r, 2))) {
            throw new ParsedStatement.ParseException("Record " + n + ": transaction amount is missing");
        }
        BigDecimal amount = amount(field(r, 2), ccy, n);
        LocalDate value = asOf;
        String funds = field(r, 3);
        int i = 4;
        if ("V".equalsIgnoreCase(funds)) {
            value = date(field(r, 4), n);
            i = 6;
        } else if ("S".equalsIgnoreCase(funds)) {
            i = 7;
        } else if ("D".equalsIgnoreCase(funds)) {
            int pairs = Integer.parseInt(field(r, 4).isBlank() ? "0" : field(r, 4));
            i = 5 + 2 * pairs;
        }
        String bankRef = trimToNull(field(r, i));
        String customerRef = trimToNull(field(r, i + 1));
        StringBuilder text = new StringBuilder();
        for (int k = i + 2; k < r.size(); k++) {
            if (!text.isEmpty()) {
                text.append(',');
            }
            text.append(r.get(k));
        }
        String narrative = trimToNull(text.toString());
        String uetr = null;
        if (narrative != null) {
            Matcher m = UETR.matcher(narrative);
            if (m.find()) {
                uetr = m.group(1).toLowerCase();
            }
        }
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("type_code", type);
        raw.put("amount", amount.toPlainString());
        raw.put("currency", ccy);
        raw.put("value_date", value.toString());
        putIf(raw, "funds_type", funds);
        putIf(raw, "bank_reference", bankRef);
        putIf(raw, "customer_reference", customerRef);
        return new StatementLine(bankRef, customerRef, value, asOf, dir, amount, ccy, null, narrative, raw,
                new StatementLine.Details(null, uetr, null, type, false, null, null, null, null, null));
    }

    /** Index after a funds type field at {@code i} and its extra fields. */
    private static int skipFunds(List<String> r, int i, int n) {
        String f = field(r, i);
        if ("V".equalsIgnoreCase(f)) {
            return i + 3;
        }
        if ("S".equalsIgnoreCase(f)) {
            return i + 4;
        }
        if ("D".equalsIgnoreCase(f)) {
            String c = field(r, i + 1);
            int pairs = blank(c) ? 0 : Integer.parseInt(c);
            return i + 2 + 2 * pairs;
        }
        return i + 1;
    }

    /** Records as field lists: 88 continuations joined to their record, the trailing "/" removed. */
    static List<List<String>> records(String content) {
        List<List<String>> out = new ArrayList<>();
        for (String raw : content.replace("\r", "").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.endsWith("/")) {
                line = line.substring(0, line.length() - 1);
            }
            List<String> fields = new ArrayList<>(List.of(line.split(",", -1)));
            if ("88".equals(fields.getFirst())) {
                if (out.isEmpty()) {
                    throw new ParsedStatement.ParseException("88 continuation without a record to continue");
                }
                List<String> prev = out.getLast();
                String cont = line.length() > 3 ? line.substring(3) : "";
                if ("16".equals(prev.getFirst())) {
                    // continuation of the free text
                    String last = prev.removeLast();
                    prev.add(last.isEmpty() ? cont : last + " " + cont);
                } else {
                    prev.addAll(fields.subList(1, fields.size()));
                }
                continue;
            }
            out.add(fields);
        }
        return out;
    }

    private static BigDecimal amount(String s, String ccy, int n) {
        String v = s.strip();
        if (v.startsWith("+")) {
            v = v.substring(1);
        }
        if (!v.matches("\\d+")) {
            throw new ParsedStatement.ParseException("Record " + n + ": invalid amount " + s);
        }
        return new BigDecimal(v).movePointLeft(ZERO_DECIMALS.contains(ccy) ? 0 : 2);
    }

    private static BigDecimal signedAmount(String s, String ccy, int n) {
        String v = s.strip();
        boolean negative = v.startsWith("-");
        BigDecimal a = amount(negative ? v.substring(1) : v, ccy, n);
        return negative ? a.negate() : a;
    }

    private static LocalDate date(String s, int n) {
        try {
            return LocalDate.parse(s.strip(), YYMMDD);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ParsedStatement.ParseException("Record " + n + ": invalid date " + s + " (YYMMDD)");
        }
    }

    private static String field(List<String> r, int i) {
        return i < r.size() ? r.get(i).strip() : "";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static void putIf(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }
}
