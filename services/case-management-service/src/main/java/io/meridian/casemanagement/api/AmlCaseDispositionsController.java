package io.meridian.casemanagement.api;

import io.meridian.casemanagement.api.generated.AmlCaseDispositionsApi;
import io.meridian.casemanagement.api.generated.model.AmlCaseDisposition;
import io.meridian.casemanagement.api.generated.model.AmlCaseDispositionsApproveRequest;
import io.meridian.casemanagement.api.generated.model.AmlCaseDispositionsRejectRequest;
import io.meridian.casemanagement.application.CaseService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Checker decisions on proposed case dispositions. */
@RestController
public class AmlCaseDispositionsController implements AmlCaseDispositionsApi {
    private final CaseService cases;
    private final Http http;

    public AmlCaseDispositionsController(CaseService cases, Http http) {
        this.cases = cases;
        this.http = http;
    }

    @Override
    public ResponseEntity<AmlCaseDisposition> amlCaseDispositionsApprove(UUID id, String idempotencyKey,
                                                                         AmlCaseDispositionsApproveRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, AmlCaseDisposition.class,
                () -> http.ok(cases.approveDisposition(id, comment), AmlCaseDisposition.class));
    }

    @Override
    public ResponseEntity<AmlCaseDisposition> amlCaseDispositionsReject(UUID id, String idempotencyKey,
                                                                        AmlCaseDispositionsRejectRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, AmlCaseDisposition.class,
                () -> http.ok(cases.rejectDisposition(id, reason), AmlCaseDisposition.class));
    }
}
