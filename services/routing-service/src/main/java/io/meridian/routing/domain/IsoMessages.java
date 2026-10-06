package io.meridian.routing.domain;

import java.io.StringReader;
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
import org.xml.sax.InputSource;

/**
 * Namespace-agnostic reader for the ISO 20022 messages a rail pushes to us: pacs.002 (status report), pacs.008
 * (inbound credit transfer), camt.056 (recall request) and pacs.004 (return). XXE-safe: DOCTYPEs are rejected.
 * Each message is flattened into one field map per transaction, keyed by stable snake_case names.
 */
public final class IsoMessages {

    /** A parsed message: canonical type (pacs.002, pacs.008, camt.056, pacs.004), group MsgId, transactions. */
    public record Parsed(String type, String messageId, List<Map<String, String>> transactions) {
        public Map<String, String> first() {
            return transactions.isEmpty() ? Map.of() : transactions.getFirst();
        }
    }

    public static class InvalidMessage extends RuntimeException {
        public InvalidMessage(String message) {
            super(message);
        }
    }

    private IsoMessages() {}

    public static Parsed parse(String xml) {
        Document doc = read(xml);
        Element root = doc.getDocumentElement();
        Element body = firstChildElement(root);
        if (body == null) {
            throw new InvalidMessage("empty ISO 20022 document");
        }
        String msgId = text(body, "GrpHdr/MsgId");
        if (msgId == null) {
            msgId = text(body, "Assgnmt/Id");
        }
        List<Map<String, String>> txs = new ArrayList<>();
        switch (body.getLocalName()) {
            case "FIToFIPmtStsRpt" -> {
                String orgMsgId = text(body, "OrgnlGrpInfAndSts/OrgnlMsgId");
                String groupStatus = text(body, "OrgnlGrpInfAndSts/GrpSts");
                for (Element tx : children(body, "TxInfAndSts")) {
                    Map<String, String> m = new LinkedHashMap<>();
                    put(m, "original_message_id", orgMsgId);
                    put(m, "original_end_to_end_id", text(tx, "OrgnlEndToEndId"));
                    put(m, "original_transaction_id", text(tx, "OrgnlTxId"));
                    put(m, "original_uetr", text(tx, "OrgnlUETR"));
                    String st = text(tx, "TxSts");
                    put(m, "status", st != null ? st : groupStatus);
                    put(m, "reason_code", text(tx, "StsRsnInf/Rsn/Cd"));
                    put(m, "reason", text(tx, "StsRsnInf/AddtlInf"));
                    put(m, "clearing_system_ref", text(tx, "ClrSysRef"));
                    put(m, "settlement_date", text(tx, "OrgnlTxRef/IntrBkSttlmDt"));
                    put(m, "acceptance_time", text(tx, "AccptncDtTm"));
                    txs.add(m);
                }
                if (txs.isEmpty() && groupStatus != null) {
                    Map<String, String> m = new LinkedHashMap<>();
                    put(m, "original_message_id", orgMsgId);
                    put(m, "status", groupStatus);
                    put(m, "reason_code", text(body, "OrgnlGrpInfAndSts/StsRsnInf/Rsn/Cd"));
                    txs.add(m);
                }
                return new Parsed("pacs.002", msgId, txs);
            }
            case "FIToFICstmrCdtTrf" -> {
                String groupDate = text(body, "GrpHdr/IntrBkSttlmDt");
                for (Element tx : children(body, "CdtTrfTxInf")) {
                    Map<String, String> m = new LinkedHashMap<>();
                    put(m, "instruction_id", text(tx, "PmtId/InstrId"));
                    put(m, "end_to_end_id", text(tx, "PmtId/EndToEndId"));
                    put(m, "transaction_id", text(tx, "PmtId/TxId"));
                    put(m, "uetr", text(tx, "PmtId/UETR"));
                    Element amt = find(tx, "IntrBkSttlmAmt");
                    if (amt != null) {
                        put(m, "amount", amt.getTextContent().trim());
                        put(m, "currency", amt.getAttribute("Ccy"));
                    }
                    String d = text(tx, "IntrBkSttlmDt");
                    put(m, "settlement_date", d != null ? d : groupDate);
                    put(m, "charge_bearer", text(tx, "ChrgBr"));
                    put(m, "debtor_name", text(tx, "Dbtr/Nm"));
                    put(m, "debtor_account_number", account(tx, "DbtrAcct"));
                    put(m, "debtor_agent_bic", text(tx, "DbtrAgt/FinInstnId/BICFI"));
                    put(m, "creditor_name", text(tx, "Cdtr/Nm"));
                    put(m, "creditor_account_number", account(tx, "CdtrAcct"));
                    put(m, "creditor_agent_bic", text(tx, "CdtrAgt/FinInstnId/BICFI"));
                    put(m, "purpose_code", text(tx, "Purp/Cd"));
                    put(m, "remittance_unstructured", text(tx, "RmtInf/Ustrd"));
                    txs.add(m);
                }
                return new Parsed("pacs.008", msgId, txs);
            }
            case "FIToFIPmtCxlReq" -> {
                String assigner = text(body, "Assgnmt/Assgnr/Agt/FinInstnId/BICFI");
                for (Element tx : descendants(body, "TxInf")) {
                    Map<String, String> m = new LinkedHashMap<>();
                    put(m, "cancellation_id", text(tx, "CxlId"));
                    put(m, "original_end_to_end_id", text(tx, "OrgnlEndToEndId"));
                    put(m, "original_transaction_id", text(tx, "OrgnlTxId"));
                    put(m, "original_uetr", text(tx, "OrgnlUETR"));
                    put(m, "reason_code", text(tx, "CxlRsnInf/Rsn/Cd"));
                    put(m, "reason", text(tx, "CxlRsnInf/AddtlInf"));
                    String orgtr = text(tx, "CxlRsnInf/Orgtr/Nm");
                    put(m, "requested_by", orgtr != null ? orgtr : assigner);
                    txs.add(m);
                }
                return new Parsed("camt.056", msgId, txs);
            }
            case "PmtRtr" -> {
                for (Element tx : children(body, "TxInf")) {
                    Map<String, String> m = new LinkedHashMap<>();
                    put(m, "return_id", text(tx, "RtrId"));
                    put(m, "original_end_to_end_id", text(tx, "OrgnlEndToEndId"));
                    put(m, "original_transaction_id", text(tx, "OrgnlTxId"));
                    put(m, "original_uetr", text(tx, "OrgnlUETR"));
                    Element amt = find(tx, "RtrdIntrBkSttlmAmt");
                    if (amt != null) {
                        put(m, "amount", amt.getTextContent().trim());
                        put(m, "currency", amt.getAttribute("Ccy"));
                    }
                    put(m, "reason_code", text(tx, "RtrRsnInf/Rsn/Cd"));
                    put(m, "reason", text(tx, "RtrRsnInf/AddtlInf"));
                    txs.add(m);
                }
                return new Parsed("pacs.004", msgId, txs);
            }
            default -> throw new InvalidMessage("unsupported ISO 20022 message " + body.getLocalName());
        }
    }

    /** Canonical short type ("pacs.002") from a declared type such as "pacs.002.001.10". */
    public static String canonical(String declared) {
        if (declared == null) {
            return null;
        }
        String[] p = declared.trim().toLowerCase(java.util.Locale.ROOT).split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : declared;
    }

    // ------------------------------------------------------------------ DOM helpers

    static Document read(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new InvalidMessage("payload is empty");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new InvalidMessage("payload is not well-formed XML: " + e.getMessage());
        }
    }

    private static String account(Element tx, String acct) {
        String iban = text(tx, acct + "/Id/IBAN");
        return iban != null ? iban : text(tx, acct + "/Id/Othr/Id");
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    /** Text of the element reached by a '/'-separated path of local names (each step: first descendant). */
    static String text(Element ctx, String path) {
        Element e = find(ctx, path);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    static Element find(Element ctx, String path) {
        Element cur = ctx;
        for (String seg : path.split("/")) {
            cur = firstDescendant(cur, seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static Element firstDescendant(Element ctx, String local) {
        NodeList all = ctx.getElementsByTagNameNS("*", local);
        return all.getLength() == 0 ? null : (Element) all.item(0);
    }

    private static Element firstChildElement(Element e) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> children(Element e, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && local.equals(el.getLocalName())) {
                out.add(el);
            }
        }
        return out;
    }

    private static List<Element> descendants(Element e, String local) {
        NodeList all = e.getElementsByTagNameNS("*", local);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            out.add((Element) all.item(i));
        }
        return out;
    }
}
