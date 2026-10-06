package io.meridian.deposit.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.AccountMandatesApi;
import io.meridian.deposit.api.generated.model.AccountMandate;
import io.meridian.deposit.api.generated.model.AccountMandateCreate;
import io.meridian.deposit.api.generated.model.AccountMandatesEvaluateRequest;
import io.meridian.deposit.application.HolderService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Signing mandates: validated creation and evaluation of who must approve an operation. */
@RestController
public class AccountMandatesController implements AccountMandatesApi {
    private static final Set<String> RULES = Set.of("ANY_ONE", "ANY_TWO", "ALL", "PRIMARY_ONLY", "PRIMARY_PLUS_ONE", "CUSTOM");

    private final HolderService holders;
    private final Actions actions;
    private final CrudEngine crud;

    public AccountMandatesController(HolderService holders, Actions actions, CrudEngine crud) {
        this.holders = holders;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AccountMandate> accountMandatesCreate(String idempotencyKey, AccountMandateCreate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        BigDecimal min = Http.decimal(req, "min_amount");
        BigDecimal max = Http.decimal(req, "max_amount");
        if (min != null && min.signum() < 0 || max != null && min != null && max.compareTo(min) < 0) {
            throw ApiException.unprocessable("invalid_amount_band", "min_amount must be >= 0 and max_amount >= min_amount");
        }
        if (req.path("required_approvals").asInt(1) < 1) {
            throw ApiException.unprocessable("invalid_required_approvals", "required_approvals must be at least 1");
        }
        if ("CUSTOM".equals(Http.text(req, "rule")) && !req.hasNonNull("approver_groups")
                && req.path("required_approvals").asInt(1) < 2) {
            throw ApiException.unprocessable("approver_groups_required",
                    "CUSTOM mandates need approver_groups or required_approvals >= 2");
        }
        if (!RULES.contains(Http.text(req, "rule"))) {
            throw ApiException.unprocessable("invalid_rule", "Unknown mandate rule");
        }
        return crud.create("account_mandate", AccountMandate.class, body, idempotencyKey, Crud.params());
    }

    /**
     * POST /v1/account-mandates/evaluate. The contract's request has no signatories field, so the signing
     * customers are passed as {@code ?signatories=<uuid>,<uuid>}; without them the response lists who may sign.
     */
    @Override
    public ResponseEntity<Void> accountMandatesEvaluate(String idempotencyKey, AccountMandatesEvaluateRequest body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        Set<UUID> signatories = new LinkedHashSet<>();
        String raw = Http.query("signatories");
        if (raw != null) {
            for (String s : raw.split(",")) {
                try {
                    signatories.add(UUID.fromString(s.trim()));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("invalid_signatories", "signatories must be comma-separated UUIDs");
                }
            }
        }
        return Http.json(actions.json(), holders.evaluate(UUID.fromString(Http.text(req, "deposit_account_id")),
                Http.text(req, "operation_type"), Http.decimal(req, "amount"), signatories));
    }
}
