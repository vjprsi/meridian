package io.meridian.payment.domain;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses bulk payment files into lines: CSV (header row, RFC 4180 quoting) and ISO 20022 pain.001
 * (CustomerCreditTransferInitiation, any version; namespace-agnostic, external entities disabled). Values are kept as
 * text; business validation happens per item so one bad line never rejects the whole file. File-level controls
 * (pain.001 NbOfTxs / CtrlSum) are checked here.
 */
public final class BulkFileParser {
    /** One line of the file. Amount stays textual so unparsable values surface as item validation errors. */
    public record Line(String creditorName, String accountIdType, String accountNumber, String bic, String clearingId,
                       String amount, String currency, String endToEndId, String remittance) {}

    /** File-level header data. */
    public record Parsed(List<Line> lines, Integer declaredCount, BigDecimal controlSum, String debtorIban,
                         String requestedExecutionDate, String categoryPurpose, Boolean batchBooking, String messageId) {}

    public static final class FileFormatException extends RuntimeException {
        private final String code;

        public FileFormatException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private BulkFileParser() {}

    public static Parsed parse(String format, String content) {
        if (content == null || content.isBlank()) {
            throw new FileFormatException("empty_file", "The file has no content");
        }
        return switch (format) {
            case "CSV" -> csv(content);
            case "PAIN_001" -> pain001(content);
            default -> throw new FileFormatException("unsupported_file_format",
                    "File format " + format + " cannot be uploaded; use CSV or PAIN_001 (or create with JSON items)");
        };
    }

    // ------------------------------------------------------------------ CSV

    static Parsed csv(String content) {
        List<List<String>> rows = csvRows(content.replace("﻿", ""));
        if (rows.isEmpty()) {
            throw new FileFormatException("empty_file", "The CSV file has no header row");
        }
        List<String> header = rows.getFirst().stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            idx.put(header.get(i), i);
        }
        for (String required : List.of("creditor_name", "creditor_account_number", "amount")) {
            if (!idx.containsKey(required)) {
                throw new FileFormatException("missing_csv_column", "CSV header must contain " + required);
            }
        }
        List<Line> lines = new ArrayList<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.stream().allMatch(String::isBlank)) {
                continue;
            }
            lines.add(new Line(col(r, idx, "creditor_name"), col(r, idx, "creditor_account_id_type"),
                    col(r, idx, "creditor_account_number"), col(r, idx, "creditor_agent_bic"),
                    col(r, idx, "creditor_agent_clearing_id"), col(r, idx, "amount"), col(r, idx, "currency"),
                    col(r, idx, "end_to_end_id"),
                    idx.containsKey("remittance_unstructured") ? col(r, idx, "remittance_unstructured")
                            : col(r, idx, "remittance")));
        }
        if (lines.isEmpty()) {
            throw new FileFormatException("empty_file", "The CSV file has no payment lines");
        }
        return new Parsed(lines, null, null, null, null, null, null, null);
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
    static List<List<String>> csvRows(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
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
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(f.toString());
                f.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                f.append(c);
            }
        }
        if (quoted) {
            throw new FileFormatException("invalid_csv", "Unterminated quoted field");
        }
        if (!f.isEmpty() || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ pain.001

    static Parsed pain001(String content) {
        Document doc = xml(content);
        Element root = first(doc.getDocumentElement(), "CstmrCdtTrfInitn");
        if (root == null) {
            throw new FileFormatException("invalid_pain001", "Document/CstmrCdtTrfInitn not found");
        }
        Element grp = first(root, "GrpHdr");
        Integer count = null;
        BigDecimal ctrl = null;
        String msgId = null;
        if (grp != null) {
            String n = text(grp, "NbOfTxs");
            count = n == null ? null : Integer.valueOf(n.trim());
            String c = text(grp, "CtrlSum");
            ctrl = c == null ? null : new BigDecimal(c.trim());
            msgId = text(grp, "MsgId");
        }
        List<Line> lines = new ArrayList<>();
        String debtorIban = null;
        String reqDate = null;
        String ctgy = null;
        Boolean batchBooking = null;
        for (Element pmtInf : children(root, "PmtInf")) {
            Element dbtrAcct = first(pmtInf, "DbtrAcct");
            if (dbtrAcct != null && debtorIban == null) {
                debtorIban = path(dbtrAcct, "Id", "IBAN");
            }
            Element reqd = first(pmtInf, "ReqdExctnDt");
            if (reqd != null && reqDate == null) {
                String dt = text(reqd, "Dt");
                reqDate = dt != null ? dt : reqd.getTextContent().trim();
            }
            if (ctgy == null) {
                ctgy = path(pmtInf, "PmtTpInf", "CtgyPurp", "Cd");
            }
            String bb = text(pmtInf, "BtchBookg");
            if (bb != null && batchBooking == null) {
                batchBooking = Boolean.parseBoolean(bb.trim());
            }
            for (Element tx : children(pmtInf, "CdtTrfTxInf")) {
                Element amt = first(first(tx, "Amt"), "InstdAmt");
                String iban = path(tx, "CdtrAcct", "Id", "IBAN");
                String other = path(tx, "CdtrAcct", "Id", "Othr", "Id");
                Element agent = first(first(tx, "CdtrAgt"), "FinInstnId");
                String bic = agent == null ? null : firstNonNull(text(agent, "BICFI"), text(agent, "BIC"));
                String clearing = agent == null ? null : path(agent, "ClrSysMmbId", "MmbId");
                lines.add(new Line(path(tx, "Cdtr", "Nm"), iban != null ? "IBAN" : "ACCOUNT_NUMBER",
                        iban != null ? iban : other, bic, clearing,
                        amt == null ? null : amt.getTextContent().trim(), amt == null ? null : amt.getAttribute("Ccy"),
                        path(tx, "PmtId", "EndToEndId"), path(tx, "RmtInf", "Ustrd")));
            }
        }
        if (lines.isEmpty()) {
            throw new FileFormatException("empty_file", "The pain.001 file has no CdtTrfTxInf");
        }
        if (count != null && count != lines.size()) {
            throw new FileFormatException("control_count_mismatch",
                    "GrpHdr/NbOfTxs is " + count + " but the file has " + lines.size() + " transactions");
        }
        if (ctrl != null) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Line l : lines) {
                try {
                    sum = sum.add(new BigDecimal(l.amount()));
                } catch (RuntimeException e) {
                    throw new FileFormatException("invalid_amount", "Unparsable amount " + l.amount());
                }
            }
            if (sum.compareTo(ctrl) != 0) {
                throw new FileFormatException("control_sum_mismatch",
                        "GrpHdr/CtrlSum is " + ctrl.toPlainString() + " but the transactions sum to " + sum.toPlainString());
            }
        }
        return new Parsed(lines, count, ctrl, debtorIban, reqDate, ctgy, batchBooking, msgId);
    }

    static Document xml(String content) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new FileFormatException("invalid_xml", "The file is not well-formed XML: " + e.getMessage());
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    static Element first(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        if (localName.equals(parent.getLocalName())) {
            return parent;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && localName.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    static String text(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e.getTextContent().trim();
            }
        }
        return null;
    }

    static String path(Element parent, String... names) {
        Element cur = parent;
        for (int i = 0; i < names.length - 1 && cur != null; i++) {
            Element next = null;
            for (Node n = cur.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && names[i].equals(e.getLocalName())) {
                    next = e;
                    break;
                }
            }
            cur = next;
        }
        return cur == null ? null : text(cur, names[names.length - 1]);
    }
}
