package io.meridian.deposit.api;

import io.meridian.deposit.api.generated.AccountBlocksApi;
import io.meridian.deposit.api.generated.model.AccountBlock;
import io.meridian.deposit.api.generated.model.AccountBlockCreate;
import io.meridian.deposit.api.generated.model.AccountBlockUpdate;
import io.meridian.deposit.api.generated.model.AccountBlocksLiftRequest;
import io.meridian.deposit.application.BlockService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Account blocks: placing (ledger hold or account restriction) and lifting. */
@RestController
public class AccountBlocksController implements AccountBlocksApi {
    private final BlockService blocks;
    private final Actions actions;
    private final CrudEngine crud;

    public AccountBlocksController(BlockService blocks, Actions actions, CrudEngine crud) {
        this.blocks = blocks;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AccountBlock> accountBlocksCreate(String idempotencyKey, AccountBlockCreate body) {
        return actions.create(idempotencyKey, body, AccountBlock.class, () -> blocks.place(body, idempotencyKey));
    }

    @Override
    public ResponseEntity<AccountBlock> accountBlocksLift(UUID id, String idempotencyKey, AccountBlocksLiftRequest body) {
        return actions.action(idempotencyKey, id, "lift", body, AccountBlock.class,
                () -> blocks.lift(id, Http.text(actions.tree(body), "reason"), idempotencyKey));
    }

    @Override
    public ResponseEntity<AccountBlock> accountBlocksUpdate(UUID id, String ifMatch, AccountBlockUpdate body) {
        return actions.guardedUpdate(crud, "account_block", AccountBlock.class, id, ifMatch, body,
                Set.of("amount", "currency", "deposit_account_id", "scope", "hold_id", "block_type"),
                "lift the block and place a new one");
    }
}
