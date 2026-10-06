package io.meridian.statement.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link StatementDocument} as PDF (a minimal, valid single-font PDF), CSV (RFC 4180), ISO 20022
 * camt.053.001.08, SWIFT MT940, OFX 2.2 or JSON. Output is a pure function of the document, so checksums are stable.
 */
public final class StatementRenderer {
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");
    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int LINES_PER_PAGE = 60;

    public record Rendered(byte[] content, String contentType, String extension) {
        public String sha256() {
            return StatementRenderer.sha256(content);
        }
    }

    private StatementRenderer() {}

    public static Rendered render(StatementDocument d, String format) {
        return switch (format.toUpperCase(Locale.ROOT)) {
            case "CSV" -> new Rendered(csv(d).getBytes(StandardCharsets.UTF_8), "text/csv;charset=UTF-8", "csv");
            case "CAMT_053" -> new Rendered(camt053(d).getBytes(StandardCharsets.UTF_8), "application/xml", "xml");
            case "MT940" -> new Rendered(mt940(d).getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8", "sta");
            case "OFX" -> new Rendered(ofx(d).getBytes(StandardCharsets.UTF_8), "application/x-ofx", "ofx");
            case "JSON" -> new Rendered(json(d).getBytes(StandardCharsets.UTF_8), "application/json", "json");
            case "PDF" -> new Rendered(pdf(d), "application/pdf", "pdf");
            default -> throw new IllegalArgumentException("unsupported statement format " + format);
        };
    }

    // ------------------------------------------------------------------ CSV

    static String csv(StatementDocument d) {
        StringBuilder sb = new StringBuilder();
        sb.append("line_no,booking_date,value_date,description,reference,counterparty,direction,amount,running_balance,"
                + "currency,transaction_code\r\n");
        sb.append(row("0", d.periodStart().toString(), d.periodStart().toString(), "Opening balance", "", "", "", "",
                d.money(d.openingBalance()), d.currency(), ""));
        for (StatementDocument.Line l : d.lines()) {
            sb.append(row(Integer.toString(l.lineNo()), l.bookingDate().toString(), l.valueDate().toString(),
                    l.description(), nz(l.reference()), nz(l.counterparty()), l.direction(), d.money(l.amount()),
                    d.money(l.runningBalance()), d.currency(), nz(l.transactionCode())));
        }
        sb.append(row(Integer.toString(d.lines().size() + 1), d.periodEnd().toString(), d.periodEnd().toString(),
                "Closing balance", "", "", "", "", d.money(d.closingBalance()), d.currency(), ""));
        return sb.toString();
    }

    private static String row(String... cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells) {
            String v = c == null ? "" : c;
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            }
            out.add(v);
        }
        return String.join(",", out) + "\r\n";
    }

    // ------------------------------------------------------------------ camt.053

    static String camt053(StatementDocument d) {
        String ccy = d.currency();
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">\n<BkToCstmrStmt>\n");
        x.append("<GrpHdr><MsgId>STM-").append(d.statementId()).append("</MsgId><CreDtTm>")
                .append(d.generatedAt().toLocalDateTime().withNano(0)).append("</CreDtTm></GrpHdr>\n");
        x.append("<Stmt>\n<Id>").append(d.statementId()).append("</Id><ElctrncSeqNb>").append(d.statementNo())
                .append("</ElctrncSeqNb><CreDtTm>").append(d.generatedAt().toLocalDateTime().withNano(0)).append("</CreDtTm>\n");
        x.append("<FrToDt><FrDtTm>").append(d.periodStart()).append("T00:00:00</FrDtTm><ToDtTm>").append(d.periodEnd())
                .append("T23:59:59</ToDtTm></FrToDt>\n");
        x.append("<Acct><Id>");
        if (d.iban() != null) {
            x.append("<IBAN>").append(esc(d.iban())).append("</IBAN>");
        } else {
            x.append("<Othr><Id>").append(esc(d.accountNumber())).append("</Id></Othr>");
        }
        x.append("</Id><Ccy>").append(ccy).append("</Ccy></Acct>\n");
        balance(x, "OPBD", d, d.openingBalance(), d.periodStart());
        balance(x, "CLBD", d, d.closingBalance(), d.periodEnd());
        long creditCount = d.lines().stream().filter(l -> "CREDIT".equals(l.direction())).count();
        x.append("<TxsSummry><TtlNtries><NbOfNtries>").append(d.lines().size()).append("</NbOfNtries></TtlNtries>")
                .append("<TtlCdtNtries><NbOfNtries>").append(creditCount).append("</NbOfNtries><Sum>")
                .append(d.money(d.totalCredits())).append("</Sum></TtlCdtNtries><TtlDbtNtries><NbOfNtries>")
                .append(d.lines().size() - creditCount).append("</NbOfNtries><Sum>").append(d.money(d.totalDebits()))
                .append("</Sum></TtlDbtNtries></TxsSummry>\n");
        for (StatementDocument.Line l : d.lines()) {
            x.append("<Ntry><NtryRef>").append(l.lineNo()).append("</NtryRef><Amt Ccy=\"").append(ccy).append("\">")
                    .append(d.money(l.amount())).append("</Amt><CdtDbtInd>")
                    .append("CREDIT".equals(l.direction()) ? "CRDT" : "DBIT").append("</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>")
                    .append("<BookgDt><Dt>").append(l.bookingDate()).append("</Dt></BookgDt><ValDt><Dt>")
                    .append(l.valueDate()).append("</Dt></ValDt>");
            if (l.ledgerEntryId() != null) {
                x.append("<AcctSvcrRef>").append(esc(l.ledgerEntryId())).append("</AcctSvcrRef>");
            }
            x.append("<BkTxCd><Prtry><Cd>").append(esc(nz(l.transactionCode()))).append("</Cd></Prtry></BkTxCd>");
            x.append("<NtryDtls><TxDtls>");
            if (l.reference() != null) {
                x.append("<Refs><EndToEndId>").append(esc(l.reference())).append("</EndToEndId></Refs>");
            }
            x.append("<RmtInf><Ustrd>").append(esc(truncate(l.description(), 140))).append("</Ustrd></RmtInf>")
                    .append("</TxDtls></NtryDtls></Ntry>\n");
        }
        x.append("</Stmt>\n</BkToCstmrStmt>\n</Document>\n");
        return x.toString();
    }

    private static void balance(StringBuilder x, String type, StatementDocument d, BigDecimal amount, LocalDate date) {
        x.append("<Bal><Tp><CdOrPrtry><Cd>").append(type).append("</Cd></CdOrPrtry></Tp><Amt Ccy=\"").append(d.currency())
                .append("\">").append(d.money(amount.abs())).append("</Amt><CdtDbtInd>")
                .append(amount.signum() < 0 ? "DBIT" : "CRDT").append("</CdtDbtInd><Dt><Dt>").append(date)
                .append("</Dt></Dt></Bal>\n");
    }

    // ------------------------------------------------------------------ MT940

    static String mt940(StatementDocument d) {
        StringBuilder m = new StringBuilder();
        m.append(":20:STM").append(d.statementNo()).append("\r\n");
        m.append(":25:").append(d.iban() != null ? d.iban() : d.accountNumber()).append("\r\n");
        m.append(":28C:").append(d.statementNo()).append("/1\r\n");
        m.append(":60F:").append(mtBalance(d, d.openingBalance(), d.periodStart())).append("\r\n");
        for (StatementDocument.Line l : d.lines()) {
            m.append(":61:").append(l.valueDate().format(YYMMDD)).append(l.bookingDate().format(MMDD))
                    .append("CREDIT".equals(l.direction()) ? "C" : "D").append(mtAmount(d, l.amount()))
                    .append("NTRF").append(truncate(l.reference() != null ? l.reference() : "NONREF", 16)).append("\r\n");
            m.append(":86:").append(truncate(l.description().replace("\r", " ").replace("\n", " "), 390)).append("\r\n");
        }
        m.append(":62F:").append(mtBalance(d, d.closingBalance(), d.periodEnd())).append("\r\n-");
        return m.toString();
    }

    private static String mtBalance(StatementDocument d, BigDecimal amount, LocalDate date) {
        return (amount.signum() < 0 ? "D" : "C") + date.format(YYMMDD) + d.currency() + mtAmount(d, amount.abs());
    }

    private static String mtAmount(StatementDocument d, BigDecimal amount) {
        return d.money(amount).replace('.', ',');
    }

    // ------------------------------------------------------------------ OFX

    static String ofx(StatementDocument d) {
        StringBuilder o = new StringBuilder();
        o.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?OFX OFXHEADER=\"200\" VERSION=\"220\" SECURITY=\"NONE\""
                + " OLDFILEUID=\"NONE\" NEWFILEUID=\"NONE\"?>\n");
        o.append("<OFX><BANKMSGSRSV1><STMTTRNRS><TRNUID>").append(d.statementId()).append("</TRNUID>")
                .append("<STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS><STMTRS><CURDEF>").append(d.currency())
                .append("</CURDEF><BANKACCTFROM><ACCTID>").append(esc(d.iban() != null ? d.iban() : d.accountNumber()))
                .append("</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>\n<BANKTRANLIST><DTSTART>")
                .append(d.periodStart().format(OFX_DATE)).append("</DTSTART><DTEND>").append(d.periodEnd().format(OFX_DATE))
                .append("</DTEND>\n");
        for (StatementDocument.Line l : d.lines()) {
            boolean credit = "CREDIT".equals(l.direction());
            o.append("<STMTTRN><TRNTYPE>").append(credit ? "CREDIT" : "DEBIT").append("</TRNTYPE><DTPOSTED>")
                    .append(l.bookingDate().format(OFX_DATE)).append("</DTPOSTED><DTUSER>").append(l.valueDate().format(OFX_DATE))
                    .append("</DTUSER><TRNAMT>").append(credit ? "" : "-").append(d.money(l.amount())).append("</TRNAMT><FITID>")
                    .append(esc(l.ledgerEntryId() != null ? l.ledgerEntryId() : d.statementId() + "-" + l.lineNo()))
                    .append("</FITID><NAME>").append(esc(truncate(l.description(), 32))).append("</NAME></STMTTRN>\n");
        }
        o.append("</BANKTRANLIST><LEDGERBAL><BALAMT>").append(d.money(d.closingBalance())).append("</BALAMT><DTASOF>")
                .append(d.periodEnd().format(OFX_DATE)).append("</DTASOF></LEDGERBAL></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>\n");
        return o.toString();
    }

    // ------------------------------------------------------------------ JSON

    static String json(StatementDocument d) {
        StringBuilder j = new StringBuilder("{");
        field(j, "statement_id", d.statementId()).append(',');
        j.append("\"statement_no\":").append(d.statementNo()).append(',');
        field(j, "account_number", d.accountNumber()).append(',');
        field(j, "iban", d.iban()).append(',');
        field(j, "currency", d.currency()).append(',');
        field(j, "period_start", d.periodStart().toString()).append(',');
        field(j, "period_end", d.periodEnd().toString()).append(',');
        field(j, "opening_balance", d.money(d.openingBalance())).append(',');
        field(j, "total_credits", d.money(d.totalCredits())).append(',');
        field(j, "total_debits", d.money(d.totalDebits())).append(',');
        field(j, "closing_balance", d.money(d.closingBalance())).append(',');
        field(j, "interest_earned", d.money(d.interestEarned())).append(',');
        field(j, "fees_charged", d.money(d.feesCharged())).append(',');
        j.append("\"lines\":[");
        for (int i = 0; i < d.lines().size(); i++) {
            StatementDocument.Line l = d.lines().get(i);
            j.append(i == 0 ? "{" : ",{");
            j.append("\"line_no\":").append(l.lineNo()).append(',');
            field(j, "booking_date", l.bookingDate().toString()).append(',');
            field(j, "value_date", l.valueDate().toString()).append(',');
            field(j, "description", l.description()).append(',');
            field(j, "reference", l.reference()).append(',');
            field(j, "direction", l.direction()).append(',');
            field(j, "amount", d.money(l.amount())).append(',');
            field(j, "running_balance", d.money(l.runningBalance())).append(',');
            field(j, "transaction_code", l.transactionCode());
            j.append('}');
        }
        return j.append("]}").toString();
    }

    private static StringBuilder field(StringBuilder j, String name, String value) {
        j.append('"').append(name).append("\":");
        if (value == null) {
            return j.append("null");
        }
        j.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> j.append("\\\"");
                case '\\' -> j.append("\\\\");
                case '\n' -> j.append("\\n");
                case '\r' -> j.append("\\r");
                case '\t' -> j.append("\\t");
                default -> {
                    if (c < 0x20) {
                        j.append(String.format("\\u%04x", (int) c));
                    } else {
                        j.append(c);
                    }
                }
            }
        }
        return j.append('"');
    }

    // ------------------------------------------------------------------ PDF

    /** A minimal but valid PDF 1.4: Courier text, one page per 60 lines, correct xref offsets. */
    static byte[] pdf(StatementDocument d) {
        List<String> text = new ArrayList<>();
        text.add("ACCOUNT STATEMENT No. " + d.statementNo());
        text.add("Account: " + (d.iban() != null ? d.iban() : d.accountNumber()) + "   Currency: " + d.currency());
        text.add("Period: " + d.periodStart() + " to " + d.periodEnd());
        text.add("Opening balance: " + d.money(d.openingBalance()));
        text.add("");
        text.add(String.format("%-10s %-10s %-34s %14s %14s", "Booked", "Value", "Description", "Amount", "Balance"));
        for (StatementDocument.Line l : d.lines()) {
            String amount = ("CREDIT".equals(l.direction()) ? "" : "-") + d.money(l.amount());
            text.add(String.format("%-10s %-10s %-34s %14s %14s", l.bookingDate(), l.valueDate(),
                    truncate(l.description(), 34), amount, d.money(l.runningBalance())));
        }
        text.add("");
        text.add("Total credits: " + d.money(d.totalCredits()) + "   Total debits: " + d.money(d.totalDebits()));
        text.add("Interest earned: " + d.money(d.interestEarned()) + "   Fees charged: " + d.money(d.feesCharged()));
        text.add("Closing balance: " + d.money(d.closingBalance()));

        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < text.size(); i += LINES_PER_PAGE) {
            pages.add(text.subList(i, Math.min(text.size(), i + LINES_PER_PAGE)));
        }
        int pageCount = pages.size();
        // objects: 1 catalog, 2 pages, 3 font, then (page, content) pairs
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pageCount; p++) {
            kids.append(4 + p * 2).append(" 0 R ");
        }
        objects.add("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>");
        for (int p = 0; p < pageCount; p++) {
            StringBuilder stream = new StringBuilder("BT /F1 8 Tf 10 TL 36 806 Td\n");
            for (String line : pages.get(p)) {
                stream.append('(').append(pdfEscape(line)).append(") Tj T*\n");
            }
            stream.append("ET");
            int contentObj = 5 + p * 2;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> "
                    + "/Contents " + contentObj + " 0 R >>");
            objects.add("<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream");
        }
        StringBuilder out = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.length());
            out.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = out.length();
        out.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) {
            out.append(String.format("%010d 00000 n \n", off));
        }
        out.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref)
                .append("\n%%EOF\n");
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String pdfEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 32 || c > 126) {
                b.append('?');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ helpers

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
