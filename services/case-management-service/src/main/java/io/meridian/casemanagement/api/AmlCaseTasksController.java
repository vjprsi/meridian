package io.meridian.casemanagement.api;

import io.meridian.casemanagement.api.generated.AmlCaseTasksApi;
import io.meridian.casemanagement.api.generated.model.AmlCaseTask;
import io.meridian.casemanagement.api.generated.model.AmlCaseTaskCreate;
import io.meridian.casemanagement.api.generated.model.AmlCaseTaskUpdate;
import io.meridian.casemanagement.api.generated.model.AmlCaseTasksCompleteRequest;
import io.meridian.casemanagement.application.CaseService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Investigation tasks. */
@RestController
public class AmlCaseTasksController implements AmlCaseTasksApi {
    private final CaseService cases;
    private final Http http;

    public AmlCaseTasksController(CaseService cases, Http http) {
        this.cases = cases;
        this.http = http;
    }

    @Override
    public ResponseEntity<AmlCaseTask> amlCaseTasksCreate(String idempotencyKey, AmlCaseTaskCreate body) {
        return http.once(idempotencyKey, body, AmlCaseTask.class,
                () -> http.respond(HttpStatus.CREATED, cases.createTask(http.tree(body)), AmlCaseTask.class));
    }

    @Override
    public ResponseEntity<AmlCaseTask> amlCaseTasksUpdate(UUID id, String ifMatch, AmlCaseTaskUpdate body) {
        TenantContext.require();
        return http.ok(cases.updateTask(id, http.tree(body), Http.ifMatch(ifMatch)), AmlCaseTask.class);
    }

    @Override
    public ResponseEntity<AmlCaseTask> amlCaseTasksComplete(UUID id, String idempotencyKey, AmlCaseTasksCompleteRequest body) {
        String note = Http.text(http.tree(body), "note");
        return http.once(idempotencyKey, body, AmlCaseTask.class, () -> http.ok(cases.completeTask(id, note), AmlCaseTask.class));
    }
}
