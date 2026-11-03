package io.meridian.wallet.api;

import io.meridian.wallet.api.generated.WalletVouchersApi;
import io.meridian.wallet.api.generated.model.WalletVoucher;
import io.meridian.wallet.api.generated.model.WalletVouchersCashInOthersRequest;
import io.meridian.wallet.api.generated.model.WalletVouchersClaimRequest;
import io.meridian.wallet.api.generated.model.WalletVouchersP2pOnTheFlyRequest;
import io.meridian.wallet.api.generated.model.WalletVouchersTransferUnregisteredRequest;
import io.meridian.wallet.application.VoucherService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * WE-TXN-1 (G-03): unregistered / voucher flows - cash-in-others (MQ-FIN-4), P2P on-the-fly (MQ-FIN-17) and
 * transfer-between-unregistered (MQ-FIN-14) issuance, claim by registration, and the expiry / rollback sweep.
 */
@RestController
public class WalletVouchersController implements WalletVouchersApi {
    private final VoucherService vouchers;
    private final Actions actions;

    public WalletVouchersController(VoucherService vouchers, Actions actions) {
        this.vouchers = vouchers;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletVouchersCashInOthers(String idempotencyKey,
                                                                          WalletVouchersCashInOthersRequest body) {
        return actions.objectAction(idempotencyKey, "voucher:cash-in-others", body,
                () -> vouchers.issue("CASH_IN_OTHERS", actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletVouchersP2pOnTheFly(String idempotencyKey,
                                                                         WalletVouchersP2pOnTheFlyRequest body) {
        return actions.objectAction(idempotencyKey, "voucher:p2p-on-the-fly", body,
                () -> vouchers.issue("P2P_UNREGISTERED", actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletVouchersTransferUnregistered(String idempotencyKey,
                                                                                  WalletVouchersTransferUnregisteredRequest body) {
        return actions.objectAction(idempotencyKey, "voucher:transfer-unregistered", body,
                () -> vouchers.issue("UNREGISTERED_TRANSFER", actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletVoucher> walletVouchersClaim(UUID id, String idempotencyKey,
                                                             WalletVouchersClaimRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "claim", body, WalletVoucher.class,
                () -> vouchers.claim(id, Http.uuid(req, "claiming_wallet_id"), Http.text(req, "passcode")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletVouchersExpireRollback(String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "voucher:expire-rollback", null, vouchers::expireAndRollback);
    }
}
