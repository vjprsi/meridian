package io.meridian.disbursement.api;

import io.meridian.disbursement.api.generated.DisbursementProgramsApi;
import io.meridian.disbursement.api.generated.model.DisbursementBatchPage;
import io.meridian.disbursement.api.generated.model.DisbursementProgram;
import io.meridian.disbursement.api.generated.model.DisbursementProgramCreate;
import io.meridian.disbursement.api.generated.model.DisbursementProgramUpdate;
import io.meridian.disbursement.api.generated.model.DisbursementProgramsCloseRequest;
import io.meridian.disbursement.api.generated.model.DisbursementProgramsSuspendRequest;
import io.meridian.disbursement.application.ProgramService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Disbursement programs: creation rules, guarded updates and the lifecycle actions. */
@RestController
public class DisbursementProgramsController implements DisbursementProgramsApi {
    private final ProgramService programs;
    private final Actions actions;
    private final CrudEngine crud;

    public DisbursementProgramsController(ProgramService programs, Actions actions, CrudEngine crud) {
        this.programs = programs;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<DisbursementProgram> disbursementProgramsCreate(String idempotencyKey, DisbursementProgramCreate body) {
        return actions.create(idempotencyKey, body, DisbursementProgram.class, () -> programs.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<DisbursementProgram> disbursementProgramsUpdate(UUID id, String ifMatch, DisbursementProgramUpdate body) {
        programs.checkUpdate(id, actions.tree(body));
        return crud.update("disbursement_program", DisbursementProgram.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<DisbursementProgram> disbursementProgramsActivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "activate", null, DisbursementProgram.class, () -> programs.activate(id));
    }

    @Override
    public ResponseEntity<DisbursementProgram> disbursementProgramsSuspend(UUID id, String idempotencyKey,
                                                                           DisbursementProgramsSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, DisbursementProgram.class,
                () -> programs.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DisbursementProgram> disbursementProgramsClose(UUID id, String idempotencyKey,
                                                                         DisbursementProgramsCloseRequest body) {
        return actions.action(idempotencyKey, id, "close", body, DisbursementProgram.class,
                () -> programs.close(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DisbursementBatchPage> disbursementProgramsBatches(UUID id, Integer limit, String cursor) {
        crud.get("disbursement_program", DisbursementProgram.class, Crud.params().with("id", id)); // 404 when not visible
        return crud.list("disbursement_batch", DisbursementBatchPage.class,
                Crud.params().with("disbursement_program_id", id).with("limit", limit).with("cursor", cursor));
    }
}
