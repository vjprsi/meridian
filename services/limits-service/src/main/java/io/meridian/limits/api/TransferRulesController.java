package io.meridian.limits.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.api.generated.TransferRulesApi;
import io.meridian.limits.api.generated.model.TransferRule;
import io.meridian.limits.api.generated.model.TransferRuleCreate;
import io.meridian.limits.api.generated.model.TransferRuleUpdate;
import io.meridian.limits.api.generated.model.TransferRulesActivateRequest;
import io.meridian.limits.api.generated.model.TransferRulesDeactivateRequest;
import io.meridian.limits.api.generated.model.TransferRulesEvaluateRequest;
import io.meridian.limits.api.generated.model.TransferRulesRejectRequest;
import io.meridian.limits.api.generated.model.TransferRulesSubmitRequest;
import io.meridian.limits.application.TcpAdminService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-LIMIT-1: transfer-rule matrix (allow/deny) with maker-checker activation and a stateless evaluation endpoint. */
@RestController
public class TransferRulesController implements TransferRulesApi {
    private static final String TABLE = TcpAdminService.TRANSFER_RULE;

    private final TcpAdminService admin;
    private final CrudEngine crud;
    private final Http http;

    public TransferRulesController(TcpAdminService admin, CrudEngine crud, Http http) {
        this.admin = admin;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<TransferRule> transferRulesCreate(String idempotencyKey, TransferRuleCreate body) {
        TenantContext.require();
        admin.validate(TABLE, http.tree(body));
        return crud.create(TABLE, TransferRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<TransferRule> transferRulesUpdate(UUID id, String ifMatch, TransferRuleUpdate body) {
        TenantContext.require();
        return http.ok(admin.update(TABLE, id, http.tree(body), Http.ifMatch(ifMatch)), TransferRule.class);
    }

    @Override
    public ResponseEntity<TransferRule> transferRulesSubmit(UUID id, String idempotencyKey, TransferRulesSubmitRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, TransferRule.class,
                () -> http.ok(admin.submit(TABLE, TABLE, id, comment), TransferRule.class));
    }

    @Override
    public ResponseEntity<TransferRule> transferRulesActivate(UUID id, String idempotencyKey, TransferRulesActivateRequest body) {
        return http.once(idempotencyKey, body, TransferRule.class,
                () -> http.ok(admin.activate(TABLE, TABLE, id), TransferRule.class));
    }

    @Override
    public ResponseEntity<TransferRule> transferRulesReject(UUID id, String idempotencyKey, TransferRulesRejectRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, TransferRule.class,
                () -> http.ok(admin.reject(TABLE, TABLE, id, reason), TransferRule.class));
    }

    @Override
    public ResponseEntity<TransferRule> transferRulesDeactivate(UUID id, String idempotencyKey,
                                                                TransferRulesDeactivateRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, TransferRule.class,
                () -> http.ok(admin.deactivate(TABLE, id, reason), TransferRule.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> transferRulesEvaluate(String idempotencyKey, TransferRulesEvaluateRequest body) {
        TenantContext.require();
        return ResponseEntity.ok(admin.evaluateTransferRules(http.tree(body)));
    }
}
