package io.meridian.casemanagement.domain;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * goAML (UNODC) report XML: header, reporting person, reason (narrative), action, transactions with from / to
 * parties and report indicators. Values are escaped by the StAX writer.
 */
public final class GoAmlWriter {
    private GoAmlWriter() {}

    public record Report(String rentityId, String reportCode, String entityReference, String submissionDate,
                         String currency, String reportingPerson, String reason, String action, String fiuReference,
                         List<NarrativeBuilder.Txn> transactions, List<NarrativeBuilder.Subject> subjects,
                         List<String> indicators) {}

    public static String write(Report r) {
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("report");
            el(w, "rentity_id", r.rentityId());
            el(w, "submission_code", "E");
            el(w, "report_code", r.reportCode());
            el(w, "entity_reference", r.entityReference());
            if (r.fiuReference() != null) {
                el(w, "fiu_ref_number", r.fiuReference());
            }
            el(w, "submission_date", r.submissionDate());
            el(w, "currency_code_local", r.currency());
            w.writeStartElement("reporting_person");
            el(w, "last_name", r.reportingPerson());
            w.writeEndElement();
            el(w, "reason", r.reason());
            el(w, "action", r.action());
            int n = 0;
            for (NarrativeBuilder.Txn t : r.transactions()) {
                n++;
                w.writeStartElement("transaction");
                el(w, "transactionnumber", t.id() != null ? t.id() : String.valueOf(n));
                el(w, "transaction_description", t.description());
                el(w, "date_transaction", t.date());
                el(w, "amount_local", t.amount() == null ? null : t.amount().setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString());
                party(w, "t_from", "from_funds_code", "from_party", t.from());
                party(w, "t_to", "to_funds_code", "to_party", t.to());
                w.writeEndElement();
            }
            if (!r.subjects().isEmpty()) {
                w.writeStartElement("involved_parties");
                for (NarrativeBuilder.Subject s : r.subjects()) {
                    w.writeStartElement("party");
                    el(w, "role", s.role());
                    if ("ENTITY".equalsIgnoreCase(s.type())) {
                        w.writeStartElement("entity");
                        el(w, "name", s.name());
                        el(w, "incorporation_country_code", s.country());
                        el(w, "incorporation_number", s.idNumber());
                    } else {
                        w.writeStartElement("person");
                        el(w, "last_name", s.name());
                        el(w, "nationality1", s.country());
                        el(w, "id_number", s.idNumber());
                    }
                    w.writeEndElement();
                    w.writeEndElement();
                }
                w.writeEndElement();
            }
            w.writeStartElement("report_indicators");
            for (String i : r.indicators()) {
                el(w, "indicator", i);
            }
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndDocument();
            w.flush();
            w.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("cannot write goAML XML", e);
        }
        return out.toString();
    }

    private static void party(XMLStreamWriter w, String tag, String fundsTag, String partyTag, String name) throws XMLStreamException {
        w.writeStartElement(tag);
        el(w, fundsTag, "K");
        w.writeStartElement(partyTag);
        el(w, "name", name);
        w.writeEndElement();
        w.writeEndElement();
    }

    private static void el(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value == null) {
            return;
        }
        w.writeStartElement(name);
        w.writeCharacters(value);
        w.writeEndElement();
    }

    static String amount(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
