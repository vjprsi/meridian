package io.meridian.wallet.api;

import io.meridian.wallet.api.generated.WalletBankLinksApi;
import io.meridian.wallet.api.generated.model.WalletBankLink;
import io.meridian.wallet.api.generated.model.WalletBankLinkCreate;
import io.meridian.wallet.api.generated.model.WalletBankLinkUpdate;
import io.meridian.wallet.api.generated.model.WalletBankLinksRemoveRequest;
import io.meridian.wallet.api.generated.model.WalletBankLinksVerifyRequest;
import io.meridian.wallet.application.BankLinkService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bank accounts linked to wallets (verification by name enquiry, micro-deposit or a checker). */
@RestController
public class WalletBankLinksController implements WalletBankLinksApi {
    private static final Set<String> PROTECTED = Set.of("wallet_id", "bank_code", "account_number", "deposit_account_id",
            "currency", "verification_method");

    private final BankLinkService links;
    private final Actions actions;
    private final CrudEngine crud;

    public WalletBankLinksController(BankLinkService links, Actions actions, CrudEngine crud) {
        this.links = links;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<WalletBankLink> walletBankLinksCreate(String idempotencyKey, WalletBankLinkCreate body) {
        return actions.create(idempotencyKey, body, WalletBankLink.class, () -> links.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<WalletBankLink> walletBankLinksUpdate(UUID id, String ifMatch, WalletBankLinkUpdate body) {
        return actions.guardedUpdate(crud, "wallet_bank_link", WalletBankLink.class, id, ifMatch, body, PROTECTED,
                "remove the link and add a new one");
    }

    @Override
    public ResponseEntity<WalletBankLink> walletBankLinksVerify(UUID id, String idempotencyKey, WalletBankLinksVerifyRequest body) {
        return actions.action(idempotencyKey, id, "verify", body, WalletBankLink.class,
                () -> links.verify(id, Http.text(actions.tree(body), "verification_code")));
    }

    @Override
    public ResponseEntity<WalletBankLink> walletBankLinksRemove(UUID id, String idempotencyKey, WalletBankLinksRemoveRequest body) {
        return actions.action(idempotencyKey, id, "remove", body, WalletBankLink.class,
                () -> links.remove(id, Http.text(actions.tree(body), "reason")));
    }
}
