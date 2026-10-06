package io.meridian.alm.api;

import io.meridian.alm.api.generated.FtpRulesApi;
import io.meridian.alm.api.generated.model.FtpRule;
import io.meridian.alm.api.generated.model.FtpRuleCreate;
import io.meridian.alm.api.generated.model.FtpRuleUpdate;
import io.meridian.alm.application.FtpService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** FTP rules: validated create / update (method parameters, validity window); list, get and delete are generic. */
@RestController
public class FtpRulesController implements FtpRulesApi {
    private final FtpService service;
    private final CrudEngine crud;

    public FtpRulesController(FtpService service, CrudEngine crud) {
        this.service = service;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<FtpRule> ftpRulesCreate(String idempotencyKey, FtpRuleCreate body) {
        TenantContext.require();
        service.checkRule(null, body);
        return crud.create("ftp_rule", FtpRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<FtpRule> ftpRulesUpdate(UUID id, String ifMatch, FtpRuleUpdate body) {
        TenantContext.require();
        service.checkRule(id, body);
        return crud.update("ftp_rule", FtpRule.class, body, ifMatch, Crud.params().with("id", id));
    }
}
