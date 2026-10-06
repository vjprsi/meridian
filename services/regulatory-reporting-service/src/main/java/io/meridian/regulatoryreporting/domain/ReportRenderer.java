package io.meridian.regulatoryreporting.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Render step of a report run: deterministic XML (XBRL-like instance), CSV and JSON files of the cells. The same
 * cells always render to the same bytes, so the SHA-256 checksum identifies the content.
 */
public final class ReportRenderer {
    private ReportRenderer() {}

    /** Header facts of a report instance. */
    public record Header(String reportCode, String reportName, String regulator, String jurisdiction, int version,
                         String taxonomy, String legalEntityId, String asOfDate, String periodStart, String periodEnd,
                         String currency, int revision) {}

    /** A rendered file. */
    public record Rendered(String format, String fileName, String contentType, String content, String sha256,
                           long sizeBytes, int recordCount) {}

    public static Rendered render(String format, Header h, List<ReportEngine.Cell> cells) {
        String base = (h.reportCode() + "_" + h.asOfDate() + "_r" + h.revision()).replaceAll("[^A-Za-z0-9_.\\-]", "_");
        return switch (format) {
            case "XBRL", "XML" -> file(format, base + ".xml", "application/xml", xml(format, h, cells), cells.size());
            case "CSV" -> file(format, base + ".csv", "text/csv", csv(cells), cells.size());
            case "JSON" -> file(format, base + ".json", "application/json", json(h, cells), cells.size());
            default -> throw new IllegalArgumentException("format " + format + " cannot be rendered");
        };
    }

    /** The formats produced for a definition: its submission format plus CSV and JSON working copies. */
    public static List<String> formatsFor(String definitionFormat) {
        return switch (definitionFormat) {
            case "XBRL", "XML" -> List.of(definitionFormat, "CSV", "JSON");
            case "CSV" -> List.of("CSV", "JSON");
            case "JSON" -> List.of("JSON", "CSV");
            default -> throw new IllegalArgumentException("format " + definitionFormat + " cannot be rendered");
        };
    }

    public static boolean renderable(String format) {
        return List.of("XBRL", "XML", "CSV", "JSON").contains(format);
    }

    static String xml(String format, Header h, List<ReportEngine.Cell> cells) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        String ns = "urn:meridian:reporting:" + ncName(h.reportCode());
        if ("XBRL".equals(format)) {
            sb.append("<xbrli:xbrl xmlns:xbrli=\"http://www.xbrl.org/2003/instance\" xmlns:iso4217=\"http://www.xbrl.org/2003/iso4217\"")
                    .append(" xmlns:link=\"http://www.xbrl.org/2003/linkbase\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
                    .append(" xmlns:rep=\"").append(esc(ns)).append("\">\n");
            if (h.taxonomy() != null) {
                sb.append("  <link:schemaRef xlink:type=\"simple\" xlink:href=\"").append(esc(h.taxonomy())).append("\"/>\n");
            }
            sb.append("  <xbrli:context id=\"C1\">\n")
                    .append("    <xbrli:entity><xbrli:identifier scheme=\"urn:meridian:legal-entity\">")
                    .append(esc(h.legalEntityId())).append("</xbrli:identifier></xbrli:entity>\n")
                    .append("    <xbrli:period>");
            if (h.periodStart() != null) {
                sb.append("<xbrli:startDate>").append(esc(h.periodStart())).append("</xbrli:startDate><xbrli:endDate>")
                        .append(esc(h.periodEnd())).append("</xbrli:endDate>");
            } else {
                sb.append("<xbrli:instant>").append(esc(h.asOfDate())).append("</xbrli:instant>");
            }
            sb.append("</xbrli:period>\n  </xbrli:context>\n");
            sb.append("  <xbrli:unit id=\"U1\"><xbrli:measure>iso4217:").append(esc(h.currency() == null ? "XXX" : h.currency()))
                    .append("</xbrli:measure></xbrli:unit>\n");
            sb.append("  <xbrli:unit id=\"PURE\"><xbrli:measure>xbrli:pure</xbrli:measure></xbrli:unit>\n");
            for (ReportEngine.Cell c : cells) {
                if (c.value() == null) {
                    continue;
                }
                String el = "rep:" + ncName(c.cellRef());
                sb.append("  <").append(el).append(" contextRef=\"C1\"");
                if ("NUMBER".equals(c.type())) {
                    int decimals = c.value().contains(".") ? c.value().length() - c.value().indexOf('.') - 1 : 0;
                    sb.append(" unitRef=\"U1\" decimals=\"").append(decimals).append('"');
                }
                sb.append('>').append(esc(c.value())).append("</").append(el).append(">\n");
            }
            sb.append("</xbrli:xbrl>\n");
        } else {
            sb.append("<Report xmlns=\"").append(esc(ns)).append("\" code=\"").append(esc(h.reportCode()))
                    .append("\" version=\"").append(h.version()).append("\" revision=\"").append(h.revision()).append("\">\n");
            sb.append("  <Header>\n");
            tag(sb, "ReportName", h.reportName());
            tag(sb, "Regulator", h.regulator());
            tag(sb, "Jurisdiction", h.jurisdiction());
            tag(sb, "LegalEntity", h.legalEntityId());
            tag(sb, "AsOfDate", h.asOfDate());
            tag(sb, "PeriodStart", h.periodStart());
            tag(sb, "PeriodEnd", h.periodEnd());
            tag(sb, "Currency", h.currency());
            sb.append("  </Header>\n  <Facts>\n");
            for (ReportEngine.Cell c : cells) {
                sb.append("    <Fact ref=\"").append(esc(c.cellRef())).append("\" type=\"").append(c.type()).append('"');
                if (c.label() != null) {
                    sb.append(" label=\"").append(esc(c.label())).append('"');
                }
                if (c.value() == null) {
                    sb.append(" nil=\"true\"/>\n");
                } else {
                    sb.append('>').append(esc(c.value())).append("</Fact>\n");
                }
            }
            sb.append("  </Facts>\n</Report>\n");
        }
        return sb.toString();
    }

    static String csv(List<ReportEngine.Cell> cells) {
        StringBuilder sb = new StringBuilder("cell_ref,label,type,value\r\n");
        for (ReportEngine.Cell c : cells) {
            sb.append(csvField(c.cellRef())).append(',').append(csvField(c.label())).append(',')
                    .append(c.type()).append(',').append(csvField(c.value())).append("\r\n");
        }
        return sb.toString();
    }

    static String json(Header h, List<ReportEngine.Cell> cells) {
        StringBuilder sb = new StringBuilder("{\"report\":{");
        sb.append("\"code\":").append(js(h.reportCode())).append(",\"name\":").append(js(h.reportName()))
                .append(",\"regulator\":").append(js(h.regulator())).append(",\"jurisdiction\":").append(js(h.jurisdiction()))
                .append(",\"version\":").append(h.version()).append(",\"revision\":").append(h.revision())
                .append(",\"legal_entity_id\":").append(js(h.legalEntityId())).append(",\"as_of_date\":").append(js(h.asOfDate()))
                .append(",\"period_start\":").append(js(h.periodStart())).append(",\"period_end\":").append(js(h.periodEnd()))
                .append(",\"currency\":").append(js(h.currency())).append("},\"facts\":[");
        for (int i = 0; i < cells.size(); i++) {
            ReportEngine.Cell c = cells.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"cell_ref\":").append(js(c.cellRef())).append(",\"label\":").append(js(c.label()))
                    .append(",\"type\":").append(js(c.type())).append(",\"value\":").append(js(c.value())).append('}');
        }
        return sb.append("]}").toString();
    }

    private static Rendered file(String format, String name, String contentType, String content, int records) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Rendered(format, name, contentType, content, sha256(bytes), bytes.length, records);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void tag(StringBuilder sb, String name, String value) {
        if (value != null) {
            sb.append("    <").append(name).append('>').append(esc(value)).append("</").append(name).append(">\n");
        }
    }

    /** XML-escapes text and attribute values; strips characters not allowed in XML 1.0. */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** A valid XML NCName derived from a cell ref or code. */
    static String ncName(String s) {
        String n = s.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        return Character.isLetter(n.charAt(0)) || n.charAt(0) == '_' ? n : "_" + n;
    }

    private static String csvField(String s) {
        if (s == null) {
            return "";
        }
        String v = s;
        // Neutralise spreadsheet formula injection.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static String js(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
