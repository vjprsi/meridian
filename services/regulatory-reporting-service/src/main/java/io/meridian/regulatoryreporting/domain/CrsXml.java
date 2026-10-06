package io.meridian.regulatoryreporting.domain;

import static io.meridian.regulatoryreporting.domain.ReportRenderer.esc;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OECD CRS (CrsXML v2) / FATCA (FATCA XML v2) style message of the reportable accounts of one reporting financial
 * institution and year. Accounts are grouped by receiving (tax residence) jurisdiction, one body per jurisdiction.
 */
public final class CrsXml {
    private CrsXml() {}

    /** Reporting FI and message metadata. */
    public record Message(String regime, int year, String legalEntityId, String transmittingCountry, String messageRefId,
                          String timestamp) {}

    public static String render(Message m, List<Map<String, Object>> records) {
        boolean crs = "CRS".equals(m.regime());
        String p = crs ? "crs" : "ftc";
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append('<').append(p).append(crs ? ":CRS_OECD" : ":FATCA_OECD")
                .append(crs ? " xmlns:crs=\"urn:oecd:ties:crs:v2\"" : " xmlns:ftc=\"urn:oecd:ties:fatca:v2\"")
                .append(" xmlns:stf=\"urn:oecd:ties:stf:v4\" version=\"2.0\">\n");
        sb.append("  <").append(p).append(":MessageSpec>\n");
        el(sb, 4, p + ":SendingCompanyIN", m.legalEntityId());
        el(sb, 4, p + ":TransmittingCountry", m.transmittingCountry());
        el(sb, 4, p + ":ReceivingCountry", crs ? "MULTI" : "US");
        el(sb, 4, p + ":MessageType", crs ? "CRS" : "FATCA");
        el(sb, 4, p + ":MessageRefId", m.messageRefId());
        el(sb, 4, p + ":MessageTypeIndic", crs ? "CRS701" : "FATCA1");
        el(sb, 4, p + ":ReportingPeriod", m.year() + "-12-31");
        el(sb, 4, p + ":Timestamp", m.timestamp());
        sb.append("  </").append(p).append(":MessageSpec>\n");

        Map<String, List<Map<String, Object>>> byCountry = new TreeMap<>();
        for (Map<String, Object> r : records) {
            String c = crs ? str(r.get("tax_residence")) : "US";
            byCountry.computeIfAbsent(c, k -> new java.util.ArrayList<>()).add(r);
        }
        for (var e : byCountry.entrySet()) {
            sb.append("  <").append(p).append(crs ? ":CrsBody" : ":FATCA").append(" receivingCountry=\"").append(esc(e.getKey())).append("\">\n");
            sb.append("    <").append(p).append(":ReportingFI>\n");
            el(sb, 6, p + ":ResCountryCode", m.transmittingCountry());
            el(sb, 6, p + ":IN", m.legalEntityId());
            sb.append("      <").append(p).append(":DocSpec>");
            sb.append("<stf:DocTypeIndic>").append(crs ? "OECD1" : "FATCA1").append("</stf:DocTypeIndic>");
            sb.append("<stf:DocRefId>").append(esc(m.messageRefId() + "-FI-" + e.getKey())).append("</stf:DocRefId>");
            sb.append("</").append(p).append(":DocSpec>\n");
            sb.append("    </").append(p).append(":ReportingFI>\n");
            sb.append("    <").append(p).append(":ReportingGroup>\n");
            for (Map<String, Object> r : e.getValue()) {
                account(sb, p, crs, m, r);
            }
            sb.append("    </").append(p).append(":ReportingGroup>\n");
            sb.append("  </").append(p).append(crs ? ":CrsBody" : ":FATCA").append(">\n");
        }
        sb.append("</").append(p).append(crs ? ":CRS_OECD" : ":FATCA_OECD").append(">\n");
        return sb.toString();
    }

    private static void account(StringBuilder sb, String p, boolean crs, Message m, Map<String, Object> r) {
        sb.append("      <").append(p).append(":AccountReport>\n");
        sb.append("        <").append(p).append(":DocSpec><stf:DocTypeIndic>").append(crs ? "OECD1" : "FATCA1")
                .append("</stf:DocTypeIndic><stf:DocRefId>").append(esc(m.messageRefId() + "-" + str(r.get("id"))))
                .append("</stf:DocRefId></").append(p).append(":DocSpec>\n");
        sb.append("        <").append(p).append(":AccountNumber");
        if (Boolean.TRUE.equals(r.get("is_closed"))) {
            sb.append(" ClosedAccount=\"true\"");
        }
        if (Boolean.TRUE.equals(r.get("is_undocumented"))) {
            sb.append(" UndocumentedAccount=\"true\"");
        }
        sb.append('>').append(esc(str(r.get("account_ref")))).append("</").append(p).append(":AccountNumber>\n");
        boolean individual = "INDIVIDUAL".equals(str(r.get("account_holder_type")));
        sb.append("        <").append(p).append(":AccountHolder>\n");
        String holder = individual ? ":Individual" : ":Organisation";
        sb.append("          <").append(p).append(holder).append(">\n");
        el(sb, 12, p + ":ResCountryCode", str(r.get("tax_residence")));
        if (r.get("tin") != null) {
            sb.append("            <").append(p).append(":TIN issuedBy=\"").append(esc(str(r.get("tax_residence"))))
                    .append("\">").append(esc(str(r.get("tin")))).append("</").append(p).append(":TIN>\n");
        } else if (individual) {
            sb.append("            <").append(p).append(":TIN unknown=\"true\">NOTIN</").append(p).append(":TIN>\n");
        }
        String name = str(r.get("holder_name"));
        if (individual) {
            String first = name;
            String last = name;
            int sp = name == null ? -1 : name.indexOf(' ');
            if (sp > 0) {
                first = name.substring(0, sp);
                last = name.substring(sp + 1);
            }
            sb.append("            <").append(p).append(":Name><").append(p).append(":FirstName>").append(esc(first))
                    .append("</").append(p).append(":FirstName><").append(p).append(":LastName>").append(esc(last))
                    .append("</").append(p).append(":LastName></").append(p).append(":Name>\n");
        } else {
            el(sb, 12, p + ":Name", name);
        }
        if (r.get("holder_address") != null) {
            sb.append("            <").append(p).append(":Address><").append(p).append(":CountryCode>")
                    .append(esc(str(r.get("tax_residence")))).append("</").append(p).append(":CountryCode><")
                    .append(p).append(":AddressFree>").append(esc(str(r.get("holder_address")))).append("</")
                    .append(p).append(":AddressFree></").append(p).append(":Address>\n");
        }
        if (individual && r.get("date_of_birth") != null) {
            sb.append("            <").append(p).append(":BirthInfo><").append(p).append(":BirthDate>")
                    .append(esc(str(r.get("date_of_birth")))).append("</").append(p).append(":BirthDate></")
                    .append(p).append(":BirthInfo>\n");
        }
        sb.append("          </").append(p).append(holder).append(">\n");
        sb.append("        </").append(p).append(":AccountHolder>\n");
        String ccy = str(r.get("currency"));
        sb.append("        <").append(p).append(":AccountBalance currCode=\"").append(esc(ccy)).append("\">")
                .append(esc(amount(r.get("account_balance")))).append("</").append(p).append(":AccountBalance>\n");
        payment(sb, p, crs ? "CRS502" : "FATCA502", r.get("gross_interest"), ccy);
        payment(sb, p, crs ? "CRS501" : "FATCA501", r.get("gross_dividends"), ccy);
        payment(sb, p, crs ? "CRS503" : "FATCA503", r.get("gross_proceeds"), ccy);
        sb.append("      </").append(p).append(":AccountReport>\n");
    }

    private static void payment(StringBuilder sb, String p, String type, Object amount, String ccy) {
        if (amount == null) {
            return;
        }
        sb.append("        <").append(p).append(":Payment><").append(p).append(":Type>").append(type).append("</")
                .append(p).append(":Type><").append(p).append(":PaymentAmnt currCode=\"").append(esc(ccy)).append("\">")
                .append(esc(amount(amount))).append("</").append(p).append(":PaymentAmnt></").append(p).append(":Payment>\n");
    }

    private static String amount(Object o) {
        if (o == null) {
            return "0.00";
        }
        java.math.BigDecimal d = Expr.toDecimal(o);
        return d == null ? String.valueOf(o) : d.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
    }

    private static void el(StringBuilder sb, int indent, String name, String value) {
        if (value == null) {
            return;
        }
        sb.append(" ".repeat(indent)).append('<').append(name).append('>').append(esc(value)).append("</").append(name).append(">\n");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
