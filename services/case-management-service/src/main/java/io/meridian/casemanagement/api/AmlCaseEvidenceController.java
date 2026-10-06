package io.meridian.casemanagement.api;

import io.meridian.casemanagement.api.generated.AmlCaseEvidenceApi;
import io.meridian.casemanagement.api.generated.model.AmlCaseEvidence;
import io.meridian.casemanagement.api.generated.model.AmlCaseEvidenceCreate;
import io.meridian.casemanagement.application.CaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Evidence attached to cases (document reference plus SHA-256 for chain of custody). */
@RestController
public class AmlCaseEvidenceController implements AmlCaseEvidenceApi {
    private final CaseService cases;
    private final Http http;

    public AmlCaseEvidenceController(CaseService cases, Http http) {
        this.cases = cases;
        this.http = http;
    }

    @Override
    public ResponseEntity<AmlCaseEvidence> amlCaseEvidenceCreate(String idempotencyKey, AmlCaseEvidenceCreate body) {
        return http.once(idempotencyKey, body, AmlCaseEvidence.class,
                () -> http.respond(HttpStatus.CREATED, cases.addEvidence(http.tree(body)), AmlCaseEvidence.class));
    }
}
