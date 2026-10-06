package io.meridian.regulatoryreporting.infrastructure;

import io.meridian.regulatoryreporting.application.RegulatorGateway;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Regulator gateway simulator: performs the technical checks a real gateway does at transmission (non-empty file,
 * checksum matches the content, well-formed XML for XML filings) and returns a deterministic transmission id.
 * Business acceptance arrives later as an acknowledgement or rejection recorded by an operator or a callback.
 */
@Component
public class SimulatedRegulatorGateway implements RegulatorGateway {

    @Override
    public Receipt submit(Filing f) {
        if (f.content() == null || f.content().isBlank()) {
            return new Receipt(false, null, "Empty file refused by the gateway");
        }
        if (!ReportRenderer.sha256(f.content()).equals(f.sha256())) {
            return new Receipt(false, null, "Checksum mismatch: file corrupted in transit");
        }
        if (f.contentType().contains("xml") && !f.content().stripLeading().startsWith("<?xml")) {
            return new Receipt(false, null, "Malformed XML: missing XML declaration");
        }
        String id = (f.regulator() + "-" + f.channel()).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "")
                + "-" + f.sha256().substring(0, 12).toUpperCase(Locale.ROOT);
        return new Receipt(true, id, "Received by " + f.regulator() + " via " + f.channel() + "; transmission " + id
                + " accepted for processing");
    }
}
