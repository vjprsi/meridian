package io.meridian.pricingrules.domain;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Import/export of the decision-table subset of DMN 1.5 XML: one {@code decision} with one {@code decisionTable}
 * (hitPolicy, aggregation, input/inputExpression typeRef + text, output name/typeRef/defaultOutputEntry,
 * rule inputEntry/outputEntry text and description). Parsing is hardened against XXE (no DTDs, no external
 * entities).
 */
public final class DmnXml {
    public static final String DMN_NS = "https://www.omg.org/spec/DMN/20230324/MODEL/";
    private static final int MAX_XML_LENGTH = 2_000_000;

    private DmnXml() {}

    /** Result of an import: decision name/id plus the table in stored JSON form. */
    public record Imported(String decisionId, String decisionName, String hitPolicy, String aggregation,
                           List<Object> inputs, List<Object> outputs, List<Object> rules) {}

    public static Imported parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new DecisionTableException("invalid_dmn", "dmn_xml is empty");
        }
        if (xml.length() > MAX_XML_LENGTH) {
            throw new DecisionTableException("invalid_dmn", "dmn_xml exceeds " + MAX_XML_LENGTH + " characters");
        }
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new DecisionTableException("invalid_dmn", "dmn_xml is not well-formed XML: " + e.getMessage());
        }
        List<Element> decisions = children(doc.getDocumentElement(), "decision");
        if (!"definitions".equals(local(doc.getDocumentElement()))) {
            throw new DecisionTableException("invalid_dmn", "Root element must be <definitions>");
        }
        if (decisions.size() != 1) {
            throw new DecisionTableException("invalid_dmn", "Exactly one <decision> is supported, found " + decisions.size());
        }
        Element decision = decisions.getFirst();
        List<Element> tables = children(decision, "decisionTable");
        if (tables.size() != 1) {
            throw new DecisionTableException("invalid_dmn", "The decision must contain exactly one <decisionTable>");
        }
        Element table = tables.getFirst();
        String hitPolicy = attr(table, "hitPolicy");
        hitPolicy = hitPolicy == null ? "UNIQUE" : hitPolicy.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        String aggregation = attr(table, "aggregation");

        List<Object> inCols = new ArrayList<>();
        for (Element in : children(table, "input")) {
            Map<String, Object> c = new LinkedHashMap<>();
            Element expr = first(in, "inputExpression");
            String text = expr == null ? null : text(first(expr, "text"));
            String label = attr(in, "label");
            String name = label != null ? label : (text != null ? text : attr(in, "id"));
            c.put("name", name);
            c.put("type", expr == null || attr(expr, "typeRef") == null ? "any" : attr(expr, "typeRef"));
            if (text != null && !text.equals(name)) {
                c.put("expression", text);
            }
            inCols.add(c);
        }
        List<Object> outCols = new ArrayList<>();
        for (Element out : children(table, "output")) {
            Map<String, Object> c = new LinkedHashMap<>();
            String name = attr(out, "name") != null ? attr(out, "name") : attr(out, "label");
            c.put("name", name);
            c.put("type", attr(out, "typeRef") == null ? "any" : attr(out, "typeRef"));
            Element def = first(out, "defaultOutputEntry");
            if (def != null && text(first(def, "text")) != null) {
                c.put("default", unquote(text(first(def, "text"))));
            }
            outCols.add(c);
        }
        List<Object> rows = new ArrayList<>();
        for (Element rule : children(table, "rule")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("inputs", children(rule, "inputEntry").stream().map(e -> blankToDash(text(first(e, "text")))).toList());
            r.put("outputs", children(rule, "outputEntry").stream().map(e -> text(first(e, "text"))).toList());
            Element desc = first(rule, "description");
            if (desc != null && text(desc) != null) {
                r.put("annotation", text(desc));
            }
            rows.add(r);
        }
        aggregation = aggregation == null || aggregation.isBlank() ? "NONE" : aggregation.trim().toUpperCase(Locale.ROOT);
        // Validate now so an import never stores an unusable table.
        DecisionTableModel.parse(hitPolicy, aggregation, inCols, outCols, rows);
        return new Imported(attr(decision, "id"), attr(decision, "name"), hitPolicy, aggregation, inCols, outCols, rows);
    }

    /** Writes a DMN 1.5 document for the table. Deterministic for a given table. */
    public static String write(String code, String name, DecisionTableModel t) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().newDocument();
            Element defs = doc.createElementNS(DMN_NS, "definitions");
            defs.setAttribute("id", "defs_" + safeId(code));
            defs.setAttribute("name", name == null ? code : name);
            defs.setAttribute("namespace", "https://meridian.example/dmn/" + safeId(code));
            doc.appendChild(defs);
            Element decision = el(doc, defs, "decision");
            decision.setAttribute("id", safeId(code));
            decision.setAttribute("name", name == null ? code : name);
            Element table = el(doc, decision, "decisionTable");
            table.setAttribute("id", "dt_" + safeId(code));
            table.setAttribute("hitPolicy", t.hitPolicy().name().replace('_', ' '));
            if (t.aggregation() != DecisionTableModel.Aggregation.NONE) {
                table.setAttribute("aggregation", t.aggregation().name());
            }
            int i = 0;
            for (DecisionTableModel.InputColumn c : t.inputs()) {
                Element in = el(doc, table, "input");
                in.setAttribute("id", "in_" + (++i));
                in.setAttribute("label", c.name());
                Element expr = el(doc, in, "inputExpression");
                expr.setAttribute("id", "ie_" + i);
                expr.setAttribute("typeRef", c.type().typeRef());
                el(doc, expr, "text").setTextContent(c.path());
            }
            i = 0;
            for (DecisionTableModel.OutputColumn c : t.outputs()) {
                Element out = el(doc, table, "output");
                out.setAttribute("id", "out_" + (++i));
                out.setAttribute("name", c.name());
                out.setAttribute("typeRef", c.type().typeRef());
                if (c.defaultValue() != null) {
                    Element def = el(doc, out, "defaultOutputEntry");
                    el(doc, def, "text").setTextContent(feel(c.defaultValue()));
                }
            }
            for (DecisionTableModel.Rule r : t.rules()) {
                Element rule = el(doc, table, "rule");
                rule.setAttribute("id", "rule_" + r.number());
                if (r.annotation() != null) {
                    el(doc, rule, "description").setTextContent(r.annotation());
                }
                int k = 0;
                for (UnaryTest u : r.inputEntries()) {
                    Element ie = el(doc, rule, "inputEntry");
                    ie.setAttribute("id", "r" + r.number() + "_i" + (++k));
                    el(doc, ie, "text").setTextContent(u.source());
                }
                k = 0;
                for (String s : r.outputSources()) {
                    Element oe = el(doc, rule, "outputEntry");
                    oe.setAttribute("id", "r" + r.number() + "_o" + (++k));
                    el(doc, oe, "text").setTextContent(s);
                }
            }
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer tr = tf.newTransformer();
            tr.setOutputProperty(OutputKeys.INDENT, "yes");
            tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            tr.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter w = new StringWriter();
            tr.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write DMN", e);
        }
    }

    private static String feel(Object v) {
        if (v instanceof String s) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        if (v instanceof java.time.LocalDate d) {
            return "date(\"" + d + "\")";
        }
        return v instanceof java.math.BigDecimal b ? b.toPlainString() : String.valueOf(v);
    }

    private static Element el(Document doc, Element parent, String name) {
        Element e = doc.createElementNS(DMN_NS, name);
        parent.appendChild(e);
        return e;
    }

    private static String safeId(String s) {
        String id = s == null ? "decision" : s.replaceAll("[^A-Za-z0-9_.-]", "_");
        return Character.isLetter(id.isEmpty() ? '1' : id.charAt(0)) || id.startsWith("_") ? id : "_" + id;
    }

    private static String unquote(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String local(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && localName.equals(local(e))) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element first(Element parent, String localName) {
        List<Element> l = children(parent, localName);
        return l.isEmpty() ? null : l.getFirst();
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }
}
