package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.GradesApi;
import io.meridian.agentnetwork.api.generated.model.Grade;
import io.meridian.agentnetwork.api.generated.model.GradeCreate;
import io.meridian.agentnetwork.api.generated.model.GradeServicePage;
import io.meridian.agentnetwork.api.generated.model.GradeUpdate;
import io.meridian.agentnetwork.api.generated.model.GradesApproveRequest;
import io.meridian.agentnetwork.api.generated.model.GradesRejectRequest;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-HIER-1: grades within a category (maker-checker, singleton un-registered grade, delete-when-referenced guard). */
@RestController
public class GradesController implements GradesApi {
    private final io.meridian.agentnetwork.application.GradeService grades;
    private final Actions actions;
    private final CrudEngine crud;

    public GradesController(io.meridian.agentnetwork.application.GradeService grades, Actions actions, CrudEngine crud) {
        this.grades = grades;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<Grade> gradesCreate(String idempotencyKey, GradeCreate body) {
        return actions.create(idempotencyKey, body, Grade.class, () -> grades.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Grade> gradesUpdate(UUID id, String ifMatch, GradeUpdate body) {
        grades.checkEditable(id, actions.tree(body));
        return actions.guardedUpdate(crud, "grade", Grade.class, id, ifMatch, body, Set.of("code", "domain_category_id"),
                "code and category are fixed; the status changes through submit/approve");
    }

    @Override
    public ResponseEntity<Void> gradesDelete(UUID id) {
        TenantContext.require();
        grades.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Grade> gradesSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, Grade.class, () -> grades.submit(id));
    }

    @Override
    public ResponseEntity<Grade> gradesApprove(UUID id, String idempotencyKey, GradesApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, Grade.class, () -> grades.approve(id));
    }

    @Override
    public ResponseEntity<Grade> gradesReject(UUID id, String idempotencyKey, GradesRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, Grade.class,
                () -> grades.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Grade> gradesRetire(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retire", null, Grade.class, () -> grades.retire(id));
    }

    @Override
    public ResponseEntity<Grade> gradesUnregistered() {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(grades.unregistered(), Grade.class));
    }

    @Override
    public ResponseEntity<GradeServicePage> gradesServices(UUID id, Integer limit, String cursor) {
        crud.get("grade", Grade.class, Crud.params().with("id", id));
        return crud.list("grade_service", GradeServicePage.class,
                Crud.params().with("grade_id", id).with("limit", limit).with("cursor", cursor));
    }
}
