package io.meridian.alm.api;

import io.meridian.alm.api.generated.FtpAssignmentsApi;
import io.meridian.alm.api.generated.model.FtpAssignmentsQuoteRequest;
import io.meridian.alm.api.generated.model.FtpAssignmentsRunRequest;
import io.meridian.alm.application.FtpService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** FTP assignments: period run over supplied exposures and single-exposure quotes. */
@RestController
public class FtpAssignmentsController implements FtpAssignmentsApi {
    private final FtpService service;
    private final Actions actions;

    public FtpAssignmentsController(FtpService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> ftpAssignmentsRun(String idempotencyKey, FtpAssignmentsRunRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "ftp-run", body, () -> service.run(
                body.getPeriodStart(), body.getPeriodEnd(), req.get("exposures")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> ftpAssignmentsQuote(String idempotencyKey, FtpAssignmentsQuoteRequest body) {
        return actions.objectAction(idempotencyKey, "ftp-quote", body, () -> service.quote(actions.tree(body)));
    }
}
