package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SWIFT MT940 (customer statement) and MT942 (interim transaction report) messages, with or without the
 * {1:}{2:}{4: ... -} block envelope, one or several messages per file (pages of one statement: opening balance of
 * the first, closing balance of the last).
 *
 * <ul>
 *   <li>:20: transaction reference (statement id), :25: account, :28C: statement / sequence number</li>
 *   <li>:60F:/:60M: opening and :62F:/:62M: closing booked balance (MT940); :34F: floor limit and :90D: / :90C:
 *       number and sum of debit / credit entries (MT942 control totals)</li>
 *   <li>:61: value date, optional entry date, mark C / D / RC (reversal of credit, a debit) / RD (reversal of
 *       debit, a credit), optional funds code, amount (decimal comma), transaction type, account owner's reference
 *       (end-to-end id unless NONREF) // account servicing institution's reference, supplementary details</li>
 *   <li>:86: information to account owner: structured with ?NN sub-fields (German GVC: ?20-?29 / ?60-?63
 *       remittance with SEPA keywords EREF+, KREF+, MREF+, SVWZ+, ?31 IBAN, ?32-?33 name), slash keywords
 *       (/EREF/, /IREF/, /UETR/, /NAME/, /IBAN/, /REMI/, ...) or free text</li>
 * </ul>
 */
public final class Mt940Parser {
    private static final Pattern FIELD = Pattern.compile("^:(\\d{2}[A-Z]?):", Pattern.MULTILINE);
    private static final Pattern LINE_61 = Pattern.compile(
            "^(\\d{6})(\\d{4})?(RC|RD|EC|ED|C|D)([A-Z])?(\\d{1,15}(?:,\\d{0,4})?)([NSF][A-Z0-9]{3})(.*)$", Pattern.DOTALL);
    private static final Pattern BALANCE = Pattern.compile("^([CD])(\\d{6})([A-Z]{3})(\\d{1,15}(?:,\\d{0,4})?)$");
    private static final Pattern TOTAL_90 = Pattern.compile("^(\\d{1,5})([A-Z]{3})(\\d{1,15}(?:,\\d{0,4})?)$");
    private static final Pattern FLOOR_34 = Pattern.compile("^([A-Z]{3})([CD]?)(\\d{1,15}(?:,\\d{0,4})?)$");
    private static final Pattern SUBFIELD = Pattern.compile("\\?(\\d{2})");
    private static final List<String> SLASH_KEYS = List.of("EREF", "IREF", "KREF", "MARF", "CSID", "UETR", "NAME",
            "IBAN", "BIC", "REMI", "ORDP", "BENM", "PREF", "ADDR", "TRCD", "PURP", "RTRN", "ULTD", "ULTB", "CDTRREFTP",
            "CDTRREF", "USTD", "STRD", "CNTP", "RREF");
    private static final Pattern SLASH = Pattern.compile("/(" + String.join("|", SLASH_KEYS) + ")(?=/)");
    private static final Pattern SEPA_KEYWORD = Pattern.compile("(EREF|KREF|MREF|CRED|DEBT|SVWZ|ABWA|ABWE|IBAN|BIC)\\+");

    private Mt940Parser() {}

    public static ParsedStatement parse(String content) {
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String[]> fields = fields(envelopeBodies(text));
        if (fields.isEmpty()) {
            throw new ParsedStatement.ParseException("No MT940/MT942 fields (:20:, :61:, ...) found");
        }
        String statementId = null;
        String sequence = null;
        String account = null;
        BigDecimal opening = null;
        BigDecimal closing = null;
        LocalDate statementDate = null;
        String currency = null;
        BigDecimal declaredDebits = null;
        BigDecimal declaredCredits = null;
        List<StatementLine> lines = new ArrayList<>();
        Pending pending = null;
        int n = 0;
        for (String[] f : fields) {
            String tag = f[0];
            String v = f[1];
            switch (tag) {
                case "20" -> statementId = statementId == null ? v.trim() : statementId;
                case "25" -> account = account == null ? v.trim() : account;
                case "28C", "28" -> sequence = sequence == null ? v.trim() : sequence;
                case "60F", "60M" -> {
                    Matcher m = balance(v, tag);
                    currency = m.group(3);
                    if (opening == null) {
                        opening = signedAmount(m.group(1), m.group(4));
                    }
                }
                case "62F", "62M" -> {
                    Matcher m = balance(v, tag);
                    currency = currency == null ? m.group(3) : currency;
                    closing = signedAmount(m.group(1), m.group(4));
                    statementDate = yymmdd(m.group(2), tag);
                }
                case "34F" -> {
                    Matcher m = FLOOR_34.matcher(v.trim());
                    if (m.matches() && currency == null) {
                        currency = m.group(1);
                    }
                }
                case "90D", "90C" -> {
                    Matcher m = TOTAL_90.matcher(v.trim());
                    if (!m.matches()) {
                        throw new ParsedStatement.ParseException(":" + tag + ": is not <count><currency><amount>");
                    }
                    currency = currency == null ? m.group(2) : currency;
                    BigDecimal sum = amount(m.group(3));
                    if ("90D".equals(tag)) {
                        declaredDebits = declaredDebits == null ? sum : declaredDebits.add(sum);
                    } else {
                        declaredCredits = declaredCredits == null ? sum : declaredCredits.add(sum);
                    }
                }
                case "61" -> {
                    if (pending != null) {
                        lines.add(pending.toLine(currency));
                    }
                    n++;
                    pending = statementLine(v, n);
                }
                case "86" -> {
                    if (pending != null && pending.info == null) {
                        pending.info = v;
                    }
                }
                default -> { }
            }
        }
        if (pending != null) {
            lines.add(pending.toLine(currency));
        }
        if (currency == null && !lines.isEmpty()) {
            throw new ParsedStatement.ParseException("No currency: the message has no :60F:/:62F:/:34F:/:90D: field");
        }
        if (statementDate == null) {
            statementDate = lines.stream().map(StatementLine::valueDate).max(LocalDate::compareTo).orElse(null);
        }
        return new ParsedStatement(statementDate, opening, closing, lines, statementId, sequence, account,
                declaredDebits, declaredCredits);
    }

    /** Text of each {4: ... -} block, or the whole content when it has no envelope. */
    private static List<String> envelopeBodies(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int start = text.indexOf("{4:", i);
            if (start < 0) {
                break;
            }
            int end = text.indexOf("-}", start);
            out.add(text.substring(start + 3, end < 0 ? text.length() : end));
            if (end < 0) {
                break;
            }
            i = end + 2;
        }
        if (out.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    private static List<String[]> fields(List<String> bodies) {
        List<String[]> out = new ArrayList<>();
        for (String body : bodies) {
            Matcher m = FIELD.matcher(body);
            int lastStart = -1;
            String lastTag = null;
            while (m.find()) {
                if (lastTag != null) {
                    out.add(new String[] {lastTag, clean(body.substring(lastStart, m.start()))});
                }
                lastTag = m.group(1);
                lastStart = m.end();
            }
            if (lastTag != null) {
                out.add(new String[] {lastTag, clean(body.substring(lastStart))});
            }
        }
        return out;
    }

    /** Trims the field and drops a trailing message terminator line ("-"). */
    private static String clean(String v) {
        String s = v.strip();
        if (s.endsWith("\n-")) {
            s = s.substring(0, s.length() - 2).strip();
        } else if (s.equals("-")) {
            s = "";
        }
        return s;
    }

    private static Matcher balance(String v, String tag) {
        Matcher m = BALANCE.matcher(v.trim());
        if (!m.matches()) {
            throw new ParsedStatement.ParseException(":" + tag + ": is not <C|D><YYMMDD><currency><amount>: " + v.trim());
        }
        return m;
    }

    private static Pending statementLine(String v, int n) {
        String first = v;
        String supplementary = null;
        int nl = v.indexOf('\n');
        if (nl >= 0) {
            first = v.substring(0, nl);
            supplementary = v.substring(nl + 1).replace('\n', ' ').trim();
        }
        Matcher m = LINE_61.matcher(first.trim());
        if (!m.matches()) {
            throw new ParsedStatement.ParseException("Statement line " + n + " (:61:) is malformed: " + first.trim());
        }
        Pending p = new Pending();
        p.n = n;
        p.valueDate = yymmdd(m.group(1), "61");
        if (m.group(2) != null) {
            int month = Integer.parseInt(m.group(2).substring(0, 2));
            int day = Integer.parseInt(m.group(2).substring(2));
            try {
                LocalDate entry = LocalDate.of(p.valueDate.getYear(), month, day);
                // an entry date in January for a December value date (or vice versa) crosses the year end
                if (entry.isAfter(p.valueDate.plusMonths(6))) {
                    entry = entry.minusYears(1);
                } else if (entry.isBefore(p.valueDate.minusMonths(6))) {
                    entry = entry.plusYears(1);
                }
                p.bookingDate = entry;
            } catch (DateTimeException e) {
                throw new ParsedStatement.ParseException("Statement line " + n + ": invalid entry date " + m.group(2));
            }
        }
        String mark = m.group(3);
        p.reversal = mark.startsWith("R");
        p.direction = switch (mark) {
            case "C", "RD", "EC" -> StatementLine.Direction.CREDIT;
            default -> StatementLine.Direction.DEBIT;
        };
        p.amount = amount(m.group(5));
        if (p.amount.signum() == 0) {
            throw new ParsedStatement.ParseException("Statement line " + n + ": amount must not be zero");
        }
        p.transactionType = m.group(6);
        String refs = m.group(7);
        int slash = refs.indexOf("//");
        String ownerRef = slash >= 0 ? refs.substring(0, slash) : refs;
        String bankRef = slash >= 0 ? refs.substring(slash + 2) : null;
        ownerRef = ownerRef.trim();
        p.ownerRef = ownerRef.isEmpty() || "NONREF".equalsIgnoreCase(ownerRef) ? null : ownerRef;
        p.bankRef = bankRef == null || bankRef.isBlank() ? null : bankRef.trim();
        p.supplementary = supplementary == null || supplementary.isEmpty() ? null : supplementary;
        return p;
    }

    /** Fields of :86: information (structured or free text). */
    static Map<String, String> info(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String flat = raw.replace("\n", "");
        if (SUBFIELD.matcher(flat).find() && flat.indexOf('?') <= 4) {
            // GVC structure: 3-digit code then ?NN sub-fields
            Matcher m = SUBFIELD.matcher(flat);
            List<int[]> marks = new ArrayList<>();
            while (m.find()) {
                marks.add(new int[] {Integer.parseInt(m.group(1)), m.start(), m.end()});
            }
            StringBuilder remittance = new StringBuilder();
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < marks.size(); i++) {
                int code = marks.get(i)[0];
                String value = flat.substring(marks.get(i)[2], i + 1 < marks.size() ? marks.get(i + 1)[1] : flat.length());
                if ((code >= 20 && code <= 29) || (code >= 60 && code <= 63)) {
                    remittance.append(value);
                } else if (code == 31) {
                    out.put("counterparty_account", value.trim());
                } else if (code == 30) {
                    out.put("counterparty_bic", value.trim());
                } else if (code == 32 || code == 33) {
                    name.append(value);
                } else if (code == 0) {
                    out.put("posting_text", value.trim());
                }
            }
            if (!name.isEmpty()) {
                out.put("counterparty", name.toString().trim());
            }
            sepaKeywords(remittance.toString(), out);
            return out;
        }
        Matcher sm = SLASH.matcher(flat);
        List<int[]> marks = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        while (sm.find()) {
            marks.add(new int[] {sm.start(), sm.end() + 1}); // value starts after the closing slash
            keys.add(sm.group(1));
        }
        if (!marks.isEmpty() && marks.getFirst()[0] <= 1) {
            for (int i = 0; i < marks.size(); i++) {
                int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : flat.length();
                int begin = Math.min(marks.get(i)[1], end);
                String value = flat.substring(begin, end).replaceAll("/+$", "").replace("/", " ").trim();
                String key = keys.get(i);
                if (value.isEmpty()) {
                    continue;
                }
                switch (key) {
                    case "EREF" -> out.putIfAbsent("end_to_end_id", value);
                    case "IREF" -> out.putIfAbsent("instruction_id", value);
                    case "UETR" -> out.putIfAbsent("uetr", value);
                    case "NAME" -> out.putIfAbsent("counterparty", value);
                    case "IBAN" -> out.putIfAbsent("counterparty_account", value);
                    case "REMI", "USTD", "STRD", "CDTRREF" -> out.merge("narrative", value, (a, b) -> a + " " + b);
                    case "KREF", "PREF", "RREF" -> out.putIfAbsent("batch_ref", value);
                    default -> { }
                }
            }
            return out;
        }
        if (SEPA_KEYWORD.matcher(flat).find()) {
            sepaKeywords(flat, out);
            return out;
        }
        out.put("narrative", raw.replace('\n', ' ').trim());
        return out;
    }

    /** German SEPA keywords inside remittance text: EREF+ (end-to-end id), KREF+ (customer / batch ref), SVWZ+. */
    private static void sepaKeywords(String text, Map<String, String> out) {
        Matcher m = SEPA_KEYWORD.matcher(text);
        List<int[]> marks = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[] {m.start(), m.end()});
            keys.add(m.group(1));
        }
        if (marks.isEmpty()) {
            if (!text.isBlank()) {
                out.put("narrative", text.trim());
            }
            return;
        }
        String prefix = text.substring(0, marks.getFirst()[0]).trim();
        StringBuilder narrative = new StringBuilder(prefix);
        for (int i = 0; i < marks.size(); i++) {
            String value = text.substring(marks.get(i)[1], i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length()).trim();
            switch (keys.get(i)) {
                case "EREF" -> {
                    if (!"NOTPROVIDED".equals(value)) {
                        out.putIfAbsent("end_to_end_id", value);
                    }
                }
                case "KREF" -> out.putIfAbsent("batch_ref", value);
                case "IBAN" -> out.putIfAbsent("counterparty_account", value);
                case "SVWZ" -> narrative.append(narrative.isEmpty() ? "" : " ").append(value);
                default -> { }
            }
        }
        if (!narrative.isEmpty()) {
            out.put("narrative", narrative.toString().trim());
        }
    }

    private static LocalDate yymmdd(String s, String tag) {
        int yy = Integer.parseInt(s.substring(0, 2));
        try {
            return LocalDate.of(yy >= 80 ? 1900 + yy : 2000 + yy, Integer.parseInt(s.substring(2, 4)),
                    Integer.parseInt(s.substring(4, 6)));
        } catch (DateTimeException e) {
            throw new ParsedStatement.ParseException(":" + tag + ": invalid date " + s);
        }
    }

    private static BigDecimal amount(String s) {
        String v = s.replace(',', '.');
        if (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return new BigDecimal(v);
    }

    private static BigDecimal signedAmount(String mark, String amount) {
        BigDecimal v = amount(amount);
        return "D".equals(mark) ? v.negate() : v;
    }

    /** A :61: line waiting for its :86: information. */
    private static final class Pending {
        int n;
        LocalDate valueDate;
        LocalDate bookingDate;
        StatementLine.Direction direction;
        boolean reversal;
        BigDecimal amount;
        String transactionType;
        String ownerRef;
        String bankRef;
        String supplementary;
        String info;

        StatementLine toLine(String currency) {
            if (currency == null) {
                throw new ParsedStatement.ParseException("Statement line " + n + ": no currency (missing :60F:)");
            }
            Map<String, String> i = info(info);
            String e2e = i.getOrDefault("end_to_end_id", ownerRef);
            String narrative = i.get("narrative");
            if (narrative == null) {
                narrative = supplementary;
            }
            Map<String, String> raw = new LinkedHashMap<>();
            raw.put("amount", amount.toPlainString());
            raw.put("currency", currency);
            raw.put("mark", direction.name().substring(0, 1) + (reversal ? "R" : ""));
            raw.put("value_date", valueDate.toString());
            raw.put("transaction_type", transactionType);
            if (ownerRef != null) {
                raw.put("owner_ref", ownerRef);
            }
            if (bankRef != null) {
                raw.put("bank_ref", bankRef);
            }
            if (info != null) {
                raw.put("info", info.length() > 400 ? info.substring(0, 400) : info);
            }
            String batchRef = i.get("batch_ref");
            if (batchRef != null) {
                raw.put("batch_ref", batchRef);
            }
            return new StatementLine(bankRef, e2e, valueDate, bookingDate, direction, amount,
                    currency.toUpperCase(Locale.ROOT), i.get("counterparty"), narrative, raw,
                    new StatementLine.Details(i.get("instruction_id"), i.get("uetr"), i.get("counterparty_account"),
                            transactionType, reversal, null, null, null, batchRef, null));
        }
    }
}
