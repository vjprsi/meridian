package io.meridian.commission.api;

import io.meridian.commission.api.generated.JoiningCommissionsApi;
import io.meridian.commission.api.generated.model.JoiningCommission;
import io.meridian.commission.api.generated.model.JoiningCommissionCreate;
import io.meridian.commission.api.generated.model.JoiningCommissionUpdate;
import io.meridian.commission.api.generated.model.JoiningCommissionsApproveRequest;
import io.meridian.commission.api.generated.model.JoiningCommissionsRejectRequest;
import io.meridian.commission.api.generated.model.JoiningCommissionsRetireRequest;
import io.meridian.commission.application.JoiningCommissionService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-PRICE-1 (PE-11): joining-commission configurations; versioned-by-code maker-checker lifecycle. */
@RestController
public class JoiningCommissionsController implements JoiningCommissionsApi {
    private final JoiningCommissionService service;
    private final Actions actions;
    private final CrudEngine crud;

    public JoiningCommissionsController(JoiningCommissionService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<JoiningCommission> joiningCommissionsCreate(String idempotencyKey, JoiningCommissionCreate body) {
        return actions.create(idempotencyKey, body, JoiningCommission.class, () -> service.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<JoiningCommission> joiningCommissionsUpdate(UUID id, String ifMatch, JoiningCommissionUpdate body) {
        TenantContext.require();
        service.checkEditable(id, actions.tree(body));
        return crud.update("joining_commission", JoiningCommission.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<JoiningCommission> joiningCommissionsSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, JoiningCommission.class, () -> service.submit(id));
    }

    @Override
    public ResponseEntity<JoiningCommission> joiningCommissionsApprove(UUID id, String idempotencyKey,
                                                                       JoiningCommissionsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, JoiningCommission.class, () -> service.approve(id));
    }

    @Override
    public ResponseEntity<JoiningCommission> joiningCommissionsReject(UUID id, String idempotencyKey,
                                                                      JoiningCommissionsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, JoiningCommission.class,
                () -> service.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<JoiningCommission> joiningCommissionsRetire(UUID id, String idempotencyKey,
                                                                      JoiningCommissionsRetireRequest body) {
        String date = Http.text(actions.tree(body), "effective_date");
        return actions.action(idempotencyKey, id, "retire", body, JoiningCommission.class,
                () -> service.retire(id, date == null ? null : LocalDate.parse(date)));
    }
}
