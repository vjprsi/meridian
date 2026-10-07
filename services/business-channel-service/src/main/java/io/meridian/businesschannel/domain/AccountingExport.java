package io.meridian.businesschannel.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Accounting exports of the posted entries of one account: a flat CSV for spreadsheets and bookkeeping imports, and an
 * ISO 20022 camt.053.001.08 (BankToCustomerStatement) document. Entries are of a deposit account (a liability of the
 * bank): CREDIT increases the customer's balance (CRDT), DEBIT decreases it (DBIT).
 */
public final class AccountingExport {
    /** One posted entry. */
    public record Entry(String id, String journalEntryId, long accountVersion, String direction, BigDecimal amount,
                        String currency, LocalDate bookingDate, LocalDate valueDate, BigDecimal balanceAfter,
                        String reference, String description) {
        public BigDecimal signed() {
            return "CREDIT".equals(direction) ? amount : amount.negate();
        }
    }

    /** The account the statement is for. */
    public record Account(String id, String accountNumber, String iban, String currency, String bic, String ownerName) {}

    private AccountingExport() {}

    public static List<Entry> ordered(List<Entry> entries) {
        return entries.stream().sorted(Comparator.comparingLong(Entry::accountVersion)
                .thenComparing(Entry::bookingDate, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    public static String csv(List<Entry> entries) {
        StringBuilder sb = new StringBuilder("booking_date,value_date,entry_id,journal_entry_id,direction,amount,"
                + "signed_amount,currency,balance_after,reference,description\r\n");
        for (Entry e : ordered(entries)) {
            sb.append(String.join(",", str(e.bookingDate()), str(e.valueDate()), cell(e.id()), cell(e.journalEntryId()),
                    cell(e.direction()), e.amount().toPlainString(), e.signed().toPlainString(), cell(e.currency()),
                    e.balanceAfter() == null ? "" : e.balanceAfter().toPlainString(), cell(e.reference()),
                    cell(e.description()))).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * camt.053 for the period; opening balance = balance before the first entry, closing = after the last. Without
     * entries both are {@code balanceWithoutEntries} (the caller's current posted balance).
     */
    public static String camt053(String statementId, Account account, LocalDate from, LocalDate to, List<Entry> entries,
                                 BigDecimal balanceWithoutEntries, OffsetDateTime created) {
        List<Entry> list = ordered(entries);
        BigDecimal opening;
        BigDecimal closing;
        if (list.isEmpty() || list.getFirst().balanceAfter() == null || list.getLast().balanceAfter() == null) {
            opening = balanceWithoutEntries == null ? BigDecimal.ZERO : balanceWithoutEntries;
            closing = opening;
        } else {
            opening = list.getFirst().balanceAfter().subtract(list.getFirst().signed());
            closing = list.getLast().balanceAfter();
        }
        BigDecimal credits = list.stream().filter(e -> "CREDIT".equals(e.direction())).map(Entry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debits = list.stream().filter(e -> !"CREDIT".equals(e.direction())).map(Entry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String ts = created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String ccy = esc(account.currency());
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">\n");
        x.append(" <BkToCstmrStmt>\n");
        x.append("  <GrpHdr><MsgId>").append(esc(statementId)).append("</MsgId><CreDtTm>").append(ts)
                .append("</CreDtTm></GrpHdr>\n");
        x.append("  <Stmt>\n");
        x.append("   <Id>").append(esc(statementId)).append("</Id>\n");
        x.append("   <CreDtTm>").append(ts).append("</CreDtTm>\n");
        x.append("   <FrToDt><FrDtTm>").append(from).append("T00:00:00Z</FrDtTm><ToDtTm>").append(to)
                .append("T23:59:59Z</ToDtTm></FrToDt>\n");
        x.append("   <Acct><Id>");
        if (account.iban() != null && !account.iban().isBlank()) {
            x.append("<IBAN>").append(esc(account.iban())).append("</IBAN>");
        } else {
            x.append("<Othr><Id>").append(esc(account.accountNumber())).append("</Id></Othr>");
        }
        x.append("</Id><Ccy>").append(ccy).append("</Ccy>");
        if (account.ownerName() != null) {
            x.append("<Ownr><Nm>").append(esc(account.ownerName())).append("</Nm></Ownr>");
        }
        if (account.bic() != null && !account.bic().isBlank()) {
            x.append("<Svcr><FinInstnId><BICFI>").append(esc(account.bic())).append("</BICFI></FinInstnId></Svcr>");
        }
        x.append("</Acct>\n");
        balance(x, "OPBD", opening, ccy, from);
        balance(x, "CLBD", closing, ccy, to);
        x.append("   <TxsSummry><TtlNtries><NbOfNtries>").append(list.size()).append("</NbOfNtries></TtlNtries>")
                .append("<TtlCdtNtries><NbOfNtries>").append(list.stream().filter(e -> "CREDIT".equals(e.direction())).count())
                .append("</NbOfNtries><Sum>").append(credits.toPlainString()).append("</Sum></TtlCdtNtries>")
                .append("<TtlDbtNtries><NbOfNtries>").append(list.stream().filter(e -> !"CREDIT".equals(e.direction())).count())
                .append("</NbOfNtries><Sum>").append(debits.toPlainString()).append("</Sum></TtlDbtNtries></TxsSummry>\n");
        for (Entry e : list) {
            x.append("   <Ntry>");
            x.append("<NtryRef>").append(esc(e.id())).append("</NtryRef>");
            x.append("<Amt Ccy=\"").append(esc(e.currency() == null ? account.currency() : e.currency())).append("\">")
                    .append(e.amount().toPlainString()).append("</Amt>");
            x.append("<CdtDbtInd>").append("CREDIT".equals(e.direction()) ? "CRDT" : "DBIT").append("</CdtDbtInd>");
            x.append("<Sts><Cd>BOOK</Cd></Sts>");
            if (e.bookingDate() != null) {
                x.append("<BookgDt><Dt>").append(e.bookingDate()).append("</Dt></BookgDt>");
            }
            if (e.valueDate() != null) {
                x.append("<ValDt><Dt>").append(e.valueDate()).append("</Dt></ValDt>");
            }
            x.append("<AcctSvcrRef>").append(esc(e.journalEntryId())).append("</AcctSvcrRef>");
            x.append("<BkTxCd><Prtry><Cd>").append("CREDIT".equals(e.direction()) ? "CREDIT" : "DEBIT")
                    .append("</Cd></Prtry></BkTxCd>");
            String info = e.description() != null ? e.description() : e.reference();
            if (e.reference() != null) {
                x.append("<NtryDtls><TxDtls><Refs><EndToEndId>").append(esc(trim(e.reference(), 35)))
                        .append("</EndToEndId></Refs></TxDtls></NtryDtls>");
            }
            if (info != null) {
                x.append("<AddtlNtryInf>").append(esc(trim(info, 500))).append("</AddtlNtryInf>");
            }
            x.append("</Ntry>\n");
        }
        x.append("  </Stmt>\n </BkToCstmrStmt>\n</Document>\n");
        return x.toString();
    }

    private static void balance(StringBuilder x, String code, BigDecimal amount, String ccy, LocalDate date) {
        x.append("   <Bal><Tp><CdOrPrtry><Cd>").append(code).append("</Cd></CdOrPrtry></Tp><Amt Ccy=\"").append(ccy)
                .append("\">").append(amount.abs().toPlainString()).append("</Amt><CdtDbtInd>")
                .append(amount.signum() < 0 ? "DBIT" : "CRDT").append("</CdtDbtInd><Dt><Dt>").append(date)
                .append("</Dt></Dt></Bal>\n");
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** CSV cell with RFC 4180 quoting; cells starting with = + - @ are prefixed with ' (spreadsheet formula injection). */
    static String cell(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || !s.equals(v)) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
