package io.meridian.deposit.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.AccountClosuresApi;
import io.meridian.deposit.api.generated.model.AccountClosure;
import io.meridian.deposit.api.generated.model.AccountClosureCreate;
import io.meridian.deposit.api.generated.model.AccountClosuresApproveRequest;
import io.meridian.deposit.api.generated.model.AccountClosuresCancelRequest;
import io.meridian.deposit.api.generated.model.AccountClosuresRejectRequest;
import io.meridian.deposit.application.ClosureService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Account closures: quote, request, four-eyes approval with settlement, reject, cancel. */
@RestController
public class AccountClosuresController implements AccountClosuresApi {
    private final ClosureService closures;
    private final Actions actions;

    public AccountClosuresController(ClosureService closures, Actions actions) {
        this.closures = closures;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AccountClosure> accountClosuresCreate(String idempotencyKey, AccountClosureCreate body) {
        return actions.create(idempotencyKey, body, AccountClosure.class, () -> closures.request(body));
    }

    /** GET /v1/account-closures/quote?deposit_account_id= (the query parameter is described in the contract text only). */
    @Override
    public ResponseEntity<Void> accountClosuresQuote() {
        TenantContext.require();
        String account = Http.query("deposit_account_id");
        if (account == null) {
            throw ApiException.badRequest("deposit_account_id_required", "Query parameter deposit_account_id is required");
        }
        UUID id;
        try {
            id = UUID.fromString(account);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_deposit_account_id", "deposit_account_id must be a UUID");
        }
        return Http.json(actions.json(), closures.quote(id));
    }

    @Override
    public ResponseEntity<AccountClosure> accountClosuresApprove(UUID id, String idempotencyKey,
                                                                AccountClosuresApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, AccountClosure.class,
                () -> closures.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<AccountClosure> accountClosuresReject(UUID id, String idempotencyKey,
                                                               AccountClosuresRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, AccountClosure.class,
                () -> closures.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<AccountClosure> accountClosuresCancel(UUID id, String idempotencyKey,
                                                               AccountClosuresCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, AccountClosure.class,
                () -> closures.cancel(id, Http.text(actions.tree(body), "reason")));
    }
}
