package io.meridian.routing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;

/**
 * Builds an ISO 20022 pacs.008.001.08 FI-to-FI customer credit transfer for one payment, plus the settlement
 * summary file of a batch. All text is XML-escaped; amounts are formatted with the currency's minor units.
 */
public final class Pacs008Builder {
    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    public static final String MESSAGE_TYPE = "pacs.008.001.08";

    /** Payment data needed for the message. Optional fields may be null. */
    public record Transfer(String messageId, OffsetDateTime createdAt, String instructionId, String endToEndId,
                           String transactionId, String uetr, BigDecimal amount, String currency,
                           LocalDate settlementDate, String chargeBearer, String instructingAgentBic,
                           String debtorName, String debtorAccount, String debtorAccountType, String debtorAgentBic,
                           String creditorName, String creditorAccount, String creditorAccountType,
                           String creditorAgentBic, String creditorAgentClearingId, String purposeCode,
                           String remittance) {}

    private Pacs008Builder() {}

    public static String build(Transfer t) {
        require(t.messageId(), "message_id");
        require(t.endToEndId(), "end_to_end_id");
        require(t.currency(), "currency");
        require(t.debtorName(), "debtor_name");
        require(t.creditorName(), "creditor_name");
        require(t.creditorAccount(), "creditor_account_number");
        if (t.amount() == null || t.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        String amount = amount(t.amount(), t.currency());
        StringBuilder x = new StringBuilder(2048);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<Document xmlns=\"").append(NAMESPACE).append("\">\n");
        x.append(" <FIToFICstmrCdtTrf>\n");
        x.append("  <GrpHdr>\n");
        el(x, 3, "MsgId", t.messageId());
        el(x, 3, "CreDtTm", t.createdAt().withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        el(x, 3, "NbOfTxs", "1");
        x.append("   <SttlmInf>\n");
        el(x, 4, "SttlmMtd", "CLRG");
        x.append("   </SttlmInf>\n");
        if (t.instructingAgentBic() != null) {
            x.append("   <InstgAgt><FinInstnId><BICFI>").append(esc(t.instructingAgentBic()))
                    .append("</BICFI></FinInstnId></InstgAgt>\n");
        }
        x.append("  </GrpHdr>\n");
        x.append("  <CdtTrfTxInf>\n");
        x.append("   <PmtId>\n");
        el(x, 4, "InstrId", t.instructionId());
        el(x, 4, "EndToEndId", t.endToEndId());
        el(x, 4, "TxId", t.transactionId());
        el(x, 4, "UETR", t.uetr());
        x.append("   </PmtId>\n");
        x.append("   <IntrBkSttlmAmt Ccy=\"").append(esc(t.currency())).append("\">").append(amount)
                .append("</IntrBkSttlmAmt>\n");
        el(x, 3, "IntrBkSttlmDt", t.settlementDate() == null ? null : t.settlementDate().toString());
        el(x, 3, "ChrgBr", t.chargeBearer() == null ? "SHAR" : t.chargeBearer());
        party(x, "Dbtr", t.debtorName());
        account(x, "DbtrAcct", t.debtorAccount(), t.debtorAccountType());
        agent(x, "DbtrAgt", t.debtorAgentBic() != null ? t.debtorAgentBic() : t.instructingAgentBic(), null);
        agent(x, "CdtrAgt", t.creditorAgentBic(), t.creditorAgentClearingId());
        party(x, "Cdtr", t.creditorName());
        account(x, "CdtrAcct", t.creditorAccount(), t.creditorAccountType());
        if (t.purposeCode() != null) {
            x.append("   <Purp><Cd>").append(esc(t.purposeCode())).append("</Cd></Purp>\n");
        }
        if (t.remittance() != null) {
            x.append("   <RmtInf><Ustrd>").append(esc(truncate(t.remittance(), 140))).append("</Ustrd></RmtInf>\n");
        }
        x.append("  </CdtTrfTxInf>\n");
        x.append(" </FIToFICstmrCdtTrf>\n");
        x.append("</Document>\n");
        return x.toString();
    }

    /** One line of a settlement summary file. */
    public record BatchLine(String messageId, String endToEndId, String direction, BigDecimal amount) {}

    /** Settlement file of a closed batch: header with control sums and net position, one entry per message. */
    public static String settlementFile(String fileId, String railCode, String cycleId, LocalDate date, String currency,
                                        int debitCount, BigDecimal debitTotal, int creditCount, BigDecimal creditTotal,
                                        BigDecimal net, OffsetDateTime createdAt, List<BatchLine> lines) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<SettlementBatchFile xmlns=\"urn:meridian:payments:settlement-batch:1\">\n");
        x.append(" <Hdr>\n");
        el(x, 2, "FileId", fileId);
        el(x, 2, "Rail", railCode);
        el(x, 2, "CycleId", cycleId);
        el(x, 2, "SttlmDt", date.toString());
        el(x, 2, "CreDtTm", createdAt.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        el(x, 2, "Ccy", currency);
        el(x, 2, "DbtCnt", String.valueOf(debitCount));
        el(x, 2, "DbtSum", amount(debitTotal, currency));
        el(x, 2, "CdtCnt", String.valueOf(creditCount));
        el(x, 2, "CdtSum", amount(creditTotal, currency));
        el(x, 2, "NetPos", amount(net, currency));
        x.append(" </Hdr>\n");
        for (BatchLine l : lines) {
            x.append(" <Ntry>");
            x.append("<MsgId>").append(esc(l.messageId())).append("</MsgId>");
            if (l.endToEndId() != null) {
                x.append("<EndToEndId>").append(esc(l.endToEndId())).append("</EndToEndId>");
            }
            x.append("<Dir>").append(esc(l.direction())).append("</Dir>");
            x.append("<Amt>").append(l.amount() == null ? "0" : amount(l.amount(), currency)).append("</Amt>");
            x.append("</Ntry>\n");
        }
        x.append("</SettlementBatchFile>\n");
        return x.toString();
    }

    /** Amount with the currency's minor units (HALF_EVEN); unknown currencies keep two decimals. */
    public static String amount(BigDecimal amount, String currency) {
        int digits = 2;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            // keep 2
        }
        return amount.setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
    }

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    private static void party(StringBuilder x, String tag, String name) {
        x.append("   <").append(tag).append("><Nm>").append(esc(truncate(name, 140))).append("</Nm></").append(tag)
                .append(">\n");
    }

    private static void account(StringBuilder x, String tag, String account, String type) {
        if (account == null) {
            return;
        }
        String acct = account.replaceAll("\\s+", "");
        boolean iban = "IBAN".equals(type) || type == null && acct.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
        x.append("   <").append(tag).append("><Id>");
        if (iban) {
            x.append("<IBAN>").append(esc(acct.toUpperCase(java.util.Locale.ROOT))).append("</IBAN>");
        } else {
            x.append("<Othr><Id>").append(esc(truncate(acct, 34))).append("</Id></Othr>");
        }
        x.append("</Id></").append(tag).append(">\n");
    }

    private static void agent(StringBuilder x, String tag, String bic, String clearingId) {
        if (bic == null && clearingId == null) {
            return;
        }
        x.append("   <").append(tag).append("><FinInstnId>");
        if (bic != null) {
            x.append("<BICFI>").append(esc(bic)).append("</BICFI>");
        }
        if (clearingId != null) {
            x.append("<ClrSysMmbId><MmbId>").append(esc(clearingId)).append("</MmbId></ClrSysMmbId>");
        }
        x.append("</FinInstnId></").append(tag).append(">\n");
    }

    private static void el(StringBuilder x, int indent, String tag, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        x.append(" ".repeat(indent)).append('<').append(tag).append('>').append(esc(value)).append("</").append(tag)
                .append(">\n");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " is required for pacs.008");
        }
    }
}
