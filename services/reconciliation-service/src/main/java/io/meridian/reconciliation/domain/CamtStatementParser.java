package io.meridian.reconciliation.domain;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses ISO 20022 camt.053 (statement) / camt.054 (notification) messages, namespace-agnostic. Reads the opening
 * (OPBD/PRCD) and closing (CLBD) booked balances and each {@code Ntry}: amount and currency, CRDT/DBIT, booking and
 * value dates, the servicer reference, the end-to-end id, the counterparty name and the remittance text.
 * DTDs and external entities are rejected.
 */
public final class CamtStatementParser {
    private CamtStatementParser() {}

    public static ParsedStatement parse(String xml) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ParsedStatement.ParseException("Not a well-formed camt message: " + e.getMessage(), e);
        }
        BigDecimal opening = null;
        BigDecimal closing = null;
        LocalDate statementDate = null;
        for (Element bal : children(doc.getDocumentElement(), "Bal")) {
            String code = text(bal, "Cd");
            BigDecimal amt = signed(bal);
            if ("OPBD".equals(code) || "PRCD".equals(code)) {
                opening = amt;
            } else if ("CLBD".equals(code)) {
                closing = amt;
                statementDate = date(first(bal, "Dt"));
            }
        }
        List<StatementLine> lines = new ArrayList<>();
        int n = 0;
        for (Element e : children(doc.getDocumentElement(), "Ntry")) {
            n++;
            Element amtEl = first(e, "Amt");
            if (amtEl == null) {
                throw new ParsedStatement.ParseException("Entry " + n + ": Amt is missing");
            }
            String ccy = amtEl.getAttribute("Ccy");
            if (ccy == null || !ccy.matches("[A-Z]{3}")) {
                throw new ParsedStatement.ParseException("Entry " + n + ": Amt/@Ccy is missing or invalid");
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(amtEl.getTextContent().trim());
            } catch (NumberFormatException ex) {
                throw new ParsedStatement.ParseException("Entry " + n + ": invalid amount");
            }
            String ind = text(e, "CdtDbtInd");
            StatementLine.Direction dir = "CRDT".equals(ind) ? StatementLine.Direction.CREDIT
                    : "DBIT".equals(ind) ? StatementLine.Direction.DEBIT : null;
            if (dir == null) {
                throw new ParsedStatement.ParseException("Entry " + n + ": CdtDbtInd must be CRDT or DBIT");
            }
            LocalDate booking = date(first(e, "BookgDt"));
            LocalDate value = date(first(e, "ValDt"));
            if (value == null) {
                value = booking;
            }
            if (value == null) {
                throw new ParsedStatement.ParseException("Entry " + n + ": ValDt or BookgDt is required");
            }
            String ref = text(e, "AcctSvcrRef");
            String e2e = text(e, "EndToEndId");
            if ("NOTPROVIDED".equals(e2e)) {
                e2e = null;
            }
            String counterparty = dir == StatementLine.Direction.CREDIT ? partyName(e, "Dbtr") : partyName(e, "Cdtr");
            String narrative = text(e, "Ustrd");
            if (narrative == null) {
                narrative = text(e, "AddtlNtryInf");
            }
            Map<String, String> raw = new LinkedHashMap<>();
            raw.put("amount", amount.toPlainString());
            raw.put("currency", ccy);
            raw.put("cdt_dbt_ind", ind);
            raw.put("value_date", value.toString());
            if (ref != null) {
                raw.put("acct_svcr_ref", ref);
            }
            if (e2e != null) {
                raw.put("end_to_end_id", e2e);
            }
            lines.add(new StatementLine(ref, e2e, value, booking, dir, amount, ccy, counterparty, narrative, raw));
        }
        if (statementDate == null) {
            statementDate = lines.stream().map(StatementLine::valueDate).max(LocalDate::compareTo).orElse(null);
        }
        return new ParsedStatement(statementDate, opening, closing, lines);
    }

    private static BigDecimal signed(Element bal) {
        Element amt = first(bal, "Amt");
        if (amt == null) {
            return null;
        }
        BigDecimal v = new BigDecimal(amt.getTextContent().trim());
        return "DBIT".equals(text(bal, "CdtDbtInd")) ? v.negate() : v;
    }

    private static String partyName(Element entry, String role) {
        Element party = first(entry, role);
        return party == null ? null : text(party, "Nm");
    }

    private static LocalDate date(Element holder) {
        if (holder == null) {
            return null;
        }
        String d = text(holder, "Dt");
        if (d == null) {
            d = text(holder, "DtTm");
            d = d == null ? null : d.substring(0, 10);
        }
        if (d == null && holder.getLocalName().equals("Dt")) {
            d = holder.getTextContent().trim();
        }
        return d == null ? null : LocalDate.parse(d.substring(0, 10));
    }

    private static List<Element> children(Element root, String localName) {
        NodeList nl = root.getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    private static Element first(Element root, String localName) {
        NodeList nl = root.getElementsByTagNameNS("*", localName);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    private static String text(Element root, String localName) {
        Node n = first(root, localName);
        if (n == null) {
            return null;
        }
        String t = n.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }
}
