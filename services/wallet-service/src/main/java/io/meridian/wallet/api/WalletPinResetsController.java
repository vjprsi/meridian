package io.meridian.wallet.api;

import io.meridian.wallet.api.generated.WalletPinResetsApi;
import io.meridian.wallet.api.generated.model.WalletPinReset;
import io.meridian.wallet.api.generated.model.WalletPinResetCreate;
import io.meridian.wallet.api.generated.model.WalletPinResetsCompleteRequest;
import io.meridian.wallet.api.generated.model.WalletPinResetsRejectRequest;
import io.meridian.wallet.api.generated.model.WalletPinResetsVerifyRequest;
import io.meridian.wallet.application.PinResetService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** PIN resets verified by OTP, ID check or agent. */
@RestController
public class WalletPinResetsController implements WalletPinResetsApi {
    private final PinResetService resets;
    private final Actions actions;

    public WalletPinResetsController(PinResetService resets, Actions actions) {
        this.resets = resets;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<WalletPinReset> walletPinResetsCreate(String idempotencyKey, WalletPinResetCreate body) {
        return actions.create(idempotencyKey, body, WalletPinReset.class, () -> resets.create(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletPinReset> walletPinResetsVerify(UUID id, String idempotencyKey, WalletPinResetsVerifyRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "verify", body, WalletPinReset.class,
                () -> resets.verify(id, Http.text(req, "otp"), Http.uuid(req, "document_id")));
    }

    @Override
    public ResponseEntity<WalletPinReset> walletPinResetsComplete(UUID id, String idempotencyKey, WalletPinResetsCompleteRequest body) {
        return actions.action(idempotencyKey, id, "complete", body, WalletPinReset.class,
                () -> resets.complete(id, Http.text(actions.tree(body), "new_pin")));
    }

    @Override
    public ResponseEntity<WalletPinReset> walletPinResetsReject(UUID id, String idempotencyKey, WalletPinResetsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, WalletPinReset.class,
                () -> resets.reject(id, Http.text(actions.tree(body), "reason")));
    }
}
