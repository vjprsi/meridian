package io.meridian.regulatoryreporting.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.CrsFatcaRecordsApi;
import io.meridian.regulatoryreporting.api.generated.model.CrsFatcaRecordsGenerateRequest;
import io.meridian.regulatoryreporting.application.CrsService;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CRS / FATCA records: generation from customer and account data, OECD XML message. */
@RestController
public class CrsFatcaRecordsController implements CrsFatcaRecordsApi {
    private final CrsService crs;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public CrsFatcaRecordsController(CrsService crs, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.crs = crs;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> crsFatcaRecordsGenerate(String idempotencyKey,
                                                                      CrsFatcaRecordsGenerateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String regime = Fields.requireText(req, "regime");
        Integer year = Fields.integer(req, "reporting_year");
        if (year == null) {
            throw Fields.invalid("reporting_year", "required", "reporting_year is required");
        }
        UUID le = Fields.requireUuid(req, "legal_entity_id");
        String jurisdiction = Fields.requireText(req, "reporting_jurisdiction");
        return responses.object(idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.ok(crs.generate(regime, year, le, jurisdiction))));
    }

    @Override
    public ResponseEntity<String> crsFatcaRecordsOecdXml(String regime, Integer reportingYear, UUID legalEntityId,
                                                        String transmittingCountry) {
        TenantContext.require();
        String xml = crs.oecdXml(regime, reportingYear, legalEntityId, transmittingCountry);
        return responses.file(xml, "application/xml", regime + "_" + reportingYear + "_" + transmittingCountry + ".xml");
    }
}
