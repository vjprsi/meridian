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
 * Parses ISO 20022 camt.053 (statement), camt.054 (debit / credit notification) and camt.052 (account report)
 * messages, namespace- and version-agnostic (001.02 to 001.13). Per {@code Stmt} / {@code Ntfctn} / {@code Rpt}:
 * identification (Id, ElctrncSeqNb, Acct/Id), opening (OPBD / PRCD) and closing (CLBD) booked balances. Per booked
 * {@code Ntry} (pending / information entries are skipped): amount, CRDT/DBIT, reversal indicator, booking and value
 * dates, servicer reference, bank transaction code, charges and, from a single {@code TxDtls}: end-to-end id,
 * instruction id, UETR, counterparty name and account, remittance information and the instructed amount. An entry
 * with several {@code TxDtls} is a batch booking (batch reference = PmtInfId / MsgId, batch count).
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
            f.setXIncludeAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ParsedStatement.ParseException("Not a well-formed camt message: " + e.getMessage(), e);
        }
        List<Element> containers = new ArrayList<>();
        for (String name : List.of("Stmt", "Ntfctn", "Rpt")) {
            containers.addAll(descendants(doc.getDocumentElement(), name));
        }
        if (containers.isEmpty()) {
            throw new ParsedStatement.ParseException("No Stmt, Ntfctn or Rpt element: not a camt.052/053/054 message");
        }
        BigDecimal opening = null;
        BigDecimal closing = null;
        LocalDate statementDate = null;
        List<StatementLine> lines = new ArrayList<>();
        int n = 0;
        for (Element c : containers) {
            for (Element bal : children(c, "Bal")) {
                String code = text(path(bal, "Tp", "CdOrPrtry"), "Cd");
                BigDecimal amt = signed(bal);
                if (("OPBD".equals(code) || "PRCD".equals(code)) && opening == null) {
                    opening = amt;
                } else if ("CLBD".equals(code)) {
                    closing = amt;
                    statementDate = date(child(bal, "Dt"));
                }
            }
            for (Element e : children(c, "Ntry")) {
                n++;
                StatementLine line = entry(e, n);
                if (line != null) {
                    lines.add(line);
                }
            }
        }
        if (containers.size() > 1) {
            // balances of several statements in one message cannot be checked as one sequence
            opening = null;
            closing = null;
        }
        if (statementDate == null) {
            statementDate = lines.stream().map(StatementLine::valueDate).max(LocalDate::compareTo).orElse(null);
        }
        Element first = containers.getFirst();
        String statementId = text(first, "Id");
        String seq = text(first, "ElctrncSeqNb");
        if (seq == null) {
            seq = text(first, "LglSeqNb");
        }
        Element acctId = path(first, "Acct", "Id");
        String account = acctId == null ? null : firstText(acctId, "IBAN", "Id");
        return new ParsedStatement(statementDate, opening, closing, lines, statementId, seq, account, null, null);
    }

    private static StatementLine entry(Element e, int n) {
        String status = text(e, "Sts");
        Element sts = child(e, "Sts");
        if (sts != null && child(sts, "Cd") != null) {
            status = text(sts, "Cd");
        }
        if ("PDNG".equals(status) || "INFO".equals(status)) {
            return null;
        }
        Element amtEl = child(e, "Amt");
        if (amtEl == null) {
            throw new ParsedStatement.ParseException("Entry " + n + ": Amt is missing");
        }
        String ccy = amtEl.getAttribute("Ccy");
        if (ccy == null || !ccy.matches("[A-Z]{3}")) {
            throw new ParsedStatement.ParseException("Entry " + n + ": Amt/@Ccy is missing or invalid");
        }
        BigDecimal amount = decimal(amtEl, "Entry " + n + ": invalid amount");
        String ind = text(e, "CdtDbtInd");
        StatementLine.Direction dir = "CRDT".equals(ind) ? StatementLine.Direction.CREDIT
                : "DBIT".equals(ind) ? StatementLine.Direction.DEBIT : null;
        if (dir == null) {
            throw new ParsedStatement.ParseException("Entry " + n + ": CdtDbtInd must be CRDT or DBIT");
        }
        boolean reversal = "true".equalsIgnoreCase(text(e, "RvslInd"));
        LocalDate booking = date(child(e, "BookgDt"));
        LocalDate value = date(child(e, "ValDt"));
        if (value == null) {
            value = booking;
        }
        if (value == null) {
            throw new ParsedStatement.ParseException("Entry " + n + ": ValDt or BookgDt is required");
        }
        String ref = text(e, "AcctSvcrRef");
        Element bkTx = child(e, "BkTxCd");
        String txCode = null;
        if (bkTx != null) {
            Element domn = child(bkTx, "Domn");
            if (domn != null) {
                Element fmly = child(domn, "Fmly");
                txCode = text(domn, "Cd") + (fmly == null ? "" : "/" + text(fmly, "Cd") + "/" + text(fmly, "SubFmlyCd"));
            } else {
                txCode = text(path(bkTx, "Prtry"), "Cd");
            }
        }
        BigDecimal charges = charges(child(e, "Chrgs"));

        List<Element> txs = new ArrayList<>();
        for (Element d : children(e, "NtryDtls")) {
            txs.addAll(children(d, "TxDtls"));
        }
        Element btch = path(e, "NtryDtls", "Btch");
        Integer batchCount = null;
        String batchRef = null;
        if (btch != null) {
            batchRef = firstText(btch, "PmtInfId", "MsgId");
            String nb = text(btch, "NbOfTxs");
            batchCount = nb == null ? null : Integer.valueOf(nb);
        }
        String e2e = null;
        String instr = null;
        String uetr = null;
        String counterparty = null;
        String counterpartyAccount = null;
        String narrative = null;
        BigDecimal original = null;
        String originalCcy = null;
        if (txs.size() == 1) {
            Element tx = txs.getFirst();
            Element refs = child(tx, "Refs");
            if (refs != null) {
                e2e = text(refs, "EndToEndId");
                instr = text(refs, "InstrId");
                uetr = text(refs, "UETR");
                if (ref == null) {
                    ref = text(refs, "AcctSvcrRef");
                }
                if (batchRef == null) {
                    batchRef = text(refs, "PmtInfId");
                }
            }
            String party = dir == StatementLine.Direction.CREDIT ? "Dbtr" : "Cdtr";
            Element rltd = child(tx, "RltdPties");
            if (rltd != null) {
                Element p = child(rltd, party);
                if (p != null) {
                    Element pty = child(p, "Pty");
                    counterparty = text(pty != null ? pty : p, "Nm");
                }
                Element acct = path(rltd, party + "Acct", "Id");
                if (acct != null) {
                    counterpartyAccount = firstText(acct, "IBAN", "Id");
                }
            }
            narrative = remittance(child(tx, "RmtInf"));
            Element instd = path(tx, "AmtDtls", "InstdAmt", "Amt");
            if (instd != null) {
                original = decimal(instd, "Entry " + n + ": invalid InstdAmt");
                originalCcy = instd.getAttribute("Ccy");
                if (original.compareTo(amount) == 0 && ccy.equals(originalCcy)) {
                    original = null;
                    originalCcy = null;
                }
            }
            if (charges == null) {
                charges = charges(child(tx, "Chrgs"));
            }
        } else if (txs.size() > 1) {
            batchCount = batchCount == null ? txs.size() : batchCount;
            if (batchRef == null) {
                Element refs = child(txs.getFirst(), "Refs");
                batchRef = refs == null ? null : firstText(refs, "PmtInfId", "MsgId");
            }
        }
        if ("NOTPROVIDED".equals(e2e)) {
            e2e = null;
        }
        if (narrative == null) {
            narrative = text(e, "AddtlNtryInf");
        }
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("amount", amount.toPlainString());
        raw.put("currency", ccy);
        raw.put("cdt_dbt_ind", ind);
        raw.put("value_date", value.toString());
        putIf(raw, "acct_svcr_ref", ref);
        putIf(raw, "end_to_end_id", e2e);
        putIf(raw, "instruction_id", instr);
        putIf(raw, "uetr", uetr);
        putIf(raw, "batch_ref", batchRef);
        putIf(raw, "bank_transaction_code", txCode);
        if (reversal) {
            raw.put("reversal", "true");
        }
        return new StatementLine(ref, e2e, value, booking, dir, amount, ccy, counterparty, narrative, raw,
                new StatementLine.Details(instr, uetr, counterpartyAccount, txCode, reversal, charges, original,
                        originalCcy, batchRef, batchCount));
    }

    private static String remittance(Element rmt) {
        if (rmt == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Element u : children(rmt, "Ustrd")) {
            append(sb, u.getTextContent().trim());
        }
        for (Element s : children(rmt, "Strd")) {
            Element cdtrRef = child(s, "CdtrRefInf");
            if (cdtrRef != null) {
                append(sb, text(cdtrRef, "Ref"));
            }
            for (Element rd : children(s, "RfrdDocInf")) {
                append(sb, text(rd, "Nb"));
            }
            for (Element add : children(s, "AddtlRmtInf")) {
                append(sb, add.getTextContent().trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(s);
        }
    }

    private static BigDecimal charges(Element chrgs) {
        if (chrgs == null) {
            return null;
        }
        BigDecimal sum = null;
        List<Element> records = children(chrgs, "Rcrd");
        for (Element r : records.isEmpty() ? List.of(chrgs) : records) {
            Element amt = child(r, "Amt");
            if (amt != null) {
                BigDecimal v = decimal(amt, "invalid charges amount");
                sum = sum == null ? v : sum.add(v);
            }
        }
        if (sum == null) {
            Element ttl = child(chrgs, "TtlChrgsAndTaxAmt");
            sum = ttl == null ? null : decimal(ttl, "invalid charges amount");
        }
        return sum == null || sum.signum() == 0 ? null : sum;
    }

    private static BigDecimal signed(Element bal) {
        Element amt = child(bal, "Amt");
        if (amt == null) {
            return null;
        }
        BigDecimal v = decimal(amt, "invalid balance amount");
        return "DBIT".equals(text(bal, "CdtDbtInd")) ? v.negate() : v;
    }

    private static BigDecimal decimal(Element el, String message) {
        try {
            return new BigDecimal(el.getTextContent().trim());
        } catch (NumberFormatException ex) {
            throw new ParsedStatement.ParseException(message);
        }
    }

    private static LocalDate date(Element holder) {
        if (holder == null) {
            return null;
        }
        String d = text(holder, "Dt");
        if (d == null) {
            d = text(holder, "DtTm");
        }
        if (d == null && "Dt".equals(holder.getLocalName())) {
            d = holder.getTextContent().trim();
        }
        if (d == null || d.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(d.substring(0, 10));
        } catch (RuntimeException e) {
            throw new ParsedStatement.ParseException("Invalid date " + d);
        }
    }

    private static void putIf(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    /** All descendants with the local name (any namespace). */
    private static List<Element> descendants(Element root, String localName) {
        NodeList nl = root.getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    /** Direct children with the local name. */
    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el && localName.equals(el.getLocalName())) {
                out.add(el);
            }
        }
        return out;
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private static Element path(Element root, String... names) {
        Element cur = root;
        for (String n : names) {
            cur = child(cur, n);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static String text(Element parent, String localName) {
        Element c = child(parent, localName);
        if (c == null) {
            return null;
        }
        String t = c.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }

    /** Text of the first direct child (or, for Othr/Id, nested Id) among the names. */
    private static String firstText(Element parent, String... names) {
        for (String n : names) {
            String t = text(parent, n);
            if (t != null) {
                return t;
            }
        }
        Element othr = child(parent, "Othr");
        return othr == null ? null : text(othr, "Id");
    }
}
